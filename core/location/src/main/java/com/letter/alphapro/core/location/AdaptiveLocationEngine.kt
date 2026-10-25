package com.letter.alphapro.core.location

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import java.io.File
import java.io.FileWriter
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

class GpxTrackLogger(private val storageDir: File) {
    private val trackPoints = Collections.synchronizedList(mutableListOf<GeoCoordinate>())
    private val isoDateFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

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

    fun exportToGpx(fileName: String = "Track_${System.currentTimeMillis()}.gpx"): File {
        val file = File(storageDir, fileName)
        FileWriter(file).use { writer ->
            writer.write("""<?xml version="1.0" encoding="UTF-8"?>
<gpx version="1.1" creator="AlphaPro Camera" xmlns="http://www.topografix.com/GPX/1/1">
  <trk>
    <name>AlphaPro Camera Track</name>
    <trkseg>
""")
            synchronized(trackPoints) {
                for (pt in trackPoints) {
                    val timeStr = isoDateFormat.format(Date(pt.timestamp))
                    writer.write("""      <trkpt lat="${pt.latitude}" lon="${pt.longitude}">
        <ele>${pt.altitude}</ele>
        <time>$timeStr</time>
      </trkpt>
""")
                }
            }
            writer.write("""    </trkseg>
  </trk>
</gpx>""")
        }
        return file
    }
}
