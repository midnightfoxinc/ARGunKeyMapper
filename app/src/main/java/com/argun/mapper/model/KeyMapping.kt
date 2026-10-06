package com.argun.mapper.model

import android.view.KeyEvent

/**
 * Represents a user-defined mapping from an ARGUN button to a phone key.
 */
data class KeyMapping(
    val argunButton: String,
    val phoneKeyCode: Int,
    val phoneKeyName: String,
    val action: Int = KeyEvent.ACTION_DOWN
) : java.io.Serializable