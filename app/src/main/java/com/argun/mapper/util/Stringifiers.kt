package com.argun.mapper.util

/**
 * Utility functions for formatting strings in the app.
 */
object Stringifiers {

    fun formatAddress(address: String): String {
        // Bluetooth addresses arrive already colon-separated (AA:BB:CC:DD:EE:FF).
        // Normalise the case and uppercase for consistent display.
        return if (address.isNotBlank()) address.uppercase() else address
    }

    fun formatRssi(rssi: Int): String {
        return when {
            rssi >= -50 -> "Excellent ($rssi dBm)"
            rssi >= -70 -> "Good ($rssi dBm)"
            rssi >= -80 -> "Fair ($rssi dBm)"
            else -> "Weak ($rssi dBm)"
        }
    }

    fun formatConnectionDuration(startTime: Long): String {
        val seconds = ((System.currentTimeMillis() - startTime) / 1000).toInt()
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) {
            "%02d:%02d:%02d".format(hours, minutes, secs)
        } else {
            "%02d:%02d".format(minutes, secs)
        }
    }

    fun keyCodeToName(keyCode: Int): String {
        return when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_UP -> "DPAD Up"
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> "DPAD Down"
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> "DPAD Left"
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> "DPAD Right"
            android.view.KeyEvent.KEYCODE_DPAD_CENTER -> "DPAD Center"
            android.view.KeyEvent.KEYCODE_BUTTON_A -> "Button A"
            android.view.KeyEvent.KEYCODE_BUTTON_B -> "Button B"
            android.view.KeyEvent.KEYCODE_BUTTON_X -> "Button X"
            android.view.KeyEvent.KEYCODE_BUTTON_Y -> "Button Y"
            android.view.KeyEvent.KEYCODE_HOME -> "Home"
            android.view.KeyEvent.KEYCODE_BACK -> "Back"
            android.view.KeyEvent.KEYCODE_MENU -> "Menu"
            android.view.KeyEvent.KEYCODE_VOLUME_UP -> "Volume Up"
            android.view.KeyEvent.KEYCODE_VOLUME_DOWN -> "Volume Down"
            else -> "Key $keyCode"
        }
    }
}