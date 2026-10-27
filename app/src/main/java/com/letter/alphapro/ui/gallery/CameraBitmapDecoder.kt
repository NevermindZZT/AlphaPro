package com.letter.alphapro.ui.gallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.letter.alphapro.core.ptp.PhotoOrientation
import java.io.ByteArrayInputStream
import java.io.IOException

/** Bounded, software bitmap decoding; the caller owns the returned bitmap. */
object CameraBitmapDecoder {
    /** Returns a real EXIF tag (1..8), or null when no valid tag is present. */
    fun orientation(bytes: ByteArray): Int? {
        val value = try {
            ByteArrayInputStream(bytes).use { input ->
                val exif = ExifInterface(input)
                if (exif.hasAttribute(ExifInterface.TAG_ORIENTATION)) {
                    exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0).takeIf { it in 1..8 }
                } else {
                    null
                }
            }
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        }
        return value ?: PhotoOrientation.readExifOrientation(bytes)
    }

    /**
     * Decodes raw, un-oriented encoded image bytes and applies EXIF exactly once.
     * BitmapFactory ignores EXIF; ImageDecoder must not be substituted because it auto-orients.
     * A preview/thumbnail's explicit orientation (even 1) wins over its parent's fallback.
     * Already-oriented pixels must not be supplied with their old orientation tag/fallback.
     *
     * The longest edge is at most maxEdge, additionally capped at 4096 for preview memory.
     * Power-of-two sampling happens before pixel allocation (no full-size intermediate).
     * Unsupported/corrupt images, invalid limits and allocation failures return null.
     */
    fun decode(bytes: ByteArray, maxEdge: Int, fallbackOrientation: Int? = null): Bitmap? {
        if (bytes.isEmpty() || maxEdge <= 0) return null
        var owned: Bitmap? = null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val edge = minOf(maxEdge, MAX_DECODE_EDGE)
            val longest = maxOf(bounds.outWidth, bounds.outHeight).toLong()
            var sample = 1
            while ((longest + sample - 1) / sample > edge) {
                // Keep inSampleSize positive even for maliciously large dimensions.
                if (sample >= (1 shl 30)) return null
                sample *= 2
            }
            val value = orientation(bytes) ?: fallbackOrientation?.takeIf { it in 1..8 } ?: 1
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            owned = decoded
            // Some codecs round sampled dimensions differently. Never return an oversized image.
            if (decoded.width > edge || decoded.height > edge) {
                decoded.recycle()
                owned = null
                return null
            }
            val plan = PhotoOrientation.transform(value)
            if (plan.rotationDegrees == 0f && plan.scaleX == 1f && plan.scaleY == 1f) {
                return decoded
            }
            val matrix = Matrix().apply {
                setRotate(plan.rotationDegrees)
                // Android postScale left-multiplies: S * R (rotate, then output-axis mirror).
                // 5: (x,y)->(y,x); 7: (x,y)->(-y,-x), before bounds translation.
                postScale(plan.scaleX, plan.scaleY)
            }
            val result = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            // createBitmap may return its input for an identity-equivalent transform.
            if (result !== decoded) decoded.recycle()
            owned = null
            return result
        } catch (_: RuntimeException) {
            owned?.recycle()
            return null
        } catch (_: OutOfMemoryError) {
            owned?.recycle()
            return null
        }
    }

    private const val MAX_DECODE_EDGE = 4096
}
