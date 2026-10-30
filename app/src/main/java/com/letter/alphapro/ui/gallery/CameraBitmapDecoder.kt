package com.letter.alphapro.ui.gallery

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.exifinterface.media.ExifInterface
import com.letter.alphapro.core.heif.HeifDecoder
import com.letter.alphapro.core.heif.HeifInput
import com.letter.alphapro.core.ptp.PhotoOrientation
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.function.BooleanSupplier

/** Display decoding only: never compresses, exports, or rewrites the source photograph. */
object CameraBitmapDecoder {
    fun orientation(bytes: ByteArray): Int? {
        val value = try {
            ByteArrayInputStream(bytes).use { input -> readOrientation(ExifInterface(input)) }
        } catch (_: IOException) { null }
        catch (_: RuntimeException) { null }
        return value ?: PhotoOrientation.readExifOrientation(bytes)
    }

    private fun readOrientation(exif: ExifInterface): Int? =
        if (exif.hasAttribute(ExifInterface.TAG_ORIENTATION)) {
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0).takeIf { it in 1..8 }
        } else null

    fun decode(bytes: ByteArray, maxEdge: Int, fallbackOrientation: Int? = null,
               cancelled: BooleanSupplier? = null): Bitmap? =
        decodeDetailed(bytes, maxEdge, fallbackOrientation, cancelled)?.image

    /** Platform success is authoritative, including HEIF420 supported by the device. */
    internal fun decodeDetailed(bytes: ByteArray, maxEdge: Int, fallbackOrientation: Int? = null,
                                cancelled: BooleanSupplier? = null,
                                allowSoftwareFallback: Boolean = true): PreviewDecodeResult<Bitmap>? {
        if (bytes.isEmpty() || maxEdge <= 0) return null
        return PreviewDecodePolicy.decode(
            platform = {
                decodeSystem(maxEdge, fallbackOrientation,
                    { options -> BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) },
                    { orientation(bytes) })
                    ?: if (android.os.Build.VERSION.SDK_INT >= 28)
                        SystemImageDecoder.decodeBytes(bytes, maxEdge, cancelled) else null
            },
            mayUseSoftware = { allowSoftwareFallback && HeifInput.isHeif(bytes) },
            software = { decodeSoftwarePreview(bytes, maxEdge, cancelled) },
            cancelled = cancelled
        )
    }

    /** Used only after all preferred camera-rendered/system candidates failed. */
    internal fun decodeSoftwarePreview(bytes: ByteArray, maxEdge: Int, cancelled: BooleanSupplier?): Bitmap? {
        if (maxEdge <= 0 || !HeifInput.isHeif(bytes)) return null
        HeifDecoder.requireAvailable()
        if (bytes.size > 8 * 1024 * 1024) throw IOException("Encoded HEIF preview exceeds 8MiB")
        return synchronized(heifLock) {
            if (cancelled?.asBoolean == true) throw CancellationException()
            val image = if (maxEdge <= 640 && bytes.size <= 512 * 1024) {
                HeifDecoder.decodeThumbnail(bytes, HEIF_BUDGET, cancelled)
            } else {
                HeifDecoder.decodePreview(bytes, HEIF_BUDGET, cancelled)
            }
            // libheif has already applied container transforms. Do not apply EXIF again.
            fitNativePreview(image, maxEdge)
        }
    }

    fun decodeFilePreview(file: File, maxEdge: Int, heif: Boolean,
                          cancelled: BooleanSupplier? = null): Bitmap? =
        decodeFilePreviewDetailed(file, maxEdge, heif, cancelled)?.image

    internal fun decodeFilePreviewDetailed(file: File, maxEdge: Int, heif: Boolean,
                                          cancelled: BooleanSupplier? = null): PreviewDecodeResult<Bitmap>? {
        if (maxEdge <= 0 || file.length() !in 1..32 * 1024 * 1024L) return null
        return PreviewDecodePolicy.decode(
            platform = {
                decodeSystem(maxEdge, null,
                    { options -> BitmapFactory.decodeFile(file.absolutePath, options) },
                    { try { readOrientation(ExifInterface(file)) } catch (_: IOException) { null } })
                    ?: if (android.os.Build.VERSION.SDK_INT >= 28)
                        SystemImageDecoder.decodeFile(file, maxEdge, cancelled) else null
            },
            mayUseSoftware = {
                heif || file.inputStream().use { input ->
                    val header = ByteArray(4096)
                    val size = input.read(header)
                    size > 0 && HeifInput.isHeif(header.copyOf(size))
                }
            },
            software = {
                synchronized(heifLock) {
                    if (cancelled?.asBoolean == true) throw CancellationException()
                    fitNativePreview(HeifDecoder.decodeFilePreview(file.absolutePath, HEIF_BUDGET, cancelled), maxEdge)
                }
            },
            cancelled = cancelled
        )
    }

    /** BitmapFactory's EXIF plan is applied once; sampling precedes allocation. */
    private fun decodeSystem(maxEdge: Int, fallbackOrientation: Int?,
                             decode: (BitmapFactory.Options) -> Bitmap?,
                             sourceOrientation: () -> Int?): Bitmap? {
        var owned: Bitmap? = null
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            decode(bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val edge = minOf(maxEdge, MAX_DECODE_EDGE)
            val longest = maxOf(bounds.outWidth, bounds.outHeight).toLong()
            var sample = 1
            while ((longest + sample - 1) / sample > edge) {
                if (sample >= (1 shl 30)) return null
                sample *= 2
            }
            val value = sourceOrientation() ?: fallbackOrientation?.takeIf { it in 1..8 } ?: 1
            val options = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inScaled = false
            }
            val decoded = decode(options) ?: return null
            owned = decoded
            if (decoded.width > edge || decoded.height > edge) {
                decoded.recycle()
                owned = null
                return null
            }
            val plan = PhotoOrientation.transform(value)
            if (plan.rotationDegrees == 0f && plan.scaleX == 1f && plan.scaleY == 1f) return decoded
            val matrix = Matrix().apply {
                setRotate(plan.rotationDegrees)
                postScale(plan.scaleX, plan.scaleY)
            }
            val result = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (result !== decoded) decoded.recycle()
            owned = null
            return result
        } catch (cancelled: CancellationException) {
            owned?.recycle()
            throw cancelled
        } catch (_: RuntimeException) {
            owned?.recycle()
            return null
        } catch (failure: OutOfMemoryError) {
            owned?.recycle()
            // Do not respond to allocation failure by allocating a full software HEIF decode.
            throw IOException("Insufficient memory for system preview decode", failure)
        }
    }

    private fun fitNativePreview(image: Bitmap, maxEdge: Int): Bitmap {
        val edge = minOf(maxEdge, MAX_DECODE_EDGE)
        val longest = maxOf(image.width, image.height)
        if (longest <= edge) return image
        try {
            val result = Bitmap.createScaledBitmap(image,
                maxOf(1, image.width * edge / longest), maxOf(1, image.height * edge / longest), true)
            if (result !== image) image.recycle()
            return result
        } catch (failure: Throwable) {
            image.recycle()
            if (failure is OutOfMemoryError) throw IOException("Insufficient memory for preview scaling", failure)
            throw failure
        }
    }

    @androidx.annotation.RequiresApi(28)
    private object SystemImageDecoder {
        fun decodeBytes(bytes: ByteArray, edge: Int, cancelled: BooleanSupplier?): Bitmap? =
            decode(android.graphics.ImageDecoder.createSource(java.nio.ByteBuffer.wrap(bytes)), edge, cancelled)
        fun decodeFile(file: File, edge: Int, cancelled: BooleanSupplier?): Bitmap? =
            decode(android.graphics.ImageDecoder.createSource(file), edge, cancelled)

        private fun decode(source: android.graphics.ImageDecoder.Source, maxEdge: Int,
                           cancelled: BooleanSupplier?): Bitmap? {
            var owned: Bitmap? = null
            try {
                val edge = minOf(maxEdge, MAX_DECODE_EDGE)
                val image = android.graphics.ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    if (cancelled?.asBoolean == true) throw CancellationException()
                    decoder.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                    val longest = maxOf(info.size.width, info.size.height)
                    if (longest > edge) decoder.setTargetSize(
                        maxOf(1, info.size.width * edge / longest), maxOf(1, info.size.height * edge / longest))
                    decoder.setOnPartialImageListener { false }
                }
                owned = image
                // ImageDecoder has already applied orientation/crop: never run our EXIF matrix again.
                return fitNativePreview(image, edge).also { owned = null }
            } catch (cancelledDecode: CancellationException) {
                owned?.recycle()
                throw cancelledDecode
            } catch (failure: IOException) {
                owned?.let { if (!it.isRecycled) it.recycle() }
                if (failure.cause is OutOfMemoryError) throw failure
                return null
            } catch (_: RuntimeException) {
                owned?.let { if (!it.isRecycled) it.recycle() }
                return null
            } catch (failure: OutOfMemoryError) {
                owned?.let { if (!it.isRecycled) it.recycle() }
                throw IOException("Insufficient memory for platform ImageDecoder", failure)
            }
        }
    }

    private val heifLock = Any()
    private const val HEIF_BUDGET = 512L * 1024 * 1024
    private const val MAX_DECODE_EDGE = 4096
}
