package com.argun.mapper.model

/**
 * ARGUN device information extracted from GATT characteristics.
 *
 * Device Name: "ARGunGame"
 * Service UUID: 0000fff0-0000-1000-8000-00805f9b34fb
 * Notification Characteristic: fff3
 */
data class ArgunDevice(
    val name: String = "ARGunGame",
    val address: String = "",
    val rssi: Int = 0,
    val bonded: Boolean = false,
    val connected: Boolean = false
) : java.io.Serializable