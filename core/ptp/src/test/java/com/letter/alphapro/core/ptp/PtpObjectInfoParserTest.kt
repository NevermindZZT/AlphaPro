package com.letter.alphapro.core.ptp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class PtpObjectInfoParserTest {
    @Test fun readsActualNameAndCaptureDateNotModificationDate() {
        val result = PtpObjectInfoParser.parse(dataset("DSC01234.JPG", "20261025T235959", "20261027T010203"))
        assertEquals("DSC01234.JPG", result.filename)
        assertEquals("2026-10-25", result.captureDate)
        assertEquals(9L, result.storageId)
        assertEquals(17L, result.parentHandle)
        assertEquals(123456L, result.fileSize)
    }
    @Test fun absentOrInvalidCaptureDateRemainsUnknown() {
        for (date in listOf("", "00000000T000000", "20260230T120000")) {
            assertEquals("", PtpObjectInfoParser.parse(dataset("DSC00001.HIF", date, "20261025T120000")).captureDate)
        }
    }
    @Test fun rejectsTruncatedString() {
        val bytes = dataset("DSC00001.JPG", "20261025T120000", "")
        try { PtpObjectInfoParser.parse(bytes.copyOf(56)); fail("Truncated name") }
        catch (_: IllegalArgumentException) {}
    }
    private fun dataset(name: String, capture: String, modified: String): ByteArray {
        val header = ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(9).putShort(0x3801.toShort()).putShort(0).putInt(123456)
        header.position(38); header.putInt(17)
        fun text(value: String): ByteArray = if (value.isEmpty()) byteArrayOf(0)
            else byteArrayOf((value.length + 1).toByte()) + (value + '\u0000').toByteArray(Charsets.UTF_16LE)
        return header.array() + text(name) + text(capture) + text(modified)
    }
}
