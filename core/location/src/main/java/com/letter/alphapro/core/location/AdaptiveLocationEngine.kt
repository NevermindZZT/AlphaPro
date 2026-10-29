package com.letter.alphapro.core.location

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

class AdaptiveLocationEngine(
    private val locationSource: LocationSource,
    private val maxAllowedAccuracyMeters: Float = 150f
) {
    private var lastEmittedLocation: GeoCoordinate? = null

    fun getFilteredLocationUpdates(): Flow<GeoCoordinate> {
        return locationSource.getLocationUpdates(5000L)
            .filter { coord ->
                // Filter out bad fixes
                coord.accuracy in 0.001f..maxAllowedAccuracyMeters &&
                    coord.latitude != 0.0 && coord.longitude != 0.0
            }
            .map { coord ->
                lastEmittedLocation = coord
                coord
            }
    }
}

class GpxTrackLogger(
    val storageDir: File,
    private val atomicWrite: (File, (java.io.Writer) -> Unit) -> Unit = ::writeTrackAtomically
) {
    private val trackPoints = Collections.synchronizedList(mutableListOf<GeoCoordinate>())
    private val storageLock = Any()
    // A new process must never overwrite the previous process's track for the same day.
    private val sessionId = UUID.randomUUID().toString()
    private val sessionStartedAt = Date()
    private val _saveFailed = kotlinx.coroutines.flow.MutableStateFlow(false)
    val saveFailed: kotlinx.coroutines.flow.StateFlow<Boolean> = _saveFailed
    private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    private val dayDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    val pointCount: Int get() = trackPoints.size

    fun recordPoint(coord: GeoCoordinate) {
        trackPoints.add(coord)
    }

    fun findClosestCoordinate(photoTimeMillis: Long, maxToleranceMillis: Long = 15 * 60 * 1000L): GeoCoordinate? {
        synchronized(trackPoints) {
            if (trackPoints.isEmpty()) return null
            var closest: GeoCoordinate? = null
            var minDiff = Long.MAX_VALUE

            for (pt in trackPoints) {
                val diff = abs(pt.timestamp - photoTimeMillis)
                if (diff < minDiff) {
                    minDiff = diff
                    closest = pt
                }
            }
            return if (minDiff <= maxToleranceMillis) closest else null
        }
    }

    /** Recoverable disk/permission failures must not escape a service coroutine. */
    fun flushSafely(): Result<File?> = storageResult { flush() }

    /** Do not silently share an older file when saving the current track failed. */
    fun fileForSharing(): Result<File?> = storageResult { flush() ?: getLatestTrackFile() }

    private inline fun storageResult(block: () -> File?): Result<File?> = synchronized(storageLock) {
        try {
            Result.success(block()).also { _saveFailed.value = false }
        } catch (failure: java.io.IOException) {
            _saveFailed.value = true
            Result.failure(failure)
        } catch (failure: SecurityException) {
            _saveFailed.value = true
            Result.failure(failure)
        }
    }

    fun flush(): File? = synchronized(storageLock) {
        if (trackPoints.isEmpty()) return@synchronized null
        val dayStr = dayDateFormat.format(sessionStartedAt)
        exportToGpx("Track_${dayStr}_$sessionId.gpx")
    }

    fun exportToGpx(fileName: String = "Track_${System.currentTimeMillis()}.gpx"): File = synchronized(storageLock) {
        require(File(fileName).name == fileName && fileName.endsWith(".gpx", ignoreCase = true))
        if (!storageDir.isDirectory && !storageDir.mkdirs()) {
            throw java.io.IOException("Track storage is unavailable")
        }
        val file = File(storageDir, fileName)
        // Recording is never blocked on disk I/O. Writers/formatters are serialized separately.
        val snapshot = synchronized(trackPoints) { trackPoints.toList() }
        atomicWrite(file) { writer ->
            writer.write("""<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="AlphaPro Camera" xmlns="http://www.topografix.com/GPX/1/1">
  <metadata>
    <name>AlphaPro Camera Track</name>
    <time>${isoDateFormat.format(Date())}</time>
  </metadata>
  <trk>
    <name>AlphaPro Track</name>
    <trkseg>
""")
            for (pt in snapshot) {
                val timeStr = isoDateFormat.format(Date(pt.timestamp))
                writer.write("""      <trkpt lat="${pt.latitude}" lon="${pt.longitude}">
        <ele>${pt.altitude}</ele>
        <time>$timeStr</time>
      </trkpt>
""")
            }
            writer.write("""    </trkseg>
  </trk>
</gpx>""")
        }
        file
    }

    fun getLatestTrackFile(): File? = synchronized(storageLock) {
        if (!storageDir.exists()) return@synchronized null
        storageDir.listFiles { f -> f.isFile && f.name.endsWith(".gpx", ignoreCase = true) }
            ?.maxByOrNull { it.lastModified() }
    }
}

/** Same-directory temporary file + atomic replacement: failure never truncates a saved GPX. */
internal fun writeTrackAtomically(file: File, write: (java.io.Writer) -> Unit) {
    val temp = File.createTempFile(".track-", ".tmp", file.parentFile)
    try {
        java.io.FileOutputStream(temp).use { stream ->
            val writer = stream.bufferedWriter(Charsets.UTF_8)
            write(writer)
            writer.flush()
            stream.fd.sync()
        }
        java.nio.file.Files.move(temp.toPath(), file.toPath(),
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    } finally {
        temp.delete()
    }
}
