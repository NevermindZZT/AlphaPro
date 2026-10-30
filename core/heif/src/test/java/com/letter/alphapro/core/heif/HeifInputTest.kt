package com.letter.alphapro.core.heif

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class HeifInputTest {
    private fun box(type: String, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + payload.size).putInt(8 + payload.size)
            .put(type.toByteArray(Charsets.US_ASCII)).put(payload).array()
    private fun ftyp(major: String, vararg compatible: String) = box("ftyp",
        major.toByteArray(Charsets.US_ASCII) + ByteArray(4) +
            compatible.joinToString("").toByteArray(Charsets.US_ASCII))

    @Test fun sonyExtendedHevcBrandIsHeif() {
        assertTrue(HeifInput.isHeif(ftyp("heix", "mif1", "heix")))
        assertTrue(HeifInput.isHeif(ftyp("mif1", "heic")))
    }
    @Test fun genericFtypDoesNotMakeVideoHeif() {
        assertFalse(HeifInput.isHeif(ftyp("isom", "iso2", "mp41")))
    }
    @Test fun avifWithMif1BrandIsNotHevc() {
        assertFalse(HeifInput.isHeif(ftyp("avif", "mif1")))
        assertFalse(HeifInput.isHeif(ftyp("mif1", "avif")))
    }
    @Test fun jpegOrRawElementaryStreamDoesNotMatch() {
        assertFalse(HeifInput.isHeif(byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte())))
        assertFalse(HeifInput.isHeif(byteArrayOf(0, 0, 0, 1, 0x40, 1, 12, 1)))
    }
    @Test fun boundedPaddingBeforeBrandIsAccepted() {
        assertTrue(HeifInput.isHeif(box("free", ByteArray(4)) + ftyp("heix")))
        assertTrue(HeifInput.isHeif(box("skip", ByteArray(4)) + ftyp("heic")))
    }
    @Test fun compressedPayloadIsNotSearchedForAnAccidentalFtyp() {
        assertFalse(HeifInput.isHeif(box("mdat", ftyp("heix"))))
    }
    @Test fun truncatedAndOverflowingHeadersAreRejected() {
        (0..15).forEach { assertFalse(HeifInput.isHeif(ftyp("heix").copyOf(it))) }
        assertFalse(HeifInput.isHeif(ByteBuffer.allocate(16).putInt(-1).put("free".toByteArray()).putLong(0).array()))
        assertFalse(HeifInput.isHeif(ByteBuffer.allocate(16).putInt(1).put("free".toByteArray()).putLong(Long.MAX_VALUE).array()))
    }
    @Test fun extendedSizeFtypAndBoundedProbeWork() {
        val payload = "heix".toByteArray() + ByteArray(4) + "mif1".toByteArray()
        val extended = ByteBuffer.allocate(16 + payload.size).putInt(1).put("ftyp".toByteArray())
            .putLong((16 + payload.size).toLong()).put(payload).array()
        assertTrue(HeifInput.isHeif(extended))
        assertFalse(HeifInput.isHeif(box("free", ByteArray(4096)) + ftyp("heix")))
    }
}
