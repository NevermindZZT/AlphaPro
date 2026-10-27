package com.letter.alphapro.core.ptp

import org.junit.Assert.*
import org.junit.Test

class SonyContentDateTest {
    @Test fun readsRealLocalCreatedFieldAsLittleEndianMillisecondsForAllCaptureFormats() {
        // UTC created is the preceding day; localCreated is camera wallclock just after midnight.
        val created = 1_709_136_300_123L
        val localCreated = 1_709_165_100_123L
        val bytes = batch(
            content(42, created, localCreated,
                file(3, PtpConstants.FMT_SONY_ARW, "DSC00001.ARW"),
                file(1, PtpConstants.FMT_JPEG, "DSC00001.JPG")),
            content(43, created, 1_709_251_200_000L,
                file(2, PtpConstants.FMT_SONY_HEIF, "DSC00002.HIF"))
        )

        val parsed = SonyContentParser.parse(bytes)

        assertEquals(1, parsed.slot)
        assertEquals(listOf(created, created), parsed.timestamps)
        assertEquals(listOf(created, created, created), parsed.files.map { it.created })
        assertEquals(listOf("2024-02-29", "2024-02-29", "2024-03-01"), parsed.files.map { it.date })
        assertEquals(listOf(0x010000030000002aL, 0x010000010000002aL, 0x010000020000002bL),
            parsed.files.map { it.handle })
        val groups = parsed.files.map(::metadata).toCaptureGroups()
        assertEquals(listOf("content:1:42", "content:1:43"), groups.map { it.id })
        assertEquals(listOf("2024-03-01", "2024-02-29"), groups.toDateSections().map { it.key })
        assertSame(groups[0], groups.toDateSections()[1].captures.single())
        assertEquals("RAW + JPG", groups[0].badgeLabel)
    }

    @Test fun zeroOrInvalidLocalCreatedStaysUnknownEvenWithValidCreatedAndModifiedFields() {
        val created = 1_709_165_100_123L
        for (localCreated in listOf(0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            val parsed = SonyContentParser.parse(batch(
                content(42, created, localCreated, file(1, PtpConstants.FMT_JPEG, "DSC00001.JPG"))
            ))
            assertEquals(listOf(created), parsed.timestamps)
            assertEquals(created, parsed.files.single().created)
            assertEquals(localCreated.toString(), "", parsed.files.single().date)
            assertEquals(CameraPhotoDate.UNKNOWN_KEY,
                parsed.files.map(::metadata).toCaptureGroups().toDateSections().single().key)
        }
    }

    private fun metadata(file: SonyCatalogFile) = PtpImageMetadata(
        handle = file.handle,
        filename = file.name,
        fileSize = file.size,
        formatCode = file.formatCode,
        isHeif = file.isHeif,
        isRaw = file.isRaw,
        captureDate = file.date,
        isContentCatalog = true
    )

    private fun number(value: Long, size: Int) = ByteArray(size) { (value ushr (8 * it)).toByte() }

    private fun batch(vararg contents: ByteArray): ByteArray =
        number(4, 2) + ByteArray(2) + number(0, 8) + ByteArray(128) +
            number(1, 4) + number(contents.size.toLong(), 4) +
            contents.fold(byteArrayOf()) { bytes, content -> bytes + content }

    private fun content(id: Long, created: Long, localCreated: Long, vararg files: ByteArray): ByteArray =
        number(1, 4) + number(id, 4) + ByteArray(20) +
            number(created, 8) + number(1_767_225_600_000L, 8) +
            number(localCreated, 8) + number(1_767_225_600_000L, 8) +
            ByteArray(8) + number(0, 4) + number(0, 4) + number(files.size.toLong(), 4) +
            files.fold(byteArrayOf()) { bytes, file -> bytes + file }

    private fun file(id: Long, format: Int, filename: String): ByteArray {
        val path = "DCIM/100MSDCF/$filename\u0000".encodeToByteArray()
        return number(id, 2) + ByteArray(2) + number(path.size.toLong(), 4) + path +
            number(format.toLong(), 4) + number(600_000, 8) + ByteArray(32) +
            number(0, 4) + number(0, 4) + number(0, 4)
    }
}
