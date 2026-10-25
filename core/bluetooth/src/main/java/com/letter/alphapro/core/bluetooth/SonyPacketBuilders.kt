package com.letter.alphapro.core.bluetooth

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

object SonyLocationPacketBuilder {
    private val FIXED_HEADER = byteArrayOf(
        0x00, 0x5D, 0x08, 0x02, 0xFC.toByte(), 0x03, 0x00, 0x00, 0x10, 0x10, 0x10
    )
    private const val PADDING_SIZE = 65
    const val TOTAL_PACKET_SIZE = 95

    fun build(
        latitude: Double,
        longitude: Double,
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

        // Offsets in minutes
        val rawOffsetMinutes = (timeZone.rawOffset / 60_000).toShort()
        val dstOffsetMinutes = if (timeZone.inDaylightTime(calendar.time)) {
            (timeZone.dstSavings / 60_000).toShort()
        } else {
            0.toShort()
        }

        val buffer = ByteBuffer.allocate(TOTAL_PACKET_SIZE).order(ByteOrder.BIG_ENDIAN)
        buffer.put(FIXED_HEADER)
        buffer.putInt(latInt)
        buffer.putInt(lngInt)

        buffer.putShort(year)
        buffer.put(month)
        buffer.put(day)
        buffer.put(hour)
        buffer.put(minute)
        buffer.put(second)

        buffer.put(ByteArray(PADDING_SIZE))
        buffer.putShort(rawOffsetMinutes)
        buffer.putShort(dstOffsetMinutes)

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
