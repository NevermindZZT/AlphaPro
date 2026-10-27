package com.letter.alphapro.ui.gallery

import android.graphics.Bitmap
import android.util.LruCache
import com.letter.alphapro.core.ptp.PtpImageMetadata
import com.letter.alphapro.core.ptp.PtpIpClient
import com.letter.alphapro.core.ptp.PtpDataException
import com.letter.alphapro.core.ptp.PtpResponseException
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** UI-owned, bounded caches. One object's failure is explicit, not a global silent placeholder. */
class CameraPhotoLoader(private val client: PtpIpClient, private val scope: CoroutineScope) {
    private val cache = object : LruCache<Long, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: Long, value: Bitmap) = value.allocationByteCount
    }
    private val jobs = mutableMapOf<Long, Job>()
    private val startedReads = mutableSetOf<Long>()
    private val slots = Semaphore(2)
    private val sourceOrientations = ConcurrentHashMap<Long, Int>()
    private val _thumbnails = MutableStateFlow<Map<Long, Bitmap>>(emptyMap())
    val thumbnails = _thumbnails.asStateFlow()
    private val _thumbnailErrors = MutableStateFlow<Map<Long, String>>(emptyMap())
    val thumbnailErrors = _thumbnailErrors.asStateFlow()
    private val _preview = MutableStateFlow(PhotoPreviewState())
    val preview = _preview.asStateFlow()
    private var previewJob: Job? = null
    private var generation = 0

    /** Retry at most one idempotent read after transport recovery, never a capture or write. */
    private suspend fun <T> readWithRecovery(read: suspend () -> T): T {
        try { return read() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            if (failure is PtpDataException || (failure is PtpResponseException &&
                failure.responseCode != com.letter.alphapro.core.ptp.PtpConstants.RC_SESSION_NOT_OPEN)) throw failure
            currentCoroutineContext().ensureActive()
            client.reconnectIfNeeded()
            currentCoroutineContext().ensureActive()
            // Another waiting read may already have restored this same camera session.
            return read()
        }
    }

    fun requestThumbnail(metadata: PtpImageMetadata) {
        if (cache.get(metadata.handle) != null || jobs[metadata.handle]?.isActive == true) return
        val epoch = generation
        jobs[metadata.handle] = scope.launch {
            try {
                val bitmap = slots.withPermit {
                    startedReads.add(metadata.handle)
                    withContext(Dispatchers.IO) {
                        val bytes = try {
                            readWithRecovery { client.getThumbnail(metadata) }
                        } catch (large: PtpDataException) {
                            if (!metadata.isContentCatalog) throw large
                            // A drained oversized thumbnail may still have a usable rendered preview.
                            readWithRecovery { client.getPreview(metadata) }
                        } ?: error("相机未返回缩略图数据")
                        currentCoroutineContext().ensureActive()
                        val fallback = if (CameraBitmapDecoder.orientation(bytes) == null) sourceOrientation(metadata) else null
                        currentCoroutineContext().ensureActive()
                        checkNotNull(CameraBitmapDecoder.decode(bytes, 512, fallback)) { "缩略图解码失败" }
                    }
                }
                if (generation == epoch) {
                    cache.put(metadata.handle, bitmap)
                    _thumbnails.value = cache.snapshot()
                    _thumbnailErrors.value = _thumbnailErrors.value - metadata.handle
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (generation == epoch) _thumbnailErrors.value = _thumbnailErrors.value + (metadata.handle to errorText(failure))
            } finally {
                if (jobs[metadata.handle] === currentCoroutineContext()[Job]) {
                    jobs.remove(metadata.handle)
                    startedReads.remove(metadata.handle)
                }
            }
        }
    }

    fun retryThumbnail(metadata: PtpImageMetadata) {
        _thumbnailErrors.value = _thumbnailErrors.value - metadata.handle
        requestThumbnail(metadata)
    }

    fun releaseThumbnailRequest(handle: Long) {
        // Drop off-screen queued work, but do not cancel an accepted shared TCP read just for scrolling.
        if (handle !in startedReads) jobs.remove(handle)?.cancel()
    }

    fun showPreview(metadata: PtpImageMetadata) {
        previewJob?.cancel()
        val epoch = generation
        _preview.value = PhotoPreviewState(handle = metadata.handle, isLoading = true)
        previewJob = scope.launch {
            try {
                val loaded = withContext(Dispatchers.IO) {
                    var source = "相机大预览"
                    var bytes: ByteArray? = null
                    if (metadata.isContentCatalog) {
                        try { bytes = readWithRecovery { client.getPreview(metadata) } }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* A rendered-original fallback may still be supported. */ }
                    }
                    currentCoroutineContext().ensureActive()
                    var fallback = bytes?.takeIf { CameraBitmapDecoder.orientation(it) == null }?.let { sourceOrientation(metadata) }
                    var bitmap = bytes?.let { CameraBitmapDecoder.decode(it, 3072, fallback) }
                    if (bitmap == null || maxOf(bitmap.width, bitmap.height) <= 512) {
                        check(!metadata.isRaw) { "相机未提供 RAW 大预览；不会自动下载完整 RAW" }
                        source = "渲染原片预览（仅临时读取）"
                        bytes = readRenderedOriginal(metadata)
                        currentCoroutineContext().ensureActive()
                        fallback = if (CameraBitmapDecoder.orientation(bytes) == null) sourceOrientation(metadata) else null
                        bitmap = CameraBitmapDecoder.decode(bytes, 3072, fallback)
                    }
                    val image = checkNotNull(bitmap) { "手机暂不能解码该预览，请尝试导出原片" }
                    check(maxOf(image.width, image.height) > 512) { "相机仅返回低分辨率图像" }
                    image to (source+" · "+image.width+" × "+image.height)
                }
                if (generation == epoch) _preview.value = PhotoPreviewState(metadata.handle, loaded.first, sourceLabel = loaded.second)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (generation == epoch) _preview.value = PhotoPreviewState(handle = metadata.handle, error = errorText(failure))
            }
        }
    }

    private fun errorText(failure: Exception): String = when (failure) {
        is PtpDataException -> "该照片返回的数据不完整或超出预览限制，可重试其他照片"
        is PtpResponseException -> "相机拒绝该照片读取：" + failure.message
        else -> "预览读取失败：" + (failure.cause?.message ?: failure.message ?: failure.javaClass.simpleName)
    }

    private suspend fun sourceOrientation(metadata: PtpImageMetadata): Int? {
        sourceOrientations[metadata.handle]?.let { return it }
        if (metadata.fileSize <= 0) return null
        val value = try {
            val header = readWithRecovery { client.readPartialObject(metadata, 0, minOf(metadata.fileSize, 64 * 1024L).toInt()) }
            CameraBitmapDecoder.orientation(header)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { null } // Optional metadata never discards an otherwise usable thumbnail.
        currentCoroutineContext().ensureActive()
        if (value != null) sourceOrientations[metadata.handle] = value
        return value
    }

    private suspend fun readRenderedOriginal(metadata: PtpImageMetadata): ByteArray {
        check(!metadata.isRaw && metadata.fileSize in 1..32 * 1024 * 1024L) { "未提供大预览，或原片超出安全预览大小；请手动导出" }
        val stream = ByteArrayOutputStream()
        var offset = 0L
        while (offset < metadata.fileSize) {
            currentCoroutineContext().ensureActive()
            val length = minOf(512 * 1024L, metadata.fileSize - offset).toInt()
            val chunk = readWithRecovery { client.readPartialObject(metadata, offset, length) }
            check(chunk.size == length) { "原片预览数据不完整" }
            stream.write(chunk); offset += chunk.size
        }
        return stream.toByteArray()
    }

    fun clearPreview() { previewJob?.cancel(); previewJob = null; _preview.value = PhotoPreviewState() }
    fun reset() {
        generation++
        jobs.values.forEach { it.cancel() }; jobs.clear(); startedReads.clear()
        cache.evictAll(); sourceOrientations.clear(); _thumbnails.value = emptyMap(); _thumbnailErrors.value = emptyMap()
        clearPreview()
    }
}
