package com.argun.mapper.input

import android.view.KeyEvent

/**
 * How the app is able to deliver key events to other apps.
 *
 * Ordered from least to most capable. The user picks one in Settings; the app
 * detects which ones are actually usable on the current device.
 */
enum class InjectionMode {
    /** No privilege available. Buttons are decoded and shown in-app, nothing else works. */
    NONE,

    /**
     * `INJECT_EVENTS` has been granted (via adb, Shizuku's UI, or root). Events go
     * straight through [android.hardware.input.InputManager].
     */
    PERMISSION,

    /**
     * The device is rooted. Events are dispatched by shelling out to `input`,
     * which does not depend on any Android permission.
     */
    ROOT
}

/**
 * A way of delivering a key event to the system.
 */
interface InjectionStrategy {

    /** Human-readable name shown in the settings screen. */
    val displayName: String

    /**
     * True if this strategy can currently be used. Checked when the strategy is
     * selected and can change at runtime (e.g. root revoked).
     */
    fun isAvailable(): Boolean

    /**
     * Deliver a key event.
     *
     * @param keyCode Android keycode to deliver.
     * @param down true for ACTION_DOWN, false for ACTION_UP.
     * @return true if the event was handed to the system.
     */
    fun inject(keyCode: Int, down: Boolean): Boolean

    /** Short note explaining why the strategy is unavailable, or null if fine. */
    fun unavailableReason(): String? = null
}

/** Result of a delivery attempt, for surfacing status in the UI. */
data class InjectionStatus(
    val mode: InjectionMode,
    val working: Boolean,
    val detail: String
)