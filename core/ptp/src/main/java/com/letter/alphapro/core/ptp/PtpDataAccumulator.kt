package com.letter.alphapro.core.ptp

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The final, matching operation response was consumed; the stream is still synchronized. */
class PtpResponseException(val responseCode: Int) : Exception("Camera response 0x" + responseCode.toString(16))

/** Object-local failure, reported only after consuming the final matching response. */
class PtpDataException(message: String) : Exception(message)

/** Validate and collect a bounded data-in transaction through its final operation response. */
internal class PtpDataAccumulator(private val transactionId: Int, private val limit: Int) {
    companion object { const val UNKNOWN_TOTAL = -1L }

    init { require(limit > 0) }
    private var expected: Long? = null
    private var received = 0L
    private var ended = false
    private var completed = false
    private var failure: PtpDataException? = null
    private var output = ByteArrayOutputStream(minOf(limit, 8192))
    internal val retainedByteCount: Int get() = output.size()

    fun consume(type: Int, payload: ByteArray): ByteArray? {
        check(!completed) { "PTP transaction already completed" }
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)
        when (type) {
            PtpConstants.PKT_START_DATA -> {
                require(payload.size == 12 && expected == null) { "Invalid PTP start-data" }
                require(buffer.int == transactionId) { "PTP data transaction mismatch" }
                val length = buffer.long
                require(length >= 0 || length == UNKNOWN_TOTAL) { "Invalid PTP announced length" }
                expected = length
                if (length > limit) refuse("Camera data exceeds requested limit ($length > $limit)")
            }
            PtpConstants.PKT_DATA, PtpConstants.PKT_END_DATA ->
                consumeData(type, payload.size, ByteArrayInputStream(payload))
            PtpConstants.PKT_CMD_RESPONSE -> {
                require(payload.size in 6..26 && (payload.size - 6) % 4 == 0) { "Invalid PTP response length" }
                val code = buffer.short.toInt() and 0xffff
                require(buffer.int == transactionId) { "PTP response transaction mismatch" }
                // A refusal may abort a data phase; a successful operation must end it properly.
                require(code != PtpConstants.RC_OK || expected == null || ended) { "PTP response before data end" }
                completed = true
                if (code != PtpConstants.RC_OK) {
                    val response = PtpResponseException(code)
                    failure?.let { it.addSuppressed(response); throw it }
                    throw response
                }
                failure?.let { throw it }
                return output.toByteArray()
            }
            else -> error("Unexpected PTP packet " + type)
        }
        return null
    }

    /** Read data packets in small chunks, including discarded bytes; never allocate a wire-sized array. */
    fun consumeData(type: Int, payloadLength: Int, input: InputStream, checkActive: () -> Unit = {}) {
        check(!completed) { "PTP transaction already completed" }
        require(type == PtpConstants.PKT_DATA || type == PtpConstants.PKT_END_DATA) { "Invalid PTP data packet" }
        require(payloadLength >= 4 && expected != null && !ended) { "Invalid PTP data phase" }
        val id = ByteArray(4)
        readFully(input, id, 4, checkActive)
        require(ByteBuffer.wrap(id).order(ByteOrder.LITTLE_ENDIAN).int == transactionId) { "PTP data transaction mismatch" }
        val count = payloadLength - 4
        received += count.toLong()
        if (received > limit) refuse("Camera data exceeds requested limit ($received > $limit)")
        if (expected != UNKNOWN_TOTAL && received > expected!!) refuse("Camera data length overflow")
        val chunk = ByteArray(minOf(count, 8192))
        var remaining = count
        while (remaining > 0) {
            val size = minOf(remaining, chunk.size)
            readFully(input, chunk, size, checkActive)
            if (failure == null) output.write(chunk, 0, size)
            remaining -= size
        }
        if (type == PtpConstants.PKT_END_DATA) {
            if (expected != UNKNOWN_TOTAL && received != expected) refuse("Incomplete camera data ($received != $expected)")
            ended = true
        }
    }

    private fun refuse(message: String) {
        if (failure == null) failure = PtpDataException(message)
        // Drop previously retained bytes as soon as the result cannot be returned.
        output = ByteArrayOutputStream(0)
    }

    private fun readFully(input: InputStream, bytes: ByteArray, size: Int, checkActive: () -> Unit) {
        var offset = 0
        while (offset < size) {
            checkActive()
            val count = input.read(bytes, offset, size - offset)
            if (count < 0) throw EOFException("PTP/IP stream closed unexpectedly")
            if (count == 0) throw EOFException("PTP/IP stream made no progress")
            offset += count
        }
    }
}
