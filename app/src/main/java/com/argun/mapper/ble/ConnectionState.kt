package com.argun.mapper.ble

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattService

/**
 * Current state of the ARGUN BLE connection.
 */
sealed class ConnectionState {
    /** Not connected to any device. */
    object Disconnected : ConnectionState()

    /** A connection attempt is in progress. */
    object Connecting : ConnectionState()

    /** Connected, with the resolved device and its ARGUN GATT service. */
    data class Connected(
        val device: BluetoothDevice,
        val service: BluetoothGattService
    ) : ConnectionState()

    /** Connection failed or dropped with the given reason. */
    data class Failed(val reason: String) : ConnectionState()

    val isConnected: Boolean get() = this is Connected
}