package com.letter.alphapro.core.bluetooth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GattOperationLaneTest {
    private val lane = GattOperationLane()
    private val connection = Any()
    private val attribute = "characteristic-uuid:7"

    @Test
    fun matchesConnectionIdentityKindAndAttributeInstance() = runBlocking {
        // These are equal by value, but are distinct connection identities.
        val active = EqualToken(1)
        val impostor = EqualToken(1)
        lane.activate(active)
        val operation = async(start = CoroutineStart.UNDISPATCHED) {
            lane.execute(active, GattOperationKind.READ, attribute) { true }
        }

        assertFalse(lane.complete(impostor, GattOperationKind.READ, attribute, 0))
        assertFalse(lane.complete(active, GattOperationKind.WRITE, attribute, 0))
        assertFalse(lane.complete(active, GattOperationKind.DESCRIPTOR, attribute, 0))
        assertFalse(lane.complete(active, GattOperationKind.READ, "other-uuid:7", 0))
        assertFalse(lane.complete(active, GattOperationKind.READ, "characteristic-uuid:8", 0))
        assertFalse(operation.isCompleted)

        val bytes = byteArrayOf(1, 2, 3)
        assertTrue(lane.complete(active, GattOperationKind.READ, attribute, 19, bytes))
        bytes[0] = 99
        val result = withTimeout(2_000) { operation.await() }
        assertEquals(19, result.status)
        assertArrayEquals(byteArrayOf(1, 2, 3), result.value)
        assertFalse(lane.complete(active, GattOperationKind.READ, attribute, 0))
    }

    @Test
    fun pendingIsInstalledBeforeSynchronousCallbackForEveryKind() = runBlocking {
        lane.activate(connection)
        for (kind in GattOperationKind.values()) {
            val key = if (kind == GattOperationKind.MTU) "mtu" else attribute
            val result = lane.execute(connection, kind, key) {
                assertTrue(lane.complete(connection, kind, key, 0))
                true
            }
            assertEquals(0, result.status)
            assertNull(result.value)
        }
    }

    @Test
    fun writeWaitsForCallbackAndSerializesFollowingOperation() = runBlocking {
        lane.activate(connection)
        val initiations = mutableListOf<String>()
        val secondInitiated = CompletableDeferred<Unit>()
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            // Also models NO_RESPONSE: there is deliberately no early-success path.
            lane.execute(connection, GattOperationKind.WRITE, attribute) {
                initiations += "write"
                true
            }
        }
        val second = async(start = CoroutineStart.UNDISPATCHED) {
            lane.execute(connection, GattOperationKind.READ, attribute) {
                initiations += "read"
                secondInitiated.complete(Unit)
                true
            }
        }
        assertEquals(listOf("write"), initiations)
        assertFalse(first.isCompleted)
        assertFalse(second.isCompleted)
        assertTrue(lane.complete(connection, GattOperationKind.WRITE, attribute, 0))
        withTimeout(2_000) { secondInitiated.await() }
        assertEquals(listOf("write", "read"), initiations)
        assertTrue(lane.complete(connection, GattOperationKind.READ, attribute, 0))
        withTimeout(2_000) {
            first.await()
            second.await()
        }
        Unit
    }

    @Test
    fun timeoutPoisonsTokenAndLateCallbackCannotCompleteNextOperation() = runBlocking {
        lane.activate(connection)
        val error = expectFailure<GattOperationException> {
            lane.execute(connection, GattOperationKind.READ, attribute, timeoutMs = 25) { true }
        }
        assertEquals("GATT operation timed out", error.message)
        assertFalse(lane.complete(connection, GattOperationKind.READ, attribute, 0))
        lane.activate(connection)
        assertUnavailable(connection)

        val replacement = Any()
        lane.activate(replacement)
        val operation = async(start = CoroutineStart.UNDISPATCHED) {
            lane.execute(replacement, GattOperationKind.READ, attribute) { true }
        }
        assertFalse(lane.complete(connection, GattOperationKind.READ, attribute, 0))
        assertFalse(operation.isCompleted)
        assertTrue(lane.complete(replacement, GattOperationKind.READ, attribute, 0))
        withTimeout(2_000) { operation.await() }
        // Switching away and back must not resurrect the poisoned identity, either.
        lane.activate(connection)
        assertUnavailable(connection)
    }

    @Test
    fun cancellationAfterAcceptancePoisonsTokenAndRejectsAlreadyQueuedOperation() = runBlocking {
        lane.activate(connection)
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            lane.execute(connection, GattOperationKind.WRITE, attribute) { true }
        }
        var nextInitiated = false
        val queued = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching {
                lane.execute(connection, GattOperationKind.WRITE, attribute) {
                    nextInitiated = true
                    true
                }
            }
        }
        first.cancelAndJoin()
        val failure = withTimeout(2_000) { queued.await() }.exceptionOrNull()
        assertTrue(failure is GattOperationException)
        assertFalse(nextInitiated)
        assertFalse(lane.complete(connection, GattOperationKind.WRITE, attribute, 0))
        lane.activate(connection)
        assertUnavailable(connection)
        val replacement = Any()
        lane.activate(replacement)
        assertSynchronousSuccess(replacement)
    }

    @Test
    fun cancellationDuringAcceptedInitiationPoisonsEvenWithSynchronousCallback() = runBlocking {
        lane.activate(connection)
        val operation = launch(start = CoroutineStart.UNDISPATCHED) {
            lane.execute(connection, GattOperationKind.WRITE, attribute) {
                assertTrue(lane.complete(connection, GattOperationKind.WRITE, attribute, 0))
                cancel("cancelled while initiating")
                true
            }
        }
        operation.join()
        assertTrue(operation.isCancelled)
        lane.activate(connection)
        assertUnavailable(connection)
    }

    @Test
    fun cancellationWhileQueuedDoesNotPoisonConnection() = runBlocking {
        lane.activate(connection)
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            lane.execute(connection, GattOperationKind.READ, attribute) { true }
        }
        var queuedInitiated = false
        val queued = launch(start = CoroutineStart.UNDISPATCHED) {
            lane.execute(connection, GattOperationKind.WRITE, attribute) {
                queuedInitiated = true
                true
            }
        }
        queued.cancelAndJoin()
        assertFalse(queuedInitiated)
        assertTrue(lane.complete(connection, GattOperationKind.READ, attribute, 0))
        withTimeout(2_000) { first.await() }
        assertSynchronousSuccess(connection)
    }

    @Test
    fun alreadyCancelledCallerDoesNotInitiateOrPoisonConnection() = runBlocking {
        lane.activate(connection)
        var initiated = false
        val operation = launch(start = CoroutineStart.UNDISPATCHED) {
            cancel("cancelled before initiating")
            lane.execute(connection, GattOperationKind.READ, attribute) {
                initiated = true
                true
            }
        }
        operation.join()
        assertFalse(initiated)
        assertSynchronousSuccess(connection)
    }

    @Test
    fun callerTimeoutRemainsCancellationAndPoisonsAcceptedOperation() = runBlocking {
        lane.activate(connection)
        expectFailure<CancellationException> {
            withTimeout(25) {
                lane.execute(connection, GattOperationKind.READ, attribute) { true }
            }
        }
        assertUnavailable(connection)
    }

    @Test
    fun disconnectFromAnotherThreadWakesInflightPromptly() = runBlocking {
        lane.activate(connection)
        val operation = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { lane.execute(connection, GattOperationKind.READ, attribute) { true } }
        }
        withContext(Dispatchers.Default) { lane.close(connection) }
        val failure = withTimeout(2_000) { operation.await() }.exceptionOrNull()
        assertTrue(failure is GattOperationException)
        assertEquals("GATT connection closed", failure?.message)
        assertFalse(lane.complete(connection, GattOperationKind.READ, attribute, 0))
        lane.activate(connection)
        assertUnavailable(connection)
    }

    @Test
    fun freshActivationWakesOldOperationAndIgnoresStaleClose() = runBlocking {
        lane.activate(connection)
        val old = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { lane.execute(connection, GattOperationKind.READ, attribute) { true } }
        }
        val replacement = Any()
        lane.activate(replacement)
        val failure = withTimeout(2_000) { old.await() }.exceptionOrNull()
        assertEquals("GATT connection replaced", failure?.message)
        lane.close(connection)
        assertSynchronousSuccess(replacement)
    }

    @Test
    fun callbacksCanArriveFromAnotherThread() = runBlocking {
        lane.activate(connection)
        val operation = async(start = CoroutineStart.UNDISPATCHED) {
            lane.execute(connection, GattOperationKind.DESCRIPTOR, attribute) { true }
        }
        withContext(Dispatchers.Default) {
            assertTrue(lane.complete(connection, GattOperationKind.DESCRIPTOR, attribute, 133))
        }
        assertEquals(133, withTimeout(2_000) { operation.await() }.status)
    }

    @Test
    fun falseInitiationThrowsSafeReasonClearsPendingAndAllowsRetry() = runBlocking {
        lane.activate(connection)
        val failure = expectFailure<GattOperationException> {
            lane.execute(connection, GattOperationKind.WRITE, attribute) { false }
        }
        assertEquals("GATT operation initiation rejected", failure.message)
        assertFalse(lane.complete(connection, GattOperationKind.WRITE, attribute, 0))
        assertSynchronousSuccess(connection)
    }

    @Test
    fun inactiveOrWrongConnectionNeverInitiates() = runBlocking {
        assertUnavailable(connection)
        lane.activate(connection)
        assertUnavailable(Any())
        assertSynchronousSuccess(connection)
    }

    @Test
    fun settlingAcceptedOperationSurvivesCancellationAndCrossThreadCallback() = runBlocking {
        lane.activate(connection)
        val recorded = CompletableDeferred<GattOperationResult>()
        val operation = launch(start = CoroutineStart.UNDISPATCHED) {
            val result = lane.execute(
                connection, GattOperationKind.WRITE, attribute,
                timeoutMs = 2_000, settleAfterInitiation = true
            ) { true }
            // A cancelled Deferred cannot expose this result through await(); record it in-body.
            recorded.complete(result)
        }
        var nextInitiated = false
        val queued = async(start = CoroutineStart.UNDISPATCHED) {
            lane.execute(connection, GattOperationKind.READ, attribute) {
                nextInitiated = true
                assertTrue(lane.complete(connection, GattOperationKind.READ, attribute, 0))
                true
            }
        }

        operation.cancel()
        assertFalse(operation.isCompleted)
        assertFalse(nextInitiated)
        assertTrue(lane.isActive(connection))
        withContext(Dispatchers.Default) {
            assertTrue(lane.complete(connection, GattOperationKind.WRITE, attribute, 0, byteArrayOf(7)))
        }
        val result = withTimeout(2_000) { recorded.await() }
        assertEquals(0, result.status)
        assertArrayEquals(byteArrayOf(7), result.value)
        withTimeout(2_000) {
            operation.join()
            assertEquals(0, queued.await().status)
        }
        assertTrue(operation.isCancelled)
        assertTrue(nextInitiated)
        assertTrue(lane.isActive(connection))
        assertSynchronousSuccess(connection)
    }

    @Test
    fun settlingResultIsRecordedBeforeOriginalCallerCancellationCheckAndFinally() = runBlocking {
        lane.activate(connection)
        val recorded = CompletableDeferred<GattOperationResult>()
        var cancellationObserved = false
        var finallyRan = false
        var passedCancellationCheck = false
        val operation = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val result = lane.execute(
                    connection, GattOperationKind.WRITE, attribute,
                    timeoutMs = 2_000, settleAfterInitiation = true
                ) { true }
                recorded.complete(result)
                currentCoroutineContext().ensureActive()
                passedCancellationCheck = true
            } catch (error: CancellationException) {
                cancellationObserved = true
                throw error
            } finally {
                finallyRan = true
            }
        }
        operation.cancel()
        assertTrue(lane.complete(connection, GattOperationKind.WRITE, attribute, 0))
        assertEquals(0, withTimeout(2_000) { recorded.await() }.status)
        withTimeout(2_000) { operation.join() }
        assertTrue(cancellationObserved)
        assertTrue(finallyRan)
        assertFalse(passedCancellationCheck)
        assertTrue(lane.isActive(connection))
        assertSynchronousSuccess(connection)
    }

    @Test
    fun settlingCancellationDuringAcceptedInitiationReturnsSynchronousCallback() = runBlocking {
        lane.activate(connection)
        var recordedStatus: Int? = null
        val operation = launch(start = CoroutineStart.UNDISPATCHED) {
            recordedStatus = lane.execute(
                connection, GattOperationKind.WRITE, attribute, settleAfterInitiation = true
            ) {
                assertTrue(lane.complete(connection, GattOperationKind.WRITE, attribute, 0))
                cancel("cancelled while initiating")
                true
            }.status
        }
        withTimeout(2_000) { operation.join() }
        assertEquals(0, recordedStatus ?: -1)
        assertTrue(operation.isCancelled)
        assertTrue(lane.isActive(connection))
        assertSynchronousSuccess(connection)
    }

    @Test
    fun settlingCancellationWhileQueuedNeverInitiatesOrPoisonsConnection() = runBlocking {
        lane.activate(connection)
        val first = async(start = CoroutineStart.UNDISPATCHED) {
            lane.execute(connection, GattOperationKind.READ, attribute) { true }
        }
        var queuedInitiated = false
        val queued = launch(start = CoroutineStart.UNDISPATCHED) {
            lane.execute(
                connection, GattOperationKind.WRITE, attribute, settleAfterInitiation = true
            ) {
                queuedInitiated = true
                true
            }
        }
        withTimeout(2_000) { queued.cancelAndJoin() }
        assertFalse(queuedInitiated)
        assertTrue(lane.isActive(connection))
        assertTrue(lane.complete(connection, GattOperationKind.READ, attribute, 0))
        withTimeout(2_000) { first.await() }
        assertFalse(queuedInitiated)
        assertSynchronousSuccess(connection)
    }

    @Test
    fun settlingAlreadyCancelledCallerNeverInitiatesOrPoisonsConnection() = runBlocking {
        lane.activate(connection)
        var initiated = false
        val operation = launch(start = CoroutineStart.UNDISPATCHED) {
            cancel("cancelled before initiating")
            lane.execute(
                connection, GattOperationKind.WRITE, attribute, settleAfterInitiation = true
            ) {
                initiated = true
                true
            }
        }
        withTimeout(2_000) { operation.join() }
        assertTrue(operation.isCancelled)
        assertFalse(initiated)
        assertTrue(lane.isActive(connection))
        assertSynchronousSuccess(connection)
    }

    @Test
    fun settlingCancelledOperationStillRequiresExactCallbackMatch() = runBlocking {
        val active = EqualToken(1)
        val impostor = EqualToken(1)
        lane.activate(active)
        val recorded = CompletableDeferred<GattOperationResult>()
        val operation = launch(start = CoroutineStart.UNDISPATCHED) {
            recorded.complete(lane.execute(
                active, GattOperationKind.WRITE, attribute,
                timeoutMs = 2_000, settleAfterInitiation = true
            ) { true })
        }
        operation.cancel()
        withContext(Dispatchers.Default) {
            assertFalse(lane.complete(impostor, GattOperationKind.WRITE, attribute, 0))
            assertFalse(lane.complete(active, GattOperationKind.READ, attribute, 0))
            assertFalse(lane.complete(active, GattOperationKind.DESCRIPTOR, attribute, 0))
            assertFalse(lane.complete(active, GattOperationKind.WRITE, "other-uuid:7", 0))
            assertFalse(lane.complete(active, GattOperationKind.WRITE, "characteristic-uuid:8", 0))
        }
        assertFalse(recorded.isCompleted)
        assertFalse(operation.isCompleted)
        withContext(Dispatchers.Default) {
            assertTrue(lane.complete(active, GattOperationKind.WRITE, attribute, 133))
            assertFalse(lane.complete(active, GattOperationKind.WRITE, attribute, 0))
        }
        assertEquals(133, withTimeout(2_000) { recorded.await() }.status)
        withTimeout(2_000) { operation.join() }
        assertTrue(lane.isActive(active))
        assertSynchronousSuccess(active)
    }

    @Test
    fun settlingRejectedInitiationClearsPendingAndAllowsRetry() = runBlocking {
        lane.activate(connection)
        val failure = expectFailure<GattOperationException> {
            lane.execute(
                connection, GattOperationKind.WRITE, attribute, settleAfterInitiation = true
            ) { false }
        }
        assertEquals("GATT operation initiation rejected", failure.message)
        assertFalse(lane.complete(connection, GattOperationKind.WRITE, attribute, 0))
        assertTrue(lane.isActive(connection))
        assertSynchronousSuccess(connection)
    }

    @Test
    fun settlingTimeoutStillPoisonsConnectionEvenAfterCallerCancellation() = runBlocking {
        lane.activate(connection)
        val recorded = CompletableDeferred<Throwable?>()
        val operation = launch(start = CoroutineStart.UNDISPATCHED) {
            recorded.complete(runCatching {
                lane.execute(
                    connection, GattOperationKind.WRITE, attribute,
                    timeoutMs = 25, settleAfterInitiation = true
                ) { true }
            }.exceptionOrNull())
        }
        operation.cancel()
        val failure = withTimeout(2_000) { recorded.await() }
        withTimeout(2_000) { operation.join() }
        assertTrue(failure is GattOperationException)
        assertEquals("GATT operation timed out", failure?.message)
        assertFalse(lane.isActive(connection))
        assertFalse(lane.complete(connection, GattOperationKind.WRITE, attribute, 0))
        lane.activate(connection)
        assertUnavailable(connection)
        val replacement = Any()
        lane.activate(replacement)
        assertSynchronousSuccess(replacement)
    }

    private suspend fun assertUnavailable(token: Any) {
        var initiated = false
        expectFailure<GattOperationException> {
            lane.execute(token, GattOperationKind.READ, attribute) {
                initiated = true
                true
            }
        }
        assertFalse(initiated)
    }

    private suspend fun assertSynchronousSuccess(token: Any) {
        val result = lane.execute(token, GattOperationKind.READ, attribute) {
            assertTrue(lane.complete(token, GattOperationKind.READ, attribute, 0))
            true
        }
        assertEquals(0, result.status)
    }

    private suspend inline fun <reified T : Throwable> expectFailure(block: suspend () -> Unit): T {
        try {
            block()
        } catch (error: Throwable) {
            assertTrue("Expected " + T::class.java.simpleName + ", got " + error, error is T)
            return error as T
        }
        throw AssertionError("Expected " + T::class.java.simpleName)
    }

    private data class EqualToken(val value: Int)
}
