package com.argun.mapper.ble

/**
 * Utilities for BLE CCCD descriptor values.
 */
object NotificationHelper {

    /** 0x0000 - notifications disabled. */
    val DISABLED: ByteArray = byteArrayOf(0x00, 0x00)

    /** 0x0001 - notifications enabled. */
    val NOTIFICATION: ByteArray = byteArrayOf(0x01, 0x00)

    /** 0x0002 - indications enabled. */
    val INDICATION: ByteArray = byteArrayOf(0x02, 0x00)

    /** Encodes a 16-bit value as little-endian bytes, as the CCCD expects. */
    fun encode(value: Short): ByteArray = byteArrayOf(
        (value.toInt() and 0xFF).toByte(),
        ((value.toInt() shr 8) and 0xFF).toByte()
    )

    /** Decodes a little-endian 16-bit CCCD value. */
    fun decode(data: ByteArray): Short {
        if (data.size < 2) return 0
        return ((data[1].toInt() shl 8) or (data[0].toInt() and 0xFF)).toShort()
    }
}