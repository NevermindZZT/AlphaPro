package com.letter.alphapro.core.location

import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.w3c.dom.Element

class GpxTrackLoggerTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun emptySessionFlushAndSharingReturnNullWithoutCreatingStorage() {
        val storage = File(temporaryFolder.root, "not-created/tracks")
        val logger = GpxTrackLogger(storage)

        assertEquals(0, logger.pointCount)
        assertFalse(logger.saveFailed.value)
        assertNull(logger.flush())
        assertNull(logger.flushSafely().getOrThrow())
        assertNull(logger.fileForSharing().getOrThrow())
        assertNull(logger.getLatestTrackFile())
        assertFalse(storage.exists())
        assertFalse(logger.saveFailed.value)
    }

    @Test
    fun flushCreatesNestedStorageDirectories() {
        val storage = File(temporaryFolder.root, "parent/child/tracks")
        val logger = GpxTrackLogger(storage)
        val point = point(0)
        logger.recordPoint(point)

        val saved = requireNotNull(logger.flushSafely().getOrThrow())

        assertTrue(storage.isDirectory)
        assertEquals(storage, saved.parentFile)
        assertTrue(saved.isFile)
        assertEquals(listOf(point), readPoints(saved))
        assertFalse(logger.saveFailed.value)
    }

    @Test
    fun explicitEmptyExportIsWellFormedGpx() {
        val logger = GpxTrackLogger(temporaryFolder.newFolder("tracks"))

        val exported = logger.exportToGpx("empty.gpx")

        assertEquals(emptyList<GeoCoordinate>(), readPoints(exported))
        assertEquals(0, logger.pointCount)
    }

    @Test
    fun exportedGpxPreservesCoordinatesElevationAndUtcTimesInNonUtcTimezone() {
        val previousZone = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Honolulu"))
            val logger = GpxTrackLogger(temporaryFolder.newFolder("tracks"))
            val points = listOf(
                GeoCoordinate(31.230416, 121.473701, 12.75, timestamp = 1_704_067_200_000L),
                GeoCoordinate(-33.86882, -151.209296, -4.5, timestamp = 1_704_067_261_000L)
            )
            points.forEach(logger::recordPoint)

            val file = logger.exportToGpx("utc.gpx")
            val root = parseGpx(file)

            assertEquals("1.1", root.getAttribute("version"))
            assertEquals("AlphaPro Camera", root.getAttribute("creator"))
            assertEquals(points, readPoints(file))
            val times = root.getElementsByTagNameNS(GPX_NAMESPACE, "time")
            assertEquals(3, times.length)
            for (index in 0 until times.length) {
                val time = times.item(index).textContent
                assertTrue(time, time.endsWith("Z"))
                Instant.parse(time)
            }
            assertEquals("2024-01-01T00:00:00Z", times.item(1).textContent)
            assertEquals("2024-01-01T00:01:01Z", times.item(2).textContent)
        } finally {
            TimeZone.setDefault(previousZone)
        }
    }

    @Test
    fun repeatedFlushReplacesTheSameSessionSnapshotWithUpdatedPoints() {
        val storage = temporaryFolder.newFolder("tracks")
        val logger = GpxTrackLogger(storage)
        logger.recordPoint(point(0))
        val first = requireNotNull(logger.flushSafely().getOrThrow())
        assertEquals(listOf(point(0)), readPoints(first))

        logger.recordPoint(point(1))
        val updated = requireNotNull(logger.flushSafely().getOrThrow())

        assertEquals(first, updated)
        assertEquals(listOf(point(0), point(1)), readPoints(first))
        assertEquals(listOf(updated), storage.listFiles()!!.toList())
        assertEquals(2, logger.pointCount)
    }

    @Test
    fun distinctSessionsPreserveBothFilesWithDayAndUniqueSessionIds() {
        val storage = temporaryFolder.newFolder("tracks")
        val firstLogger = GpxTrackLogger(storage)
        val secondLogger = GpxTrackLogger(storage)
        firstLogger.recordPoint(point(0))
        secondLogger.recordPoint(point(1))

        val first = requireNotNull(firstLogger.flushSafely().getOrThrow())
        val second = requireNotNull(secondLogger.flushSafely().getOrThrow())

        assertNotEquals(first, second)
        val filePattern = Regex("Track_\\d{4}-\\d{2}-\\d{2}_([0-9a-f-]{36})\\.gpx")
        val firstId = requireNotNull(filePattern.matchEntire(first.name)).groupValues[1]
        val secondId = requireNotNull(filePattern.matchEntire(second.name)).groupValues[1]
        assertNotEquals(UUID.fromString(firstId), UUID.fromString(secondId))
        assertEquals(setOf(first, second), storage.listFiles()!!.toSet())
        assertEquals(listOf(point(0)), readPoints(first))
        assertEquals(listOf(point(1)), readPoints(second))
    }

    @Test
    fun blockedDirectoryReturnsIOExceptionAndRetainsPointsForRecovery() {
        val blocked = temporaryFolder.newFile("blocked")
        val logger = GpxTrackLogger(blocked)
        logger.recordPoint(point(0))

        val failed = logger.flushSafely()

        assertTrue(failed.isFailure)
        assertTrue(failed.exceptionOrNull() is IOException)
        assertTrue(logger.saveFailed.value)
        assertEquals(1, logger.pointCount)
        assertEquals(point(0), logger.findClosestCoordinate(point(0).timestamp))
        assertTrue(blocked.delete())
        logger.recordPoint(point(1))

        val recovered = requireNotNull(logger.flushSafely().getOrThrow())

        assertTrue(blocked.isDirectory)
        assertEquals(listOf(point(0), point(1)), readPoints(recovered))
        assertEquals(2, logger.pointCount)
        assertFalse(logger.saveFailed.value)
    }

    @Test
    fun injectedSecurityExceptionIsSafeAndSuccessfulRetryClearsFailure() {
        val storage = temporaryFolder.newFolder("tracks")
        val denied = SecurityException("injected permission denial")
        var denyWrites = true
        val logger = GpxTrackLogger(storage) { file, write ->
            if (denyWrites) throw denied
            writeTrackAtomically(file, write)
        }
        logger.recordPoint(point(0))

        val failed = logger.flushSafely()

        assertTrue(failed.isFailure)
        assertSame(denied, failed.exceptionOrNull())
        assertTrue(logger.saveFailed.value)
        assertEquals(1, logger.pointCount)
        assertTrue(storage.listFiles()!!.isEmpty())
        denyWrites = false
        val recovered = requireNotNull(logger.flushSafely().getOrThrow())
        assertEquals(listOf(point(0)), readPoints(recovered))
        assertFalse(logger.saveFailed.value)
    }

    @Test
    fun partialAtomicWriteFailurePreservesPreviousGpxAndCleansTemporaryFiles() {
        val storage = temporaryFolder.newFolder("tracks")
        val failure = IOException("injected failure after partial output")
        var failWrites = false
        val logger = GpxTrackLogger(storage) { file, write ->
            writeTrackAtomically(file) { writer ->
                if (failWrites) {
                    writer.write("<?xml version=\"1.0\"?><gpx><trk>")
                    writer.flush()
                    throw failure
                }
                write(writer)
            }
        }
        logger.recordPoint(point(0))
        val previous = requireNotNull(logger.flushSafely().getOrThrow())
        val originalBytes = previous.readBytes()
        logger.recordPoint(point(1))
        failWrites = true

        val failed = logger.flushSafely()

        assertSame(failure, failed.exceptionOrNull())
        assertTrue(logger.saveFailed.value)
        assertEquals(2, logger.pointCount)
        assertArrayEquals(originalBytes, previous.readBytes())
        assertEquals(listOf(point(0)), readPoints(previous))
        assertEquals(listOf(previous), storage.listFiles()!!.toList())
        failWrites = false
        assertEquals(previous, logger.flushSafely().getOrThrow())
        assertEquals(listOf(point(0), point(1)), readPoints(previous))
        assertEquals(listOf(previous), storage.listFiles()!!.toList())
        assertFalse(logger.saveFailed.value)
    }

    @Test
    fun sharingDoesNotFallBackToStaleFileWhenCurrentFlushFails() {
        val storage = temporaryFolder.newFolder("tracks")
        val previousLogger = GpxTrackLogger(storage)
        previousLogger.recordPoint(point(0))
        val stale = requireNotNull(previousLogger.flushSafely().getOrThrow())
        val denied = SecurityException("cannot save current session")
        var denyWrites = true
        val currentLogger = GpxTrackLogger(storage) { file, write ->
            if (denyWrites) throw denied
            writeTrackAtomically(file, write)
        }
        currentLogger.recordPoint(point(1))
        assertEquals(stale, currentLogger.getLatestTrackFile())

        val failed = currentLogger.fileForSharing()

        assertTrue(failed.isFailure)
        assertSame(denied, failed.exceptionOrNull())
        assertNull(failed.getOrNull())
        assertTrue(currentLogger.saveFailed.value)
        assertEquals(1, currentLogger.pointCount)
        assertEquals(listOf(point(0)), readPoints(stale))
        denyWrites = false
        val shared = requireNotNull(currentLogger.fileForSharing().getOrThrow())
        assertNotEquals(stale, shared)
        assertEquals(listOf(point(1)), readPoints(shared))
        assertFalse(currentLogger.saveFailed.value)
    }

    @Test
    fun emptySessionSharesLatestGpxAndIgnoresDirectoriesAndOtherFiles() {
        val storage = temporaryFolder.newFolder("tracks")
        val writer = GpxTrackLogger(storage)
        writer.recordPoint(point(0))
        val older = writer.exportToGpx("older.gpx")
        writer.recordPoint(point(1))
        val latest = writer.exportToGpx("latest.GPX")
        assertTrue(older.setLastModified(1_000L))
        assertTrue(latest.setLastModified(2_000L))
        File(storage, "not-a-track.txt").writeText("ignore me")
        assertTrue(File(storage, "directory.gpx").mkdir())
        val emptySession = GpxTrackLogger(storage)

        assertNull(emptySession.flushSafely().getOrThrow())
        assertEquals(latest, emptySession.getLatestTrackFile())
        assertEquals(latest, emptySession.fileForSharing().getOrThrow())
        assertEquals(0, emptySession.pointCount)
        assertEquals(listOf(point(0), point(1)), readPoints(latest))
        assertFalse(emptySession.saveFailed.value)
    }

    @Test(timeout = 15_000L)
    fun recordingContinuesDuringDiskWriteAndExportUsesAnImmutableSnapshot() {
        val writeStarted = CountDownLatch(1)
        val allowWrite = CountDownLatch(1)
        val logger = GpxTrackLogger(temporaryFolder.newFolder("tracks")) { file, write ->
            writeStarted.countDown()
            check(allowWrite.await(5, TimeUnit.SECONDS)) { "write was not released" }
            writeTrackAtomically(file, write)
        }
        logger.recordPoint(point(0))
        val executor = Executors.newFixedThreadPool(2)
        try {
            val exporting = executor.submit<File> { logger.exportToGpx("snapshot.gpx") }
            assertTrue(writeStarted.await(5, TimeUnit.SECONDS))
            executor.submit { logger.recordPoint(point(1)) }.get(5, TimeUnit.SECONDS)
            assertEquals(2, logger.pointCount)
            allowWrite.countDown()

            assertEquals(listOf(point(0)), readPoints(exporting.get(5, TimeUnit.SECONDS)))
            val updated = requireNotNull(logger.flushSafely().getOrThrow())
            assertEquals(listOf(point(0), point(1)), readPoints(updated))
        } finally {
            allowWrite.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test(timeout = 30_000L)
    fun concurrentRecordFlushAndExportAlwaysProduceWellFormedSnapshots() {
        val storage = temporaryFolder.newFolder("tracks")
        val logger = GpxTrackLogger(storage)
        logger.recordPoint(point(0))
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(3)
        try {
            val recording = executor.submit {
                check(start.await(5, TimeUnit.SECONDS))
                for (index in 1..120) logger.recordPoint(point(index))
            }
            val flushing = executor.submit {
                check(start.await(5, TimeUnit.SECONDS))
                repeat(20) {
                    assertSnapshot(readPoints(requireNotNull(logger.flushSafely().getOrThrow())))
                }
            }
            val exporting = executor.submit {
                check(start.await(5, TimeUnit.SECONDS))
                repeat(20) {
                    assertSnapshot(readPoints(logger.exportToGpx("concurrent.gpx")))
                }
            }
            start.countDown()
            listOf(recording, flushing, exporting).forEach { it.get(20, TimeUnit.SECONDS) }

            val final = requireNotNull(logger.flushSafely().getOrThrow())
            assertEquals(121, logger.pointCount)
            assertEquals((0..120).map(::point), readPoints(final))
            storage.listFiles()!!.forEach { file ->
                assertTrue(file.name, file.name.endsWith(".gpx"))
                assertSnapshot(readPoints(file))
            }
            assertFalse(logger.saveFailed.value)
        } finally {
            start.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun assertSnapshot(points: List<GeoCoordinate>) {
        assertTrue(points.isNotEmpty())
        assertTrue(points.size <= 121)
        assertEquals((0 until points.size).map(::point), points)
    }

    private fun point(index: Int) = GeoCoordinate(
        latitude = 31.0 + index / 1_000.0,
        longitude = 121.0 + index / 1_000.0,
        altitude = index.toDouble(),
        timestamp = 1_704_067_200_000L + index * 1_000L
    )

    private fun parseGpx(file: File): Element {
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val document = factory.newDocumentBuilder().parse(file)
        val root = document.documentElement
        assertEquals("gpx", root.localName)
        assertEquals(GPX_NAMESPACE, root.namespaceURI)
        assertEquals(1, root.getElementsByTagNameNS(GPX_NAMESPACE, "trk").length)
        assertEquals(1, root.getElementsByTagNameNS(GPX_NAMESPACE, "trkseg").length)
        return root
    }

    private fun readPoints(file: File): List<GeoCoordinate> {
        val nodes = parseGpx(file).getElementsByTagNameNS(GPX_NAMESPACE, "trkpt")
        return (0 until nodes.length).map { index ->
            val point = nodes.item(index) as Element
            GeoCoordinate(
                latitude = point.getAttribute("lat").toDouble(),
                longitude = point.getAttribute("lon").toDouble(),
                altitude = point.getElementsByTagNameNS(GPX_NAMESPACE, "ele")
                    .item(0).textContent.toDouble(),
                timestamp = Instant.parse(point.getElementsByTagNameNS(GPX_NAMESPACE, "time")
                    .item(0).textContent).toEpochMilli()
            )
        }
    }

    private companion object {
        const val GPX_NAMESPACE = "http://www.topografix.com/GPX/1/1"
    }
}
