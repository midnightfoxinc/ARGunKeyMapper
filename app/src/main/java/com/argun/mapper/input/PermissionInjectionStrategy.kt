package com.argun.mapper.input

import android.content.Context
import android.hardware.input.InputManager
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyEvent

/**
 * Injects events through [InputManager], which requires the app to hold
 * `android.permission.INJECT_EVENTS`.
 *
 * That permission is `signature|privileged`, so it can only be obtained if the app
 * is installed as a privileged/system app, or if it is granted externally by:
 *
 *  - adb:  `adb shell pm grant com.argun.mapper android.permission.INJECT_EVENTS`
 *  - Shizuku's "grant permission" screen (which is the same operation via the
 *    Shizuku shell binder)
 *  - root: `su -c pm grant com.argun.mapper android.permission.INJECT_EVENTS`
 *
 * Once granted, this is the best strategy: events carry `SOURCE_GAMEPAD` and
 * `repeatCount`, so games see a real gamepad rather than a stream of taps.
 *
 * `injectInputEvent` is hidden API from API 30 onward, so it is called reflectively.
 * That is safe here because the permission, not the reflection, is the gate — and
 * on API 30+ the app can no longer hold the permission anyway, so this path is
 * effectively unused on modern devices.
 */
class PermissionInjectionStrategy(private val context: Context) : InjectionStrategy {

    companion object {
        private const val TAG = "PermissionInjection"
        private const val INJECT_MODE_ASYNC = 0
        private const val SOURCE_GAMEPAD = InputDevice.SOURCE_GAMEPAD
    }

    override val displayName: String = "Permission (adb / Shizuku)"

    private var cachedMethod: java.lang.reflect.Method? = null

    override fun isAvailable(): Boolean =
        PrivilegeDetector.hasInjectEventsPermission(context) && resolveMethod() != null

    override fun unavailableReason(): String? {
        if (!PrivilegeDetector.hasInjectEventsPermission(context)) {
            return "INJECT_EVENTS not granted. Enable via adb, Shizuku, or root."
        }
        return if (resolveMethod() == null) {
            "injectInputEvent unavailable on this Android version"
        } else {
            null
        }
    }

    override fun inject(keyCode: Int, down: Boolean): Boolean {
        val manager = try {
            context.getSystemService(Context.INPUT_SERVICE) as? InputManager
        } catch (e: Exception) {
            Log.e(TAG, "No InputManager", e)
            null
        } ?: return false

        val method = resolveMethod() ?: return false

        val now = SystemClock.uptimeMillis()
        val event = KeyEvent(now, now, if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, keyCode, 0)
            .apply { source = SOURCE_GAMEPAD }

        return try {
            method.invoke(manager, event, INJECT_MODE_ASYNC)
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "INJECT_EVENTS denied: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Injection failed", e)
            false
        }
    }

    private fun resolveMethod(): java.lang.reflect.Method? {
        cachedMethod?.let { return it }
        return try {
            val m = Class.forName("android.hardware.input.InputManager")
                .getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            cachedMethod = m
            m
        } catch (e: Exception) {
            Log.d(TAG, "injectInputEvent not found: ${e.message}")
            null
        }
    }
}