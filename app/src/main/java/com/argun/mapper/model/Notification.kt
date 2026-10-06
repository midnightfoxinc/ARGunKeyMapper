package com.argun.mapper.model

/**
 * Represents a received notification from the ARGUN device.
 */
data class Notification(
    val rawValue: String,
    val parsedEvent: String? = null,
    val isButtonEvent: Boolean = false,
    val isHandshake: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
) : java.io.Serializable