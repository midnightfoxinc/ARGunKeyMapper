package com.argun.mapper

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.argun.mapper.R
import com.argun.mapper.ble.BleManager
import com.argun.mapper.ble.HidPeripheral
import com.argun.mapper.data.database.AppDatabase
import com.argun.mapper.data.repository.KeyMappingRepository
import com.argun.mapper.input.InjectionMode
import com.argun.mapper.input.InputSimulator
import com.argun.mapper.model.ArgunButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Foreground service that maintains the ARGUN BLE connection and
 * processes button events in the background.
 */
class ArgunService : Service() {

    companion object {
        private const val TAG = "ArgunService"
        const val CHANNEL_ID = "argun_mapper_channel"
        const val NOTIFICATION_ID = 1
        const val EXTRA_DEVICE_ADDRESS = "device_address"

        private const val ACTION_CONNECT = "com.argun.mapper.CONNECT"
        private const val ACTION_DISCONNECT = "com.argun.mapper.DISCONNECT"
        private const val ACTION_APPLY_HOGP = "com.argun.mapper.APPLY_HOGP"
        private const val EXTRA_HOGP_ENABLED = "hogp_enabled"

        fun startConnect(context: Context, address: String) {
            val intent = Intent(context, ArgunService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_DEVICE_ADDRESS, address)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, ArgunService::class.java).apply {
                action = ACTION_DISCONNECT
            }
            // startForegroundService would require a notification we are tearing down,
            // so a plain start is correct here while the service is already running.
            context.startService(intent)
        }

        /**
         * Start or stop the HID peripheral in place, so switching to or from HOGP
         * mid-session does not require reconnecting the ARGUN.
         */
        fun applyHogp(context: Context, enabled: Boolean) {
            val intent = Intent(context, ArgunService::class.java).apply {
                action = ACTION_APPLY_HOGP
                putExtra(EXTRA_HOGP_ENABLED, enabled)
            }
            runCatching { context.startService(intent) }
        }
    }

    private lateinit var bleManager: BleManager
    private lateinit var inputSimulator: InputSimulator
    private lateinit var hidPeripheral: HidPeripheral
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var eventJob: Job? = null

    /** User-configured ARGUN button -> keycode, refreshed from Room. */
    @Volatile
    private var keyCodes: Map<String, Int> = emptyMap()

    private val repository: KeyMappingRepository? by lazy {
        try {
            val db = AppDatabase.getDatabase(applicationContext)
            KeyMappingRepository(db.buttonBindingDao(), db.savedDeviceDao())
        } catch (e: Exception) {
            Log.e(TAG, "Could not open database; using default mappings", e)
            null
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ArgunService onCreate")
        bleManager = BleManager(this)
        inputSimulator = InputSimulator(this)
        hidPeripheral = HidPeripheral(this)
        inputSimulator.hogpStatusProvider = { hidPeripheral.currentStatus.ready }
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand action=${intent?.action}")
        when (intent?.action) {
            ACTION_CONNECT -> {
                val address = intent.getStringExtra(EXTRA_DEVICE_ADDRESS)
                if (address.isNullOrBlank()) {
                    Log.e(TAG, "CONNECT requested without a device address")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startForeground(NOTIFICATION_ID, buildNotification(address))
                startHidPeripheralIfSelected()
                connect(address)
            }
            ACTION_DISCONNECT -> {
                stopHidPeripheral()
                stopForegroundCompat()
                stopSelf()
            }
            ACTION_APPLY_HOGP -> {
                if (intent.getBooleanExtra(EXTRA_HOGP_ENABLED, false)) {
                    startHidPeripheralIfSelected()
                } else {
                    stopHidPeripheral()
                }
            }
        }
        return START_STICKY
    }

    private fun startHidPeripheralIfSelected() {
        if (inputSimulator.mode != InjectionMode.HOGP) return
        val started = hidPeripheral.start()
        Log.i(TAG, "HOGP peripheral start=$started")
    }

    private fun stopHidPeripheral() {
        runCatching { hidPeripheral.stop() }
    }

    private fun connect(address: String) {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE)
                as? android.bluetooth.BluetoothManager)?.adapter
        if (adapter == null) {
            Log.e(TAG, "No Bluetooth adapter available")
            stopSelf()
            return
        }
        try {
            bleManager.connect(adapter.getRemoteDevice(address))
            listenForEvents()
        } catch (e: Exception) {
            Log.e(TAG, "Connect failed", e)
            stopSelf()
        }
    }

    private fun listenForEvents() {
        eventJob?.cancel()
        eventJob = serviceScope.launch {
            // Keep mappings fresh in the background, then collect BLE events.
            // These are two independent coroutines: collectLatest suspends forever
            // on the Room flow, so nesting the event collector inside it would
            // never start.
            launch {
                repository?.allBindings?.collect { bindings ->
                    keyCodes = bindings.associate { it.argunButton to it.phoneKeyCode }
                    Log.i(TAG, "Loaded ${keyCodes.size} key mappings")
                }
            }

            bleManager.eventFlow.collectLatest { notification ->
                if (!notification.isButtonEvent) {
                    Log.d(TAG, "Non-button event: ${notification.rawValue}")
                    return@collectLatest
                }
                ArgunButton.parseEvent(notification.rawValue)?.let { (button, isDown) ->
                    val keyCode = keyCodes[button.tag] ?: button.defaultKeyCode
                    // HOGP runs alongside whichever strategy is selected: the
                    // peripheral reports button state, the OS turns it into key
                    // events. When another route is active this is simply ignored.
                    if (inputSimulator.mode == InjectionMode.HOGP) {
                        hidPeripheral.onButton(button, isDown)
                    }
                    val ok = inputSimulator.injectArgunButton(button, isDown, keyCode)
                    Log.d(TAG, "Button ${button.tag} -> keyCode $keyCode (ok=$ok)")
                }
            }
        }
    }

    private fun buildNotification(address: String) = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle(getString(R.string.ble_notification_title))
        .setContentText(getString(R.string.connected_device, address))
        .setSmallIcon(android.R.drawable.ic_btn_speak_now)
        .setOngoing(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, com.argun.mapper.ui.MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
        )
        .build()

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.ble_notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Log.d(TAG, "ArgunService onDestroy")
        eventJob?.cancel()
        serviceScope.cancel()
        stopHidPeripheral()
        bleManager.disconnect()
        super.onDestroy()
    }
}