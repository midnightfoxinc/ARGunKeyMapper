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
    }

    private val rootStrategy = RootInjectionStrategy()
    private val permissionStrategy = PermissionInjectionStrategy(context)

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Mode the user selected; may be more capable than what currently works. */
    var mode: InjectionMode = InjectionMode.valueOf(
        prefs.getString(KEY_MODE, null) ?: InjectionMode.NONE.name
    )
        private set

    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    private var lastSuccess = false

    /** True when the last injection was accepted by the system. */
    val working: Boolean get() = lastSuccess

    /** Human-readable explanation of the current state, for the settings screen. */
    fun status(): InjectionStatus {
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

    /** Send a raw key event. */
    fun sendKey(keyCode: Int, down: Boolean): Boolean {
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
        InjectionMode.ROOT -> rootStrategy.isAvailable()
        InjectionMode.PERMISSION -> permissionStrategy.isAvailable()
        InjectionMode.NONE -> true
    }

    private fun resolveStrategy(): InjectionStrategy? = when (mode) {
        InjectionMode.ROOT -> rootStrategy.takeIf { it.isAvailable() }
        InjectionMode.PERMISSION -> permissionStrategy.takeIf { it.isAvailable() }
        InjectionMode.NONE -> null
    }

    private fun describeUnavailable(): String {
        val privileges = detectPrivileges()
        return when {
            privileges.rootAvailable -> "Root available but not selected"
            privileges.injectEventsGranted -> "INJECT_EVENTS granted but not selected"
            privileges.shizukuRunning -> "Shizuku is running — use it to grant INJECT_EVENTS"
            privileges.shizukuInstalled -> "Shizuku installed but not running"
            privileges.rootManagerInstalled -> "Root manager found — grant superuser access"
            else -> "No injection privilege. Root this device, or grant via adb: " +
                "adb shell pm grant com.argun.mapper android.permission.INJECT_EVENTS"
        }
    }
}