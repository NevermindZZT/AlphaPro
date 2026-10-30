package com.letter.alphapro.ui.gallery

import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal enum class ThumbnailStage { SMALL, RENDERED, SYSTEM, SOFTWARE }
internal data class ThumbnailAttempt(val source: String, val stage: ThumbnailStage, val failure: Exception)
internal class ThumbnailLoadException(val attempts: List<ThumbnailAttempt>) :
    IOException(attempts.lastOrNull()?.failure?.message ?: "No thumbnail available")
internal data class ThumbnailResult<M, T>(val image: T, val source: M, val software: Boolean)

/** No original-file/export callback exists here: a list cell may read only camera renderings. */
internal object ThumbnailPipeline {
    suspend fun <M, T> load(
        sources: List<M>,
        sourceName: (M) -> String,
        small: suspend (M) -> ByteArray?,
        rendered: suspend (M) -> ByteArray?,
        platform: suspend (M, ByteArray) -> T?,
        mayUseSoftware: (ByteArray) -> Boolean,
        software: suspend (M, ByteArray) -> T?
    ): ThumbnailResult<M, T> {
        val attempts = mutableListOf<ThumbnailAttempt>()
        val encoded = mutableListOf<Pair<M, ByteArray>>()
        suspend fun failed(source: M, stage: ThumbnailStage, failure: Exception) {
            currentCoroutineContext().ensureActive()
            // Allocation failure must not trigger a more expensive full HEIF software decode.
            if (failure.cause is OutOfMemoryError) throw failure
            attempts += ThumbnailAttempt(sourceName(source), stage, failure)
        }
        suspend fun readPlatform(source: M, stage: ThumbnailStage,
                                 read: suspend (M) -> ByteArray?): ThumbnailResult<M, T>? {
            val bytes = try {
                currentCoroutineContext().ensureActive()
                read(source)?.takeIf { it.isNotEmpty() } ?: throw IOException("Camera returned no image data")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failed(source, stage, failure); return null }
            encoded += source to bytes
            try {
                currentCoroutineContext().ensureActive()
                val result = platform(source, bytes)
                if (result != null) return ThumbnailResult(result, source, false)
                failed(source, ThumbnailStage.SYSTEM, IOException("System decoder returned no image (${bytes.size} bytes)"))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failed(source, ThumbnailStage.SYSTEM, failure) }
            return null
        }

        // Prefer a device-supported HEIF/JPEG rendering, including the paired RAW's JPEG thumb.
        for (source in sources) readPlatform(source, ThumbnailStage.SMALL, small)?.let { return it }
        // Only one larger camera rendering; never download a full HIF/ARW just for the grid.
        sources.firstOrNull()?.let { source ->
            readPlatform(source, ThumbnailStage.RENDERED, rendered)?.let { return it }
        }
        val tried = mutableListOf<ByteArray>()
        for ((source, bytes) in encoded) {
            currentCoroutineContext().ensureActive()
            if (!mayUseSoftware(bytes) || tried.any { it.contentEquals(bytes) }) continue
            tried += bytes
            try {
                val image = software(source, bytes)
                if (image != null) return ThumbnailResult(image, source, true)
                failed(source, ThumbnailStage.SOFTWARE, IOException("Software decoder returned no image"))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { failed(source, ThumbnailStage.SOFTWARE, failure) }
        }
        throw ThumbnailLoadException(attempts)
    }
}
