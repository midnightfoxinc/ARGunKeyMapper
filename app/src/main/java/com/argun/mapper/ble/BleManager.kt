package com.argun.mapper.ble

import android.bluetooth.*
import android.content.Context
import android.util.Log
import com.argun.mapper.model.ArgunButton
import com.argun.mapper.model.Notification
import java.util.UUID
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages the Bluetooth LE connection to the ARGUN device.
 *
 * GATT Profile:
 * - Service: 0000fff0-0000-1000-8000-00805f9b34fb
 *   - fff1: Notify + Read (device status register)
 *   - fff2: Read (device ID)
 *   - fff3: Notify + Read (button notifications)
 *   - fff4: Read (unknown)
 *   - fff5: Read + Write (control)
 *
 * Notification payloads are 16-byte ASCII strings like "B2DOWN", "B2UP",
 * "ARGun KeyPressed", or 16 zero bytes.
 */
class BleManager(private val context: Context) {
    companion object {
        private const val TAG = "BleManager"

        const val ARGUN_SERVICE_UUID = "0000fff0-0000-1000-8000-00805f9b34fb"
        const val NOTIFICATION_CHAR_UUID = "0000fff3-0000-1000-8000-00805f9b34fb"
        const val STATUS_CHAR_UUID = "0000fff1-0000-1000-8000-00805f9b34fb"
        const val CCCD_DESCRIPTOR_UUID = "00002902-0000-1000-8000-00805f9b34fb"
        private const val NOTIFICATION_VALUE: Short = 0x0001.toShort()
    }

    private var gatt: BluetoothGatt? = null
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(
            gatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            Log.d(TAG, "onConnectionStateChange: status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _connectionState.value = ConnectionState.Disconnected
                    gatt.close()
                    this@BleManager.gatt = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Services discovery failed: $status")
                gatt.disconnect()
                return
            }

            val service = gatt.getService(UUID.fromString(ARGUN_SERVICE_UUID))
            if (service == null) {
                Log.e(TAG, "ARGUN service not found!")
                gatt.disconnect()
                return
            }

            val nfc = service.getCharacteristic(UUID.fromString(NOTIFICATION_CHAR_UUID))
            if (nfc == null) {
                Log.e(TAG, "Notification characteristic not found!")
                gatt.disconnect()
                return
            }

            setNotificationEnabled(gatt, nfc)

            service.getCharacteristic(UUID.fromString(STATUS_CHAR_UUID))?.let {
                setNotificationEnabled(gatt, it)
            }

            _connectionState.value = ConnectionState.Connected(gatt.device, service)
            Log.d(TAG, "ARGUN service discovered")
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid.toString() != NOTIFICATION_CHAR_UUID) return
            val parsed = parsePayload(value)
            parsed?.let { _eventFlow.tryEmit(it) }
        }

        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int
        ) {
            Log.d(TAG, "Read characteristic ${characteristic.uuid}: ${value.contentToString()}")
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            Log.d(TAG, "Wrote characteristic ${characteristic.uuid}: status=$status")
        }
    }

    private val _eventFlow = MutableSharedFlow<Notification>(extraBufferCapacity = 64)
    val eventFlow: SharedFlow<Notification> = _eventFlow.asSharedFlow()

    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    var mtu: Int = 23

    private fun setNotificationEnabled(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        gatt.setCharacteristicNotification(characteristic, true)
        if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) {
            Log.w(TAG, "Characteristic does not support NOTIFY: ${characteristic.uuid}")
            return
        }
        characteristic.getDescriptor(UUID.fromString(CCCD_DESCRIPTOR_UUID))?.let { cccd ->
            cccd.value = NotificationHelper.encode(NOTIFICATION_VALUE)
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(cccd)
        }
    }

    private fun parsePayload(value: ByteArray): Notification? {
        val trimmed = value.decodeToString().trimEnd('\u0000')
        Log.d(TAG, "Parsing notification: '$trimmed' (hex: ${value.contentToString()})")

        if (trimmed == "ARGun KeyPressed") {
            return Notification(rawValue = trimmed, parsedEvent = "trigger", isHandshake = true)
        }

        ArgunButton.parseEvent(trimmed)?.let { (button, isDown) ->
            return Notification(
                rawValue = trimmed,
                parsedEvent = "${button.tag}${if (isDown) "_down" else "_up"}",
                isButtonEvent = true
            )
        }

        if (value.contentEquals(ByteArray(value.size) { 0.toByte() })) {
            return null
        }

        return Notification(rawValue = trimmed)
    }

    fun connect(device: BluetoothDevice): Boolean {
        if (_connectionState.value is ConnectionState.Connected) {
            disconnect()
        }
        gatt = device.connectGatt(context, false, gattCallback)
        _connectionState.value = ConnectionState.Connecting
        return true
    }

    fun connect(address: String): Boolean {
        val bluetooth = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter = bluetooth.adapter
        val device = adapter.getRemoteDevice(address)
        return connect(device)
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
    }

    fun sendByteCommand(command: ByteArray) {
        val connected = connectionState.value as? ConnectionState.Connected ?: return
        val controlChar = connected.service.getCharacteristic(
            UUID.fromString("0000fff5-0000-1000-8000-00805f9b34fb")
        )
        controlChar?.let {
            it.value = command
            gatt?.writeCharacteristic(it)
        }
    }
}