package com.letter.alphapro.core.ptp

data class SonyCatalogFile(
    val handle: Long,
    val name: String,
    val size: Long,
    val formatCode: Int,
    val isHeif: Boolean,
    val isRaw: Boolean,
    val date: String,
    val created: Long
)

data class SonyCatalogBatch(
    val slot: Int,
    val timestamps: List<Long>,
    val files: List<SonyCatalogFile>
)

object SonyContentParser {
    fun parse(data: ByteArray): SonyCatalogBatch {
        var offset = 0

        fun take(size: Int): Int {
            require(size >= 0 && size <= data.size - offset) { "Truncated camera content list" }
            return offset.also { offset += size }
        }

        fun number(size: Int): Long {
            val at = take(size)
            return (0 until size).fold(0L) { n, i -> n or ((data[at + i].toLong() and 255) shl (8 * i)) }
        }

        fun count(limit: Int): Int = number(4).also { require(it in 0..limit.toLong()) }.toInt()

        number(2) // Dataset version
        take(2)
        number(8) // Database update time
        take(128) // Identity offset
        val slot = number(4).toInt()
        require(slot in 1..2)

        val timestamps = mutableListOf<Long>()
        val files = mutableListOf<SonyCatalogFile>()

        val totalContent = count(1000)
        repeat(totalContent) {
            number(4) // Content type
            val contentId = number(4)
            take(20) // Directory, file, group type/id
            val created = number(8)
            number(8) // Modified (not the capture date)
            val localCreated = number(8) // Camera-local wallclock, epoch milliseconds
            number(8) // Local modified
            timestamps += created
            take(8) // Rating, protection flag
            val dummy = number(4) != 0L
            take(count(4096)) // Shot marks

            val totalFiles = count(64)
            repeat(totalFiles) {
                val fileId = number(2)
                take(2)
                val pathLength = count(4096)
                val pathStart = take(pathLength)
                val path = data.copyOfRange(pathStart, pathStart + pathLength)
                    .decodeToString().trimEnd('\u0000', ' ')
                val format = number(4).toInt()
                val size = number(8)
                take(32) // UMID
                if (number(4) != 0L) take(8)
                if (number(4) != 0L) take(19 * 4)
                if (number(4) != 0L) take(4 * 4)

                val isHeif = format == PtpConstants.FMT_SONY_HEIF
                val isRaw = format == PtpConstants.FMT_SONY_ARW
                val isJpeg = format == PtpConstants.FMT_JPEG

                if (!dummy && (isHeif || isRaw || isJpeg) && fileId in 1..3) {
                    val name = path.substringAfterLast('/').substringAfterLast('\\')
                    val id = (slot.toLong() shl 56) or (fileId shl 32) or contentId
                    files += SonyCatalogFile(
                        handle = id,
                        name = name,
                        size = size,
                        formatCode = format,
                        isHeif = isHeif,
                        isRaw = isRaw,
                        date = CameraPhotoDate.fromSonyLocalCreated(localCreated),
                        created = created
                    )
                }
            }
        }
        return SonyCatalogBatch(slot, timestamps, files)
    }
}
data class CameraCaptureGroup(
    val baseName: String,
    val files: List<PtpImageMetadata>
) {
    val id: String get() = captureIdentity(files.first())

    val visualFile: PtpImageMetadata
        get() = files.firstOrNull { !it.isRaw && !it.isHeif }
            ?: files.firstOrNull { it.isHeif }
            ?: files.first()

    val rawFile: PtpImageMetadata?
        get() = files.firstOrNull { it.isRaw }

    val hasRaw: Boolean get() = rawFile != null
    val hasHeif: Boolean get() = files.any { it.isHeif }
    val hasJpeg: Boolean get() = files.any { !it.isRaw && !it.isHeif }
    val isDualFormat: Boolean get() = hasRaw && (hasHeif || hasJpeg)

    val badgeLabel: String
        get() = when {
            hasRaw && hasHeif -> "RAW + 10b HIF"
            hasRaw && hasJpeg -> "RAW + JPG"
            hasHeif -> "10b HIF"
            hasRaw -> "RAW"
            else -> "JPG"
        }

    val totalSizeMb: Double
        get() = files.sumOf { it.fileSize } / (1024.0 * 1024.0)

    val filename: String get() = visualFile.filename
    val captureDate: String get() = CameraPhotoDate.normalize(visualFile.captureDate)
}

private fun captureIdentity(file: PtpImageMetadata): String = if (file.isContentCatalog) {
    // fileId occupies bits32..47; slot + contentId identify the same exposure across formats.
    "content:" + (file.handle ushr 56) + ":" + (file.handle and 0xffffffffL)
} else {
    "name:" + file.storageId + ":" + file.parentHandle + ":" + file.filename.substringBeforeLast('.') + ":" + file.captureDate
}

fun List<PtpImageMetadata>.toCaptureGroups(): List<CameraCaptureGroup> {
    return groupBy(::captureIdentity).values.map { files ->
        CameraCaptureGroup(files.first().filename.substringBeforeLast('.'), files)
    }
}
