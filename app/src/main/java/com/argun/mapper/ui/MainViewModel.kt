package com.argun.mapper.ui

import android.app.Application
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.argun.mapper.ArgunService
import com.argun.mapper.data.database.AppDatabase
import com.argun.mapper.data.repository.KeyMappingRepository
import com.argun.mapper.input.InjectionMode
import com.argun.mapper.input.InputSimulator
import com.argun.mapper.input.PrivilegeStatus
import com.argun.mapper.model.ArgunButton
import com.argun.mapper.model.ArgunDevice
import com.argun.mapper.model.KeyMapping
import com.argun.mapper.util.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * UI state for the whole app.
 */
data class MainUiState(
    val scanning: Boolean = false,
    val devices: List<ArgunDevice> = emptyList(),
    val connectedDevice: ArgunDevice? = null,
    val statusMessage: String = "",
    val lastPressedButton: ArgunButton? = null,
    val mappings: Map<String, KeyMapping> = emptyMap(),
    val bluetoothAvailable: Boolean = true,
    val injectionMode: InjectionMode = InjectionMode.NONE,
    val privileges: PrivilegeStatus = PrivilegeStatus(),
    val injectionDetail: String = "",
    val injectionWorking: Boolean = false,
    val savedDevices: List<com.argun.mapper.data.entity.SavedDevice> = emptyList(),
    val autoReconnectAddress: String? = null
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        private const val TAG = "MainViewModel"
        private const val SCAN_DURATION_MS = 10_000L
    }

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var scanJob: Job? = null

    private val repository: KeyMappingRepository? by lazy {
        try {
            val db = AppDatabase.getDatabase(getApplication())
            KeyMappingRepository(db.buttonBindingDao(), db.savedDeviceDao())
        } catch (e: Exception) {
            Log.e(TAG, "Could not open database", e)
            null
        }
    }

    init {
        _uiState.value = _uiState.value.copy(bluetoothAvailable = checkBluetooth())
        observeMappings()
        seedDefaultsIfEmpty()
        observeSavedDevices()
        // NOT refreshInjectionState() here: probing `su` on app start blocks on the
        // root manager's dialog, which is what made the permission prompt appear
        // the moment the user opened the app. Detection runs lazily when the
        // settings card is first shown instead.
    }

    private fun observeSavedDevices() {
        val repo = repository ?: return
        viewModelScope.launch {
            repo.savedDevices.collect { list ->
                _uiState.value = _uiState.value.copy(savedDevices = list)
                if (_uiState.value.autoReconnectAddress == null) {
                    list.firstOrNull()?.let { _uiState.value = _uiState.value.copy(autoReconnectAddress = it.address) }
                }
            }
        }
    }

    // ------------------------------------------------------- input injection

    private val inputSimulator by lazy { InputSimulator(getApplication()) }

    /** Re-detects root/Shizuku/adb availability and the current delivery mode. */
    fun refreshInjectionState() {
        viewModelScope.launch(Dispatchers.IO) {
            val privileges = runCatching { inputSimulator.detectPrivileges() }
                .getOrDefault(PrivilegeStatus())
            val status = runCatching { inputSimulator.status() }.getOrNull()
            _uiState.value = _uiState.value.copy(
                privileges = privileges,
                injectionMode = inputSimulator.mode,
                injectionDetail = status?.detail ?: "Unknown",
                injectionWorking = status?.working == true
            )
        }
    }

    /** Switch to a specific delivery route, with automatic fallback if unavailable. */
    fun setInjectionMode(mode: InjectionMode) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { inputSimulator.setMode(mode) }
                .onFailure { Log.w(TAG, "Could not set injection mode: ${it.message}") }
            // setMode falls back when the requested route is unusable, so ask about
            // the route that actually ended up selected.
            ArgunService.applyHogp(getApplication(), inputSimulator.mode == InjectionMode.HOGP)
            refreshInjectionState()
        }
    }

    /** One-tap "make it work": picks the best available route and enables it. */
    fun enableInjectionAutomatically() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { inputSimulator.tryEnableBest() }
            refreshInjectionState()
        }
    }

    /**
     * Fire `su` so the root manager shows its dialog. The user approves, then
     * detection re-runs and the route becomes usable.
     */
    fun requestRootGrant() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { inputSimulator.requestRootGrant() }
            refreshInjectionState()
        }
    }

    /** Sends a short press for a button so the user can verify their setup. */
    fun testButton(button: ArgunButton) {
        viewModelScope.launch(Dispatchers.IO) {
            val keyCode = keyCodeFor(button)
            runCatching { inputSimulator.sendKeyWithHold(keyCode, 120) }
            refreshInjectionState()
        }
    }

    private fun checkBluetooth(): Boolean =
        PermissionHelper.isBluetoothEnabled(getApplication())

    private fun observeMappings() {
        val repo = repository ?: return
        viewModelScope.launch {
            repo.allBindings.collect { bindings ->
                _uiState.value = _uiState.value.copy(
                    mappings = bindings.associateBy { it.argunButton }
                )
            }
        }
    }

    private fun seedDefaultsIfEmpty() {
        val repo = repository ?: return
        viewModelScope.launch {
            val existing = repo.allBindings.firstOrNullSafe()
            if (existing.isNullOrEmpty()) {
                repo.saveDefaultMappings()
            }
        }
    }

    // ---------------------------------------------------------------- scanning

    fun startScan() {
        if (_uiState.value.scanning) return
        if (!checkBluetooth()) {
            _uiState.value = _uiState.value.copy(
                bluetoothAvailable = false,
                statusMessage = "Bluetooth is off"
            )
            return
        }
        val scanner = leScanner()
        if (scanner == null) {
            _uiState.value = _uiState.value.copy(
                statusMessage = "BLE scanner unavailable"
            )
            return
        }

        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                scanning = true,
                devices = emptyList(),
                statusMessage = "Scanning for ARGUN…"
            )

            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    onArgunFound(result)
                }

                override fun onScanFailed(errorCode: Int) {
                    Log.e(TAG, "Scan failed: $errorCode")
                    _uiState.value = _uiState.value.copy(
                        scanning = false,
                        statusMessage = "Scan failed ($errorCode)"
                    )
                }
            }

            val filters = listOf(
                ScanFilter.Builder()
                    .setServiceUuid(ParcelUuid(UUID.fromString(ARGUN_SERVICE_UUID)))
                    .build()
            )
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            try {
                scanner.startScan(filters, settings, callback)
                delay(SCAN_DURATION_MS)
                scanner.stopScan(callback)
            } catch (e: SecurityException) {
                Log.e(TAG, "Missing BLUETOOTH_SCAN permission", e)
                _uiState.value = _uiState.value.copy(statusMessage = "Bluetooth permission required")
            } catch (e: Exception) {
                Log.e(TAG, "Scan error", e)
                _uiState.value = _uiState.value.copy(statusMessage = "Scan error: ${e.message}")
            }

            val found = _uiState.value.devices
            _uiState.value = _uiState.value.copy(
                scanning = false,
                statusMessage = if (found.isEmpty()) {
                    "No ARGUN found. Make sure it is on and nearby."
                } else {
                    "Select a device to connect"
                }
            )
        }
    }

    private fun onArgunFound(result: ScanResult) {
        val device = result.device
        val name = try {
            device.name ?: ""
        } catch (e: SecurityException) {
            ""
        }
        // Accept anything advertising the ARGUN service, or named like one.
        val matchesService = result.scanRecord?.serviceUuids?.contains(
            ParcelUuid(UUID.fromString(ARGUN_SERVICE_UUID))
        ) == true
        if (!matchesService && !name.contains("ARGun", ignoreCase = true)) return

        val entry = ArgunDevice(
            name = name.ifBlank { "ARGun" },
            address = device.address,
            rssi = result.rssi
        )
        val current = _uiState.value.devices
        if (current.none { it.address == entry.address }) {
            _uiState.value = _uiState.value.copy(
                devices = current + entry,
                statusMessage = "Select a device to connect"
            )
        }
    }

    private fun leScanner() = try {
        val manager = getApplication<Application>()
            .getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager
        manager?.adapter?.bluetoothLeScanner
    } catch (e: SecurityException) {
        Log.e(TAG, "Missing BLUETOOTH_CONNECT permission", e)
        null
    }

    // --------------------------------------------------------------- connecting

    fun connect(device: ArgunDevice) {
        _uiState.value = _uiState.value.copy(
            connectedDevice = device,
            statusMessage = "Connecting to ${device.name}…"
        )
        rememberDevice(device)
        try {
            ArgunService.startConnect(getApplication(), device.address)
        } catch (e: Exception) {
            Log.e(TAG, "Could not start service", e)
            _uiState.value = _uiState.value.copy(statusMessage = "Could not start connection")
        }
    }

    /** Reconnect to the most recently used device, without scanning. */
    fun reconnectSaved() {
        val address = _uiState.value.autoReconnectAddress ?: return
        val name = _uiState.value.savedDevices.firstOrNull { it.address == address }?.name ?: "ARGUN"
        connect(ArgunDevice(name = name, address = address))
    }

    /** Forget a device so it no longer appears in the saved list or reconnects. */
    fun forgetDevice(address: String) {
        val repo = repository ?: return
        viewModelScope.launch(Dispatchers.IO) {
            repo.forgetDevice(address)
            if (_uiState.value.autoReconnectAddress == address) {
                _uiState.value = _uiState.value.copy(
                    autoReconnectAddress = _uiState.value.savedDevices.firstOrNull()?.address
                )
            }
        }
    }

    /** Forget every saved device. */
    fun forgetAllDevices() {
        val repo = repository ?: return
        viewModelScope.launch(Dispatchers.IO) { repo.forgetAllDevices() }
    }

    private fun rememberDevice(device: ArgunDevice) {
        val repo = repository ?: return
        viewModelScope.launch(Dispatchers.IO) {
            repo.rememberDevice(device)
        }
    }

    fun disconnect() {
        ArgunService.stopService(getApplication())
        _uiState.value = _uiState.value.copy(
            connectedDevice = null,
            lastPressedButton = null,
            statusMessage = "Disconnected"
        )
    }

    // ---------------------------------------------------------------- mappings

    fun keyCodeFor(button: ArgunButton): Int =
        _uiState.value.mappings[button.tag]?.phoneKeyCode ?: button.defaultKeyCode

    fun keyNameFor(button: ArgunButton): String =
        _uiState.value.mappings[button.tag]?.phoneKeyName ?: button.defaultFunction

    fun setMapping(button: ArgunButton, keyCode: Int, keyName: String) {
        val repo = repository
        if (repo == null) {
            _uiState.value = _uiState.value.copy(
                statusMessage = "Mapping not saved: database unavailable"
            )
            return
        }
        viewModelScope.launch {
            runCatching {
                repo.upsert(KeyMapping(button.tag, keyCode, keyName))
            }.onFailure {
                Log.e(TAG, "Could not persist mapping", it)
                _uiState.value = _uiState.value.copy(statusMessage = "Could not save mapping")
            }
        }
    }

    fun restoreDefaults() {
        val repo = repository ?: return
        viewModelScope.launch {
            repo.saveDefaultMappings()
            _uiState.value = _uiState.value.copy(statusMessage = "Default mappings restored")
        }
    }

    fun noteButtonPressed(button: ArgunButton) {
        _uiState.value = _uiState.value.copy(lastPressedButton = button)
    }

    fun clearMessage() {
        _uiState.value = _uiState.value.copy(statusMessage = "")
    }

    override fun onCleared() {
        scanJob?.cancel()
        super.onCleared()
    }
}

/** Emits the first value of a flow, or null if the flow completes without one. */
private suspend fun <T> Flow<T>.firstOrNullSafe(): T? =
    runCatching { firstOrNull() }.getOrNull()

private const val ARGUN_SERVICE_UUID = "0000fff0-0000-1000-8000-00805f9b34fb"