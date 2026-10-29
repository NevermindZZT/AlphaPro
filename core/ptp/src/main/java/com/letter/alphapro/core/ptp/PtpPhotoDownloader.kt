package com.letter.alphapro.core.ptp

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream

data class DownloadProgress(
    val filename: String,
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val isComplete: Boolean,
    val destinationUri: Uri? = null,
    val destinationFile: File? = null
)

class PtpPhotoDownloader(
    private val context: Context,
    private val client: PtpIpClient
) {
    companion object {
        const val CHUNK_SIZE = 512 * 1024 // 512 KB chunks
    }

    fun downloadToMediaStore(
        metadata: PtpImageMetadata,
        folderName: String = "AlphaPro"
    ): Flow<DownloadProgress> = flow {
        val resolver = context.contentResolver
        val mimeType = when {
            metadata.isHeif -> "image/heif"
            metadata.isRaw -> "image/x-sony-arw"
            else -> "image/jpeg"
        }

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, metadata.filename)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + folderName)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: error("Failed to create MediaStore entry for ${metadata.filename}")

        try {
            resolver.openOutputStream(uri)?.use { outputStream ->
                downloadStream(metadata, outputStream) { downloaded ->
                    emit(
                        DownloadProgress(
                            filename = metadata.filename,
                            bytesDownloaded = downloaded,
                            totalBytes = metadata.fileSize,
                            isComplete = false
                        )
                    )
                }
            }

            // Publish image to gallery
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val publishValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(uri, publishValues, null, null)
            }

            emit(
                DownloadProgress(
                    filename = metadata.filename,
                    bytesDownloaded = metadata.fileSize,
                    totalBytes = metadata.fileSize,
                    isComplete = true,
                    destinationUri = uri
                )
            )
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
    }.flowOn(Dispatchers.IO)

    /** Publish a completed conversion, deleting an incomplete MediaStore row on failure/cancel. */
    suspend fun publishJpeg(file: File, displayName: String, folderName: String = "AlphaPro"): Uri =
        withContext(Dispatchers.IO) {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, File(displayName).name)
                put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + folderName)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw java.io.IOException("Cannot create JPEG in MediaStore")
            var complete = false
            try {
                val output = resolver.openOutputStream(uri)
                    ?: throw java.io.IOException("Cannot open JPEG destination")
                output.use { destination ->
                    file.inputStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            val size = input.read(buffer)
                            if (size < 0) break
                            destination.write(buffer, 0, size)
                        }
                    }
                    destination.flush()
                }
                kotlinx.coroutines.currentCoroutineContext().ensureActive()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val published = resolver.update(uri, ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }, null, null)
                    if (published < 1) throw java.io.IOException("Cannot publish JPEG in MediaStore")
                }
                complete = true
                uri
            } finally {
                if (!complete) {
                    try { resolver.delete(uri, null, null) } catch (_: Exception) { /* preserve original failure */ }
                }
            }
        }

    suspend fun downloadToTempFile(
        metadata: PtpImageMetadata,
        onProgress: (bytesDownloaded: Long, totalBytes: Long) -> Unit = { _, _ -> }
    ): File {
        var tempFile: File? = null
        var delivered = false
        try {
            val downloaded = withContext(Dispatchers.IO) {
                val file = File.createTempFile("alphapro_", "_" + metadata.filename, context.cacheDir)
                tempFile = file
                FileOutputStream(file).use { out ->
                    downloadStream(metadata, out) { size ->
                        onProgress(size, metadata.fileSize)
                    }
                }
                file
            }
            // Only transfer ownership after withContext's cancellable return succeeds.
            delivered = true
            return downloaded
        } finally {
            if (!delivered) withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                tempFile?.delete()
            }
        }
    }

    private suspend fun downloadStream(
        metadata: PtpImageMetadata,
        outputStream: OutputStream,
        onProgress: suspend (Long) -> Unit
    ) {
        var offset = 0L
        val total = metadata.fileSize

        while (offset < total) {
            val length = minOf(CHUNK_SIZE.toLong(), total - offset).toInt()
            val chunk = client.readPartialObject(metadata, offset, length)
            if (chunk.isEmpty()) {
                throw java.io.IOException("PTP data transfer interrupted: empty chunk (offset=$offset, total=$total)")
            }
            outputStream.write(chunk)
            offset += chunk.size
            onProgress(offset)
        }
        outputStream.flush()
    }
}
