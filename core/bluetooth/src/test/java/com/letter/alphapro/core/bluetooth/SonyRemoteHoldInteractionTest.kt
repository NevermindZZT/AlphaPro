package com.letter.alphapro.core.bluetooth

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gesture-owned R2 interactions. All time and release events are deterministic, with no sleeps. */
class SonyRemoteHoldInteractionTest {
    private data class Write(val session: Any, val bytes: ByteArray, val at: Long) {
        val code: Int get() = bytes[1].toInt() and 0xff
    }

    private class Harness : RemoteShutterPort {
        var session: Any? = Any()
        var now = 0L
        val writes = mutableListOf<Write>()
        val waits = mutableListOf<Long>()
        var onSend: suspend (Int) -> Boolean = { true }
        var onWait: suspend (Long) -> Unit = {}
        override fun currentSession(): Any? = session
        override suspend fun send(session: Any, command: ByteArray): Boolean {
            currentCoroutineContext().ensureActive()
            check(session === this.session) { "A replacement connection received an obsolete gesture" }
            writes += Write(session, command.copyOf(), now)
            return onSend(command[1].toInt() and 0xff)
        }
        val controller = SonyRemoteShutterController(this, { now }) { millis ->
            currentCoroutineContext().ensureActive()
            waits += millis
            onWait(millis)
            currentCoroutineContext().ensureActive()
            now += millis
        }
        fun codes() = writes.map { it.code }
    }

    private val pulse = listOf(7, 9, 8, 6)

    @Test fun manualBulbNeedsNoConfirmationAndOnlyWaitsForVerifiedPulseTiming() = runBlocking {
        val h = Harness()
        val original = h.session
        val events = mutableListOf<String>()
        assertTrue(h.controller.runBulbFromPress(
            awaitRelease = {
                events += "release"
                assertEquals(pulse, h.codes())
                assertEquals(350L, h.now)
            },
            onStarted = {
                events += "started"
                assertEquals(listOf(7, 9), h.codes())
                assertEquals(150L, h.now)
            },
            onStopping = { events += "stopping" },
            onUncertain = { error("Unknown feedback is allowed, not a failed command") }
        ))
        assertEquals(listOf("started", "release", "stopping"), events)
        assertEquals(pulse + pulse, h.codes())
        assertEquals(listOf(150L, 150L, 50L, 150L, 150L, 50L), h.waits)
        h.writes.forEach {
            assertSame(original, it.session)
            assertArrayEquals(byteArrayOf(1, it.code.toByte()), it.bytes)
        }
    }

    @Test fun variableHoldUsesTheReleaseEventNotAFixedDuration() = runBlocking {
        for (heldFor in listOf(0L, 37L, 4_000L, 7_200_000L)) {
            val h = Harness()
            val release = CompletableDeferred<Unit>()
            var held = 0
            var result = false
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                result = h.controller.holdShutterUntilReleased({ false }, { release.await() }) {
                    held++
                    assertEquals(listOf(7, 9), h.codes())
                }
            }
            assertEquals(1, held)
            assertEquals(listOf(7, 9), h.codes())
            assertEquals(listOf(150L), h.waits)
            h.now += heldFor
            release.complete(Unit)
            job.join()
            assertTrue(result)
            assertEquals(pulse, h.codes())
            assertEquals(150L + heldFor, h.writes[2].at)
            assertEquals(listOf(150L, 50L), h.waits)
        }
    }

    @Test fun quickReleaseDuringHalfDownNeverFiresHoldOrBulb() = runBlocking {
        for (bulb in listOf(false, true)) {
            val h = Harness()
            var released = false
            h.onWait = { if (it == 150L) released = true }
            val result = if (bulb) h.controller.runBulbFromPress(
                releaseRequested = { released },
                awaitRelease = { error("Already released") },
                onStarted = { error("Must not fire") },
                onStopping = { error("Must not end an unstarted exposure") },
                onUncertain = { error("Clean early release is a successful no-op") }
            ) else h.controller.holdShutterUntilReleased(
                { released }, { error("Already released") }, { error("Must not hold") })
            assertTrue(result)
            assertEquals(listOf(7, 8, 6), h.codes())
            assertEquals(listOf(150L, 50L), h.waits)
        }
    }

    @Test fun releaseDuringFirstFullPulseFinishesGestureThenEndsWithoutAwaitingAgain() = runBlocking {
        val h = Harness()
        var released = false
        var waits = 0
        h.onWait = { if (it == 150L && ++waits == 2) released = true }
        assertTrue(h.controller.runBulbFromPress(
            releaseRequested = { released }, awaitRelease = { error("Release is already observed") }))
        assertEquals(pulse + pulse, h.codes())
        assertEquals(listOf(0L, 150L, 300L, 350L, 350L, 500L, 650L, 700L), h.writes.map { it.at })
    }

    @Test fun cancellationSettlingFirst09PublishesOwnershipThenEndsExactlyOnce() = runBlocking {
        val h = Harness()
        var started = 0
        var stopping = 0
        var uncertain = 0
        h.onSend = {
            if (it == 9 && h.codes().count { code -> code == 9 } == 1) {
                currentCoroutineContext()[Job]!!.cancel()
            }
            true // Accepted-write settlement is the port contract, even with pending cancellation.
        }
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            h.controller.runBulbFromPress(awaitRelease = { error("Already cancelled") },
                onStarted = { started++ }, onStopping = { stopping++ }, onUncertain = { uncertain++ })
        }
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(1, started)
        assertEquals(1, stopping)
        assertEquals(0, uncertain)
        assertEquals(pulse + pulse, h.codes())
    }

    @Test fun cancellationDuringManualWaitEndsAndUnlocks() = runBlocking {
        val h = Harness()
        val awaiting = CompletableDeferred<Unit>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            h.controller.runBulbFromPress(awaitRelease = {
                awaiting.complete(Unit)
                awaitCancellation()
            })
        }
        awaiting.await()
        job.cancel()
        job.join()
        assertEquals(pulse + pulse, h.codes())
        assertTrue(h.controller.takePhoto())
    }

    @Test fun holdOwnershipCallbackAlsoPrecedesPendingCancellation() = runBlocking {
        val h = Harness()
        var held = 0
        h.onSend = { code ->
            if (code == 9) currentCoroutineContext()[Job]!!.cancel()
            true
        }
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            h.controller.holdShutterUntilReleased({ false }, { error("Cancelled") }, { held++ })
        }
        job.join()
        assertEquals(1, held)
        assertEquals(pulse, h.codes())
    }

    @Test fun queuedStartCancellationDoesNotBecomeANonCancellablePress() = runBlocking {
        val h = Harness()
        val queued = CompletableDeferred<Unit>()
        h.onSend = { code ->
            if (code == 7) {
                assertTrue(currentCoroutineContext().isActive)
                queued.complete(Unit)
                awaitCancellation()
            }
            true
        }
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            h.controller.runBulbFromPress(awaitRelease = { error("No start") })
        }
        queued.await()
        job.cancel()
        job.join()
        assertEquals(listOf(7, 8, 6), h.codes())
    }

    @Test fun cancellationBeforeFullDownDoesNotSendAnEndPulse() = runBlocking {
        val h = Harness()
        val preparing = CompletableDeferred<Unit>()
        h.onWait = {
            if (it == 150L) {
                preparing.complete(Unit)
                awaitCancellation()
            }
        }
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            h.controller.runBulbFromPress(awaitRelease = { error("No start") })
        }
        preparing.await()
        job.cancel()
        job.join()
        assertEquals(listOf(7, 8, 6), h.codes())
    }

    @Test fun failedOrThrowingFirst09AndKeyUpsNeverGuessWithAnotherPress() = runBlocking {
        for (failedCode in listOf(9, 8, 6)) for (throws in listOf(false, true)) {
            val h = Harness()
            var uncertain = 0
            var starts = 0
            h.onSend = { code ->
                if (code == failedCode) {
                    if (throws) error("Transport failure")
                    false
                } else true
            }
            assertFalse(h.controller.runBulbFromPress(awaitRelease = { error("Failed start") },
                onStarted = { starts++ }, onUncertain = { uncertain++ }))
            assertEquals(pulse, h.codes())
            assertEquals(if (failedCode == 9) 0 else 1, starts)
            assertEquals(1, uncertain)
        }
    }

    @Test fun failedStopCommandsAreNotRetriedAndStillReleaseBothKeys() = runBlocking {
        for (failedCode in listOf(7, 9, 8, 6)) for (throws in listOf(false, true)) {
            val h = Harness()
            var uncertain = 0
            h.onSend = { code ->
                if (h.writes.size > 4 && code == failedCode) {
                    if (throws) error("Stop failed")
                    false
                } else true
            }
            assertFalse(h.controller.runBulbFromPress(awaitRelease = {}, onUncertain = { uncertain++ }))
            assertEquals(pulse + if (failedCode == 7) listOf(7, 8, 6) else pulse, h.codes())
            assertEquals(1, uncertain)
        }
    }

    @Test fun failedHoldCallbacksAndReleasesStillCleanUpAndReturnFalse() = runBlocking {
        for (failure in listOf("held", "release", "up")) {
            val h = Harness()
            h.onSend = { it != 8 || failure != "up" }
            assertFalse(h.controller.holdShutterUntilReleased({ false }, {
                if (failure == "release") error("Release callback failed")
            }, { if (failure == "held") error("Held callback failed") }))
            assertEquals(pulse, h.codes())
        }
    }

    @Test fun failedStartCallbackStillEndsAcceptedCleanlyReleasedStart() = runBlocking {
        val h = Harness()
        assertFalse(h.controller.runBulbFromPress(awaitRelease = {}, onStarted = { error("UI failed") }))
        assertEquals(pulse + pulse, h.codes())
    }

    @Test fun freshInactiveFeedbackSkipsEndSoItCannotRestartExposure() = runBlocking {
        val h = Harness()
        assertTrue(h.controller.runBulbFromPress(awaitRelease = {}, isExposureActive = { false },
            onStopping = { error("Already ended") }, onUncertain = { error("Known inactive") }))
        assertEquals(pulse, h.codes())
    }

    @Test fun replacementSessionNeverReceivesTheEndPulse() = runBlocking {
        val h = Harness()
        val original = h.session
        var uncertain = 0
        assertFalse(h.controller.runBulbFromPress(awaitRelease = { h.session = Any() },
            onUncertain = { uncertain++ }))
        assertEquals(pulse, h.codes())
        assertEquals(1, uncertain)
        h.writes.forEach { assertSame(original, it.session) }
    }

    @Test fun replacementDuringHoldNeverReceivesCleanup() = runBlocking {
        val h = Harness()
        val original = h.session
        assertFalse(h.controller.holdShutterUntilReleased({ false }, { h.session = Any() }))
        assertEquals(listOf(7, 9), h.codes())
        h.writes.forEach { assertSame(original, it.session) }
    }

    @Test fun timedBulbCompensatesForFirstPulseFromAccepted09() = runBlocking {
        val h = Harness()
        assertTrue(h.controller.runBulbFromPress(durationMillis = 1_000))
        assertEquals(pulse + pulse, h.codes())
        assertEquals(listOf(150L, 150L, 50L, 800L, 150L, 150L, 50L), h.waits)
        assertEquals(1_150L, h.writes[4].at)
    }

    @Test fun overrunFirstPulseSkipsRemainingTimerDelay() = runBlocking {
        val h = Harness()
        assertTrue(h.controller.runBulbFromPress(durationMillis = 1_000, onStarted = { h.now += 2_000 }))
        assertEquals(listOf(150L, 150L, 50L, 150L, 150L, 50L), h.waits)
    }

    @Test fun timedBulbCanFinishOnReleaseBeforeItsDeadline() = runBlocking {
        val h = Harness()
        var timerCancelled = false
        h.onWait = {
            if (it == 800L) {
                try { awaitCancellation() } finally { timerCancelled = true }
            }
        }
        assertTrue(h.controller.runBulbFromPress(durationMillis = 1_000, awaitRelease = {}))
        assertTrue(timerCancelled)
        assertEquals(pulse + pulse, h.codes())
        assertEquals(350L, h.writes[4].at)
    }

    @Test fun timedDeadlineCancelsUnusedReleaseWait() = runBlocking {
        val h = Harness()
        var releaseCancelled = false
        assertTrue(h.controller.runBulbFromPress(durationMillis = 1_000, awaitRelease = {
            try { awaitCancellation() } finally { releaseCancelled = true }
        }))
        assertTrue(releaseCancelled)
        assertEquals(pulse + pulse, h.codes())
    }

    @Test fun invalidConfigurationNeverSends() = runBlocking {
        val h = Harness()
        assertFalse(h.controller.runBulbFromPress())
        for (duration in listOf(Long.MIN_VALUE, -1L, 0L, 999L, 3_600_001L, Long.MAX_VALUE)) {
            assertFalse(h.controller.runBulbFromPress(durationMillis = duration))
        }
        assertTrue(h.writes.isEmpty())
        assertTrue(h.waits.isEmpty())
    }

    @Test fun wholeHoldAndBulbActionsRejectCompetingCommands() = runBlocking {
        for (bulb in listOf(false, true)) {
            val h = Harness()
            val release = CompletableDeferred<Unit>()
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                if (bulb) h.controller.runBulbFromPress(awaitRelease = { release.await() })
                else h.controller.holdShutterUntilReleased({ false }, { release.await() })
            }
            val before = h.codes()
            assertFalse(h.controller.takePhoto())
            assertFalse(h.controller.halfPressAfDown())
            assertFalse(h.controller.halfPressAfUp())
            assertFalse(h.controller.toggleMovieRecording())
            assertFalse(h.controller.releaseAll())
            assertFalse(h.controller.holdShutterUntilReleased({ false }, {}))
            assertFalse(h.controller.runBulbFromPress(awaitRelease = {}))
            assertEquals(before, h.codes())
            release.complete(Unit)
            job.join()
            assertTrue(h.controller.halfPressAfUp())
        }
    }

    @Test fun captureUnifiedEarlyReleaseProducesIdenticalSingleShotSequence() = runBlocking {
        val h = Harness()
        var released = false
        h.onWait = { if (it == 150L) released = true }
        var heldSignalled = 0
        assertTrue(h.controller.captureUnified(
            releaseRequested = { released },
            awaitRelease = { error("Must not await release on early release") },
            onHeld = { heldSignalled++ }
        ))
        assertEquals(1, heldSignalled)
        assertEquals(pulse, h.codes())
        assertEquals(listOf(0L, 150L, 300L, 350L), h.writes.map { it.at })
        assertEquals(listOf(150L, 150L, 50L), h.waits)
    }

    @Test fun captureUnifiedSustainedHoldMaintainsFullPressUntilReleased() = runBlocking {
        for (heldFor in listOf(0L, 37L, 500L, 4_000L)) {
            val h = Harness()
            val release = CompletableDeferred<Unit>()
            var heldSignalled = 0
            var result = false
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                result = h.controller.captureUnified(
                    releaseRequested = { false },
                    awaitRelease = { release.await() },
                    onHeld = { heldSignalled++ }
                )
            }
            assertEquals(1, heldSignalled)
            assertEquals(listOf(7, 9), h.codes())
            assertEquals(listOf(150L), h.waits)
            h.now += heldFor
            release.complete(Unit)
            job.join()
            assertTrue(result)
            assertEquals(pulse, h.codes())
            val expectedWait = if (heldFor < 150L) 150L - heldFor else 0L
            val expectedReleaseAt = 150L + heldFor + expectedWait
            assertEquals(expectedReleaseAt, h.writes[2].at)
            assertEquals(expectedReleaseAt + 50L, h.writes[3].at)
        }
    }
}
