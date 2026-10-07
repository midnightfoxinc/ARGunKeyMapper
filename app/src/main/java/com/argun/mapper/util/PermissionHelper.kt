package com.argun.mapper.util

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.util.Log

/**
 * Helper for managing Bluetooth and location permissions.
 */
object PermissionHelper {
    private const val TAG = "PermissionHelper"

    fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            arrayOf(
                "android.permission.BLUETOOTH_SCAN",
                "android.permission.BLUETOOTH_CONNECT",
                // HOGP advertises as a peripheral, which is gated separately.
                "android.permission.BLUETOOTH_ADVERTISE"
            )
        }
        else -> {
            arrayOf(
                "android.permission.BLUETOOTH",
                "android.permission.BLUETOOTH_ADMIN",
                "android.permission.ACCESS_FINE_LOCATION"
            )
        }
    }

    fun hasAllPermissions(context: Context): Boolean {
        return requiredPermissions().all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
    }

    fun isBluetoothEnabled(context: Context): Boolean {
        // Touching BluetoothAdapter requires BLUETOOTH_CONNECT on API 31+, so this
        // throws SecurityException if the permission was revoked while running.
        return try {
            val bluetoothManager =
                context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
            bluetoothManager?.adapter?.isEnabled == true
        } catch (e: SecurityException) {
            Log.w(TAG, "BLUETOOTH_CONNECT not held: ${e.message}")
            false
        }
    }

    /**
     * Location must be ON (not merely permitted) for BLE scanning on API 23..30.
     * On API 31+ the scan works without location, so we report true.
     */
    fun isLocationEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return true
        val locationManager =
            context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            locationManager.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                @Suppress("DEPRECATION")
                locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }
}