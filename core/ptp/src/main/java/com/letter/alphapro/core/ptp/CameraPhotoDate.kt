package com.letter.alphapro.core.ptp

import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/** Camera-local calendar dates. Empty means unavailable, never the current date or epoch day. */
object CameraPhotoDate {
    const val UNKNOWN_KEY = "unknown"
    const val UNKNOWN_TITLE = "Unknown date"

    private val iso = Regex(
        "([0-9]{4})-([0-9]{2})-([0-9]{2})(?:[T ]([0-9]{2}):([0-9]{2}):([0-9]{2})" +
            "(?:[.]([0-9]{1,9}))?(Z|[+-][0-9]{2}:?[0-9]{2})?)?"
    )
    private val exif = Regex(
        "([0-9]{4}):([0-9]{2}):([0-9]{2})(?: ([0-9]{2}):([0-9]{2}):([0-9]{2})" +
            "(?:[.]([0-9]{1,9}))?(Z|[+-][0-9]{2}:?[0-9]{2})?)?"
    )
    private val ptp = Regex(
        "([0-9]{4})([0-9]{2})([0-9]{2})T([0-9]{2})([0-9]{2})([0-9]{2})" +
            "(?:[.]([0-9]{1,9}))?(Z|[+-][0-9]{2}:?[0-9]{2})?"
    )

    /**
     * Sony's content-list localCreated is milliseconds since the epoch with the camera's
     * local wallclock encoded as UTC (as in the reference SonyContentParser). UTC here only
     * decodes that encoding; it is not a guessed camera/device timezone. Do not substitute
     * created or modified when localCreated is missing, since those can name a different day.
     */
    fun fromSonyLocalCreated(localCreated: Long): String {
        if (localCreated <= 0L) return ""
        return try {
            val date = Instant.ofEpochMilli(localCreated).atOffset(ZoneOffset.UTC).toLocalDate()
            if (date.year in 1..9999) date.toString() else ""
        } catch (_: DateTimeException) {
            ""
        }
    }

    /**
     * Normalize ISO, PTP YYYYMMDDTHHmmss, and Exif yyyy:MM:dd HH:mm:ss to yyyy-MM-dd.
     * Validate the entire supplied value, including time/offset if present, without converting
     * the written calendar day to the phone's timezone. Invalid and all-zero values stay unknown.
     */
    fun normalize(value: String?): String {
        val text = value?.trim { it.isWhitespace() || it == '\u0000' }.orEmpty()
        val match = iso.matchEntire(text) ?: exif.matchEntire(text) ?: ptp.matchEntire(text)
            ?: return ""
        val parts = match.groupValues
        return try {
            val year = parts[1].toInt()
            if (year == 0) return ""
            val date = LocalDate.of(year, parts[2].toInt(), parts[3].toInt())
            if (parts[4].isNotEmpty()) {
                LocalTime.of(parts[4].toInt(), parts[5].toInt(), parts[6].toInt())
            }
            if (parts[8].isNotEmpty()) ZoneOffset.of(parts[8])
            date.toString()
        } catch (_: DateTimeException) {
            ""
        }
    }
}

/** Stable keys allow a UI to retain independent expanded/collapsed state for each date. */
data class CameraPhotoDateSection(
    val key: String,
    val title: String,
    val captures: List<CameraCaptureGroup>
)

/** Newest calendar day first, unknown last; never reorder captures or alter capture identities. */
fun List<CameraCaptureGroup>.toDateSections(): List<CameraPhotoDateSection> {
    val byDate = groupBy { CameraPhotoDate.normalize(it.captureDate) }
    val known = byDate.keys.filter { it.isNotEmpty() }.sortedDescending().map { date ->
        CameraPhotoDateSection(key = date, title = date, captures = byDate.getValue(date))
    }
    val unknown = byDate[""] ?: return known
    return known + CameraPhotoDateSection(
        key = CameraPhotoDate.UNKNOWN_KEY,
        title = CameraPhotoDate.UNKNOWN_TITLE,
        captures = unknown
    )
}
