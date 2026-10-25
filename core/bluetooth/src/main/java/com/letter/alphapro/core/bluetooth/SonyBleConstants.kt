package com.letter.alphapro.core.bluetooth

import java.util.UUID

object SonyBleConstants {
    // Primary Location Service UUID for Sony Alpha Cameras (A6700, A7M4, A7R5, etc.)
    val SERVICE_LOCATION_UUID: UUID = UUID.fromString("8000dd00-dd00-ffff-ffff-ffffffffffff")

    // Control & Wi-Fi Bootstrap Service UUID
    val SERVICE_CONTROL_UUID: UUID = UUID.fromString("8000cc00-cc00-ffff-ffff-ffffffffffff")

    // Wireless Remote Shutter Service UUID
    val SERVICE_REMOTE_UUID: UUID = UUID.fromString("8000ff00-ff00-ffff-ffff-ffffffffffff")

    // Characteristic: Location Data Target (Periodic 95-byte GPS payload)
    val CHAR_LOCATION_DATA_UUID: UUID = UUID.fromString("0000dd11-0000-1000-8000-00805f9b34fb")
    val CHAR_LOCATION_READ_UUID: UUID = UUID.fromString("0000dd21-0000-1000-8000-00805f9b34fb")

    // Characteristics: GPS Unlock & Activation Commands (A6700 / A7R5 handshake)
    val CHAR_GPS_UNLOCK_COMMAND_30_UUID: UUID = UUID.fromString("0000dd30-0000-1000-8000-00805f9b34fb")
    val CHAR_GPS_UNLOCK_COMMAND_31_UUID: UUID = UUID.fromString("0000dd31-0000-1000-8000-00805f9b34fb")
    val CHAR_CAMERA_LOCATION_ENABLED_UUID: UUID = UUID.fromString("0000dd01-0000-1000-8000-00805f9b34fb")

    // Characteristic: Millisecond Time Synchronization (13-byte clock calibration)
    val CHAR_TIME_SYNC_UUID: UUID = UUID.fromString("0000cc13-0000-1000-8000-00805f9b34fb")

    // Camera Auto Time / Area Adjustments
    val CHAR_AUTO_TIME_CORRECTION_UUID: UUID = UUID.fromString("0000dd32-0000-1000-8000-00805f9b34fb")
    val CHAR_AUTO_AREA_ADJUSTMENT_UUID: UUID = UUID.fromString("0000dd33-0000-1000-8000-00805f9b34fb")

    // Wi-Fi Bootstrap Characteristics (Creators' App protocol)
    val CHAR_WIFI_SSID_UUID: UUID = UUID.fromString("0000cc06-0000-1000-8000-00805f9b34fb")
    val CHAR_WIFI_PASSWORD_UUID: UUID = UUID.fromString("0000cc07-0000-1000-8000-00805f9b34fb")
    val CHAR_WIFI_POWER_UUID: UUID = UUID.fromString("0000cc08-0000-1000-8000-00805f9b34fb")
    val CHAR_CAMERA_STATUS_UUID: UUID = UUID.fromString("0000cc09-0000-1000-8000-00805f9b34fb")
    val CHAR_WIFI_BSSID_UUID: UUID = UUID.fromString("0000cc0c-0000-1000-8000-00805f9b34fb")

    // Remote Shutter Control & Status
    val CHAR_REMOTE_COMMAND_UUID: UUID = UUID.fromString("0000ff01-0000-1000-8000-00805f9b34fb")
    val CHAR_REMOTE_STATUS_UUID: UUID = UUID.fromString("0000ff02-0000-1000-8000-00805f9b34fb")

    // Standard Client Characteristic Configuration Descriptor (CCCD)
    val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Command Payloads
    val CMD_ACTIVATE_GPS = byteArrayOf(0x01)
    val CMD_WIFI_POWER_ON = byteArrayOf(0x01)
    val CMD_WIFI_POWER_OFF = byteArrayOf(0x00)

    // Remote Shutter Command Sequences
    val CMD_FULL_SHUTTER_DOWN = byteArrayOf(0x01, 0x09)
    val CMD_FULL_SHUTTER_UP = byteArrayOf(0x01, 0x08)
    val CMD_HALF_SHUTTER_DOWN = byteArrayOf(0x01, 0x07) // Half-press AF
    val CMD_HALF_SHUTTER_UP = byteArrayOf(0x01, 0x06)   // Release AF
    val CMD_AF_ON_DOWN = byteArrayOf(0x01, 0x15)
    val CMD_AF_ON_UP = byteArrayOf(0x01, 0x14)
    val CMD_MOVIE_REC_TOGGLE = byteArrayOf(0x01, 0x0E)

    // Remote Status Notifications from FF02
    val STATUS_READY = byteArrayOf(0x02, 0xA0.toByte(), 0x00)
    val STATUS_SHUTTER_ACTIVE = byteArrayOf(0x02, 0xA0.toByte(), 0x20)
    val STATUS_FOCUS_ACQUIRED = byteArrayOf(0x02, 0x3F, 0x20)
}
