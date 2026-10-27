package com.letter.alphapro.core.ptp

/** Rotate clockwise first, then scale along the rotated (output) axes. */
data class OrientationTransform(
    val rotationDegrees: Float,
    val scaleX: Float,
    val scaleY: Float
)

/** EXIF orientation rules with no Android or image-decoder dependency. */
object PhotoOrientation {
    fun transform(value: Int): OrientationTransform = when (value) {
        2 -> OrientationTransform(0f, -1f, 1f) // Mirror X.
        3 -> OrientationTransform(180f, 1f, 1f)
        4 -> OrientationTransform(0f, 1f, -1f) // Mirror Y.
        5 -> OrientationTransform(90f, -1f, 1f) // Transpose: (x, y) -> (y, x).
        6 -> OrientationTransform(90f, 1f, 1f)
        7 -> OrientationTransform(90f, 1f, -1f) // Transverse.
        8 -> OrientationTransform(270f, 1f, 1f)
        else -> OrientationTransform(0f, 1f, 1f)
    }

    /**
     * Reads only an explicit, valid IFD0 Orientation SHORT from JPEG APP1 Exif or classic TIFF.
     * No thumbnail/SubIFD orientation is substituted for the original image's orientation.
     *
     * At most 1 MiB of header and 4096 IFD0 entries are examined, with no image-sized copies.
     * A partial original header is sufficient if the entire orientation entry is available;
     * the rest of the APP1 segment/IFD need not have arrived yet. Missing, unsupported or
     * malformed metadata returns null, never an invented normal orientation.
     */
    fun readExifOrientation(data: ByteArray): Int? {
        val limit = minOf(data.size, MAX_HEADER_BYTES)
        if (limit < 2) return null
        if (data.byte(0) != 0xff || data.byte(1) != 0xd8) {
            return readTiff(data, 0, limit)
        }

        var offset = 2
        while (offset < limit) {
            if (data.byte(offset) != 0xff) return null
            // JPEG permits FF padding before a marker.
            while (offset < limit && data.byte(offset) == 0xff) offset++
            if (offset >= limit) return null
            val marker = data.byte(offset++)
            if (marker == 0xda || marker == 0xd9) return null // SOS / EOI: no pixel scan.
            if (marker == 0x00 || marker == 0xd8) return null
            if (marker == 0x01 || marker in 0xd0..0xd7) continue // Standalone markers.
            if (limit - offset < 2) return null
            val length = (data.byte(offset) shl 8) or data.byte(offset + 1)
            if (length < 2) return null
            val payload = offset + 2
            val end = offset + length // JPEG segment lengths are unsigned 16-bit.
            val availableEnd = minOf(end, limit)
            if (marker == 0xe1 && availableEnd - payload >= 6 &&
                data.byte(payload) == 0x45 && data.byte(payload + 1) == 0x78 &&
                data.byte(payload + 2) == 0x69 && data.byte(payload + 3) == 0x66 &&
                data.byte(payload + 4) == 0 && data.byte(payload + 5) == 0
            ) {
                readTiff(data, payload + 6, availableEnd)?.let { return it }
            }
            if (end > limit) return null
            offset = end
        }
        return null
    }

    private fun readTiff(data: ByteArray, start: Int, end: Int): Int? {
        if (end - start < 8) return null
        val littleEndian = when {
            data.byte(start) == 0x49 && data.byte(start + 1) == 0x49 -> true
            data.byte(start) == 0x4d && data.byte(start + 1) == 0x4d -> false
            else -> return null
        }
        fun short(at: Int): Int = if (littleEndian) {
            data.byte(at) or (data.byte(at + 1) shl 8)
        } else {
            (data.byte(at) shl 8) or data.byte(at + 1)
        }
        fun long(at: Int): Long = if (littleEndian) {
            short(at).toLong() or (short(at + 2).toLong() shl 16)
        } else {
            (short(at).toLong() shl 16) or short(at + 2).toLong()
        }
        if (short(start + 2) != 42) return null // Classic TIFF only, not BigTIFF.
        val ifdOffset = long(start + 4)
        if (ifdOffset < 8 || ifdOffset > (end - start - 2).toLong()) return null
        val ifd = start + ifdOffset.toInt()
        val count = short(ifd)
        if (count > MAX_IFD_ENTRIES) return null
        var entry = ifd + 2
        repeat(count) {
            if (end - entry < 12) return null
            if (short(entry) == 0x0112) {
                // A single SHORT fits inline, regardless of TIFF byte order.
                if (short(entry + 2) != 3 || long(entry + 4) != 1L) return null
                return short(entry + 8).takeIf { it in 1..8 }
            }
            entry += 12
        }
        return null
    }

    private fun ByteArray.byte(index: Int): Int = this[index].toInt() and 0xff

    private const val MAX_HEADER_BYTES = 1024 * 1024
    private const val MAX_IFD_ENTRIES = 4096
}
