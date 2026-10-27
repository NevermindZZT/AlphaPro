package com.letter.alphapro.core.ptp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
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
    val isContentCatalog: Boolean = false
)

class PtpIpClient {
    private var cmdSocket: Socket? = null
    private var eventSocket: Socket? = null
    private var cmdIn: InputStream? = null
    private var cmdOut: OutputStream? = null
    private var transactionId = 1

    private val transactionMutex = Mutex()

    suspend fun connect(
        cameraHost: String,
        port: Int = PtpConstants.PTP_IP_PORT,
        network: android.net.Network? = null
    ) = withContext(Dispatchers.IO) {
        disconnect()

        val socketFactory = network?.socketFactory ?: javax.net.SocketFactory.getDefault()

        // 1. Connect Command Socket bound to camera Wi-Fi network (bypasses mobile 5G)
        val cmd = socketFactory.createSocket()
        cmd.connect(InetSocketAddress(cameraHost, port), 6000)
        cmd.soTimeout = 15000
        cmdSocket = cmd
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

        val connectionNumber = ByteBuffer.wrap(ackPayload).order(ByteOrder.LITTLE_ENDIAN).int

        // 3. Connect Event Socket bound to camera Wi-Fi network
        val evt = socketFactory.createSocket()
        evt.connect(InetSocketAddress(cameraHost, port), 6000)
        evt.soTimeout = 15000
        eventSocket = evt
        val evtIn = evt.getInputStream()
        val evtOut = evt.getOutputStream()

        // 4. Handshake: InitEventRequest
        val initEvtPayload = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(connectionNumber).array()
        sendPacket(evtOut, PtpConstants.PKT_INIT_EVENT_REQ, initEvtPayload)
        val (evtAckType, _) = readPacket(evtIn)
        require(evtAckType == PtpConstants.PKT_INIT_EVENT_ACK) { "Expected InitEventAck, got $evtAckType" }

        // 5. Open Sony Extended Session (Mode 2 = Combined shooting and transfer for modern Sony cameras)
        executeCommand(PtpConstants.OC_SONY_OPEN_EXTENDED_SESSION, listOf(1L, 2L))
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            executeCommand(PtpConstants.OC_CLOSE_SESSION, listOf(1L))
        } catch (_: Exception) {}
        try { cmdSocket?.close() } catch (_: Exception) {}
        try { eventSocket?.close() } catch (_: Exception) {}
        cmdSocket = null
        eventSocket = null
    }

    suspend fun loadMediaList(): List<PtpImageMetadata> {
        // Method 1: Try modern Sony Content Catalog (A6700, A7M4, A7R5, A7C2, ZV-E1)
        try {
            val catalogPhotos = fetchContentCatalog()
            if (catalogPhotos.isNotEmpty()) {
                return catalogPhotos
            }
        } catch (_: Exception) {}

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
            executeDataOut(
                PtpConstants.OC_SONY_SET_PROPERTY,
                listOf(PtpConstants.PROP_SONY_TRANSFER_MODE.toLong(), 1L),
                byteArrayOf(2, 0)
            )
        } catch (_: Exception) {}

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

    suspend fun getThumbnail(metadata: PtpImageMetadata): ByteArray? {
        return try {
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
        } catch (_: Exception) {
            null
        }
    }

    suspend fun executeCommand(opCode: Int, params: List<Long> = emptyList()): Int = transactionMutex.withLock {
        withContext(Dispatchers.IO) {
            val tId = transactionId++
            val payload = ByteBuffer.allocate(4 + 2 + 4 + params.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            payload.putInt(1)
            payload.putShort(opCode.toShort())
            payload.putInt(tId)
            params.forEach { payload.putInt(it.toInt()) }

            sendPacket(cmdOut!!, PtpConstants.PKT_CMD_REQUEST, payload.array())
            val (respType, respPayload) = readPacket(cmdIn!!)
            require(respType == PtpConstants.PKT_CMD_RESPONSE) { "Unexpected response type $respType" }

            val respBuf = ByteBuffer.wrap(respPayload).order(ByteOrder.LITTLE_ENDIAN)
            val respCode = respBuf.short.toInt() and 0xFFFF
            respCode
        }
    }

    suspend fun executeDataOut(opCode: Int, params: List<Long>, data: ByteArray): Int = transactionMutex.withLock {
        withContext(Dispatchers.IO) {
            val tId = transactionId++
            val payload = ByteBuffer.allocate(4 + 2 + 4 + params.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            payload.putInt(2)
            payload.putShort(opCode.toShort())
            payload.putInt(tId)
            params.forEach { payload.putInt(it.toInt()) }

            sendPacket(cmdOut!!, PtpConstants.PKT_CMD_REQUEST, payload.array())

            val startData = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
            startData.putInt(tId)
            startData.putLong(data.size.toLong())
            sendPacket(cmdOut!!, PtpConstants.PKT_START_DATA, startData.array())

            val endData = ByteBuffer.allocate(4 + data.size).order(ByteOrder.LITTLE_ENDIAN)
            endData.putInt(tId)
            endData.put(data)
            sendPacket(cmdOut!!, PtpConstants.PKT_END_DATA, endData.array())

            val (respType, respPayload) = readPacket(cmdIn!!)
            require(respType == PtpConstants.PKT_CMD_RESPONSE) { "Unexpected response type $respType" }

            val respBuf = ByteBuffer.wrap(respPayload).order(ByteOrder.LITTLE_ENDIAN)
            val respCode = respBuf.short.toInt() and 0xFFFF
            respCode
        }
    }

    suspend fun executeDataIn(opCode: Int, params: List<Long> = emptyList(), limit: Int = 8 * 1024 * 1024): ByteArray = transactionMutex.withLock {
        withContext(Dispatchers.IO) {
            val tId = transactionId++
            val payload = ByteBuffer.allocate(4 + 2 + 4 + params.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            payload.putInt(1)
            payload.putShort(opCode.toShort())
            payload.putInt(tId)
            params.forEach { payload.putInt(it.toInt()) }

            sendPacket(cmdOut!!, PtpConstants.PKT_CMD_REQUEST, payload.array())

            val dataStream = java.io.ByteArrayOutputStream()
            while (true) {
                val (type, bytes) = readPacket(cmdIn!!)
                when (type) {
                    PtpConstants.PKT_START_DATA -> {}
                    PtpConstants.PKT_DATA, PtpConstants.PKT_END_DATA -> {
                        val dataOffset = if (bytes.size >= 4) 4 else 0
                        if (bytes.size > dataOffset) {
                            dataStream.write(bytes, dataOffset, bytes.size - dataOffset)
                        }
                    }
                    PtpConstants.PKT_CMD_RESPONSE -> {
                        break
                    }
                }
            }
            dataStream.toByteArray()
        }
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
        val data = executeDataIn(PtpConstants.OC_GET_OBJECT_INFO, listOf(handle))
        val buf = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        buf.int
        val formatCode = buf.short.toInt() and 0xFFFF
        buf.short
        val size = buf.int.toLong() and 0xFFFFFFFFL

        val filename = "DSC" + String.format("%05d", handle % 100000) + when (formatCode) {
            PtpConstants.FMT_SONY_HEIF -> ".HIF"
            PtpConstants.FMT_SONY_ARW -> ".ARW"
            else -> ".JPG"
        }

        return PtpImageMetadata(
            handle = handle,
            filename = filename,
            fileSize = size,
            formatCode = formatCode,
            isHeif = formatCode == PtpConstants.FMT_SONY_HEIF,
            isRaw = formatCode == PtpConstants.FMT_SONY_ARW,
            captureDate = "2026:10:25",
            isContentCatalog = false
        )
    }

    suspend fun readPartialObject(metadata: PtpImageMetadata, offset: Long, length: Int): ByteArray {
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
        val totalLength = 8 + payload.size
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(totalLength)
        header.putInt(type)
        out.write(header.array())
        out.write(payload)
        out.flush()
    }

    private fun readPacket(input: InputStream): Pair<Int, ByteArray> {
        val header = readExact(input, 8)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val length = buf.int
        val type = buf.int
        val payloadLength = length - 8
        val payload = if (payloadLength > 0) readExact(input, payloadLength) else ByteArray(0)
        return Pair(type, payload)
    }

    private fun readExact(input: InputStream, size: Int): ByteArray {
        val buffer = ByteArray(size)
        var readTotal = 0
        while (readTotal < size) {
            val count = input.read(buffer, readTotal, size - readTotal)
            require(count >= 0) { "PTP/IP stream closed unexpectedly" }
            readTotal += count
        }
        return buffer
    }
}
