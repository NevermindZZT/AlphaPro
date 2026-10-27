package com.letter.alphapro.core.ptp

import org.junit.Assert.*
import org.junit.Test

class CatalogCaptureGroupTest {
    @Test fun pairedFormatsUseOneCaptureWithExactVisualHandle() {
        val raw = file(1, 42, 3, true)
        val jpg = file(1, 42, 1, false)
        val group = listOf(raw, jpg).toCaptureGroups().single()
        assertEquals(jpg.handle, group.visualFile.handle)
        assertEquals(raw.handle, group.rawFile!!.handle)
        assertEquals("RAW + JPG", group.badgeLabel)
    }
    @Test fun identicalBasenamesFromDifferentCapturesAndSlotsStaySeparate() {
        val files = listOf(file(1, 42, 3, true), file(1, 42, 2, false),
            file(1, 43, 3, true), file(1, 43, 2, false), file(2, 42, 3, true), file(2, 42, 2, false))
        val groups = files.toCaptureGroups()
        assertEquals(3, groups.size)
        assertEquals(3, groups.map { it.id }.distinct().size)
        assertTrue(groups.all { it.files.size == 2 })
    }
    private fun file(slot: Long, content: Long, fileId: Long, raw: Boolean) = PtpImageMetadata(
        handle = (slot shl 56) or (fileId shl 32) or content,
        filename = if (raw) "DSC00001.ARW" else "DSC00001.JPG", fileSize = 100,
        formatCode = if (raw) PtpConstants.FMT_SONY_ARW else PtpConstants.FMT_JPEG,
        isHeif = false, isRaw = raw, captureDate = "", isContentCatalog = true)
}
