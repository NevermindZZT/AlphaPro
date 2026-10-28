package com.letter.alphapro.core.bluetooth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SonyRemoteShutterControllerTest {
    private data class Write(val session: Any, val command: ByteArray, val at: Long) {
        val code: Int get() = command[1].toInt() and 0xff
    }

    private class FakePort : RemoteShutterPort {
        var session: Any? = Any()
        var now = 0L
        val writes = mutableListOf<Write>()
        val waits = mutableListOf<Long>()
        var onSend: suspend (Int) -> Boolean = { true }
        var onWait: suspend (Long) -> Unit = {}
        override fun currentSession(): Any? = session
        override suspend fun send(session: Any, command: ByteArray): Boolean {
            check(session === this.session) { "A replacement session received an old command" }
            writes += Write(session, command.copyOf(), now)
            return onSend(command[1].toInt() and 0xff)
        }
        fun controller() = SonyRemoteShutterController(this, { now }) { millis ->
            waits += millis
            onWait(millis)
            now += millis
        }
        fun codes(): List<Int> = writes.map { it.code }
    }

    @Test
    fun singlePreservesVerifiedBytesAndTiming() = runBlocking {
        val port = FakePort()
        val token = port.session
        assertTrue(port.controller().takePhoto())
        assertEquals(listOf(7, 9, 8, 6), port.codes())
        port.writes.forEach { write ->
            assertArrayEquals(byteArrayOf(1, write.code.toByte()), write.command)
            assertSame(token, write.session)
        }
        assertEquals(listOf(0L, 150L, 300L, 350L), port.writes.map { it.at })
        assertEquals(listOf(150L, 150L, 50L), port.waits)
    }

    @Test
    fun moviePreservesVerifiedBytesAndTiming() = runBlocking {
        val port = FakePort()
        assertTrue(port.controller().toggleMovieRecording())
        assertEquals(listOf(15, 14), port.codes())
        assertEquals(listOf(0L, 150L), port.writes.map { it.at })
        assertEquals(listOf(150L), port.waits)
    }

    @Test
    fun holdSignalsOnlyAfterFullDownAndUsesBoundedDuration() = runBlocking {
        for (duration in listOf(100L, 4_000L, 3_600_000L)) {
            val port = FakePort()
            var signalled = 0
            assertTrue(port.controller().holdShutter(duration) {
                assertEquals(listOf(7, 9), port.codes())
                assertEquals(150L, port.now)
                signalled++
            })
            assertEquals(1, signalled)
            assertEquals(listOf(7, 9, 8, 6), port.codes())
            assertEquals(listOf(150L, duration, 50L), port.waits)
        }
    }

    @Test
    fun malformedConfigurationNeverSendsOrReportsProgress() = runBlocking {
        val port = FakePort()
        val controller = port.controller()
        for (duration in listOf(Long.MIN_VALUE, -1L, 0L, 99L, 3_600_001L, Long.MAX_VALUE)) {
            assertFalse(controller.holdShutter(duration) { error("Invalid hold signalled") })
        }
        for ((interval, total) in listOf(0 to 1, -1 to 1, 3601 to 1, 1 to 0, 1 to -1, 1 to 1000)) {
            assertFalse(controller.runIntervalometer(interval, total,
                { _, _ -> error("Invalid series progressed") },
                { error("Invalid series scheduled") }))
        }
        assertTrue(port.writes.isEmpty())
        assertTrue(port.waits.isEmpty())
    }

    @Test
    fun missingSessionRejectsEveryAction() = runBlocking {
        val port = FakePort().apply { session = null }
        val controller = port.controller()
        assertFalse(controller.halfPressAfDown())
        assertFalse(controller.halfPressAfUp())
        assertFalse(controller.takePhoto())
        assertFalse(controller.toggleMovieRecording())
        assertFalse(controller.holdShutter(100))
        assertFalse(controller.releaseAll())
        assertFalse(controller.runIntervalometer(1, 1, { _, _ -> error("No session") }))
        assertTrue(port.writes.isEmpty())
    }

    @Test
    fun failedPressOrEitherReleaseIsFalseAndBothReleasesAreAttempted() = runBlocking {
        for (failed in listOf(7, 9, 8, 6)) {
            val port = FakePort().apply { onSend = { it != failed } }
            assertFalse("Failure at $failed must be reported", port.controller().takePhoto())
            assertEquals(if (failed == 7) listOf(7, 8, 6) else listOf(7, 9, 8, 6), port.codes())
            assertEquals(1, port.codes().count { it == failed }) // No automatic press/release retry.
        }
    }

    @Test
    fun pressAndReleaseExceptionsAreFalseWithoutSuppressingSecondRelease() = runBlocking {
        for (failed in listOf(7, 9, 8, 6)) {
            val port = FakePort().apply {
                onSend = { if (it == failed) throw IllegalStateException("write"); true }
            }
            assertFalse(port.controller().takePhoto())
            assertEquals(if (failed == 7) listOf(7, 8, 6) else listOf(7, 9, 8, 6), port.codes())
        }
    }

    @Test
    fun releaseCancellationExceptionIsFailureNotSuppressionOfHalfUp() = runBlocking {
        val port = FakePort().apply {
            onSend = { if (it == 8) throw CancellationException("release transport"); true }
        }
        assertFalse(port.controller().takePhoto())
        assertEquals(listOf(7, 9, 8, 6), port.codes())
    }

    @Test
    fun failedSpacingStillAttemptsHalfUp() = runBlocking {
        val port = FakePort().apply {
            onWait = { if (it == 50L) throw IllegalStateException("timer") }
        }
        assertFalse(port.controller().takePhoto())
        assertEquals(listOf(7, 9, 8, 6), port.codes())
    }

    @Test
    fun failedHoldPressNeverSignalsHeld() = runBlocking {
        for (failed in listOf(7, 9)) {
            val port = FakePort().apply { onSend = { it != failed } }
            assertFalse(port.controller().holdShutter(100) { error("Not held") })
            assertEquals(listOf(8, 6), port.codes().takeLast(2))
        }
    }

    @Test
    fun throwingHeldCallbackStillReleases() = runBlocking {
        val port = FakePort()
        assertFalse(port.controller().holdShutter(100) { error("UI callback failed") })
        assertEquals(listOf(7, 9, 8, 6), port.codes())
        assertEquals(listOf(150L, 50L), port.waits)
    }

    @Test
    fun afDownHoldsAndVerifiedAfUpOnlyReleasesHalfPress() = runBlocking {
        val port = FakePort()
        val controller = port.controller()
        assertTrue(controller.halfPressAfDown())
        assertEquals(listOf(7), port.codes())
        assertTrue(port.waits.isEmpty())
        assertTrue(controller.halfPressAfUp())
        assertEquals(listOf(7, 6), port.codes())
        assertTrue(port.waits.isEmpty())
    }

    @Test
    fun failedAndExceptionalAfDownCleanUpBothStages() = runBlocking {
        for (throws in listOf(false, true)) {
            val port = FakePort().apply {
                onSend = { if (it == 7) { if (throws) error("AF down"); false } else true }
            }
            assertFalse(port.controller().halfPressAfDown())
            assertEquals(listOf(7, 8, 6), port.codes())
        }
    }

    @Test
    fun releaseAllAttemptsEveryReleaseEvenIfFirstTwoThrow() = runBlocking {
        val port = FakePort().apply {
            onSend = { if (it == 8 || it == 6) error("release"); true }
        }
        assertFalse(port.controller().releaseAll())
        assertEquals(listOf(8, 6, 14), port.codes())
        assertEquals(listOf(50L), port.waits)
    }

    @Test
    fun movieUpRunsForDownFailureDownExceptionAndUpFailure() = runBlocking {
        for (failure in listOf("down-false", "down-exception", "up-false", "up-exception")) {
            val port = FakePort().apply {
                onSend = { code ->
                    when {
                        code == 15 && failure == "down-false" -> false
                        code == 15 && failure == "down-exception" -> error("down")
                        code == 14 && failure == "up-false" -> false
                        code == 14 && failure == "up-exception" -> error("up")
                        else -> true
                    }
                }
            }
            assertFalse(port.controller().toggleMovieRecording())
            assertEquals(listOf(15, 14), port.codes())
        }
    }

    @Test(timeout = 5_000)
    fun cancellationAtHalfFullAfDelayAndHoldAlwaysReleases() = runBlocking {
        for (point in listOf("half", "full", "af-delay", "hold")) {
            val port = FakePort()
            val entered = CompletableDeferred<Unit>()
            var waitCount = 0
            port.onSend = { code ->
                if ((point == "half" && code == 7) || (point == "full" && code == 9)) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
                if (code == 8 || code == 6) assertTrue(currentCoroutineContext().isActive)
                true
            }
            port.onWait = {
                waitCount++
                if ((point == "af-delay" && waitCount == 1) || (point == "hold" && waitCount == 2)) {
                    entered.complete(Unit)
                    awaitCancellation()
                }
            }
            var held = 0
            var cancelled: CancellationException? = null
            val controller = port.controller()
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    if (point == "hold") controller.holdShutter(2_000) { held++ }
                    else controller.takePhoto()
                    error("Cancellation was swallowed")
                } catch (failure: CancellationException) {
                    cancelled = failure
                    throw failure
                }
            }
            entered.await()
            val reason = CancellationException("caller-$point")
            job.cancel(reason)
            job.join()
            // Coroutine stack recovery may copy CancellationException across suspensions.
            assertNotNull(cancelled)
            assertEquals(reason.message, cancelled?.message)
            assertTrue(job.isCancelled)
            assertEquals(listOf(8, 6), port.codes().takeLast(2))
            assertEquals(if (point == "hold") 1 else 0, held)
            assertEquals(50L, port.waits.last())
            // Ownership must be available again after cleanup.
            port.onSend = { true }
            port.onWait = {}
            assertTrue(controller.toggleMovieRecording())
        }
    }

    @Test(timeout = 5_000)
    fun cancelledAfDownReleasesAndPropagates() = runBlocking {
        val port = FakePort()
        val entered = CompletableDeferred<Unit>()
        port.onSend = { if (it == 7) { entered.complete(Unit); awaitCancellation() }; true }
        val job = launch(start = CoroutineStart.UNDISPATCHED) { port.controller().halfPressAfDown() }
        entered.await()
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(listOf(7, 8, 6), port.codes())
    }

    @Test
    fun originalPressCancellationSurvivesCleanupExceptions() = runBlocking {
        val original = CancellationException("original")
        val port = FakePort().apply {
            onSend = {
                when (it) {
                    9 -> throw original
                    8 -> throw CancellationException("cleanup")
                    6 -> error("other cleanup")
                    else -> true
                }
            }
        }
        var caught: CancellationException? = null
        try {
            port.controller().takePhoto()
        } catch (failure: CancellationException) {
            caught = failure
        }
        assertSame(original, caught)
        assertEquals(listOf(7, 9, 8, 6), port.codes())
    }

    @Test(timeout = 5_000)
    fun cancellationDuringReleaseDelayStillRunsHalfUpBeforeReturning() = runBlocking {
        val port = FakePort()
        val entered = CompletableDeferred<Unit>()
        val continueRelease = CompletableDeferred<Unit>()
        port.onWait = {
            if (it == 50L) {
                entered.complete(Unit)
                continueRelease.await()
                assertTrue(currentCoroutineContext().isActive)
            }
        }
        val controller = port.controller()
        var cancelled: CancellationException? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                controller.takePhoto()
            } catch (failure: CancellationException) {
                cancelled = failure
                throw failure
            }
        }
        entered.await()
        val reason = CancellationException("during release")
        job.cancel(reason)
        assertFalse(job.isCompleted)
        assertFalse(controller.halfPressAfDown())
        continueRelease.complete(Unit)
        job.join()
        assertSame(reason, cancelled)
        assertEquals(listOf(7, 9, 8, 6), port.codes())
    }

    @Test(timeout = 5_000)
    fun movieCancellationInDownOrDelayAlwaysRunsUp() = runBlocking {
        for (duringDown in listOf(true, false)) {
            val port = FakePort()
            val entered = CompletableDeferred<Unit>()
            port.onSend = {
                if (it == 15 && duringDown) { entered.complete(Unit); awaitCancellation() }
                true
            }
            port.onWait = { entered.complete(Unit); awaitCancellation() }
            val job = launch(start = CoroutineStart.UNDISPATCHED) {
                port.controller().toggleMovieRecording()
            }
            entered.await()
            job.cancel()
            job.join()
            assertTrue(job.isCancelled)
            assertEquals(listOf(15, 14), port.codes())
        }
    }

    @Test(timeout = 5_000)
    fun competingRemoteActionsRejectImmediatelyWithoutInterleaving() = runBlocking {
        val port = FakePort()
        val pause = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        var firstWait = true
        port.onWait = {
            if (firstWait) {
                firstWait = false
                entered.complete(Unit)
                pause.await()
            }
        }
        val controller = port.controller()
        var result = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) { result = controller.takePhoto() }
        entered.await()
        assertFalse(controller.halfPressAfDown())
        assertFalse(controller.halfPressAfUp())
        assertFalse(controller.takePhoto())
        assertFalse(controller.toggleMovieRecording())
        assertFalse(controller.holdShutter(100))
        assertFalse(controller.releaseAll())
        assertFalse(controller.runIntervalometer(1, 1, { _, _ -> error("Busy progress") }))
        assertEquals(listOf(7), port.codes())
        pause.complete(Unit)
        job.join()
        assertTrue(result)
        assertEquals(listOf(7, 9, 8, 6), port.codes())
        assertTrue(controller.toggleMovieRecording())
        assertEquals(listOf(7, 9, 8, 6, 15, 14), port.codes())
    }

    @Test
    fun replacementTokenEvenIfValueEqualReceivesNoOldCommands() = runBlocking {
        data class EqualToken(val value: Int)
        for (replaceAfter in listOf(7, 9, 8)) {
            val port = FakePort().apply { session = EqualToken(1) }
            val original = port.session
            port.onSend = {
                if (it == replaceAfter) port.session = EqualToken(1)
                true
            }
            assertFalse(port.controller().takePhoto())
            assertEquals(when (replaceAfter) {
                7 -> listOf(7)
                9 -> listOf(7, 9)
                else -> listOf(7, 9, 8)
            }, port.codes())
            port.writes.forEach { assertSame(original, it.session) }
        }
    }

    @Test
    fun replacementDuringMovieDoesNotReceiveMovieUp() = runBlocking {
        val port = FakePort().apply { onWait = { session = Any() } }
        assertFalse(port.controller().toggleMovieRecording())
        assertEquals(listOf(15), port.codes())
    }

    @Test
    fun intervalUsesStartToStartCadenceAndReportsOnlyCompletedGestures() = runBlocking {
        val port = FakePort().apply { now = 123L }
        val progress = mutableListOf<Pair<Int, Int>>()
        val next = mutableListOf<Long>()
        assertTrue(port.controller().runIntervalometer(1, 3,
            { current, total ->
                assertEquals(6, port.codes().last())
                progress += current to total
            }, { next += it }))
        assertEquals(listOf(123L, 1123L, 2123L), port.writes.filter { it.code == 7 }.map { it.at })
        assertEquals(listOf(1123L, 2123L), next)
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), progress)
        assertEquals(listOf(150L, 150L, 50L, 650L, 150L, 150L, 50L, 650L, 150L, 150L, 50L), port.waits)
    }

    @Test
    fun intervalOverrunSkipsElapsedDeadlinesWithoutCatchUpBurst() = runBlocking {
        val port = FakePort()
        port.onSend = { if (it == 9) port.now += 2_200; true }
        val deadlines = mutableListOf<Long>()
        assertTrue(port.controller().runIntervalometer(1, 3, { _, _ -> }, { deadlines += it }))
        assertEquals(listOf(0L, 3000L, 6000L), port.writes.filter { it.code == 7 }.map { it.at })
        assertEquals(listOf(3000L, 6000L), deadlines)
        assertEquals(2, port.waits.count { it == 450L })
    }

    @Test
    fun intervalStopsOnFirstFailureAndNeverReportsFalseProgress() = runBlocking {
        for (failedCode in listOf(7, 9, 8, 6)) {
            val port = FakePort()
            var shot = 0
            port.onSend = { code ->
                if (code == 7) shot++
                !(shot == 2 && code == failedCode)
            }
            val progress = mutableListOf<Int>()
            val deadlines = mutableListOf<Long>()
            assertFalse(port.controller().runIntervalometer(1, 4,
                { current, _ -> progress += current }, { deadlines += it }))
            assertEquals(listOf(1), progress)
            assertEquals(listOf(1000L), deadlines)
            assertEquals(2, port.codes().count { it == 7 })
            assertEquals(listOf(8, 6), port.codes().takeLast(2))
        }
    }

    @Test
    fun intervalDoesNotReportFirstFailedGesture() = runBlocking {
        val port = FakePort().apply { onSend = { it != 8 } }
        assertFalse(port.controller().runIntervalometer(1, 3,
            { _, _ -> error("Release failed; no progress") }, { error("No next trigger") }))
        assertEquals(listOf(7, 9, 8, 6), port.codes())
    }

    @Test
    fun intervalPinsOneSessionAcrossEntireSeries() = runBlocking {
        val port = FakePort()
        val progress = mutableListOf<Int>()
        assertFalse(port.controller().runIntervalometer(1, 3,
            { current, _ -> progress += current }, { port.session = Any() }))
        assertEquals(listOf(1), progress)
        assertEquals(listOf(7, 9, 8, 6), port.codes())
    }

    @Test(timeout = 5_000)
    fun intervalOwnsRemoteBetweenTriggersAndCancellationStopsWithoutExtraPress() = runBlocking {
        val port = FakePort()
        val waiting = CompletableDeferred<Unit>()
        port.onWait = { if (it == 650L) { waiting.complete(Unit); awaitCancellation() } }
        val controller = port.controller()
        val progress = mutableListOf<Int>()
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            controller.runIntervalometer(1, 3, { current, _ -> progress += current })
        }
        waiting.await()
        assertFalse(controller.takePhoto())
        assertFalse(controller.toggleMovieRecording())
        assertFalse(controller.releaseAll())
        job.cancel()
        job.join()
        assertTrue(job.isCancelled)
        assertEquals(listOf(1), progress)
        assertEquals(listOf(7, 9, 8, 6), port.codes())
        port.onWait = {}
        assertTrue(controller.takePhoto())
    }

    @Test
    fun intervalAcceptsUpperBoundsWithoutRealTimeWaiting() = runBlocking {
        val port = FakePort()
        var submitted = 0
        assertTrue(port.controller().runIntervalometer(3600, 999,
            { current, total ->
                assertEquals(999, total)
                submitted = current
            }))
        assertEquals(999, submitted)
        assertEquals(999, port.codes().count { it == 7 })
        assertEquals(998L * 3_600_000L, port.writes.last { it.code == 7 }.at)
    }

    @Test
    fun verifiedAfUpReportsHalfReleaseFailureWithoutChangingBaselineBytes() = runBlocking {
        val port = FakePort().apply { onSend = { it != 6 } }
        assertFalse(port.controller().halfPressAfUp())
        assertEquals(listOf(6), port.codes())
    }

    @Test
    fun releaseAllPreservesOrderOnSuccess() = runBlocking {
        val port = FakePort()
        assertTrue(port.controller().releaseAll())
        assertEquals(listOf(8, 6, 14), port.codes())
        assertEquals(listOf(0L, 50L, 50L), port.writes.map { it.at })
    }

    @Test
    fun intervalCallbackFailureStopsWithoutAnotherPress() = runBlocking {
        val port = FakePort()
        assertFalse(port.controller().runIntervalometer(1, 2, { _, _ -> error("observer") }))
        assertEquals(listOf(7, 9, 8, 6), port.codes())
    }
}
