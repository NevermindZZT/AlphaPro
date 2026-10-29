package com.letter.alphapro.core.bluetooth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext

/**
 * Remote-command transport, independent of Android and the shared GATT operation lane.
 *
 * [currentSession] returns a stable identity token for the usable connection, or null. A new
 * connection MUST use a different identity (even if tokens compare equal). [send] must reject an
 * obsolete [session] atomically at transport initiation; it must never redirect to a new connection.
 * A true result means command submission completed successfully, not that a photo/file exists.
 * Implementations must bound callback waits, including when called in NonCancellable cleanup.
 */
interface RemoteShutterPort {
    fun currentSession(): Any?
    suspend fun send(session: Any, command: ByteArray): Boolean
}

/**
 * Serializes remote gestures only; delays never reserve the GATT/GPS lane. Each action pins one
 * session, rejects competing actions immediately, and never retries a press. False means invalid
 * configuration, busy/unavailable/stale transport, or a failed command/callback. Caller cancellation
 * propagates after best-effort, non-cancellable release; the caller owns its coroutine/job lifecycle.
 *
 * The injected clock must be monotonic milliseconds; [wait] must be a cancellable millisecond wait.
 * Cleanup uses that same wait in NonCancellable. Production transport callback bounds belong to the
 * GATT lane, not this controller. Use one controller instance per remote connection owner.
 */
class SonyRemoteShutterController(
    private val port: RemoteShutterPort,
    private val clockMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wait: suspend (Long) -> Unit = { delay(it) }
) {
    constructor(
        gattManager: SonyBleGattManager,
        clockMillis: () -> Long = { System.nanoTime() / 1_000_000 }
    ) : this(
        object : RemoteShutterPort {
            override fun currentSession(): Any? = gattManager.remoteSessionToken()
            override suspend fun send(session: Any, command: ByteArray): Boolean =
                gattManager.sendRemoteCommand(command, expectedSession = session)
        },
        clockMillis
    )

    private val remoteOwnership = Mutex()

    /** Leaves AF half-down on success. Failure/cancellation releases both shutter stages. */
    suspend fun halfPressAfDown(): Boolean = remoteAction { session ->
        var held = false
        var released = true
        try {
            held = sendPinned(session, SonyBleConstants.CMD_HALF_SHUTTER_DOWN)
            currentCoroutineContext().ensureActive()
        } finally {
            if (!held || !currentCoroutineContext().isActive) {
                released = releaseShutterLocked(session)
            }
        }
        held && released
    }

    /** Preserve the verified AF-only release; emergency release is a separate operation. */
    suspend fun halfPressAfUp(): Boolean = remoteAction { session ->
        withContext(NonCancellable) { releaseCommand(session, SonyBleConstants.CMD_HALF_SHUTTER_UP) }
    }

    /** Verified single gesture: 07, 150 ms, 09, 150 ms, 08, 50 ms, 06. */
    suspend fun takePhoto(): Boolean = remoteAction { captureLocked(it, 150) }

    /** Verified movie gesture: 0f, 150 ms, 0e. Movie-up is attempted even on down failure. */
    suspend fun toggleMovieRecording(): Boolean = remoteAction { session ->
        var pressed = false
        var released: Boolean
        try {
            pressed = sendPinned(session, SonyBleConstants.CMD_MOVIE_REC_DOWN)
            if (pressed) wait(150)
        } finally {
            released = withContext(NonCancellable) {
                releaseCommand(session, SonyBleConstants.CMD_MOVIE_REC_UP)
            }
        }
        pressed && released
    }

    /**
     * Continuous-drive hold (NOT latched Bluetooth BULB). Holds full-down for [durationMillis] (100..3,600,000 ms), after 07/150 ms/09.
     * [onHeld] runs only after successful full-down. Always releases 08/50 ms/06, including when
     * [onHeld], a press, the hold wait, or the caller fails/cancels. This does not select camera mode.
     */
    suspend fun holdShutter(durationMillis: Long, onHeld: () -> Unit = {}): Boolean {
        if (durationMillis !in 100L..3_600_000L) return false
        return remoteAction { captureLocked(it, durationMillis, onHeld) }
    }

    /** Holds full-down until the owning gesture releases; no fixed burst duration is imposed. */
    suspend fun holdShutterUntilReleased(
        releaseRequested: () -> Boolean,
        awaitRelease: suspend () -> Unit,
        onHeld: () -> Unit = {}
    ): Boolean = remoteAction { session ->
        captureLocked(session, 0, onHeld = onHeld,
            shouldSkipFull = releaseRequested, holdAction = awaitRelease)
    }

    /**
     * Unified shutter: sends 07, waits 150 ms, then sends 09.
     * If [releaseRequested] is true before or during 09 (a quick tap), holds 09 for at least
     * [minFullPressMillis] (default 150 ms) before releasing 08/50 ms/06, completing a clean single shot.
     * If the user continues to hold, awaits [awaitRelease] before releasing, allowing continuous shooting.
     */
    suspend fun captureUnified(
        releaseRequested: () -> Boolean,
        awaitRelease: suspend () -> Unit,
        minFullPressMillis: Long = 150L,
        onHeld: () -> Unit = {}
    ): Boolean = remoteAction { session ->
        captureLocked(
            session = session,
            holdMillis = 0L,
            onHeld = onHeld,
            shouldSkipFull = null,
            holdAction = {
                val fullPressStarted = clockMillis()
                if (!releaseRequested()) {
                    awaitRelease()
                }
                val elapsed = clockMillis() - fullPressStarted
                val remaining = minFullPressMillis - elapsed
                if (remaining > 0) {
                    wait(remaining)
                }
            }
        )
    }

    /**
     * Bluetooth BULB is latched, not a held key: submit a complete start pulse, then a complete
     * end pulse on release/deadline. The camera must already be configured for Bluetooth BULB.
     * Acceptance owns the gesture, but does not confirm a physical exposure or a saved file.
     * Unknown feedback is allowed; fresh inactive feedback suppresses the end pulse (which could
     * otherwise restart exposure). A failed/uncertain press or key-up is never retried.
     */
    suspend fun runBulbFromPress(
        durationMillis: Long? = null,
        releaseRequested: () -> Boolean = { false },
        awaitRelease: (suspend () -> Unit)? = null,
        isExposureActive: () -> Boolean? = { null },
        onStarted: () -> Unit = {},
        onStopping: () -> Unit = {},
        onUncertain: () -> Unit = {}
    ): Boolean {
        if (durationMillis == null && awaitRelease == null) return false
        if (durationMillis != null && durationMillis !in 1_000L..3_600_000L) return false
        return remoteAction { session ->
            var firstFullAccepted = false
            var firstFullAttempted = false
            var firstKeysReleased = false
            var startedAt = 0L
            var startComplete = false
            var stopComplete = false
            var uncertaintyReported = false
            fun reportUncertain() {
                if (!uncertaintyReported) {
                    uncertaintyReported = true
                    try { onUncertain() } catch (_: Exception) {}
                }
            }
            try {
                startComplete = captureLocked(session, 150,
                    onHeld = {
                        firstFullAccepted = true
                        startedAt = clockMillis()
                        // Ownership must be visible even if the accepted write settled after cancel.
                        onStarted()
                    },
                    onFullPressAttempt = { firstFullAttempted = true },
                    shouldSkipFull = releaseRequested,
                    onReleased = { firstKeysReleased = it })
                if (!startComplete) {
                    reportUncertain()
                    return@remoteAction false
                }
                // A release during half-down is a successful no-op, not a BULB start.
                if (!firstFullAccepted) return@remoteAction true
                currentCoroutineContext().ensureActive()
                if (!releaseRequested()) {
                    if (durationMillis == null) {
                        awaitRelease!!()
                    } else {
                        val remaining = durationMillis - (clockMillis() - startedAt).coerceAtLeast(0L)
                        if (remaining > 0) {
                            if (awaitRelease == null) wait(remaining)
                            else awaitReleaseOrDeadline(remaining, awaitRelease)
                        }
                    }
                }
            } finally {
                if (firstFullAccepted && firstKeysReleased) {
                    // Only the end/cleanup is non-cancellable. Queued start presses stay cancellable.
                    withContext(NonCancellable) {
                        stopComplete = try {
                            if (isExposureActive() == false) true
                            else {
                                try { onStopping() } catch (_: Exception) {}
                                captureLocked(session, 150)
                            }
                        } catch (_: Exception) { false }
                        if (!stopComplete) reportUncertain()
                    }
                } else if (firstFullAttempted || !firstKeysReleased) {
                    // captureLocked already attempted both key-ups. Never guess with another press.
                    reportUncertain()
                }
            }
            startComplete && stopComplete
        }
    }

    private suspend fun awaitReleaseOrDeadline(millis: Long, awaitRelease: suspend () -> Unit) {
        coroutineScope {
            val deadline = async { wait(millis) }
            val release = async { awaitRelease() }
            try {
                select<Unit> {
                    deadline.onAwait { }
                    release.onAwait { }
                }
            } finally {
                deadline.cancel()
                release.cancel()
            }
        }
    }

    /** Bluetooth BULB is latched: a complete press starts it, another press ends it.
     * Only send that second pulse after fresh feedback or explicit operator confirmation.
     * Uncertain results are never retried, because a press could start rather than stop exposure.
     */
    suspend fun runBulbTimer(
        durationMillis: Long,
        awaitStartedConfirmation: suspend () -> Boolean,
        isExposureConfirmed: () -> Boolean = { false },
        isExposureActive: () -> Boolean? = { null },
        onStartPulseSent: () -> Unit = {},
        onConfirmed: () -> Unit = {},
        onStopPulse: () -> Unit = {},
        onUncertain: () -> Unit = {}
    ): Boolean {
        if (durationMillis !in 1_000L..3_600_000L) return false
        return remoteAction { session ->
            var startAccepted = false
            var fullPressAccepted = false
            var fullPressAttempted = false
            var startedAt = 0L
            var confirmed = false
            var stopAccepted = false
            var uncertaintyReported = false
            fun reportUncertain() {
                if (!uncertaintyReported) {
                    uncertaintyReported = true
                    try { onUncertain() } catch (_: Exception) {}
                }
            }
            try {
                startAccepted = captureLocked(session, 150, onHeld = {
                    fullPressAccepted = true
                    startedAt = clockMillis()
                    onStartPulseSent()
                }, onFullPressAttempt = { fullPressAttempted = true })
                if (!startAccepted) { reportUncertain(); return@remoteAction false }
                currentCoroutineContext().ensureActive()
                confirmed = awaitStartedConfirmation()
                if (!confirmed) return@remoteAction false
                onConfirmed()
                val remaining = durationMillis - (clockMillis() - startedAt).coerceAtLeast(0L)
                if (remaining > 0) wait(remaining)
            } catch (cancelled: CancellationException) {
                if (fullPressAttempted && !fullPressAccepted) reportUncertain()
                throw cancelled
            } catch (failure: Exception) {
                reportUncertain()
                throw failure
            } finally {
                if ((fullPressAccepted && confirmed) || (fullPressAttempted && isExposureConfirmed())) {
                    withContext(NonCancellable) {
                        if (isExposureActive() == false) {
                            // A native BULB timer or operator may already have ended exposure.
                            // Another shutter press would start a NEW exposure, not stop the old one.
                            stopAccepted = true
                        } else {
                            try { onStopPulse() } catch (_: Exception) {}
                            stopAccepted = try { captureLocked(session, 150) } catch (_: Exception) { false }
                            if (!stopAccepted) reportUncertain()
                        }
                    }
                } else if (fullPressAccepted) {
                    releaseShutterLocked(session)
                    reportUncertain()
                }
            }
            startAccepted && confirmed && stopAccepted
        }
    }

    /** Best-effort key releases; these alone do not end a latched Bluetooth BULB exposure. */
    suspend fun releaseAll(expectedSession: Any? = null): Boolean {
        if (expectedSession != null && port.currentSession() !== expectedSession) return false
        return remoteAction { session ->
            if (expectedSession != null && session !== expectedSession) return@remoteAction false
            withContext(NonCancellable) {
                val shutter = releaseShutterLocked(session)
                val movie = releaseCommand(session, SonyBleConstants.CMD_MOVIE_REC_UP)
                shutter && movie
            }
        }
    }

    /**
     * Submits [totalShots] verified single gestures, starting immediately, with monotonic
     * start-to-start [intervalSeconds] cadence. Valid ranges are 1..999 shots and 1..3600 seconds.
     * Owns the remote across waits, pins one session for the entire series, and stops on first
     * failure. Elapsed deadlines are skipped after overruns (no catch-up burst).
     *
     * [onTriggerSubmitted] increments only after successful press AND both releases; it is not a
     * camera exposure/file count. [onNextTriggerAt] receives the next monotonic deadline before
     * each inter-trigger wait, never a wall-clock timestamp or a deadline after the final trigger.
     */
    suspend fun runIntervalometer(
        intervalSeconds: Int,
        totalShots: Int,
        onTriggerSubmitted: (Int, Int) -> Unit,
        onNextTriggerAt: (Long) -> Unit = {}
    ): Boolean {
        if (intervalSeconds !in 1..3600 || totalShots !in 1..999) return false
        return remoteAction { session ->
            val intervalMillis = intervalSeconds * 1_000L
            var deadline = clockMillis()
            for (shot in 1..totalShots) {
                if (!captureLocked(session, 150)) return@remoteAction false
                currentCoroutineContext().ensureActive()
                onTriggerSubmitted(shot, totalShots)
                if (shot < totalShots) {
                    deadline += intervalMillis
                    val now = clockMillis()
                    if (deadline < now) {
                        deadline += ((now - deadline) / intervalMillis + 1) * intervalMillis
                    }
                    onNextTriggerAt(deadline)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val remaining = deadline - clockMillis()
                        if (remaining <= 0) break
                        wait(remaining)
                    }
                }
            }
            true
        }
    }

    private suspend fun remoteAction(action: suspend (Any) -> Boolean): Boolean {
        if (!remoteOwnership.tryLock()) return false
        try {
            currentCoroutineContext().ensureActive()
            val session = port.currentSession() ?: return false
            val result = action(session)
            currentCoroutineContext().ensureActive()
            return result && port.currentSession() === session
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            currentCoroutineContext().ensureActive()
            return false
        } finally {
            remoteOwnership.unlock()
        }
    }

    private suspend fun captureLocked(
        session: Any,
        holdMillis: Long,
        onHeld: () -> Unit = {},
        onFullPressAttempt: () -> Unit = {},
        shouldSkipFull: (() -> Boolean)? = null,
        onReleased: (Boolean) -> Unit = {},
        holdAction: (suspend () -> Unit)? = null
    ): Boolean {
        var pressed = false
        var skipped = false
        var released: Boolean
        try {
            // Enter cleanup even if initiation fails/throws: acceptance may be uncertain.
            if (sendPinned(session, SonyBleConstants.CMD_HALF_SHUTTER_DOWN)) {
                wait(150)
                skipped = shouldSkipFull?.invoke() == true
                if (!skipped) {
                    onFullPressAttempt()
                    pressed = sendPinned(session, SonyBleConstants.CMD_FULL_SHUTTER_DOWN)
                    if (pressed) {
                        // Record acceptance before observing pending caller cancellation.
                        onHeld()
                        currentCoroutineContext().ensureActive()
                        if (holdAction == null) wait(holdMillis) else holdAction()
                    }
                }
            }
        } finally {
            released = releaseShutterLocked(session)
            onReleased(released)
        }
        return (pressed || skipped) && released
    }

    private suspend fun sendPinned(session: Any, command: ByteArray): Boolean {
        currentCoroutineContext().ensureActive()
        if (port.currentSession() !== session) return false
        // The Android port settles an accepted finite write but keeps queue waiting cancellable.
        return port.currentSession() === session && port.send(session, command.copyOf())
    }

    private suspend fun releaseShutterLocked(session: Any): Boolean = withContext(NonCancellable) {
        val full = releaseCommand(session, SonyBleConstants.CMD_FULL_SHUTTER_UP)
        val spaced = try {
            wait(50)
            true
        } catch (_: Exception) {
            false
        }
        // Do not short-circuit: one release (or spacing) failure must not suppress the other.
        val half = releaseCommand(session, SonyBleConstants.CMD_HALF_SHUTTER_UP)
        full && spaced && half
    }

    private suspend fun releaseCommand(session: Any, command: ByteArray): Boolean = try {
        sendPinned(session, command)
    } catch (_: Exception) {
        false
    }
}
