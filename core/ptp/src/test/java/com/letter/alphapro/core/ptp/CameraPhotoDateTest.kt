package com.letter.alphapro.core.ptp

import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class CameraPhotoDateTest {
    @Test fun sonyTimestampUsesMillisecondsAndKeepsEncodedLocalDayInEveryPhoneTimezone() {
        // Camera local wallclock 2024-02-29 00:05:00.123, encoded as epoch milliseconds.
        val localCreated = 1_709_165_100_123L
        val previous = TimeZone.getDefault()
        try {
            for (zone in listOf("Pacific/Honolulu", "Asia/Tokyo", "UTC")) {
                TimeZone.setDefault(TimeZone.getTimeZone(zone))
                assertEquals(zone, "2024-02-29", CameraPhotoDate.fromSonyLocalCreated(localCreated))
                assertEquals("2025-12-31", CameraPhotoDate.fromSonyLocalCreated(1_767_225_599_999L))
                assertEquals("2026-01-01", CameraPhotoDate.fromSonyLocalCreated(1_767_225_600_000L))
            }
        } finally {
            TimeZone.setDefault(previous)
        }
    }

    @Test fun unknownSonyTimestampsNeverProduceEpochOrCurrentDate() {
        for (timestamp in listOf(0L, -1L, Long.MIN_VALUE, Long.MAX_VALUE)) {
            assertEquals(timestamp.toString(), "", CameraPhotoDate.fromSonyLocalCreated(timestamp))
        }
    }

    @Test fun normalizesPtpExifAndIsoDatesWithoutTimezoneConversion() {
        val sameDay = listOf(
            "20240229T000500",
            "20240229T000500.123",
            "20240229T000500+1400",
            "2024:02:29 23:59:59",
            "2024:02:29",
            "2024-02-29",
            "2024-02-29T23:59:59.123456789Z",
            "2024-02-29T00:05:00+14:00",
            "2024-02-29T23:59:59-12:00",
            " 20240229T000500\u0000 "
        )
        sameDay.forEach { assertEquals(it, "2024-02-29", CameraPhotoDate.normalize(it)) }
        assertEquals("0001-01-01", CameraPhotoDate.normalize("0001-01-01"))
    }

    @Test fun absentZeroMalformedAndImpossibleDatesStayUnknown() {
        val unknown = listOf(
            null, "", " ", "\u0000", "unknown", "0", "0000:00:00 00:00:00",
            "00000000T000000", "0000-01-01", "2026-00-01", "2026-01-00",
            "2026:02:29 10:20:30", "20240230T102030", "2026-13-01", "2026-04-31",
            "2026-10-25T24:00:00", "20261025T126000", "2026:10:25 12:00:60",
            "2026-10-25T12:00:00+25:00", "20261025T120000+1260",
            "2026-10", "20261025", "25/10/2026", "2026-10-25junk",
            "2026-10-25T", "2026:10:25 99:99:99"
        )
        unknown.forEach { assertEquals(it.toString(), "", CameraPhotoDate.normalize(it)) }
    }

    @Test fun mixedFormatsMergeByDateNewestFirstWithUnknownLastAndStableCaptureOrder() {
        val unknown = capture(1, 10, "")
        val older = capture(1, 11, "2025:12:31 23:59:59")
        val newestFirst = capture(1, 12, "20260101T000001")
        val sameDayDifferentSlot = capture(2, 12, "2026:01:01 23:59:59")
        val malformed = capture(1, 13, "20260230T000000")
        val middle = capture(1, 14, "2025-01-02")
        val oldest = capture(1, 15, "2024:12:31 23:59:59")
        val input = listOf(unknown, older, newestFirst, sameDayDifferentSlot, malformed, middle, oldest)
        val originalIds = input.map { it.id }

        val sections = input.toDateSections()

        assertEquals(listOf("2026-01-01", "2025-12-31", "2025-01-02", "2024-12-31", "unknown"),
            sections.map { it.key })
        assertEquals(listOf(newestFirst, sameDayDifferentSlot), sections[0].captures)
        assertSame(newestFirst, sections[0].captures[0])
        assertSame(sameDayDifferentSlot, sections[0].captures[1])
        assertEquals(listOf(unknown, malformed), sections.last().captures)
        assertEquals("Unknown date", sections.last().title)
        assertEquals("2026-01-01", sections.first().title)
        assertEquals("2026-01-01", newestFirst.captureDate)
        assertEquals(originalIds, input.map { it.id })
        assertEquals("content:1:12", newestFirst.id)
        assertEquals("content:2:12", sameDayDifferentSlot.id)
        assertEquals(input.size, sections.sumOf { it.captures.size })
    }

    @Test fun sectionsHandleEmptyKnownOnlyAndUnknownOnlyListsWithoutInventingBuckets() {
        assertTrue(emptyList<CameraCaptureGroup>().toDateSections().isEmpty())
        val known = capture(1, 1, "20261025T120000")
        assertEquals(listOf("2026-10-25"), listOf(known).toDateSections().map { it.key })
        val unknown = capture(1, 2, "0000:00:00")
        val section = listOf(unknown).toDateSections().single()
        assertEquals(CameraPhotoDate.UNKNOWN_KEY, section.key)
        assertEquals(listOf(unknown), section.captures)
    }

    private fun capture(slot: Long, content: Long, date: String): CameraCaptureGroup {
        val file = PtpImageMetadata(
            handle = (slot shl 56) or (1L shl 32) or content,
            filename = "DSC00001.JPG",
            fileSize = 100,
            formatCode = PtpConstants.FMT_JPEG,
            isHeif = false,
            isRaw = false,
            captureDate = date,
            isContentCatalog = true
        )
        return listOf(file).toCaptureGroups().single()
    }
}
