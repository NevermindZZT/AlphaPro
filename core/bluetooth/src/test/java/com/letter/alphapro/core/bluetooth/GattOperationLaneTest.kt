package com.letter.alphapro.core.bluetooth

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
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
