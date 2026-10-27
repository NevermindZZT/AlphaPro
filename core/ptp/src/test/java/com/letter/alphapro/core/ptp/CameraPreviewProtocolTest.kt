package com.letter.alphapro.core.ptp

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

    @Test(timeout = 15000) fun cancellationMidTransactionClosesRatherThanIgnoringMisalignedData() = runBlocking {
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
            read.cancel()
            release.countDown()
            read.join()
            assertTrue(client.lastSessionFailure is kotlinx.coroutines.CancellationException)
            try { client.getThumbnail(photo()); fail("Expected closed stream after cancellation") }
            catch (_: PtpSessionClosedException) {}
            client.disconnect()
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
        val dropSocket: Boolean = false, val malformedLength: Boolean = false, val payload: ByteArray? = null
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
                val isRead = op == 0x923e || op == 0x923d || op == 0x923c || op == 0x1004
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
