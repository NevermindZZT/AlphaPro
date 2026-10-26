package com.letter.alphapro.core.ptp

object PtpConstants {
    const val PTP_IP_PORT = 15740

    // PTP/IP Packet Types
    const val PKT_INIT_CMD_REQ = 0x0001
    const val PKT_INIT_CMD_ACK = 0x0002
    const val PKT_INIT_EVENT_REQ = 0x0003
    const val PKT_INIT_EVENT_ACK = 0x0004
    const val PKT_CMD_REQUEST = 0x0006
    const val PKT_CMD_RESPONSE = 0x0007
    const val PKT_EVENT = 0x0008
    const val PKT_START_DATA = 0x0009
    const val PKT_DATA = 0x000A
    const val PKT_CANCEL_TRANSACTION = 0x000B
    const val PKT_END_DATA = 0x000C
    const val PKT_PING = 0x000D
    const val PKT_PONG = 0x000E

    // Standard PTP Operations
    const val OC_GET_DEVICE_INFO = 0x1001
    const val OC_OPEN_SESSION = 0x1002
    const val OC_CLOSE_SESSION = 0x1003
    const val OC_GET_STORAGE_IDS = 0x1004
    const val OC_GET_STORAGE_INFO = 0x1005
    const val OC_GET_NUM_OBJECTS = 0x1006
    const val OC_GET_OBJECT_HANDLES = 0x1007
    const val OC_GET_OBJECT_INFO = 0x1008
    const val OC_GET_OBJECT = 0x1009
    const val OC_GET_THUMB = 0x100A

    // Extended Sony & MTP Operations
    const val OC_SONY_SDIO_CONNECT = 0x9201
    const val OC_SONY_GET_PARTIAL_LARGE_OBJECT = 0x9201
    const val OC_SONY_SET_PROPERTY = 0x9207
    const val PROP_SONY_TRANSFER_MODE = 0xD30F
    const val OC_SONY_OPEN_EXTENDED_SESSION = 0x9210
    const val OC_SONY_GET_CONTENT_CATALOG = 0x923C
    const val OC_SONY_READ_CONTENT_OBJECT = 0x923D
    const val OC_SONY_GET_CONTENT_THUMB = 0x923E
    const val OC_MTP_GET_OBJECT_PROP_LIST = 0x9805

    // PTP Response Codes
    const val RC_OK = 0x2001
    const val RC_GENERAL_ERROR = 0x2002
    const val RC_SESSION_NOT_OPEN = 0x2003
    const val RC_INVALID_TRANSACTION_ID = 0x2004
    const val RC_OPERATION_NOT_SUPPORTED = 0x2005
    const val RC_PARAMETER_NOT_SUPPORTED = 0x2006
    const val RC_INCOMPLETE_TRANSFER = 0x2007
    const val RC_INVALID_STORAGE_ID = 0x2008
    const val RC_INVALID_OBJECT_HANDLE = 0x2009
    const val RC_DEVICE_BUSY = 0x2019

    // Image & Video Format Codes
    const val FMT_ASSOCIATION = 0x3001 // Directory/Folder
    const val FMT_JPEG = 0x3801
    const val FMT_TIFF = 0x3802
    const val FMT_SONY_ARW = 0xB101 // Sony RAW
    const val FMT_SONY_HEIF = 0xB110 // Sony HEIF / HIF
    const val FMT_MP4 = 0x300D
    const val FMT_MTP_MP4 = 0xB981

    // Client GUID for PTP/IP handshake
    val DEFAULT_GUID = byteArrayOf(
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0xFF.toByte(), 0xFF.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    )
    const val CLIENT_NAME = "AlphaPro"
}
