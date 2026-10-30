package com.letter.alphapro.core.heif

/** Bounded ISO-BMFF brand probe; `ftyp` alone also matches MP4 and AVIF. */
object HeifInput {
    private val brands = setOf("heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs", "mif1", "msf1")

    fun isHeif(bytes: ByteArray): Boolean {
        val available = minOf(bytes.size, 4096)
        var offset = 0
        while (offset <= available - 8) {
            val size = uint32(bytes, offset)
            val type = fourcc(bytes, offset + 4)
            var header = 8
            val length = if (size == 1L) {
                if (offset > available - 16 || uint32(bytes, offset + 8) != 0L) return false
                header = 16
                uint32(bytes, offset + 12)
            } else size
            if (length < header || length > Int.MAX_VALUE) return false
            if (type == "ftyp") {
                if (length < header + 8 || offset > available - header - 8) return false
                val end = minOf(available.toLong(), offset.toLong() + length).toInt()
                val major = fourcc(bytes, offset + header)
                val compatible = mutableSetOf(major)
                var position = offset + header + 8 // skip minor version
                while (position <= end - 4) {
                    compatible += fourcc(bytes, position)
                    position += 4
                }
                if ("avif" in compatible || "avis" in compatible) return false
                return compatible.any { it in brands }
            }
            // Only padding may precede ftyp; never scan arbitrary compressed payload for magic.
            if (type != "free" && type != "skip") return false
            if (length > available - offset) return false
            offset += length.toInt()
        }
        return false
    }

    private fun uint32(bytes: ByteArray, at: Int): Long =
        (0..3).fold(0L) { value, i -> (value shl 8) or (bytes[at + i].toLong() and 0xff) }

    private fun fourcc(bytes: ByteArray, at: Int): String =
        String(bytes, at, 4, Charsets.US_ASCII)
}
