package com.letter.alphapro.core.heif

import android.graphics.drawable.BitmapDrawable
import coil.ImageLoader
import coil.decode.DecodeResult
import coil.decode.Decoder
import coil.fetch.SourceResult
import coil.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class CoilHeifDecoder(
    private val source: SourceResult,
    private val options: Options
) : Decoder {

    override suspend fun decode(): DecodeResult = withContext(Dispatchers.IO) {
        val tempFile = File.createTempFile("coil_heif_", ".hif", options.context.cacheDir)
        try {
            source.source.source().inputStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            // Decode 640px thumbnail or full resolution according to request size
            val bitmap = HeifDecoder.decodeFile(tempFile.absolutePath, 256L * 1024 * 1024)
            DecodeResult(
                drawable = BitmapDrawable(options.context.resources, bitmap),
                isSampled = false
            )
        } finally {
            tempFile.delete()
        }
    }

    class Factory : Decoder.Factory {
        override fun create(result: SourceResult, options: Options, imageLoader: ImageLoader): Decoder? {
            val mimeType = result.mimeType
            val isHeif = mimeType?.contains("heif", ignoreCase = true) == true ||
                         mimeType?.contains("heic", ignoreCase = true) == true
            return if (isHeif) CoilHeifDecoder(result, options) else null
        }
    }
}
