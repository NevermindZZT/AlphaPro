package com.letter.alphapro.core.ptp

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Loopback PTP/IP camera: bounds/refusals must not poison the following transaction. */
class CameraPreviewProtocolTest {
    @Test(timeout = 15000) fun largePreviewUsesSizeTwoAndThumbnailUsesSizeOne() = runBlocking {
        FakeCamera().use { camera ->
            val client = PtpIpClient()
            client.connect("127.0.0.1", camera.port)
            assertArrayEquals(image, client.getPreview(photo()))
            assertArrayEquals(image, client.getThumbnail(photo()))
            client.disconnect()
            val images = camera.requests.filter { it.first == 0x923e }
            assertEquals(listOf(42L, 0x01000002L, 2L), images[0].second)
            assertEquals(listOf(42L, 0x01000002L, 1L), images[1].second)
            assertFalse(camera.requests.any { it.first == 0x923d })
        }
    }

    @Test(timeout = 15000) fun refusalAndPartialLengthMismatchDoNotCloseSharedSession() = runBlocking {
        FakeCamera(plan = { op, params, _ -> when {
            op == 0x923e && params.last() == 2L -> Reply(code = 0x2005, bytes = 0)
            op == 0x923d -> Reply(announced = 65536)
            else -> Reply()
        } }).use { camera ->
            val client = PtpIpClient()
            client.connect("127.0.0.1", camera.port)
            try { client.getPreview(photo()); fail("Expected preview refusal") }
            catch (failure: PtpResponseException) { assertEquals(0x2005, failure.responseCode) }
            try { client.readPartialObject(photo(), 0, 65536); fail("Expected length mismatch") }
            catch (_: PtpDataException) {}
            assertArrayEquals(image, client.getThumbnail(photo()))
            assertNull(client.lastSessionFailure)
            assertFalse(client.reconnectIfNeeded())
            client.disconnect()
            assertEquals(listOf(42L, 0x01000002L, 0L, 0L, 65536L), camera.requests.first { it.first == 0x923d }.second)
        }
    }

    @Test(timeout = 15000) fun unknownAnnouncedPartialLengthAcceptsShortRead() = runBlocking {
        FakeCamera(plan = { _, _, _ -> Reply(announced = -1) }).use { camera ->
            val client = PtpIpClient()
            client.connect("127.0.0.1", camera.port)
            assertArrayEquals(image, client.readPartialObject(photo(), 0, 65536))
            assertArrayEquals(image, client.getThumbnail(photo()))
            client.disconnect()
        }
    }

    @Test(timeout = 15000) fun oversizedSinglePacketIsDiscardedAndNextObjectStillWorks() = runBlocking {
        FakeCamera(plan = { _, _, index -> if (index == 1) Reply(announced = -1, bytes = 600 * 1024) else Reply() }).use { camera ->
            val client = PtpIpClient()
            client.connect("127.0.0.1", camera.port)
            try { client.getThumbnail(photo()); fail("Expected bound failure to propagate") }
            catch (_: PtpDataException) {}
            assertArrayEquals(image, client.getThumbnail(photo()))
            assertNull(client.lastSessionFailure)
            assertEquals(1, camera.sessions.get())
            client.disconnect()
        }
    }

    @Test(timeout = 15000) fun wrongDataIdEvenDuringDiscardClosesSessionAndPreservesFirstCause() = runBlocking {
        assertFatalDataFailure(Reply(announced = 1024 * 1024, dataIdOffset = 1))
    }

    @Test(timeout = 15000) fun wrongFinalResponseIdAfterDataFailureIsStillFatal() = runBlocking {
        assertFatalDataFailure(Reply(announced = 100, responseIdOffset = 1))
    }

    @Test(timeout = 15000) fun malformedFrameAndSocketLossAreNotRecoverableObjectFailures() = runBlocking {
        assertFatalDataFailure(Reply(malformedLength = true))
        assertFatalDataFailure(Reply(dropSocket = true))
    }

    @Test(timeout = 15000) fun pingPongWorksOnCommandDataInAndDataOutLanes() = runBlocking {
        FakeCamera(ping = true).use { camera ->
            val client = PtpIpClient()
            client.connect("127.0.0.1", camera.port)
            assertEquals(0x2001, client.executeCommand(0x1001))
            assertArrayEquals(image, client.getThumbnail(photo()))
            assertEquals(0x2001, client.executeDataOut(0x9207, listOf(1), byteArrayOf(2)))
            assertEquals(4, camera.pongs.get()) // Includes session initialization.
            client.disconnect()
        }
    }

    @Test(timeout = 15000) fun commandAndDataOutResponsesRequireMatchingTransactions() = runBlocking {
        for (write in listOf(false, true)) {
            FakeCamera(plan = { op, _, _ -> if (op == 0x9207 || op == 0x1001) Reply(responseIdOffset = 1) else Reply() }).use { camera ->
                val client = PtpIpClient()
                client.connect("127.0.0.1", camera.port)
                try {
                    if (write) client.executeDataOut(0x9207, listOf(1), byteArrayOf(2)) else client.executeCommand(0x1001)
                    fail("Expected response transaction mismatch")
                } catch (_: IllegalArgumentException) {}
                assertNotNull(client.lastSessionFailure)
                client.disconnect()
            }
        }
    }

    @Test(timeout = 15000) fun concurrentReconnectsCoalesceAndDoNotReplayFailedReadsOrWrites() = runBlocking {
        FakeCamera(plan = { op, _, index ->
            if (op == 0x100e || index == 1) Reply(dropSocket = true) else Reply()
        }).use { camera ->
            val client = PtpIpClient()
            client.connect("127.0.0.1", camera.port)
            try { client.getThumbnail(photo()); fail("Expected socket loss") } catch (_: EOFException) {}
            val results = List(8) { async(Dispatchers.IO) { client.reconnectIfNeeded() } }.awaitAll()
            assertEquals(1, results.count { it })
            assertEquals(2, camera.sessions.get())
            assertEquals(1, camera.requests.count { it.first == 0x923e }) // No hidden read replay.
            assertNull(client.lastSessionFailure)
            assertArrayEquals(image, client.getThumbnail(photo())) // Explicit idempotent retry only.
            try { client.executeCommand(0x100e); fail("Expected uncertain capture result") } catch (_: EOFException) {}
            assertTrue(client.reconnectIfNeeded())
            assertEquals(1, camera.requests.count { it.first == 0x100e }) // Never capture twice.
            client.disconnect()
            try { client.reconnectIfNeeded(); fail("Explicit disconnect must forget target") }
            catch (_: PtpSessionClosedException) {}
        }
    }

    @Test(timeout = 15000) fun reconnectWaitsForInFlightReadAndDoesNotReplaceHealthySession() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        FakeCamera(plan = { op, _, _ ->
            if (op == 0x923d) { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            Reply()
        }).use { camera ->
            val client = PtpIpClient()
            client.connect("127.0.0.1", camera.port)
            val read = async(Dispatchers.IO) { client.readPartialObject(photo(), 0, 4) }
            check(entered.await(5, TimeUnit.SECONDS))
            val reconnect = async(Dispatchers.IO) { client.reconnectIfNeeded() }
            release.countDown()
            assertArrayEquals(image, read.await())
            assertFalse(reconnect.await())
            assertEquals(1, camera.sessions.get())
            client.disconnect()
        }
    }

    @Test(timeout = 15000) fun readsAndConcurrentReconnectCannotEnterDuringSessionInitialization() = runBlocking {
        val opens = AtomicInteger()
        val initializing = CountDownLatch(1)
        val release = CountDownLatch(1)
        FakeCamera(plan = { op, _, index ->
            if (op == 0x9210 && opens.incrementAndGet() == 2) {
                initializing.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            if (op == 0x923e && index == 1) Reply(dropSocket = true) else Reply()
        }).use { camera ->
            val client = PtpIpClient()
            client.connect("127.0.0.1", camera.port)
            try { client.getThumbnail(photo()); fail("Expected socket loss") } catch (_: EOFException) {}
            val recovery = async(Dispatchers.IO) { client.reconnectIfNeeded() }
            check(initializing.await(5, TimeUnit.SECONDS))
            val read = async(Dispatchers.IO) { client.getThumbnail(photo()) }
            val secondRecovery = async(Dispatchers.IO) { client.reconnectIfNeeded() }
            release.countDown()
            assertTrue(recovery.await())
            assertArrayEquals(image, read.await())
            assertFalse(secondRecovery.await())
            assertEquals(2, camera.sessions.get())
            client.disconnect()
        }
    }

    @Test(timeout = 15000) fun cameraSessionNotOpenResponseCanBeExplicitlyReconnected() = runBlocking {
        FakeCamera(plan = { op, _, index ->
            if (op == 0x923e && index == 1) Reply(code = 0x2003, bytes = 0) else Reply()
        }).use { camera ->
            val client = PtpIpClient()
            client.connect("127.0.0.1", camera.port)
            try { client.getThumbnail(photo()); fail("Expected camera session refusal") }
            catch (failure: PtpResponseException) { assertEquals(0x2003, failure.responseCode) }
            assertNotNull(client.lastSessionFailure)
            assertTrue(client.reconnectIfNeeded())
            assertArrayEquals(image, client.getThumbnail(photo()))
            client.disconnect()
        }
    }

    @Test(timeout = 15000) fun cancelledAcceptedPreviewSettlesBeforeQueuedExportUsesSameSession() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        FakeCamera(plan = { op, params, _ ->
            if (op == 0x923e && params.last() == 2L) Reply(beforeResponse = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }) else Reply()
        }).use { camera ->
            val client = PtpIpClient()
            client.connect("127.0.0.1", camera.port)
            val preview = async(Dispatchers.IO) { client.getPreview(photo()) }
            check(entered.await(5, TimeUnit.SECONDS)) // Data sent; final response still withheld.
            preview.cancel()
            val output = ByteArrayOutputStream()
            val export = async(Dispatchers.IO) {
                PtpPhotoDownloader.downloadStream(client, photo(), output, client.captureSessionEpoch()) {}
            }
            release.countDown()
            preview.join()
            export.await()
            assertTrue(preview.isCancelled)
            assertArrayEquals(image, output.toByteArray())
            assertNull(client.lastSessionFailure)
            assertEquals(1, camera.sessions.get())
            assertEquals(listOf(0x9210, 0x923e, 0x923d), camera.requests.map { it.first })
            client.disconnect()
        }
    }

    @Test(timeout = 15000) fun cancellationWhileWaitingForMutexDoesNotSendOrCloseAnything() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        FakeCamera(plan = { op, _, _ ->
            if (op == 0x923d) Reply(beforeResponse = {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            }) else Reply()
        }).use { camera ->
            val client = PtpIpClient()
            client.connect("127.0.0.1", camera.port)
            val active = async(Dispatchers.IO) { client.readPartialObject(photo(), 0, 4) }
            check(entered.await(5, TimeUnit.SECONDS))
            val queued = async(start = CoroutineStart.UNDISPATCHED) { client.getPreview(photo()) }
            queued.cancel()
            queued.join()
            release.countDown()
            assertArrayEquals(image, active.await())
            assertArrayEquals(image, client.getThumbnail(photo()))
            assertEquals(1, camera.requests.count { it.first == 0x923e }) // Thumbnail only.
            assertNull(client.lastSessionFailure)
            assertEquals(1, camera.sessions.get())
            client.disconnect()
        }
    }

    @Test(timeout = 15000) fun cancelledAcceptedCommandsAndWritesSettleButAreNeverReplayed() = runBlocking {
        for (write in listOf(false, true)) {
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val op = if (write) 0x9207 else 0x100e
            FakeCamera(plan = { request, _, _ ->
                if (request == op) Reply(beforeResponse = {
                    entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                }) else Reply()
            }).use { camera ->
                val client = PtpIpClient()
                client.connect("127.0.0.1", camera.port)
                val command = async(Dispatchers.IO) {
                    if (write) client.executeDataOut(op, listOf(1), byteArrayOf(2)) else client.executeCommand(op)
                }
                check(entered.await(5, TimeUnit.SECONDS))
                command.cancel()
                val next = async(Dispatchers.IO) { client.getThumbnail(photo()) }
                release.countDown()
                command.join()
                assertArrayEquals(image, next.await())
                assertEquals(1, camera.requests.count { it.first == op })
                assertEquals(1, camera.sessions.get())
                assertNull(client.lastSessionFailure)
                client.disconnect()
            }
        }
    }

    @Test(timeout = 15000) fun exportRetriesOnlyFailedRangeOnceAfterSocketLossWithoutDuplicatingLocalBytes() = runBlocking {
        for (catalog in listOf(true, false)) {
            val partialReads = AtomicInteger()
            val op = if (catalog) 0x923d else 0x9211
            FakeCamera(plan = { request, _, _ ->
                if (request == op && partialReads.incrementAndGet() == 2) Reply(dropAfterData = true) else Reply()
            }).use { camera ->
                val client = PtpIpClient()
                client.connect("127.0.0.1", camera.port)
                val output = ByteArrayOutputStream()
                val progress = mutableListOf<Long>()
                PtpPhotoDownloader.downloadStream(client, photo().copy(fileSize = 8, isContentCatalog = catalog),
                    output, client.captureSessionEpoch()) { progress.add(it) }
                assertArrayEquals(image + image, output.toByteArray())
                assertEquals(listOf(4L, 8L), progress)
                val requests = camera.requests.filter { it.first == op }.map { it.second }
                val offsetIndex = if (catalog) 2 else 1
                assertEquals(listOf(0L, 4L, 4L), requests.map { it[offsetIndex] })
                assertEquals(requests[1], requests[2])
                assertEquals(2, camera.sessions.get())
                client.disconnect()
            }
        }
    }

    @Test(timeout = 15000) fun exportRecoversSessionNotOpenButNotObjectRefusalOrLengthFailure() = runBlocking {
        for (code in listOf(0x2003, 0x2005, 0x2001)) {
            val reads = AtomicInteger()
            FakeCamera(plan = { op, _, _ ->
                if (op == 0x923d && reads.incrementAndGet() == 1) {
                    if (code == 0x2001) Reply(announced = 100) else Reply(code = code, bytes = 0)
                } else Reply()
            }).use { camera ->
                val client = PtpIpClient()
                client.connect("127.0.0.1", camera.port)
                val output = ByteArrayOutputStream()
                try {
                    PtpPhotoDownloader.downloadStream(client, photo(), output, client.captureSessionEpoch()) {}
                    assertEquals(0x2003, code)
                    assertArrayEquals(image, output.toByteArray())
                } catch (failure: Exception) {
                    if (code == 0x2003) throw failure
                    assertTrue(if (code == 0x2005) failure is PtpResponseException else failure is PtpDataException)
                    assertEquals(0, output.size())
                    assertArrayEquals(image, client.getThumbnail(photo()))
                }
                assertEquals(if (code == 0x2003) 2 else 1, reads.get())
                assertEquals(if (code == 0x2003) 2 else 1, camera.sessions.get())
                client.disconnect()
            }
        }
    }

    @Test(timeout = 15000) fun exportStopsAfterOneFailedRetryAndWritesNoPartialChunk() = runBlocking {
        FakeCamera(plan = { op, _, _ -> if (op == 0x923d) Reply(dropSocket = true) else Reply() }).use { camera ->
            val client = PtpIpClient()
            client.connect("127.0.0.1", camera.port)
            val output = ByteArrayOutputStream()
            try {
                PtpPhotoDownloader.downloadStream(client, photo(), output, client.captureSessionEpoch()) {}
                fail("Expected exhausted retry")
            } catch (_: EOFException) {}
            assertEquals(0, output.size())
            assertEquals(2, camera.requests.count { it.first == 0x923d })
            assertEquals(2, camera.sessions.get())
            client.disconnect()
        }
    }

    @Test(timeout = 15000) fun staleExportEpochCannotReconnectDisconnectedOrReplacementCamera() = runBlocking {
        FakeCamera().use { oldCamera ->
            FakeCamera().use { replacement ->
                val client = PtpIpClient()
                client.connect("127.0.0.1", oldCamera.port)
                val epoch = client.captureSessionEpoch()
                client.disconnect()
                val output = ByteArrayOutputStream()
                try {
                    PtpPhotoDownloader.downloadStream(client, photo(), output, epoch) {}
                    fail("Stale export reopened disconnected camera")
                } catch (_: PtpSessionClosedException) {}
                client.connect("127.0.0.1", replacement.port)
                try {
                    PtpPhotoDownloader.downloadStream(client, photo(), output, epoch) {}
                    fail("Stale export used replacement camera")
                } catch (_: PtpSessionClosedException) {}
                assertEquals(0, output.size())
                assertEquals(listOf(0x9210), oldCamera.requests.map { it.first })
                assertEquals(listOf(0x9210), replacement.requests.map { it.first })
                assertArrayEquals(image, client.getThumbnail(photo()))
                client.disconnect()
            }
        }
    }

    @Test(timeout = 15000) fun disconnectIntentDuringFailedReadPreventsOldExportRecovery() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        FakeCamera(plan = { op, _, _ ->
            if (op == 0x923d) {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
                Reply(dropSocket = true)
            } else Reply()
        }).use { camera ->
            val client = PtpIpClient()
            client.connect("127.0.0.1", camera.port)
            val epoch = client.captureSessionEpoch()
            val output = ByteArrayOutputStream()
            val export = async(Dispatchers.IO) {
                try {
                    PtpPhotoDownloader.downloadStream(client, photo(), output, epoch) {}
                    false
                } catch (_: PtpSessionClosedException) { true }
            }
            check(entered.await(5, TimeUnit.SECONDS))
            // UNDISPATCHED publishes the disconnect epoch before waiting for accepted I/O.
            val disconnect = async(start = CoroutineStart.UNDISPATCHED) { client.disconnect() }
            release.countDown()
            assertTrue(export.await())
            disconnect.await()
            assertEquals(0, output.size())
            assertEquals(1, camera.sessions.get())
            assertEquals(1, camera.requests.count { it.first == 0x923d })
        }
    }

    @Test(timeout = 15000) fun cancelledAcceptedReadThatCannotSettleClosesAtTransactionDeadlineAndExportRecovers() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        FakeCamera(plan = { op, params, _ ->
            if (op == 0x923e && params.last() == 2L) Reply(beforeResponse = {
                entered.countDown(); check(release.await(5, TimeUnit.SECONDS))
            }) else Reply()
        }).use { camera ->
            // Internal test-only timings: production remains 15s socket / 30s transaction.
            val client = PtpIpClient(socketTimeoutMillis = 5000, transactionTimeoutMillis = 1000)
            client.connect("127.0.0.1", camera.port)
            val preview = async(Dispatchers.IO) { client.getPreview(photo()) }
            check(entered.await(5, TimeUnit.SECONDS))
            preview.cancel()
            preview.join() // Must finish without releasing the camera's final response.
            assertTrue(client.lastSessionFailure is java.net.SocketTimeoutException)
            release.countDown()
            val output = ByteArrayOutputStream()
            PtpPhotoDownloader.downloadStream(client, photo(), output, client.captureSessionEpoch()) {}
            assertArrayEquals(image, output.toByteArray())
            assertEquals(2, camera.sessions.get())
            assertEquals(1, camera.requests.count { it.first == 0x923e })
            client.disconnect()
        }
    }

    @Test(timeout = 15000) fun pinnedPreviewCannotReadOrReconnectReplacementCamera() = runBlocking {
        FakeCamera().use { original ->
            FakeCamera().use { replacement ->
                val client = PtpIpClient()
                client.connect("127.0.0.1", original.port)
                val epoch = client.captureSessionEpoch()
                client.connect("127.0.0.1", replacement.port)
                try { client.getPreview(photo(), epoch); fail("Stale preview must be rejected") }
                catch (_: PtpSessionClosedException) {}
                try { client.reconnectIfNeeded(epoch); fail("Stale preview must not reconnect") }
                catch (_: PtpSessionClosedException) {}
                assertEquals(0, original.requests.count { it.first == 0x923e })
                assertEquals(0, replacement.requests.count { it.first == 0x923e })
                assertArrayEquals(image, client.getPreview(photo(), client.captureSessionEpoch()))
                assertEquals(1, replacement.requests.count { it.first == 0x923e })
                client.disconnect()
            }
        }
    }

    @Test(timeout = 15000) fun reconnectRestoresPreviouslyEnabledCatalogModeBeforeNextRead() = runBlocking {
        val catalog = ByteBuffer.allocate(148).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(4); putShort(0); putLong(0); position(140); putInt(1); putInt(0)
        }.array()
        FakeCamera(plan = { op, _, index ->
            when (op) {
                0x923c -> Reply(announced = catalog.size.toLong(), bytes = catalog.size, payload = catalog)
                0x1004 -> Reply(payload = byteArrayOf(0, 0, 0, 0))
                0x923e -> if (index == 3) Reply(dataIdOffset = 1) else Reply()
                else -> Reply()
            }
        }).use { camera ->
            val client = PtpIpClient()
            client.connect("127.0.0.1", camera.port)
            assertTrue(client.loadMediaList().isEmpty())
            try { client.getThumbnail(photo()); fail("Expected fatal stream mismatch") }
            catch (_: IllegalArgumentException) {}
            assertTrue(client.reconnectIfNeeded())
            assertArrayEquals(image, client.getThumbnail(photo()))
            val operations = camera.requests.map { it.first }
            assertEquals(2, operations.count { it == 0x9207 })
            assertTrue(operations.lastIndexOf(0x9207) < operations.lastIndexOf(0x923e))
            client.disconnect()
        }
    }

    private suspend fun assertFatalDataFailure(reply: Reply) {
        FakeCamera(plan = { op, _, _ -> if (op == 0x923e) reply else Reply() }).use { camera ->
            val client = PtpIpClient()
            client.connect("127.0.0.1", camera.port)
            var original: Exception? = null
            try { client.getThumbnail(photo()); fail("Expected fatal protocol failure") }
            catch (failure: Exception) {
                assertFalse(failure is PtpDataException || failure is PtpResponseException)
                original = failure
            }
            val recorded = checkNotNull(client.lastSessionFailure)
            // Coroutine stack recovery may copy the thrown exception, not the stored cause.
            assertEquals(original!!.javaClass, recorded.javaClass)
            assertEquals(original.message, recorded.message)
            try { client.getPreview(photo()); fail("Expected closed session") }
            catch (failure: PtpSessionClosedException) {
                val root = generateSequence(failure as Throwable) { it.cause }.last()
                assertEquals(recorded.javaClass, root.javaClass)
                assertEquals(recorded.message, root.message)
            }
            assertSame(recorded, client.lastSessionFailure)
            client.disconnect()
        }
    }

    private fun photo() = PtpImageMetadata((1L shl 56) or (2L shl 32) or 42L,
        "DSC00042.JPG", 4, PtpConstants.FMT_JPEG, false, false, "", true)

    private data class Reply(
        val announced: Long = 4, val bytes: Int = 4, val code: Int = 0x2001,
        val dataIdOffset: Int = 0, val responseIdOffset: Int = 0,
        val dropSocket: Boolean = false, val malformedLength: Boolean = false, val payload: ByteArray? = null,
        val dropAfterData: Boolean = false, val beforeResponse: (() -> Unit)? = null
    )

    private class FakeCamera(
        private val ping: Boolean = false,
        private val plan: (Int, List<Long>, Int) -> Reply = { _, _, _ -> Reply() }
    ) : Closeable {
        private val server = ServerSocket(0)
        val port = server.localPort
        val requests = CopyOnWriteArrayList<Pair<Int, List<Long>>>()
        val sessions = AtomicInteger()
        val pongs = AtomicInteger()
        @Volatile private var closed = false
        @Volatile private var command: Socket? = null
        @Volatile private var event: Socket? = null
        @Volatile private var failure: Throwable? = null
        private var reads = 0
        private val worker = Thread {
            try {
                while (!closed) {
                    try { serveSession() } catch (_: EOFException) {} catch (_: SocketException) {
                        if (closed) break
                    } finally {
                        command?.close(); event?.close()
                    }
                }
            } catch (error: Throwable) { if (!closed) failure = error }
        }.apply { isDaemon = true; start() }

        private fun serveSession() {
            val cmd = server.accept().also { command = it; it.soTimeout = 5000 }
            val input = DataInputStream(cmd.getInputStream())
            check(read(input).first == 1)
            sessions.incrementAndGet()
            send(cmd.getOutputStream(), 2, buffer(4).putInt(42).array())
            val evt = server.accept().also { event = it; it.soTimeout = 5000 }
            check(read(DataInputStream(evt.getInputStream())).first == 3)
            send(evt.getOutputStream(), 4, byteArrayOf())
            while (!closed) {
                val (type, bytes) = read(input)
                check(type == 6)
                val body = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val phase = body.int
                val op = body.short.toInt() and 0xffff
                val id = body.int
                val params = mutableListOf<Long>()
                while (body.remaining() >= 4) params.add(body.int.toLong() and 0xffffffffL)
                requests.add(op to params)
                if (phase == 2) {
                    check(read(input).first == 9)
                    check(read(input).first == 12)
                }
                val isRead = op == 0x923e || op == 0x923d || op == 0x9211 || op == 0x923c || op == 0x1004
                if (isRead) reads++
                val reply = plan(op, params, reads)
                if (reply.dropSocket && op != 0x9210) { cmd.close(); return }
                if (ping) {
                    send(cmd.getOutputStream(), 14, byteArrayOf()) // Unsolicited valid PONG.
                    send(cmd.getOutputStream(), 13, byteArrayOf())
                    val pong = read(input)
                    check(pong.first == 14 && pong.second.isEmpty())
                    pongs.incrementAndGet()
                }
                if (isRead && reply.malformedLength) {
                    cmd.getOutputStream().write(buffer(8).putInt(7).putInt(12).array())
                    cmd.getOutputStream().flush()
                    continue
                }
                if (isRead && (reply.code == 0x2001 || reply.bytes > 0)) {
                    send(cmd.getOutputStream(), 9, buffer(12).putInt(id).putLong(reply.announced).array())
                    val data = reply.payload ?: ByteArray(reply.bytes) { image[it % image.size] }
                    send(cmd.getOutputStream(), 12, buffer(4 + data.size).putInt(id + reply.dataIdOffset).put(data).array())
                }
                if (reply.dropAfterData) { cmd.close(); return }
                reply.beforeResponse?.invoke()
                send(cmd.getOutputStream(), 7, buffer(6).putShort(reply.code.toShort()).putInt(id + reply.responseIdOffset).array())
            }
        }

        override fun close() {
            closed = true
            command?.close(); event?.close(); server.close()
            worker.join(5000)
            check(!worker.isAlive) { "Camera fixture did not stop" }
            failure?.let { throw AssertionError("Camera fixture failed", it) }
        }
        private fun read(input: DataInputStream): Pair<Int, ByteArray> {
            val header = ByteArray(8); input.readFully(header)
            val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            val length = b.int; val type = b.int
            require(length in 8..4096)
            val bytes = ByteArray(length - 8); input.readFully(bytes)
            return type to bytes
        }
        private fun send(output: OutputStream, type: Int, bytes: ByteArray) {
            output.write(buffer(8).putInt(8 + bytes.size).putInt(type).array()); output.write(bytes); output.flush()
        }
    }

    companion object {
        private val image = byteArrayOf(1, 2, 3, 4)
        private fun buffer(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
    }
}
