package com.letter.alphapro.core.ptp

import java.io.EOFException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class PtpDataAccumulatorTest {
    @Test fun consumesAllDataAndFinalResponse() {
        val data = PtpDataAccumulator(7, 8)
        assertNull(data.consume(PtpConstants.PKT_START_DATA, start(7, 4)))
        assertNull(data.consume(PtpConstants.PKT_DATA, chunk(7, byteArrayOf(1, 2))))
        assertNull(data.consume(PtpConstants.PKT_END_DATA, chunk(7, byteArrayOf(3, 4))))
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), data.consume(PtpConstants.PKT_CMD_RESPONSE, response(7)))
    }

    @Test fun unknownTotalUsesActualBoundedLength() {
        val data = PtpDataAccumulator(1, 8)
        data.consume(PtpConstants.PKT_START_DATA, start(1, PtpDataAccumulator.UNKNOWN_TOTAL))
        data.consume(PtpConstants.PKT_DATA, chunk(1, byteArrayOf(1)))
        data.consume(PtpConstants.PKT_END_DATA, chunk(1, byteArrayOf(2)))
        assertArrayEquals(byteArrayOf(1, 2), data.consume(PtpConstants.PKT_CMD_RESPONSE, response(1)))
        invalid { PtpDataAccumulator(1, 8).consume(PtpConstants.PKT_START_DATA, start(1, -2)) }
    }

    @Test fun unsupportedPreviewHasTypedRecoverableResponse() {
        try {
            PtpDataAccumulator(2, 8).consume(PtpConstants.PKT_CMD_RESPONSE, response(2, 0x2005))
            fail("Expected camera refusal")
        } catch (failure: PtpResponseException) { assertEquals(0x2005, failure.responseCode) }
    }

    @Test fun oversizeAnnouncedLengthDiscardsInSmallChunksAndFailsOnlyAfterResponse() {
        val data = PtpDataAccumulator(1, 8)
        val size = 1024 * 1024
        assertNull(data.consume(PtpConstants.PKT_START_DATA, start(1, size.toLong())))
        assertEquals(0, data.retainedByteCount)
        // A virtual 1 MiB wire payload: no fixture or production wire-sized allocation.
        val input = object : InputStream() {
            var position = 0
            var largestRead = 0
            val id = buffer(4).putInt(1).array()
            override fun read(): Int = error("Expected bounded bulk reads")
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                largestRead = maxOf(largestRead, length)
                val count = minOf(length, size + 4 - position)
                if (count == 0) return -1
                for (i in 0 until count) bytes[offset + i] = if (position + i < 4) id[position + i] else 0
                position += count
                return count
            }
        }
        data.consumeData(PtpConstants.PKT_END_DATA, size + 4, input)
        assertEquals(size + 4, input.position)
        assertTrue(input.largestRead <= 8192)
        assertEquals(0, data.retainedByteCount)
        recoverable { data.consume(PtpConstants.PKT_CMD_RESPONSE, response(1)) }
    }

    @Test fun unknownTotalStillEnforcesLimitAndDropsPreviouslyCollectedBytes() {
        val data = PtpDataAccumulator(1, 2)
        data.consume(PtpConstants.PKT_START_DATA, start(1, -1))
        data.consume(PtpConstants.PKT_DATA, chunk(1, byteArrayOf(1, 2)))
        assertEquals(2, data.retainedByteCount)
        data.consume(PtpConstants.PKT_END_DATA, chunk(1, byteArrayOf(3)))
        assertEquals(0, data.retainedByteCount)
        recoverable { data.consume(PtpConstants.PKT_CMD_RESPONSE, response(1)) }
    }

    @Test fun shortOrOverflowingDataDrainsFinalResponseBeforeReportingFailure() {
        for (announced in listOf(1L, 3L)) {
            val data = PtpDataAccumulator(1, 8)
            data.consume(PtpConstants.PKT_START_DATA, start(1, announced))
            assertNull(data.consume(PtpConstants.PKT_END_DATA, chunk(1, byteArrayOf(1, 2))))
            recoverable { data.consume(PtpConstants.PKT_CMD_RESPONSE, response(1)) }
        }
    }

    @Test fun firstDataFailureIsKeptAlongWithFinalRefusal() {
        val data = PtpDataAccumulator(1, 8)
        data.consume(PtpConstants.PKT_START_DATA, start(1, 9))
        data.consume(PtpConstants.PKT_END_DATA, chunk(1, byteArrayOf(1)))
        val failure = recoverable { data.consume(PtpConstants.PKT_CMD_RESPONSE, response(1, 0x2007)) }
        assertTrue(failure.message!!.contains("exceeds requested limit"))
        assertEquals(0x2007, (failure.suppressed.single() as PtpResponseException).responseCode)
    }

    @Test fun rejectsWrongTransactionsEvenWhileDiscarding() {
        invalid { PtpDataAccumulator(1, 8).consume(PtpConstants.PKT_START_DATA, start(2, 4)) }
        val data = PtpDataAccumulator(1, 8)
        data.consume(PtpConstants.PKT_START_DATA, start(1, 9))
        invalid { data.consume(PtpConstants.PKT_END_DATA, chunk(2, byteArrayOf(1))) }
        invalid { data.consume(PtpConstants.PKT_CMD_RESPONSE, response(2, 0x2005)) }
    }

    @Test fun rejectsEarlySuccessfulResponseMalformedResponseAndDuplicateEnd() {
        val data = PtpDataAccumulator(1, 8)
        data.consume(PtpConstants.PKT_START_DATA, start(1, 1))
        invalid { data.consume(PtpConstants.PKT_CMD_RESPONSE, response(1)) }
        data.consume(PtpConstants.PKT_END_DATA, chunk(1, byteArrayOf(1)))
        invalid { data.consume(PtpConstants.PKT_DATA, chunk(1, byteArrayOf())) }
        invalid { data.consume(PtpConstants.PKT_CMD_RESPONSE, response(1) + byteArrayOf(0)) }
    }

    @Test fun socketLossDuringDiscardIsNotRecoverable() {
        val data = PtpDataAccumulator(1, 8)
        data.consume(PtpConstants.PKT_START_DATA, start(1, 100))
        try {
            data.consumeData(PtpConstants.PKT_END_DATA, 104, java.io.ByteArrayInputStream(chunk(1, byteArrayOf(1))))
            fail("Expected socket loss")
        } catch (_: EOFException) {}
    }

    private fun invalid(block: () -> Unit) {
        try { block(); fail("Expected invalid framing") } catch (_: IllegalArgumentException) {}
    }
    private fun recoverable(block: () -> Unit): PtpDataException {
        try { block(); fail("Expected object-local failure") } catch (failure: PtpDataException) { return failure }
        error("unreachable")
    }
    private fun buffer(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    private fun start(id: Int, length: Long) = buffer(12).putInt(id).putLong(length).array()
    private fun chunk(id: Int, bytes: ByteArray) = buffer(4 + bytes.size).putInt(id).put(bytes).array()
    private fun response(id: Int, code: Int = 0x2001) = buffer(6).putShort(code.toShort()).putInt(id).array()
}
