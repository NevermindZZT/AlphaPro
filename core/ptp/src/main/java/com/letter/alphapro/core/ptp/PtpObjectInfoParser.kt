package com.letter.alphapro.core.ptp

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal data class PtpObjectInfo(
    val storageId: Long, val parentHandle: Long, val formatCode: Int,
    val fileSize: Long, val filename: String, val captureDate: String
)

internal object PtpObjectInfoParser {
    fun parse(data: ByteArray): PtpObjectInfo {
        require(data.size >= 52) { "Truncated PTP ObjectInfo" }
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val storage = buffer.int.toLong() and 0xffffffffL
        val format = buffer.short.toInt() and 0xffff
        buffer.short
        val size = buffer.int.toLong() and 0xffffffffL
        buffer.position(38)
        val parent = buffer.int.toLong() and 0xffffffffL
        buffer.position(52)
        fun string(): String {
            if (!buffer.hasRemaining()) return ""
            val count = buffer.get().toInt() and 0xff
            if (count == 0) return ""
            val length = count * 2
            require(buffer.remaining() >= length) { "Truncated PTP string" }
            val bytes = ByteArray(length); buffer.get(bytes)
            require(bytes[length - 1] == 0.toByte() && bytes[length - 2] == 0.toByte()) { "Unterminated PTP string" }
            return bytes.copyOfRange(0, length - 2).toString(Charsets.UTF_16LE)
        }
        val filename = string()
        val date = string()
        return PtpObjectInfo(storage, parent, format, size, filename, CameraPhotoDate.normalize(date))
    }
}
