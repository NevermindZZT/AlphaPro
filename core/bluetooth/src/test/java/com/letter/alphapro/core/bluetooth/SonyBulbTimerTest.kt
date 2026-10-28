package com.letter.alphapro.core.bluetooth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** JVM-only tests: Sony Bluetooth BULB uses two complete presses, never a held shutter. */
class SonyBulbTimerTest {
    private data class Write(val session: Any, val command: ByteArray, val at: Long, val active: Boolean) {
        val code: Int get() = command[1].toInt() and 0xff
    }

    /** An explicitly cancellable suspension, with no real-time sleeps or scheduler dependency. */
    private class Gate {
        val entered = CompletableDeferred<Unit>()
        private val pending = CompletableDeferred<Unit>()

        suspend fun await() {
            entered.complete(Unit)
            pending.await()
        }
    }

    private class Harness : RemoteShutterPort {
        var session: Any? = Any()
        var now = 0L
        val writes = mutableListOf<Write>()
        val waits = mutableListOf<Long>()
        val flow = mutableListOf<String>()
        var starts = 0
        var confirmations = 0
        var stops = 0
        var uncertainties = 0
        var onSend: suspend (Int) -> Boolean = { true }
        var onWait: suspend (Long) -> Unit = {}

        override fun currentSession(): Any? = session

        override suspend fun send(session: Any, command: ByteArray): Boolean {
            // Do not mask cancellation in this fake: a real sender can fail or be cancelled.
            currentCoroutineContext().ensureActive()
            check(session === this.session) { "A stale command reached a replacement connection" }
            val code = command[1].toInt() and 0xff
            writes += Write(session, command.copyOf(), now, currentCoroutineContext().isActive)
            flow += "send:$code"
            return onSend(code)
        }

        val controller = SonyRemoteShutterController(this, { now }) { millis ->
            currentCoroutineContext().ensureActive()
            waits += millis
            flow += "wait:$millis"
            onWait(millis)
            currentCoroutineContext().ensureActive()
            now += millis
        }

        suspend fun run(
            duration: Long = 2_000L,
            fresh: () -> Boolean = { false },
            awaitStarted: suspend () -> Boolean = { true }
        ): Boolean = controller.runBulbTimer(
            durationMillis = duration,
            awaitStartedConfirmation = {
                flow += "await"
                awaitStarted()
            },
            isExposureConfirmed = fresh,
            onStartPulseSent = { starts++; flow += "start" },
            onConfirmed = { confirmations++; flow += "confirmed" },
            onStopPulse = { stops++; flow += "stop" },
            onUncertain = { uncertainties++; flow += "uncertain" }
        )

        fun codes(): List<Int> = writes.map { it.code }

        fun assertOneStartAndNoStop() {
            assertEquals(1, codes().count { it == 9 })
            assertEquals(1, codes().count { it == 7 })
            assertEquals(listOf(7, 9, 8, 6), codes().take(4))
            // Additional best-effort releases are safe; another full-down is not.
            assertTrue(codes().drop(4).all { it == 8 || it == 6 })
            assertEquals(1, starts)
            assertEquals(0, stops)
            assertTrue("Unconfirmed exposure must be reported as uncertain", uncertainties > 0)
        }
    }

    @Test
    fun confirmedStartProducesExactlyTwoVerifiedPressAndReleasePulses() = runBlocking {
        val h = Harness()
        val token = h.session

        assertTrue(h.run())

        assertEquals(listOf(7, 9, 8, 6, 7, 9, 8, 6), h.codes())
        assertEquals(listOf(150L, 150L, 50L, 1_800L, 150L, 150L, 50L), h.waits)
        assertEquals(listOf(0L, 150L, 300L, 350L, 2_150L, 2_300L, 2_450L, 2_500L),
            h.writes.map { it.at })
        assertEquals(listOf(
            "send:7", "wait:150", "send:9", "start", "wait:150", "send:8", "wait:50", "send:6",
            "await", "confirmed", "wait:1800", "stop", "send:7", "wait:150", "send:9",
            "wait:150", "send:8", "wait:50", "send:6"
        ), h.flow)
        h.writes.forEach {
            assertArrayEquals(byteArrayOf(1, it.code.toByte()), it.command)
            assertSame(token, it.session)
        }
        assertEquals(1, h.starts)
        assertEquals(1, h.confirmations)
        assertEquals(1, h.stops)
        assertEquals(0, h.uncertainties)
    }

    @Test
    fun manualConfirmationDelayIsSubtractedFromTimeSinceAcceptedFullDown() = runBlocking {
        val h = Harness()

        assertTrue(h.run(awaitStarted = {
            assertEquals(350L, h.now)
            assertEquals(listOf(7, 9, 8, 6), h.codes())
            h.now += 750L
            true
        }))

        assertEquals(listOf(150L, 150L, 50L, 1_050L, 150L, 150L, 50L), h.waits)
        // The deadline starts at the first accepted 09, not at confirmation or key-up.
        assertEquals(2_150L, h.writes[4].at)
        assertEquals(150L, h.writes[1].at)
    }

    @Test
    fun lateConfirmationDoesNotWaitAnotherWholeExposureDuration() = runBlocking {
        val h = Harness()
        assertTrue(h.run(awaitStarted = { h.now += 5_000L; true }))
        assertEquals(listOf(150L, 150L, 50L, 150L, 150L, 50L), h.waits)
        assertEquals(5_350L, h.writes[4].at)
        assertEquals(2, h.codes().count { it == 9 })
    }

    @Test
    fun durationStartsAtAcceptedFullDownNotAtTransportSubmission() = runBlocking {
        val h = Harness()
        var fullDowns = 0
        h.onSend = { code ->
            if (code == 9 && ++fullDowns == 1) h.now += 400L
            true
        }

        assertTrue(h.run())

        assertEquals(listOf(150L, 150L, 50L, 1_800L, 150L, 150L, 50L), h.waits)
        assertEquals(2_550L, h.writes[4].at)
    }

    @Test
    fun falseConfirmationNeverSendsASecondFullDownAndReportsUncertainty() = runBlocking {
        val h = Harness()
        assertFalse(h.run(awaitStarted = { false }))
        h.assertOneStartAndNoStop()
        assertEquals(0, h.confirmations)
        assertTrue(h.waits.all { it <= 150L })
    }

    @Test
    fun timeoutStyleConfirmationFailureNeverSendsASecondFullDown() = runBlocking {
        val h = Harness()
        assertFalse(h.run(awaitStarted = {
            throw IllegalStateException("Timed out waiting for fresh BULB feedback")
        }))
        h.assertOneStartAndNoStop()
        assertEquals(0, h.confirmations)
    }

    @Test
    fun confirmationTimeoutCancellationReportsUncertaintyWithoutSecondPulse() = runBlocking {
        val h = Harness()
        var timeout: TimeoutCancellationException? = null
        try {
            h.run(awaitStarted = {
                // A zero timeout throws immediately: no wall-clock or test scheduler is needed.
                withTimeout(0L) { true }
            })
            error("Confirmation timeout must propagate")
        } catch (failure: TimeoutCancellationException) {
            timeout = failure
        }

        assertNotNull(timeout)
        h.assertOneStartAndNoStop()
        assertEquals(0, h.confirmations)
    }

    @Test(timeout = 5_000)
    fun cancellationDuringUnconfirmedAwaitOnlyReleasesAndPropagates() = runBlocking {
        val h = Harness()
        val gate = Gate()
        var cancelled: CancellationException? = null
        var returned = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                h.run(awaitStarted = { gate.await(); false })
                returned = true
            } catch (failure: CancellationException) {
                cancelled = failure
                throw failure
            }
        }
        gate.entered.await()
        job.cancel(CancellationException("cancel unconfirmed await"))
        job.join()

        assertTrue(job.isCancelled)
        assertNotNull(cancelled)
        assertFalse(returned)
        h.assertOneStartAndNoStop()
        assertEquals(0, h.confirmations)
        assertTrue(h.writes.all { it.active })
        // Cleanup also releases ownership.
        assertTrue(h.controller.toggleMovieRecording())
    }

    @Test(timeout = 5_000)
    fun cancellationAfterConfirmationSendsSecondPulseBeforePropagating() = runBlocking {
        val h = Harness()
        val gate = Gate()
        h.onWait = { if (it == 1_800L) gate.await() }
        var cancelled: CancellationException? = null
        var returned = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                h.run()
                returned = true
            } catch (failure: CancellationException) {
                cancelled = failure
                throw failure
            }
        }
        gate.entered.await()
        assertEquals(listOf(7, 9, 8, 6), h.codes())
        assertEquals(1, h.confirmations)
        job.cancel(CancellationException("cancel confirmed countdown"))
        job.join()

        assertTrue(job.isCancelled)
        assertNotNull(cancelled)
        assertFalse(returned)
        assertEquals(listOf(7, 9, 8, 6, 7, 9, 8, 6), h.codes())
        assertEquals(listOf(150L, 150L, 50L, 1_800L, 150L, 150L, 50L), h.waits)
        assertTrue(h.writes.drop(4).all { it.active })
        assertEquals(1, h.stops)
        assertEquals(0, h.uncertainties)
        assertTrue(h.controller.takePhoto())
    }

    @Test(timeout = 5_000)
    fun freshExposureFeedbackAllowsCancellationCleanupWhileConfirmationIsPending() = runBlocking {
        val h = Harness()
        val gate = Gate()
        var fresh = false
        var cancelled: CancellationException? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                h.run(fresh = { fresh }, awaitStarted = { gate.await(); false })
                error("Cancellation must propagate")
            } catch (failure: CancellationException) {
                cancelled = failure
                throw failure
            }
        }
        gate.entered.await()
        fresh = true
        job.cancel(CancellationException("feedback arrived while awaiting confirmation"))
        job.join()

        assertTrue(job.isCancelled)
        assertNotNull(cancelled)
        assertEquals(0, h.confirmations)
        assertEquals(1, h.stops)
        assertEquals(0, h.uncertainties)
        assertEquals(listOf(7, 9, 8, 6, 7, 9, 8, 6), h.codes())
        assertTrue(h.writes.drop(4).all { it.active })
    }

    @Test
    fun disconnectedSessionNeverReceivesAStopPulseAndReportsUncertainty() = runBlocking {
        val h = Harness()
        assertFalse(h.run(awaitStarted = { h.session = null; true }))
        assertEquals(listOf(7, 9, 8, 6), h.codes())
        assertEquals(1, h.confirmations)
        assertTrue(h.uncertainties > 0)
    }

    @Test
    fun equalButDifferentSessionTokenCannotRedirectStopToNewConnection() = runBlocking {
        data class Token(val value: Int)
        val h = Harness()
        val old = Token(1)
        val replacement = Token(1)
        h.session = old

        assertFalse(h.run(awaitStarted = { h.session = replacement; true }))

        assertEquals(old, replacement)
        assertFalse(old === replacement)
        assertEquals(listOf(7, 9, 8, 6), h.codes())
        assertTrue(h.writes.all { it.session === old })
        assertTrue(h.uncertainties > 0)
    }

    @Test(timeout = 5_000)
    fun cancellationAfterConnectionChangesCannotSendCleanupIntoReplacement() = runBlocking {
        val h = Harness()
        val old = h.session
        val gate = Gate()
        h.onWait = { if (it == 1_800L) gate.await() }
        var cancelled = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                h.run()
                error("Cancellation must propagate")
            } catch (failure: CancellationException) {
                cancelled = true
                throw failure
            }
        }
        gate.entered.await()
        h.session = Any()
        job.cancel(CancellationException("connection changed"))
        job.join()

        assertTrue(cancelled)
        assertEquals(listOf(7, 9, 8, 6), h.codes())
        assertTrue(h.writes.all { it.session === old })
        assertTrue(h.uncertainties > 0)
    }

    @Test
    fun failedStartCommandsReportUncertaintyWithoutTryingAnotherFullDown() = runBlocking {
        for (failedCode in listOf(7, 9, 8, 6)) {
            val h = Harness()
            h.onSend = { it != failedCode }
            var awaited = false

            assertFalse("Start code $failedCode", h.run(awaitStarted = { awaited = true; true }))

            assertFalse(awaited)
            assertEquals(0, h.stops)
            assertTrue(h.codes().count { it == 9 } <= 1)
            assertEquals(listOf(8, 6), h.codes().takeLast(2))
            assertTrue("Start code $failedCode must report uncertainty", h.uncertainties > 0)
        }
    }

    @Test
    fun throwingStartCommandsReportUncertaintyWithoutTryingAnotherFullDown() = runBlocking {
        for (failedCode in listOf(7, 9, 8, 6)) {
            val h = Harness()
            h.onSend = { code -> if (code == failedCode) error("start transport failure"); true }
            var awaited = false

            assertFalse("Start code $failedCode", h.run(awaitStarted = { awaited = true; true }))

            assertFalse(awaited)
            assertEquals(0, h.stops)
            assertTrue(h.codes().count { it == 9 } <= 1)
            assertEquals(listOf(8, 6), h.codes().takeLast(2))
            assertTrue("Start code $failedCode must report uncertainty", h.uncertainties > 0)
        }
    }

    @Test
    fun failedStopCommandsReportUncertaintyAndDoNotRetryThePulse() = runBlocking {
        for (failedCode in listOf(7, 9, 8, 6)) {
            val h = Harness()
            h.onSend = { code -> !(h.stops > 0 && code == failedCode) }

            assertFalse("Stop code $failedCode", h.run())

            assertEquals(1, h.starts)
            assertEquals(1, h.confirmations)
            assertEquals(1, h.stops)
            assertEquals(2, h.codes().count { it == 7 })
            assertEquals(if (failedCode == 7) 1 else 2, h.codes().count { it == 9 })
            assertEquals(listOf(8, 6), h.codes().takeLast(2))
            assertTrue("Stop code $failedCode must report uncertainty", h.uncertainties > 0)
        }
    }

    @Test
    fun throwingStopCommandsReportUncertaintyAndStillReleaseBothStages() = runBlocking {
        for (failedCode in listOf(7, 9, 8, 6)) {
            val h = Harness()
            h.onSend = { code ->
                if (h.stops > 0 && code == failedCode) error("stop transport failure")
                true
            }

            assertFalse("Stop code $failedCode", h.run())

            assertEquals(1, h.stops)
            assertEquals(2, h.codes().count { it == 7 })
            assertEquals(if (failedCode == 7) 1 else 2, h.codes().count { it == 9 })
            assertEquals(listOf(8, 6), h.codes().takeLast(2))
            assertTrue("Stop code $failedCode must report uncertainty", h.uncertainties > 0)
        }
    }

    @Test
    fun senderCancellationBeforeStartAcceptancePropagatesAndReportsUncertainty() = runBlocking {
        val h = Harness()
        h.onSend = { code ->
            if (code == 9) throw CancellationException("sender cancelled before acceptance")
            true
        }
        var cancelled: CancellationException? = null
        try {
            h.run()
            error("Sender cancellation must propagate")
        } catch (failure: CancellationException) {
            cancelled = failure
        }

        assertNotNull(cancelled)
        assertEquals(listOf(7, 9, 8, 6), h.codes())
        assertEquals(0, h.starts)
        assertEquals(0, h.confirmations)
        assertEquals(0, h.stops)
        assertTrue(h.uncertainties > 0)
        // Compare behavior, not exception identity: coroutine stack recovery may copy it.
    }

    @Test
    fun invalidDurationBoundsSendNoFramesAndInvokeNoCallbacks() = runBlocking {
        for (duration in listOf(Long.MIN_VALUE, -1L, 0L, 999L, 3_600_001L, Long.MAX_VALUE)) {
            val h = Harness()
            assertFalse(h.run(duration, fresh = { error("Invalid timer queried feedback") },
                awaitStarted = { error("Invalid timer awaited confirmation") }))
            assertTrue(h.writes.isEmpty())
            assertTrue(h.waits.isEmpty())
            assertTrue(h.flow.isEmpty())
        }
        for (duration in listOf(1_000L, 3_600_000L)) {
            val h = Harness()
            assertTrue(h.run(duration))
            assertEquals(2, h.codes().count { it == 9 })
        }
    }

    @Test(timeout = 5_000)
    fun activeBulbTimerRejectsCompetingRemoteActionsWithoutSendingFrames() = runBlocking {
        val h = Harness()
        val gate = Gate()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            h.run(awaitStarted = { gate.await(); false })
        }
        gate.entered.await()
        try {
            val before = h.writes.size
            assertFalse(h.controller.takePhoto())
            assertFalse(h.controller.halfPressAfDown())
            assertFalse(h.controller.halfPressAfUp())
            assertFalse(h.controller.holdShutter(1_000))
            assertFalse(h.controller.toggleMovieRecording())
            assertFalse(h.controller.releaseAll())
            assertFalse(h.run(awaitStarted = { error("Contending timer awaited confirmation") }))
            assertEquals(before, h.writes.size)
            assertEquals(1, h.starts)
        } finally {
            job.cancel(CancellationException("finish contention test"))
            job.join()
        }
        h.assertOneStartAndNoStop()
        assertTrue(h.controller.takePhoto())
    }

    @Test
    fun unavailableSessionRejectsTimerWithoutFramesOrConfirmation() = runBlocking {
        val h = Harness()
        h.session = null
        assertFalse(h.run(awaitStarted = { error("No session must not await confirmation") }))
        assertTrue(h.writes.isEmpty())
        assertTrue(h.waits.isEmpty())
        assertTrue(h.flow.isEmpty())
    }
}
