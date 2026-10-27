package com.letter.alphapro.core.bluetooth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

data class LocationDataConfig(
    val shouldSendTimeZoneAndDst: Boolean = true
) {
    val dataSize: Int = if (shouldSendTimeZoneAndDst) 95 else 91

    val fixedBytes: ByteArray = byteArrayOf(
        0x00,
        if (shouldSendTimeZoneAndDst) 0x5D else 0x59,
        0x08,
        0x02,
        0xFC.toByte(),
        if (shouldSendTimeZoneAndDst) 0x03 else 0x00,
        0x00,
        0x00,
        0x10,
        0x10,
        0x10
    )

    companion object {
        fun hasTimeZoneDstFlag(value: ByteArray): Boolean {
            return value.size >= 5 && (value[4].toInt() and 0x02) != 0
        }
    }
}

object SonyLocationPacketBuilder {
    private const val PADDING_SIZE = 65

    fun build(
        latitude: Double,
        longitude: Double,
        config: LocationDataConfig = LocationDataConfig(shouldSendTimeZoneAndDst = true),
        epochMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): ByteArray {
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = epochMillis
        }

        val year = calendar.get(Calendar.YEAR).toShort()
        val month = (calendar.get(Calendar.MONTH) + 1).toByte()
        val day = calendar.get(Calendar.DAY_OF_MONTH).toByte()
        val hour = calendar.get(Calendar.HOUR_OF_DAY).toByte()
        val minute = calendar.get(Calendar.MINUTE).toByte()
        val second = calendar.get(Calendar.SECOND).toByte()

        val latInt = (latitude * 10_000_000).toInt()
        val lngInt = (longitude * 10_000_000).toInt()

        val buffer = ByteBuffer.allocate(config.dataSize).order(ByteOrder.BIG_ENDIAN)
        buffer.put(config.fixedBytes)
        buffer.putInt(latInt)
        buffer.putInt(lngInt)

        buffer.putShort(year)
        buffer.put(month)
        buffer.put(day)
        buffer.put(hour)
        buffer.put(minute)
        buffer.put(second)

        buffer.put(ByteArray(PADDING_SIZE))

        if (config.shouldSendTimeZoneAndDst) {
            val rawOffsetMinutes = (timeZone.rawOffset / 60_000).toShort()
            val dstOffsetMinutes = if (timeZone.inDaylightTime(calendar.time)) {
                (timeZone.dstSavings / 60_000).toShort()
            } else {
                0.toShort()
            }
            buffer.putShort(rawOffsetMinutes)
            buffer.putShort(dstOffsetMinutes)
        }

        return buffer.array()
    }
}

object SonyTimeSyncPacketBuilder {
    fun build(
        epochMillis: Long = System.currentTimeMillis(),
        timeZone: TimeZone = TimeZone.getDefault()
    ): ByteArray {
        val localCalendar = Calendar.getInstance(timeZone).apply {
            timeInMillis = epochMillis
        }

        val year = localCalendar.get(Calendar.YEAR).toShort()
        val month = (localCalendar.get(Calendar.MONTH) + 1).toByte()
        val day = localCalendar.get(Calendar.DAY_OF_MONTH).toByte()
        val hour = localCalendar.get(Calendar.HOUR_OF_DAY).toByte()
        val minute = localCalendar.get(Calendar.MINUTE).toByte()
        val second = localCalendar.get(Calendar.SECOND).toByte()

        val isDst = timeZone.inDaylightTime(localCalendar.time)
        val offsetMinutes = timeZone.getOffset(epochMillis) / 60_000
        val offsetHours = offsetMinutes / 60
        val offsetRemainderMinutes = abs(offsetMinutes % 60)

        val packet = ByteArray(13)
        packet[0] = 12
        packet[1] = 0
        packet[2] = 0
        packet[3] = (year.toInt() shr 8).toByte()
        packet[4] = year.toByte()
        packet[5] = month
        packet[6] = day
        packet[7] = hour
        packet[8] = minute
        packet[9] = second
        packet[10] = if (isDst) 1 else 0
        packet[11] = offsetHours.toByte()
        packet[12] = offsetRemainderMinutes.toByte()

        return packet
    }
}

object SonyWifiDataParser {
    /** CC06/CC07 have three protocol bytes, independent of the first byte's value. */
    fun parsePrefixedAscii(value: ByteArray): String? =
        if (value.size > 3) parseAscii(value.copyOfRange(3, value.size)) else null

    fun parseAscii(value: ByteArray): String? {
        // Allow trailing C terminators, but preserve spaces: they are valid credential bytes.
        val bytes = value.dropLastWhile { it == 0.toByte() }
        if (bytes.isEmpty() || bytes.any { (it.toInt() and 0xff) !in 32..126 }) return null
        return bytes.map { (it.toInt() and 0xff).toChar() }.joinToString("")
    }

    /**
     * CC09 type 1: Wi-Fi state (0 off, 1 starting, 2 on, 3 stopping), error code.
     */
    fun parseWifiStatus(value: ByteArray): Pair<Int, Int>? {
        var offset = 0
        while (offset < value.size) {
            val length = value[offset].toInt() and 0xff
            if (length < 2 || offset + length + 1 > value.size) return null
            val type = ((value[offset + 1].toInt() and 0xff) shl 8) or (value[offset + 2].toInt() and 0xff)
            if (type == 1 && length >= 4) {
                val state = value[offset + 3].toInt() and 0xff
                val error = value[offset + 4].toInt() and 0xff
                return Pair(state, error)
            }
            offset += length + 1
        }
        return null
    }
}
