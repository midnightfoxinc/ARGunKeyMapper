package com.argun.mapper.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import android.util.Log
import com.argun.mapper.model.ArgunButton
import java.util.UUID

/**
 * HID-over-GATT peripheral: advertises an HID game pad and feeds it button state.
 *
 * This is the delivery route that needs **no Android permission**. The phone runs
 * the BLE peripheral, the platform's HID host profile connects to it, and the
 * Bluetooth stack turns each report into real key events itself — so it works on
 * stock Android 12+, where `INJECT_EVENTS` cannot be granted at all.
 *
 * The host is normally the same phone: pair with [DEVICE_NAME] under Bluetooth
 * settings and the stack connects to its own peripheral. It also works against an
 * external host (a PC, TV, or second phone), which is how the ARGUN can drive a
 * device that has no ARGUN support of its own.
 *
 * Needs API 21+; [BluetoothLeAdvertiser] and `openGattServer` both arrived there.
 */
class HidPeripheral(private val context: Context) {

    companion object {
        private const val TAG = "HidPeripheral"

        /** Name shown to the HID host when pairing. */
        const val DEVICE_NAME = "ARGUN Mapper Gamepad"

        private const val PERMISSION_READ = BluetoothGattCharacteristic.PERMISSION_READ
        private const val PERMISSION_WRITE = BluetoothGattCharacteristic.PERMISSION_WRITE

        /** Upper bound on a single attribute value served in one read response. */
        private const val MTU_ATT_MAX_LEN = 512
    }

    /** Observable state of the peripheral, surfaced in the UI. */
    data class Status(
        val advertising: Boolean = false,
        val hostConnected: Boolean = false,
        val notificationsEnabled: Boolean = false,
        val lastError: String? = null
    ) {
        /** True once the host can actually receive button reports. */
        val ready: Boolean get() = hostConnected && notificationsEnabled
    }

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager

    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private var gattServer: BluetoothGattServer? = null
    private var advertiser: BluetoothLeAdvertiser? = null
    private var hidService: BluetoothGattService? = null
    private var reportCharacteristic: BluetoothGattCharacteristic? = null

    private val connectedHosts = LinkedHashSet<String>()
    private val enabledHosts = LinkedHashSet<String>()

    @Volatile
    private var status = Status()

    @Volatile
    private var lastReport: ByteArray = HidReport.idleReport()

    /** Currently held buttons; the report is rebuilt from this on every change. */
    private val held = LinkedHashSet<ArgunButton>()

    /** Notified whenever [status] changes. */
    @Volatile
    var onStatusChanged: ((Status) -> Unit)? = null

    /** Notified whenever a report is successfully delivered to a host. */
    @Volatile
    var onReportSent: ((ByteArray) -> Unit)? = null

    val currentStatus: Status get() = status

    // ---------------------------------------------------------------- startup

    /**
     * Opens the GATT server, registers the HID service, and starts advertising.
     *
     * @return true if advertising started, false if the platform refused.
     */
    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (status.advertising) return true

        val bt = adapter
        if (bt == null) {
            fail("No Bluetooth adapter")
            return false
        }
        if (!bt.isEnabled) {
            fail("Bluetooth is off")
            return false
        }

        if (gattServer == null && !openServer(bt)) {
            fail("Could not open GATT server")
            return false
        }
        if (!registerHidService()) {
            fail("Could not add HID service")
            return false
        }
        return startAdvertising(bt)
    }

    /** Stops advertising and tears the GATT server down. */
    fun stop() {
        runCatching { advertiser?.stopAdvertising(advertiseCallback) }
        advertiser = null
        runCatching { gattServer?.clearServices() }
        runCatching { gattServer?.close() }
        gattServer = null
        hidService = null
        reportCharacteristic = null
        synchronized(held) {
            connectedHosts.clear()
            enabledHosts.clear()
            held.clear()
        }
        lastReport = HidReport.idleReport()
        updateStatus(Status())
    }

    @SuppressLint("MissingPermission")
    private fun openServer(bt: BluetoothAdapter): Boolean = runCatching {
        // openGattServer lives on BluetoothManager, not on the adapter.
        gattServer = bluetoothManager?.openGattServer(context, serverCallback)
        gattServer != null
    }.getOrElse {
        Log.e(TAG, "openGattServer failed", it)
        false
    }

    /**
     * Builds the HID service and adds it to the server.
     *
     * No report IDs are used in the report map, so the whole input report travels
     * on the Report characteristic (0x2A4D); the host enables its CCCD and every
     * subsequent [sendReport] arrives as a notification.
     */
    private fun registerHidService(): Boolean {
        val server = gattServer ?: return false
        if (hidService != null) return true

        val service = BluetoothGattService(
            UUID.fromString(HidReport.HID_SERVICE_UUID),
            BluetoothGattService.SERVICE_TYPE_PRIMARY
        )

        // HID Information: report protocol (1), no suspend support, version 1.0.
        service.addCharacteristic(
            readCharacteristic(HidReport.HID_INFO_UUID, HidReport.HID_INFORMATION)
        )

        // Report Map. Readable by the host; its CCCD is optional here because the
        // host is not the one sending reports.
        service.addCharacteristic(
            readCharacteristic(HidReport.REPORT_MAP_UUID, HidReport.REPORT_MAP)
                .apply { addDescriptor(cccdDescriptor()) }
        )

        // Protocol Mode: 0 = boot protocol, 1 = report protocol. We only do report.
        service.addCharacteristic(
            writableCharacteristic(HidReport.PROTOCOL_MODE_UUID, HidReport.PROTOCOL_MODE_VALUE)
        )

        // HID Control Point: accept suspend/resume-exit so the host can attach.
        service.addCharacteristic(
            writableCharacteristic(HidReport.HID_CONTROL_POINT_UUID, ByteArray(0))
        )

        // The input report itself.
        val report = BluetoothGattCharacteristic(
            UUID.fromString(HidReport.REPORT_UUID),
            BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            PERMISSION_READ
        ).apply {
            value = HidReport.idleReport()
            addDescriptor(cccdDescriptor())
        }
        service.addCharacteristic(report)

        reportCharacteristic = report
        hidService = service

        return runCatching { server.addService(service) }.getOrElse {
            Log.e(TAG, "addService failed", it)
            false
        }
    }

    private fun readCharacteristic(uuid: String, initialValue: ByteArray) =
        BluetoothGattCharacteristic(
            UUID.fromString(uuid),
            BluetoothGattCharacteristic.PROPERTY_READ,
            PERMISSION_READ
        ).apply { value = initialValue }

    private fun writableCharacteristic(uuid: String, initialValue: ByteArray) =
        BluetoothGattCharacteristic(
            UUID.fromString(uuid),
            BluetoothGattCharacteristic.PROPERTY_READ or
                BluetoothGattCharacteristic.PROPERTY_WRITE,
            PERMISSION_READ or PERMISSION_WRITE
        ).apply { value = initialValue }

    private fun cccdDescriptor() = BluetoothGattDescriptor(
        UUID.fromString(HidReport.CCCD_UUID),
        BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE
    )

    @SuppressLint("MissingPermission")
    private fun startAdvertising(bt: BluetoothAdapter): Boolean {
        val leAdvertiser = runCatching { bt.bluetoothLeAdvertiser }.getOrNull()
        if (leAdvertiser == null) {
            fail("This device cannot act as a BLE peripheral")
            return false
        }
        advertiser = leAdvertiser

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(UUID.fromString(HidReport.HID_SERVICE_UUID)))
            .build()

        return runCatching {
            leAdvertiser.startAdvertising(settings, data, advertiseCallback)
            true
        }.getOrElse {
            Log.e(TAG, "startAdvertising failed", it)
            fail("Could not start BLE advertising")
            false
        }
    }

    // --------------------------------------------------------- button reports

    /**
     * Applies a button transition and, if anything changed, notifies the host.
     *
     * @return true if the state changed and a report was attempted.
     */
    fun onButton(button: ArgunButton, isDown: Boolean): Boolean {
        val changed = synchronized(held) {
            if (isDown) held.add(button) else held.remove(button)
        }
        if (!changed) return false
        return sendReport(HidReport.buildReport(synchronized(held) { held.toSet() }))
    }

    /** Releases every button and tells the host, so nothing sticks down. */
    fun releaseAll(): Boolean {
        val had = synchronized(held) {
            val any = held.isNotEmpty()
            held.clear()
            any
        }
        if (!had) return false
        return sendReport(HidReport.idleReport())
    }

    /** Pushes the current state to the host without changing it. */
    fun resendCurrentReport(): Boolean = sendReport(lastReport)

    @SuppressLint("MissingPermission")
    private fun sendReport(report: ByteArray): Boolean {
        lastReport = report
        val server = gattServer ?: return false
        val characteristic = reportCharacteristic ?: return false

        val targets = synchronized(held) {
            connectedHosts.filter { enabledHosts.contains(it) }
        }
        if (targets.isEmpty()) return false

        // notifyCharacteristicChanged() without an explicit value is the only
        // overload available before API 33, so stage the payload on the
        // characteristic and send from there.
        characteristic.value = report

        var delivered = false
        targets.forEach { address ->
            val device = runCatching { adapter?.getRemoteDevice(address) }.getOrNull() ?: return@forEach
            // confirm=false: these are unacknowledged notifications, which is what
            // a HID input report is. The payload rides on characteristic.value.
            val ok = runCatching {
                server.notifyCharacteristicChanged(device, characteristic, false)
            }.getOrElse {
                Log.w(TAG, "notifyCharacteristicChanged failed", it)
                false
            }
            delivered = delivered || ok
        }
        if (delivered) onReportSent?.invoke(report)
        return delivered
    }

    // -------------------------------------------------------------- callbacks

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
            Log.i(TAG, "Advertising as $DEVICE_NAME")
            updateStatus(status.copy(advertising = true, lastError = null))
        }

        override fun onStartFailure(errorCode: Int) {
            Log.e(TAG, "Advertising failed: $errorCode")
            updateStatus(
                status.copy(
                    advertising = false,
                    lastError = when (errorCode) {
                        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED ->
                            "Already advertising"
                        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED ->
                            "BLE peripheral mode unsupported on this device"
                        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR ->
                            "Bluetooth stack error while advertising"
                        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS ->
                            "Too many advertisers; close other BLE apps"
                        else -> "Advertising failed (code $errorCode)"
                    }
                )
            )
        }
    }

    private val serverCallback = object : BluetoothGattServerCallback() {

        override fun onConnectionStateChange(
            device: BluetoothDevice,
            status: Int,
            newState: Int
        ) {
            val address = device.address
            Log.d(TAG, "Host $address newState=$newState status=$status")
            synchronized(held) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectedHosts.add(address)
                } else {
                    connectedHosts.remove(address)
                    enabledHosts.remove(address)
                }
            }
            publish()
        }

        override fun onServiceAdded(status: Int, service: BluetoothGattService?) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "Service add failed: $status")
                updateStatus(this@HidPeripheral.status.copy(lastError = "HID service rejected"))
            }
        }

        // API 33 signature: (device, requestId, offset, characteristic).
        override fun onCharacteristicReadRequest(
            device: BluetoothDevice,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic
        ) {
            val value = when (characteristic.uuid.toString()) {
                HidReport.HID_INFO_UUID -> HidReport.HID_INFORMATION
                HidReport.REPORT_MAP_UUID -> HidReport.REPORT_MAP
                HidReport.PROTOCOL_MODE_UUID -> HidReport.PROTOCOL_MODE_VALUE
                HidReport.REPORT_UUID -> lastReport
                else -> null
            }
            val server = gattServer
            if (server == null) return
            if (value == null) {
                respondRead(server, device, requestId, BluetoothGatt.GATT_READ_NOT_PERMITTED, offset, null)
                return
            }
            respondRead(server, device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
        }

        // API 33 signature: (device, requestId, characteristic, preparedWrite,
        // responseNeeded, offset, value).
        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            val ok = when (characteristic.uuid.toString()) {
                // Only the report protocol (1) and the boot default (0) are accepted.
                HidReport.PROTOCOL_MODE_UUID -> {
                    val requested = value?.firstOrNull()?.toInt() ?: HidReport.PROTOCOL_MODE_REPORT
                    requested == 0 || requested == HidReport.PROTOCOL_MODE_REPORT
                }
                // HID Control Point: accept suspend/resume exit; we never suspend.
                HidReport.HID_CONTROL_POINT_UUID -> true
                else -> false
            }
            if (ok && value != null) characteristic.value = value
            if (responseNeeded) {
                gattServer?.sendResponse(
                    device,
                    requestId,
                    if (ok) BluetoothGatt.GATT_SUCCESS else BluetoothGatt.GATT_WRITE_NOT_PERMITTED,
                    offset,
                    value
                )
            }
        }

        // API 33 signature: (device, requestId, descriptor, preparedWrite,
        // responseNeeded, offset, value).
        override fun onDescriptorWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            descriptor: BluetoothGattDescriptor,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?
        ) {
            if (descriptor.uuid.toString() == HidReport.CCCD_UUID) {
                // Bit 0 of the little-endian CCCD value is the notify flag.
                val enable = value != null &&
                    value.size >= 2 &&
                    (value[0].toInt() and 0xFF) != 0
                synchronized(held) {
                    if (enable) enabledHosts.add(device.address) else enabledHosts.remove(device.address)
                }
                if (value != null) descriptor.value = value
                Log.i(TAG, "Host ${device.address} notifications ${if (enable) "on" else "off"}")
                publish()
                // The host has only just subscribed; hand it the current state.
                if (enable) sendReport(lastReport)
            }
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
            }
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "Notification to ${device.address} failed: $status")
            }
        }

        private fun respondRead(
            server: BluetoothGattServer,
            device: BluetoothDevice,
            requestId: Int,
            gattStatus: Int,
            offset: Int,
            value: ByteArray?
        ) {
            if (gattStatus != BluetoothGatt.GATT_SUCCESS) {
                server.sendResponse(device, requestId, gattStatus, offset, null)
                return
            }
            val payload = value ?: return
            // A non-zero offset means a partial read. The HID host never issues one
            // for these characteristics, and serving the wrong window of the report
            // map would corrupt it, so refuse instead.
            if (offset != 0 || payload.size > MTU_ATT_MAX_LEN) {
                server.sendResponse(device, requestId, BluetoothGatt.GATT_INVALID_OFFSET, offset, null)
                return
            }
            server.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, payload)
        }
    }

    private fun fail(reason: String) {
        Log.w(TAG, reason)
        updateStatus(status.copy(lastError = reason))
    }

    private fun publish() {
        val (connected, enabled) = synchronized(held) {
            connectedHosts.isNotEmpty() to connectedHosts.any { enabledHosts.contains(it) }
        }
        updateStatus(
            status.copy(hostConnected = connected, notificationsEnabled = enabled)
        )
    }

    private fun updateStatus(next: Status) {
        status = next
        onStatusChanged?.invoke(next)
    }
}