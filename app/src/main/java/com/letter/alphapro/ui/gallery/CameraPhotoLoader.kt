package com.letter.alphapro.ui.gallery

import android.graphics.Bitmap
import android.util.LruCache
import com.letter.alphapro.R
import com.letter.alphapro.core.heif.HeifInput
import com.letter.alphapro.core.ptp.PtpImageMetadata
import com.letter.alphapro.core.ptp.PtpIpClient
import com.letter.alphapro.core.ptp.PtpDataException
import com.letter.alphapro.core.ptp.PtpResponseException
import com.letter.alphapro.ui.components.LocalizedText
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.function.BooleanSupplier
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** UI-owned, bounded caches. All network reads retain their original camera intent epoch. */
class CameraPhotoLoader(private val client: PtpIpClient, private val scope: CoroutineScope,
                        private val cacheDir: File) {
    private val cache = object : LruCache<Long, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap) = value.allocationByteCount
    }
    private val jobs = mutableMapOf<Long, Job>()
    private val startedReads = mutableSetOf<Long>()
    private val slots = Semaphore(2)
    private val sourceOrientations = ConcurrentHashMap<Pair<Long, Long>, Int>()
    private val _thumbnails = MutableStateFlow<Map<Long, Bitmap>>(emptyMap())
    val thumbnails = _thumbnails.asStateFlow()
    private val _thumbnailErrors = MutableStateFlow<Map<Long, LocalizedText>>(emptyMap())
    val thumbnailErrors = _thumbnailErrors.asStateFlow()
    private val _preview = MutableStateFlow(PhotoPreviewState())
    val preview = _preview.asStateFlow()
    private var previewJob: Job? = null
    private val pendingPreviews = ConcurrentHashMap.newKeySet<Job>()
    private var previewRequest = 0L
    private var generation = 0
    private class DisplayException(val text: LocalizedText) : IllegalStateException("Camera preview unavailable")

    /** Retry only an idempotent preview read, never on a replacement/explicitly disconnected camera. */
    private suspend fun <T> readWithRecovery(epoch: Long, read: suspend () -> T): T {
        try { return read() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (failure is PtpDataException || (failure is PtpResponseException &&
                failure.responseCode != com.letter.alphapro.core.ptp.PtpConstants.RC_SESSION_NOT_OPEN)) throw failure
            currentCoroutineContext().ensureActive()
            client.reconnectIfNeeded(epoch)
            currentCoroutineContext().ensureActive()
            return read()
        }
    }

    fun requestThumbnail(metadata: PtpImageMetadata, alternate: PtpImageMetadata? = null) {
        if (cache.get(metadata.handle) != null || jobs[metadata.handle]?.isActive == true) return
        val generationAtStart = generation
        val cameraEpoch = client.captureSessionEpoch()
        jobs[metadata.handle] = scope.launch {
            var owned: Bitmap? = null
            try {
                val bitmap = slots.withPermit {
                    startedReads.add(metadata.handle)
                    withContext(Dispatchers.IO) {
                        val decodeJob = currentCoroutineContext()[Job]
                        val cancelled = BooleanSupplier { decodeJob?.isActive == false }
                        val candidates = listOfNotNull(metadata, alternate).distinctBy { it.handle }
                        val result = ThumbnailPipeline.load(
                            sources = candidates,
                            sourceName = { it.filename },
                            small = { source -> readWithRecovery(cameraEpoch) { client.getThumbnail(source, cameraEpoch) } },
                            rendered = { source ->
                                if (source.isContentCatalog) readWithRecovery(cameraEpoch) { client.getPreview(source, cameraEpoch) }
                                else null
                            },
                            platform = { source, bytes ->
                                currentCoroutineContext().ensureActive()
                                val fallback = if (!HeifInput.isHeif(bytes) && CameraBitmapDecoder.orientation(bytes) == null)
                                    sourceOrientation(source, cameraEpoch) else null
                                CameraBitmapDecoder.decodeDetailed(bytes, 512, fallback, cancelled,
                                    allowSoftwareFallback = false)?.image?.also { owned = it }
                            },
                            mayUseSoftware = HeifInput::isHeif,
                            software = { _, bytes ->
                                currentCoroutineContext().ensureActive()
                                CameraBitmapDecoder.decodeSoftwarePreview(bytes, 512, cancelled)?.also { owned = it }
                            }
                        )
                        result.image
                    }
                }
                if (generation == generationAtStart && cameraEpoch == client.captureSessionEpoch()) {
                    // Always cache under the visual handle, even when its paired RAW rendered it.
                    cache.put(metadata.handle, bitmap)
                    _thumbnails.value = cache.snapshot()
                    _thumbnailErrors.value = _thumbnailErrors.value - metadata.handle
                    owned = null
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (generation == generationAtStart) _thumbnailErrors.value =
                    _thumbnailErrors.value + (metadata.handle to errorText(failure))
            } finally {
                owned?.recycle()
                if (jobs[metadata.handle] === currentCoroutineContext()[Job]) {
                    jobs.remove(metadata.handle)
                    startedReads.remove(metadata.handle)
                }
            }
        }
    }

    fun retryThumbnail(metadata: PtpImageMetadata, alternate: PtpImageMetadata? = null) {
        _thumbnailErrors.value = _thumbnailErrors.value - metadata.handle
        requestThumbnail(metadata, alternate)
    }

    fun releaseThumbnailRequest(handle: Long) {
        if (handle !in startedReads) jobs.remove(handle)?.cancel()
    }

    fun showPreview(metadata: PtpImageMetadata) {
        previewJob?.cancel()
        val request = ++previewRequest
        val generationAtStart = generation
        val cameraEpoch = client.captureSessionEpoch()
        _preview.value = PhotoPreviewState(handle = metadata.handle, isLoading = true)
        val job = scope.launch {
            var owned: Bitmap? = null
            try {
                val loaded = withContext(Dispatchers.IO) {
                    val decodeJob = currentCoroutineContext()[Job]
                    val cancelled = BooleanSupplier { decodeJob?.isActive == false }
                    var source = R.string.preview_camera_source
                    var bytes: ByteArray? = null
                    if (metadata.isContentCatalog) {
                        try { bytes = readWithRecovery(cameraEpoch) { client.getPreview(metadata, cameraEpoch) } }
                        catch (cancelledRead: CancellationException) { throw cancelledRead }
                        catch (_: Exception) { /* Try one bounded original-file fallback below. */ }
                    }
                    currentCoroutineContext().ensureActive()
                    if (bytes != null) {
                        val encoded = checkNotNull(bytes)
                        val fallback = if (!HeifInput.isHeif(encoded) && CameraBitmapDecoder.orientation(encoded) == null)
                            sourceOrientation(metadata, cameraEpoch) else null
                        try {
                            val result = CameraBitmapDecoder.decodeDetailed(encoded, 3072, fallback, cancelled)
                            owned = result?.image
                            if (result != null) source = if (result.decoder == PreviewDecoderKind.SYSTEM)
                                R.string.preview_camera_system_source else R.string.preview_camera_software_source
                        } catch (cancelledDecode: CancellationException) { throw cancelledDecode }
                        catch (_: IOException) { /* File fallback can avoid encoded-preview limits. */ }
                    }
                    currentCoroutineContext().ensureActive()
                    if (owned == null || maxOf(checkNotNull(owned).width, checkNotNull(owned).height) <= 512) {
                        owned?.recycle()
                        owned = null
                        if (metadata.isRaw) throw DisplayException(LocalizedText(R.string.preview_raw_unavailable))
                        source = R.string.preview_original_source
                        val file = readRenderedOriginal(metadata, cameraEpoch)
                        try {
                            currentCoroutineContext().ensureActive()
                            val result = CameraBitmapDecoder.decodeFilePreviewDetailed(file, 3072, metadata.isHeif, cancelled)
                            owned = result?.image
                            if (result != null) source = if (result.decoder == PreviewDecoderKind.SYSTEM)
                                R.string.preview_original_system_source else R.string.preview_original_software_source
                        } finally {
                            file.delete()
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    val image = owned ?: throw DisplayException(LocalizedText(R.string.preview_decode_unavailable))
                    if (maxOf(image.width, image.height) <= 512) throw DisplayException(LocalizedText(R.string.preview_low_resolution))
                    if (cameraEpoch != client.captureSessionEpoch()) throw IOException("Camera changed while loading preview")
                    image to LocalizedText(source, listOf(image.width, image.height))
                }
                if (generation == generationAtStart && request == previewRequest) {
                    _preview.value = PhotoPreviewState(metadata.handle, loaded.first, sourceLabel = loaded.second)
                    owned = null // visible state owns this image; no immediate recycle on page changes
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (generation == generationAtStart && request == previewRequest) {
                    _preview.value = PhotoPreviewState(handle = metadata.handle, error = errorText(failure))
                }
            } finally {
                owned?.recycle()
            }
        }
        pendingPreviews.add(job)
        job.invokeOnCompletion { pendingPreviews.remove(job) }
        previewJob = job
    }

    private fun errorText(failure: Exception): LocalizedText = when (failure) {
        is DisplayException -> failure.text
        is ThumbnailLoadException -> LocalizedText(R.string.preview_thumbnail_attempts, listOf(
            failure.attempts.joinToString("\n") { attempt ->
                "${attempt.source} · ${attempt.stage.name}: ${attempt.failure.cause?.message ?: attempt.failure.message.orEmpty()}"
            }))
        is PtpDataException -> LocalizedText(R.string.preview_bad_data)
        is PtpResponseException -> LocalizedText(R.string.preview_refused, listOf(failure.message.orEmpty()))
        else -> LocalizedText(R.string.preview_failed, listOf(failure.cause?.message ?: failure.message ?: failure.javaClass.simpleName))
    }

    private suspend fun sourceOrientation(metadata: PtpImageMetadata, epoch: Long): Int? {
        val key = epoch to metadata.handle
        sourceOrientations[key]?.let { return it }
        if (metadata.fileSize <= 0) return null
        val value = try {
            val header = client.readPartialObjectWithRecovery(metadata, 0,
                minOf(metadata.fileSize, 64 * 1024L).toInt(), epoch)
            CameraBitmapDecoder.orientation(header)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null }
        currentCoroutineContext().ensureActive()
        if (value != null) sourceOrientations[key] = value
        return value
    }

    private suspend fun readRenderedOriginal(metadata: PtpImageMetadata, epoch: Long): File {
        if (metadata.isRaw || metadata.fileSize !in 1..32 * 1024 * 1024L)
            throw DisplayException(LocalizedText(R.string.preview_original_limit))
        val file = File.createTempFile("alphapro_preview_", ".image", cacheDir)
        var complete = false
        try {
            FileOutputStream(file).use { stream ->
                var offset = 0L
                while (offset < metadata.fileSize) {
                    currentCoroutineContext().ensureActive()
                    val length = minOf(512 * 1024L, metadata.fileSize - offset).toInt()
                    val chunk = client.readPartialObjectWithRecovery(metadata, offset, length, epoch)
                    if (chunk.size != length) throw DisplayException(LocalizedText(R.string.preview_original_incomplete))
                    stream.write(chunk)
                    offset += chunk.size
                }
            }
            complete = true
            return file
        } finally {
            if (!complete) file.delete()
        }
    }

    fun clearPreview() {
        ++previewRequest
        previewJob?.cancel()
        previewJob = null
        _preview.value = PhotoPreviewState()
    }

    /** Wait for canceled accepted reads to settle BEFORE starting a download. Suspension only. */
    suspend fun stopPreviewForExport() {
        clearPreview()
        val pending = pendingPreviews.toList()
        pending.forEach { it.cancel() }
        pending.joinAll()
    }

    fun reset() {
        generation++
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        startedReads.clear()
        cache.evictAll()
        sourceOrientations.clear()
        _thumbnails.value = emptyMap()
        _thumbnailErrors.value = emptyMap()
        clearPreview()
    }
}
