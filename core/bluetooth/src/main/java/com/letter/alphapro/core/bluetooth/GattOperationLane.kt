package com.letter.alphapro.core.bluetooth

import java.util.IdentityHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class GattOperationKind {
    READ, WRITE, DESCRIPTOR, MTU
}

data class GattOperationResult(val status: Int, val value: ByteArray? = null)

class GattOperationException(message: String) : Exception(message)

/**
 * Serializes callback-backed GATT operations without depending on Android classes.
 *
 * Connections are opaque identity tokens, not equality keys. A token that has been closed,
 * replaced, or invalidated cannot be reused: a late callback has no request ID with which to
 * distinguish an abandoned operation from a subsequent operation on the same attribute.
 * The caller must activate a fresh token after reconnecting.
 */
class GattOperationLane {
    private class Pending(
        val connection: Any,
        val kind: GattOperationKind,
        val attribute: String,
        val result: CompletableDeferred<GattOperationResult> = CompletableDeferred()
    )

    private val operationMutex = Mutex()
    private val stateLock = Any()
    private val invalidatedConnections = IdentityHashMap<Any, Boolean>()
    private var activeConnection: Any? = null
    private var pending: Pending? = null

    fun activate(connection: Any) {
        synchronized(stateLock) {
            if (activeConnection === connection || invalidatedConnections.containsKey(connection)) {
                return
            }
            activeConnection?.let { invalidateLocked(it, "GATT connection replaced") }
            activeConnection = connection
        }
    }

    fun isActive(connection: Any): Boolean = synchronized(stateLock) {
        activeConnection === connection && !invalidatedConnections.containsKey(connection)
    }

    fun close(connection: Any) {
        synchronized(stateLock) {
            if (activeConnection === connection) {
                invalidateLocked(connection, "GATT connection closed")
            }
        }
    }

    /**
     * [attribute] must identify the attribute instance (for example UUID + instance ID), or
     * be "mtu" for [GattOperationKind.MTU]. All writes, including NO_RESPONSE writes, wait
     * for [complete]; accepting initiation is not successful completion.
     *
     * [initiate] runs under the state lock so disconnect cannot race between registration and
     * initiation. It must be short and non-blocking; a synchronous [complete] is supported.
     *
     * [settleAfterInitiation] keeps queueing and the pre-initiation cancellation check cancellable,
     * but once [initiate] returns true, waits non-cancellably for the matching callback within
     * [timeoutMs]. Its result reaches the caller even if cancellation occurred, so the caller can
     * record the accepted operation before its next cancellation check. This does not reactivate
     * the caller's Job. A missing callback still invalidates the connection on timeout. By default,
     * cancellation after acceptance instead abandons the operation and invalidates the connection.
     */
    suspend fun execute(
        connection: Any,
        kind: GattOperationKind,
        attribute: String,
        timeoutMs: Long = 15_000,
        settleAfterInitiation: Boolean = false,
        initiate: () -> Boolean
    ): GattOperationResult {
        require(timeoutMs > 0) { "GATT operation timeout must be positive" }
        return operationMutex.withLock {
            val context = currentCoroutineContext()
            val operation = Pending(connection, kind, attribute)
            var accepted = false
            try {
                synchronized(stateLock) {
                    context.ensureActive()
                    if (activeConnection !== connection || invalidatedConnections.containsKey(connection)) {
                        throw GattOperationException("GATT connection is not active")
                    }
                    // Register first: some implementations deliver the callback inside initiate().
                    pending = operation
                    accepted = initiate()
                    if (!accepted) {
                        throw GattOperationException("GATT operation initiation rejected")
                    }
                }
                val result = if (settleAfterInitiation) {
                    // Keep the dispatcher unchanged: returning must not re-check caller cancellation.
                    withContext(NonCancellable) {
                        withTimeoutOrNull(timeoutMs) { operation.result.await() }
                    }
                } else {
                    context.ensureActive()
                    withTimeoutOrNull(timeoutMs) { operation.result.await() }
                }
                result ?: run {
                    invalidate(connection, "GATT operation timed out")
                    throw GattOperationException("GATT operation timed out")
                }
            } catch (error: CancellationException) {
                if (accepted) invalidate(connection, "GATT operation cancelled")
                throw error
            } finally {
                synchronized(stateLock) {
                    if (pending === operation) pending = null
                }
                operation.result.cancel()
            }
        }
    }

    /** Returns false for stale, duplicate, or mismatched callbacks. */
    fun complete(
        connection: Any,
        kind: GattOperationKind,
        attribute: String,
        status: Int,
        value: ByteArray? = null
    ): Boolean = synchronized(stateLock) {
        val operation = pending ?: return@synchronized false
        if (activeConnection !== connection || operation.connection !== connection ||
            operation.kind != kind || operation.attribute != attribute ||
            invalidatedConnections.containsKey(connection)
        ) {
            return@synchronized false
        }
        pending = null
        operation.result.complete(GattOperationResult(status, value?.copyOf()))
    }

    private fun invalidate(connection: Any, reason: String) {
        synchronized(stateLock) {
            invalidateLocked(connection, reason)
        }
    }

    // Always called under stateLock. Never invalidate a newer connection's pending operation.
    private fun invalidateLocked(connection: Any, reason: String) {
        invalidatedConnections[connection] = true
        pending?.takeIf { it.connection === connection }?.let {
            pending = null
            it.result.completeExceptionally(GattOperationException(reason))
        }
    }
}
