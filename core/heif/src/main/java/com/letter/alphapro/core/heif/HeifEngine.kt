package com.letter.alphapro.core.heif

import android.graphics.Bitmap
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.function.BooleanSupplier

object HeifDecoder {
    init {
        try {
            System.loadLibrary("alpha_heif")
        } catch (_: UnsatisfiedLinkError) {
            // In unit test or environment without native library
        }
    }

    @JvmStatic
    fun createBitmap(width: Int, height: Int): Bitmap {
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    @JvmStatic
    external fun decodeFile(path: String, memoryBudget: Long, cancelled: BooleanSupplier? = null): Bitmap

    @JvmStatic
    external fun decodeThumbnail(encoded: ByteArray, memoryBudget: Long): Bitmap

    @JvmStatic
    external fun decodePreview(encoded: ByteArray, memoryBudget: Long): Bitmap

    @JvmStatic
    external fun readExif(path: String): ByteArray
}

class HeifTranscoder {
    companion object {
        private val EXIF_TAGS_TO_PRESERVE = listOf(
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
            ExifInterface.TAG_LENS_MAKE,
            ExifInterface.TAG_LENS_MODEL,
            ExifInterface.TAG_DATETIME,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME,
            ExifInterface.TAG_OFFSET_TIME,
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_EXPOSURE_PROGRAM,
            ExifInterface.TAG_EXPOSURE_MODE,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_FLASH,
            ExifInterface.TAG_WHITE_BALANCE,
            ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_ARTIST,
            ExifInterface.TAG_COPYRIGHT,
            ExifInterface.TAG_GPS_LATITUDE,
            ExifInterface.TAG_GPS_LATITUDE_REF,
            ExifInterface.TAG_GPS_LONGITUDE,
            ExifInterface.TAG_GPS_LONGITUDE_REF,
            ExifInterface.TAG_GPS_ALTITUDE,
            ExifInterface.TAG_GPS_ALTITUDE_REF,
            ExifInterface.TAG_GPS_DATESTAMP,
            ExifInterface.TAG_GPS_TIMESTAMP,
            ExifInterface.TAG_GPS_PROCESSING_METHOD
        )
    }

    suspend fun transcodeHeifToJpeg(
        sourceHifFile: File,
        targetJpegFile: File,
        quality: Int = 95
    ): File = withContext(Dispatchers.IO) {
        val memoryBudget = 512L * 1024 * 1024 // 512MB decoding budget
        val bitmap = HeifDecoder.decodeFile(sourceHifFile.absolutePath, memoryBudget)

        try {
            FileOutputStream(targetJpegFile).use { out ->
                val compressed = bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (!compressed) throw IOException("Failed to compress bitmap to JPEG")
            }

            // Copy metadata
            copyExifMetadata(sourceHifFile, targetJpegFile, bitmap.width, bitmap.height)
            targetJpegFile
        } finally {
            bitmap.recycle()
        }
    }

    private fun copyExifMetadata(sourceHifFile: File, targetJpegFile: File, width: Int, height: Int) {
        try {
            val exifBytes = HeifDecoder.readExif(sourceHifFile.absolutePath)
            val sourceExif = if (exifBytes.isNotEmpty()) {
                exifBytes.inputStream().use { ExifInterface(it, ExifInterface.STREAM_TYPE_EXIF_DATA_ONLY) }
            } else {
                null
            }

            val targetExif = ExifInterface(targetJpegFile)
            sourceExif?.let { src ->
                for (tag in EXIF_TAGS_TO_PRESERVE) {
                    src.getAttribute(tag)?.let { value ->
                        targetExif.setAttribute(tag, value)
                    }
                }
            }

            // Normal orientation because libheif already rotated pixel data
            targetExif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            targetExif.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, width.toString())
            targetExif.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, height.toString())
            targetExif.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
            targetExif.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
            targetExif.setAttribute(ExifInterface.TAG_COLOR_SPACE, "1") // sRGB
            targetExif.saveAttributes()
        } catch (_: Exception) {}
    }
}
