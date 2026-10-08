package com.argun.mapper.input

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.argun.mapper.model.ArgunButton

/**
 * Sends ARGUN button presses to the system as Android key events.
 *
 * Delivery depends on what the device allows. See [InjectionMode] for the three
 * routes and [PrivilegeDetector] for how availability is discovered:
 *
 *  - `HOGP`      — app is a HID-over-GATT peripheral; the Bluetooth stack turns
 *                  HID reports into key events. Needs no permission, and is the
 *                  only route that works on stock Android 12+.
 *  - `ROOT`      — device is rooted; uses `su`. Works without any permission.
 *  - `PERMISSION`— INJECT_EVENTS granted via adb / Shizuku / root; uses InputManager.
 *  - `NONE`      — neither available. Events are decoded and shown in-app, but the
 *                  system will not accept them, so games receive nothing.
 *
 * The chosen mode is persisted, and falls back automatically when the preferred
 * one is no longer usable (e.g. root was revoked).
 */
class InputSimulator(private val context: Context) {

    companion object {
        private const val TAG = "InputSimulator"
        private const val PREFS = "argun_injection"
        private const val KEY_MODE = "mode"

        /**
         * Treat the pistol-grip trigger's `ARGun KeyPressed` handshake as a fire press.
         *
         * This gun reports the trigger as that handshake on press and an all-zero
         * payload on release, instead of the `B2DOWN`/`B2UP` form every other button
         * uses. It is opt-in because the same handshake is also the device's keepalive,
         * so guessing silently would fire on idle traffic.
         */
        private const val KEY_TRIGGER = "trigger_handshake"
    }

    private val rootStrategy = RootInjectionStrategy()
    private val permissionStrategy = PermissionInjectionStrategy(context)

    /**
     * Set by the service when HOGP mode is active, so status() can report live
     * peripheral state instead of assuming delivery succeeded.
     */
    @Volatile
    var hogpStatusProvider: (() -> Boolean)? = null

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Mode the user selected; may be more capable than what currently works. */
    var mode: InjectionMode = InjectionMode.valueOf(
        prefs.getString(KEY_MODE, null) ?: InjectionMode.NONE.name
    )
        private set

    /**
     * Whether the pistol-grip trigger's `ARGun KeyPressed` handshake is treated as
     * a fire press. Off by default — the same handshake is also the device's
     * keepalive, so this is a deliberate opt-in, not a guess.
     */
    var triggerHandshake: Boolean
        get() = prefs.getBoolean(KEY_TRIGGER, false)
        set(value) {
            prefs.edit { putBoolean(KEY_TRIGGER, value) }
            Log.i(TAG, "Trigger handshake delivery = $value")
        }

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    private var lastSuccess = false

    /** True when the last injection was accepted by the system. */
    val working: Boolean get() = lastSuccess

    /** Human-readable explanation of the current state, for the settings screen. */
    fun status(): InjectionStatus {
        if (mode == InjectionMode.HOGP) {
            val ready = hogpStatusProvider?.invoke() ?: false
            return InjectionStatus(
                mode = mode,
                working = ready,
                detail = when {
                    !isUsable(InjectionMode.HOGP) -> "This device cannot act as a BLE peripheral"
                    ready -> "Advertising as a HID gamepad — host connected"
                    else -> "Advertising as a HID gamepad — pair it in Bluetooth settings to connect"
                }
            )
        }
        val strategy = resolveStrategy()
        return InjectionStatus(
            mode = mode,
            working = strategy != null && lastSuccess,
            detail = when {
                strategy == null -> describeUnavailable()
                else -> "Delivering via ${strategy.displayName}"
            }
        )
    }

    /** What this device offers, independent of the user's choice. */
    fun detectPrivileges(): PrivilegeStatus = PrivilegeDetector.detect(context)

    /**
     * Pick a mode and remember it. Falls back to the best available one when the
     * requested mode is not usable, so the UI never leaves the app in a dead state.
     */
    fun setMode(requested: InjectionMode) {
        val effective = if (isUsable(requested)) requested else detectPrivileges().bestAvailableMode()
        mode = effective
        prefs.edit { putString(KEY_MODE, effective.name) }
        Log.i(TAG, "Injection mode set to $effective (requested $requested)")
        lastError = if (effective == InjectionMode.NONE) describeUnavailable() else null
    }

    /**
     * Re-read the persisted mode into this instance.
     *
     * The UI and the foreground service each hold their own [InputSimulator], both
     * backed by the same SharedPreferences. A mode chosen in the UI while the service
     * is already running only reaches the service's copy through here — otherwise the
     * service would keep acting on the value it read at construction time, and
     * switching *to* HOGP mid-session would start nothing until reconnect.
     */
    fun refreshModeFromPrefs() {
        mode = InjectionMode.valueOf(
            prefs.getString(KEY_MODE, null) ?: InjectionMode.NONE.name
        )
    }

    /** Convenience for one-tap enabling from the UI. */
    fun tryEnableBest(): InjectionStatus {
        val best = detectPrivileges().bestAvailableMode()
        if (best == InjectionMode.ROOT) {
            // Root can also open the permission route for later.
            rootStrategy.selfGrantPermission(context.packageName)
        }
        setMode(best)
        return status()
    }

    /**
     * Fire `su` on its own so the root manager shows its dialog and the user can
     * approve. Returns the user-facing status afterwards. Detection alone cannot
     * trigger the dialog — this is the action behind the UI's grant button.
     */
    fun requestRootGrant(): InjectionStatus {
        val granted = runCatching {
            rootStrategy.requestRootGrant(context.packageName)
        }.getOrDefault(false)
        if (granted) {
            // Re-detect so the cached probe is invalidated and refreshed.
            setMode(InjectionMode.ROOT)
        }
        return status()
    }

    /** Send a raw key event. */
    fun sendKey(keyCode: Int, down: Boolean): Boolean {
        // HOGP never routes through a strategy — the Bluetooth stack synthesises the
        // key events from HID reports fed by HidPeripheral, so there is nothing to
        // inject here and reporting failure would be wrong.
        if (mode == InjectionMode.HOGP) return true
        val strategy = resolveStrategy()
        if (strategy == null) {
            lastSuccess = false
            lastError = describeUnavailable()
            return false
        }
        return try {
            val ok = strategy.inject(keyCode, down)
            lastSuccess = ok
            lastError = if (ok) null else strategy.unavailableReason() ?: "Injection rejected"
            ok
        } catch (e: Exception) {
            lastSuccess = false
            lastError = e.message
            Log.e(TAG, "Injection error", e)
            false
        }
    }

    /** Press and release with an optional hold. */
    fun sendKeyWithHold(keyCode: Int, holdMs: Long = 0): Boolean {
        val downOk = sendKey(keyCode, true)
        if (holdMs > 0) {
            try {
                Thread.sleep(holdMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        val upOk = sendKey(keyCode, false)
        return downOk && upOk
    }

    /**
     * Deliver an ARGUN button press using its mapped key code.
     *
     * @param keyCode the user's configured mapping, or null to use the factory default.
     */
    fun injectArgunButton(button: ArgunButton, isDown: Boolean, keyCode: Int? = null): Boolean =
        sendKey(keyCode ?: button.defaultKeyCode, isDown)

    private fun isUsable(candidate: InjectionMode): Boolean = when (candidate) {
        InjectionMode.HOGP -> PrivilegeDetector.isHogpSupported(context)
        InjectionMode.ROOT -> rootStrategy.isAvailable()
        InjectionMode.PERMISSION -> permissionStrategy.isAvailable()
        InjectionMode.NONE -> true
    }

    private fun resolveStrategy(): InjectionStrategy? = when (mode) {
        InjectionMode.HOGP -> null // delivered by the HID peripheral, not by a strategy
        InjectionMode.ROOT -> rootStrategy.takeIf { it.isAvailable() }
        InjectionMode.PERMISSION -> permissionStrategy.takeIf { it.isAvailable() }
        InjectionMode.NONE -> null
    }

    private fun describeUnavailable(): String {
        val privileges = detectPrivileges()
        return when {
            privileges.rootAvailable -> "Root available but not selected"
            privileges.injectEventsGranted -> "INJECT_EVENTS granted but not selected"
            privileges.hogpSupported -> "HID gamepad available — select it to deliver keys with no permission"
            privileges.shizukuRunning -> "Shizuku is running — use it to grant INJECT_EVENTS"
            privileges.shizukuInstalled -> "Shizuku installed but not running"
            privileges.rootManagerInstalled -> "Root manager found — grant superuser access"
            else -> "No injection privilege, and this device cannot act as a HID peripheral. " +
                "Root it, or grant via adb: " +
                "adb shell pm grant com.argun.mapper android.permission.INJECT_EVENTS"
        }
    }
}