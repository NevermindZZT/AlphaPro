package com.letter.alphapro.core.ptp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class PtpImageMetadata(
    val handle: Long,
    val filename: String,
    val fileSize: Long,
    val formatCode: Int,
    val isHeif: Boolean,
    val isRaw: Boolean,
    val captureDate: String,
    val isContentCatalog: Boolean = false,
    val storageId: Long = 0,
    val parentHandle: Long = 0
)

class PtpSessionClosedException(cause: Throwable?) : IllegalStateException("Camera session is closed", cause)

class PtpIpClient {
    private var cmdSocket: Socket? = null
    private var eventSocket: Socket? = null
    private var cmdIn: InputStream? = null
    private var cmdOut: OutputStream? = null
    private var transactionId = 1

    private val transactionMutex = Mutex()

    private data class ConnectionTarget(val host: String, val port: Int, val network: android.net.Network?)
    private var savedTarget: ConnectionTarget? = null
    @Volatile private var catalogTransferEnabled = false
    @Volatile var lastSessionFailure: Throwable? = null
        private set
    private var checkIo: () -> Unit = {}

    suspend fun connect(
        cameraHost: String,
        port: Int = PtpConstants.PTP_IP_PORT,
        network: android.net.Network? = null
    ) = withContext(Dispatchers.IO) {
        transactionMutex.withLock {
            closeTransport()
            catalogTransferEnabled = false
            lastSessionFailure = null
            val target = ConnectionTarget(cameraHost, port, network)
            savedTarget = target
            guardedIo {
                connectLocked(target)
                lastSessionFailure = null
            }
        }
    }

    /**
     * Reopen a failed session using the original host/port/Network, not BLE or Wi-Fi provisioning.
     * Serialized with all commands and initialization: concurrent callers reconnect only once.
     * Does not replay ANY operation. Callers may retry an idempotent read at most once; never
     * replay remote capture, commands, property writes or data-out after an uncertain result.
     */
    suspend fun reconnectIfNeeded(): Boolean = withContext(Dispatchers.IO) {
        transactionMutex.withLock {
            if (cmdIn != null && cmdOut != null) return@withLock false
            val target = savedTarget ?: throw PtpSessionClosedException(lastSessionFailure)
            guardedIo {
                closeTransport()
                connectLocked(target)
                if (catalogTransferEnabled) {
                    // Restore a known, idempotent session mode, never replay the failed user operation.
                    check(executeDataOutLocked(PtpConstants.OC_SONY_SET_PROPERTY,
                        listOf(PtpConstants.PROP_SONY_TRANSFER_MODE.toLong(), 1L), byteArrayOf(2, 0)) == PtpConstants.RC_OK) {
                        "Camera refused transfer-mode initialization"
                    }
                }
                lastSessionFailure = null
                true
            }
        }
    }

    private fun connectLocked(target: ConnectionTarget) {
        val socketFactory = target.network?.socketFactory ?: javax.net.SocketFactory.getDefault()

        // 1. Connect Command Socket bound to camera Wi-Fi network (bypasses mobile 5G)
        val cmd = socketFactory.createSocket().also { cmdSocket = it }
        cmd.connect(InetSocketAddress(target.host, target.port), 6000)
        cmd.soTimeout = 15000
        cmdIn = cmd.getInputStream()
        cmdOut = cmd.getOutputStream()

        // 2. Handshake: InitCommandRequest
        val nameBytes = PtpConstants.CLIENT_NAME.toByteArray(Charsets.UTF_16LE) + byteArrayOf(0, 0)
        val initCmdPayload = ByteBuffer.allocate(16 + nameBytes.size + 4).order(ByteOrder.LITTLE_ENDIAN)
        initCmdPayload.put(PtpConstants.DEFAULT_GUID)
        initCmdPayload.put(nameBytes)
        initCmdPayload.putInt(0x00010000) // Protocol version 1.0

        sendPacket(cmdOut!!, PtpConstants.PKT_INIT_CMD_REQ, initCmdPayload.array())
        val (ackType, ackPayload) = readPacket(cmdIn!!)
        require(ackType == PtpConstants.PKT_INIT_CMD_ACK) { "Expected InitCommandAck, got $ackType" }

        require(ackPayload.size >= 4) { "Truncated InitCommandAck" }
        val connectionNumber = ByteBuffer.wrap(ackPayload).order(ByteOrder.LITTLE_ENDIAN).int

        // 3. Connect Event Socket bound to camera Wi-Fi network
        val evt = socketFactory.createSocket().also { eventSocket = it }
        evt.connect(InetSocketAddress(target.host, target.port), 6000)
        evt.soTimeout = 15000
        val evtIn = evt.getInputStream()
        val evtOut = evt.getOutputStream()

        // 4. Handshake: InitEventRequest
        val initEvtPayload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(connectionNumber).array()
        sendPacket(evtOut, PtpConstants.PKT_INIT_EVENT_REQ, initEvtPayload)
        val (evtAckType, _) = readPacket(evtIn)
        require(evtAckType == PtpConstants.PKT_INIT_EVENT_ACK) { "Expected InitEventAck, got $evtAckType" }

        // 5. Open Sony Extended Session (Mode 2 = Combined shooting and transfer for modern Sony cameras)
        check(executeCommandLocked(PtpConstants.OC_SONY_OPEN_EXTENDED_SESSION, listOf(1L, 2L)) == PtpConstants.RC_OK) { "Camera refused extended session" }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        transactionMutex.withLock {
            // Closing TCP is sufficient; do not block disconnect on another camera response.
            closeTransport()
            savedTarget = null
            catalogTransferEnabled = false
        }
    }

    private fun closeTransport() {
        try { cmdSocket?.close() } catch (_: Exception) {}
        try { eventSocket?.close() } catch (_: Exception) {}
        cmdSocket = null
        eventSocket = null
        cmdIn = null
        cmdOut = null
    }

    private suspend fun <T> guardedIo(block: () -> T): T {
        val context = currentCoroutineContext()
        context.ensureActive()
        val deadline = System.nanoTime() + 30_000_000_000L
        checkIo = {
            context.ensureActive()
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) throw SocketTimeoutException("PTP transaction exceeded 30 seconds")
            val timeout = minOf(15000L, maxOf(1L, remaining / 1_000_000L)).toInt()
            cmdSocket?.soTimeout = timeout
            eventSocket?.soTimeout = timeout
        }
        try {
            return block()
        } catch (failure: Exception) {
            val sessionRefused = (listOf(failure) + failure.suppressed).any {
                it is PtpResponseException && it.responseCode == PtpConstants.RC_SESSION_NOT_OPEN
            }
            if (sessionRefused || (failure !is PtpResponseException && failure !is PtpDataException && failure !is PtpSessionClosedException)) {
                if (lastSessionFailure == null) lastSessionFailure = failure
                closeTransport()
            }
            throw failure
        } finally {
            checkIo = {}
        }
    }

    suspend fun loadMediaList(): List<PtpImageMetadata> {
        // Method 1: Try modern Sony Content Catalog (A6700, A7M4, A7R5, A7C2, ZV-E1)
        try {
            val catalogPhotos = fetchContentCatalog()
            if (catalogPhotos.isNotEmpty()) {
                return catalogPhotos
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) {}

        // Method 2: Fallback to standard PTP Storage and Handles
        val photos = mutableListOf<PtpImageMetadata>()
        val storageIds = getStorageIds()
        for (storage in storageIds) {
            val handles = getObjectHandles(storage)
            for (handle in handles) {
                try {
                    photos.add(getObjectInfo(handle))
                } catch (_: Exception) {}
            }
        }
        return photos
    }

    private suspend fun fetchContentCatalog(): List<PtpImageMetadata> {
        // Enable content catalog transfer mode on Sony camera
        try {
            val modeResponse = executeDataOut(
                PtpConstants.OC_SONY_SET_PROPERTY,
                listOf(PtpConstants.PROP_SONY_TRANSFER_MODE.toLong(), 1L),
                byteArrayOf(2, 0)
            )
            catalogTransferEnabled = modeResponse == PtpConstants.RC_OK
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (_: Exception) {}

        val allFiles = mutableListOf<PtpImageMetadata>()
        var cursor = 0L

        var pageCount = 0
        while (pageCount++ < 50) {
            val cursorLow = cursor and 0xFFFFFFFFL
            val cursorHigh = cursor ushr 32
            val data = executeDataIn(
                PtpConstants.OC_SONY_GET_CONTENT_CATALOG,
                listOf(cursorLow, cursorHigh, 100L, 1L, 0L),
                limit = 2 * 1024 * 1024
            )
            if (data.isEmpty()) break

            val batch = SonyContentParser.parse(data)
            for (file in batch.files) {
                allFiles.add(
                    PtpImageMetadata(
                        handle = file.handle,
                        filename = file.name,
                        fileSize = file.size,
                        formatCode = file.formatCode,
                        isHeif = file.isHeif,
                        isRaw = file.isRaw,
                        captureDate = file.date,
                        isContentCatalog = true
                    )
                )
            }
            if (batch.timestamps.size < 100) break
            val last = batch.timestamps.last()
            if (last <= cursor) break
            cursor = last + 1
        }
        return allFiles
    }

    /** Failures propagate to the per-object loader instead of hiding the original transport cause. */
    suspend fun getThumbnail(metadata: PtpImageMetadata): ByteArray? {
        return run {
            if (metadata.isContentCatalog) {
                val hLow = metadata.handle and 0xFFFFFFFFL
                val hHigh = metadata.handle ushr 32
                executeDataIn(
                    PtpConstants.OC_SONY_GET_CONTENT_THUMB,
                    listOf(hLow, hHigh, 1L),
                    limit = 512 * 1024
                )
            } else {
                executeDataIn(
                    PtpConstants.OC_GET_THUMB,
                    listOf(metadata.handle),
                    limit = 512 * 1024
                )
            }
        }
    }

    /** A camera-rendered large preview is a different request from its tiny thumbnail. */
    suspend fun getPreview(metadata: PtpImageMetadata): ByteArray {
        check(metadata.isContentCatalog) { "This camera requires a rendered-original preview" }
        return executeDataIn(
            PtpConstants.OC_SONY_GET_CONTENT_THUMB,
            listOf(metadata.handle and 0xffffffffL, metadata.handle ushr 32, 2L),
            limit = 8 * 1024 * 1024
        ).also { check(it.isNotEmpty()) { "Camera returned no preview" } }
    }

    suspend fun executeCommand(opCode: Int, params: List<Long> = emptyList()): Int = withContext(Dispatchers.IO) {
        require(params.size <= 5)
        transactionMutex.withLock { guardedIo { executeCommandLocked(opCode, params) } }
    }

    private fun executeCommandLocked(opCode: Int, params: List<Long>): Int {
        val input = cmdIn ?: throw PtpSessionClosedException(lastSessionFailure)
        val output = cmdOut ?: throw PtpSessionClosedException(lastSessionFailure)
        val id = transactionId++
        sendPacket(output, PtpConstants.PKT_CMD_REQUEST, commandPayload(opCode, params, id, 1))
        return readResponse(input, output, id)
    }

    suspend fun executeDataOut(opCode: Int, params: List<Long>, data: ByteArray): Int = withContext(Dispatchers.IO) {
        require(params.size <= 5)
        transactionMutex.withLock { guardedIo { executeDataOutLocked(opCode, params, data) } }
    }

    private fun executeDataOutLocked(opCode: Int, params: List<Long>, data: ByteArray): Int {
        val input = cmdIn ?: throw PtpSessionClosedException(lastSessionFailure)
        val output = cmdOut ?: throw PtpSessionClosedException(lastSessionFailure)
        val id = transactionId++
        sendPacket(output, PtpConstants.PKT_CMD_REQUEST, commandPayload(opCode, params, id, 2))
        sendPacket(output, PtpConstants.PKT_START_DATA,
            ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putInt(id).putLong(data.size.toLong()).array())
        sendPacket(output, PtpConstants.PKT_END_DATA,
            ByteBuffer.allocate(4 + data.size).order(ByteOrder.LITTLE_ENDIAN).putInt(id).put(data).array())
        return readResponse(input, output, id)
    }

    suspend fun executeDataIn(opCode: Int, params: List<Long> = emptyList(), limit: Int = 8 * 1024 * 1024): ByteArray = withContext(Dispatchers.IO) {
        require(limit in 1..32 * 1024 * 1024 && params.size <= 5)
        transactionMutex.withLock {
            guardedIo {
                val input = cmdIn ?: throw PtpSessionClosedException(lastSessionFailure)
                val output = cmdOut ?: throw PtpSessionClosedException(lastSessionFailure)
                val id = transactionId++
                sendPacket(output, PtpConstants.PKT_CMD_REQUEST, commandPayload(opCode, params, id, 1))
                val accumulator = PtpDataAccumulator(id, limit)
                var result: ByteArray? = null
                while (result == null) {
                    val (type, length) = readPacketHeader(input)
                    when (type) {
                        PtpConstants.PKT_DATA, PtpConstants.PKT_END_DATA ->
                            accumulator.consumeData(type, length, input, checkIo)
                        PtpConstants.PKT_PING, PtpConstants.PKT_PONG -> handleKeepAlive(type, length, output)
                        else -> {
                            require((type == PtpConstants.PKT_START_DATA && length == 12) ||
                                (type == PtpConstants.PKT_CMD_RESPONSE && length in 6..26)) { "Unexpected PTP data-in packet $type/$length" }
                            result = accumulator.consume(type, readExact(input, length))
                        }
                    }
                }
                checkNotNull(result)
            }
        }
    }

    private fun commandPayload(opCode: Int, params: List<Long>, id: Int, phase: Int): ByteArray {
        val payload = ByteBuffer.allocate(10 + params.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        payload.putInt(phase).putShort(opCode.toShort()).putInt(id)
        params.forEach { payload.putInt(it.toInt()) }
        return payload.array()
    }

    private fun readResponse(input: InputStream, output: OutputStream, id: Int): Int {
        while (true) {
            val (type, length) = readPacketHeader(input)
            if (type == PtpConstants.PKT_PING || type == PtpConstants.PKT_PONG) {
                handleKeepAlive(type, length, output)
                continue
            }
            require(type == PtpConstants.PKT_CMD_RESPONSE && length in 6..26 && (length - 6) % 4 == 0) { "Invalid PTP command response" }
            val response = ByteBuffer.wrap(readExact(input, length)).order(ByteOrder.LITTLE_ENDIAN)
            val code = response.short.toInt() and 0xffff
            require(response.int == id) { "PTP response transaction mismatch" }
            return code
        }
    }

    private fun handleKeepAlive(type: Int, length: Int, output: OutputStream) {
        require(length == 0) { "Invalid PTP keepalive length" }
        if (type == PtpConstants.PKT_PING) sendPacket(output, PtpConstants.PKT_PONG, ByteArray(0))
    }

    private suspend fun getStorageIds(): List<Long> {
        val data = executeDataIn(PtpConstants.OC_GET_STORAGE_IDS)
        if (data.size < 4) return emptyList()
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val count = buf.int
        val ids = mutableListOf<Long>()
        for (i in 0 until count) {
            if (buf.remaining() >= 4) ids.add(buf.int.toLong() and 0xFFFFFFFFL)
        }
        return ids
    }

    private suspend fun getObjectHandles(storageId: Long): List<Long> {
        val data = executeDataIn(PtpConstants.OC_GET_OBJECT_HANDLES, listOf(storageId, 0L, 0L))
        if (data.size < 4) return emptyList()
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val count = buf.int
        val handles = mutableListOf<Long>()
        for (i in 0 until count) {
            if (buf.remaining() >= 4) handles.add(buf.int.toLong() and 0xFFFFFFFFL)
        }
        return handles
    }

    private suspend fun getObjectInfo(handle: Long): PtpImageMetadata {
        val info = PtpObjectInfoParser.parse(executeDataIn(PtpConstants.OC_GET_OBJECT_INFO, listOf(handle)))
        val name = info.filename.ifBlank { "Object_" + handle.toString(16) }
        return PtpImageMetadata(handle, name, info.fileSize, info.formatCode,
            info.formatCode == PtpConstants.FMT_SONY_HEIF, info.formatCode == PtpConstants.FMT_SONY_ARW,
            info.captureDate, false, info.storageId, info.parentHandle)
    }

    suspend fun readPartialObject(metadata: PtpImageMetadata, offset: Long, length: Int): ByteArray {
        require(offset >= 0 && length in 1..(32 * 1024 * 1024 - 64))
        return if (metadata.isContentCatalog) {
            val hLow = metadata.handle and 0xFFFFFFFFL
            val hHigh = metadata.handle ushr 32
            val offLow = offset and 0xFFFFFFFFL
            val offHigh = offset ushr 32
            executeDataIn(
                PtpConstants.OC_SONY_READ_CONTENT_OBJECT,
                listOf(hLow, hHigh, offLow, offHigh, length.toLong()),
                limit = length + 64
            )
        } else {
            val offsetLow = offset and 0xFFFFFFFFL
            val offsetHigh = offset ushr 32
            executeDataIn(
                PtpConstants.OC_SONY_GET_PARTIAL_LARGE_OBJECT,
                listOf(metadata.handle, offsetLow, offsetHigh, length.toLong()),
                limit = length + 64
            )
        }
    }

    private fun sendPacket(out: OutputStream, type: Int, payload: ByteArray) {
        checkIo()
        val totalLength = 8 + payload.size
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(totalLength)
        header.putInt(type)
        out.write(header.array())
        out.write(payload)
        out.flush()
    }

    private fun readPacketHeader(input: InputStream): Pair<Int, Int> {
        val header = ByteBuffer.wrap(readExact(input, 8)).order(ByteOrder.LITTLE_ENDIAN)
        val length = header.int
        val type = header.int
        // Payload allocation is constrained by packet type, not by the camera's frame size.
        // Even an oversized data frame is streamed/discarded under the transaction deadline.
        require(length >= 8) { "Invalid PTP packet length" }
        return type to (length - 8)
    }

    private fun readPacket(input: InputStream): Pair<Int, ByteArray> {
        val (type, length) = readPacketHeader(input)
        // Only handshake packets use this path. Data-in packets are streamed above.
        require(length <= 64 * 1024) { "Oversized PTP handshake packet" }
        return type to readExact(input, length)
    }

    private fun readExact(input: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var readTotal = 0
        while (readTotal < size) {
            checkIo()
            val count = input.read(buffer, readTotal, size - readTotal)
            if (count < 0) throw EOFException("PTP/IP stream closed unexpectedly")
            if (count == 0) throw EOFException("PTP/IP stream made no progress")
            readTotal += count
        }
        return buffer
    }
}
