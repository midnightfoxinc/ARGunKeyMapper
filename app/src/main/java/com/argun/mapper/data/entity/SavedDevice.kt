package com.argun.mapper.data.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A previously seen ARGUN device, so the app can offer to reconnect without
 * scanning again.
 *
 * Keyed by Bluetooth address, which is stable for a given unit — the unit itself
 * is not hardcoded, any AR003 the user has used gets its own row.
 */
@Entity(tableName = "saved_devices")
data class SavedDevice(
    @PrimaryKey val address: String,
    val name: String,
    val lastSeen: Long,
    /** The last ARGUN button the user pressed, for "resume where you left off". */
    val lastButtonTag: String? = null
)