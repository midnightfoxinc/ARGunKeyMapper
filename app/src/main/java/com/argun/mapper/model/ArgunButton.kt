package com.argun.mapper.model


import android.view.KeyEvent
/**
 * ARGUN physical button definitions.
 *
 * Protocol: Button events are sent as ASCII strings via fff3 notifications.
 * Format: "B{N}DOWN" / "B{N}UP" where N is the button number (2-9).
 *
 * Default physical mapping:
 *   B2 = Trigger
 *   B3 = Game Button 2
 *   B4 = DPAD Up
 *   B5 = DPAD Right
 *   B6 = DPAD Down
 *   B7 = DPAD Left
 *   B8 = DPAD Push (Center)
 *   B9 = Game Button 3
 */
enum class ArgunButton(
    val tag: String,
    val defaultFunction: String,
    val defaultKeyCode: Int
) {
    B2(
        tag = "B2",
        defaultFunction = "Trigger",
        defaultKeyCode = android.view.KeyEvent.KEYCODE_BUTTON_A
    ),
    B3(
        tag = "B3",
        defaultFunction = "Game Button 2",
        defaultKeyCode = android.view.KeyEvent.KEYCODE_BUTTON_B
    ),
    B4(
        tag = "B4",
        defaultFunction = "DPAD Up",
        defaultKeyCode = android.view.KeyEvent.KEYCODE_DPAD_UP
    ),
    B5(
        tag = "B5",
        defaultFunction = "DPAD Right",
        defaultKeyCode = android.view.KeyEvent.KEYCODE_DPAD_RIGHT
    ),
    B6(
        tag = "B6",
        defaultFunction = "DPAD Down",
        defaultKeyCode = android.view.KeyEvent.KEYCODE_DPAD_DOWN
    ),
    B7(
        tag = "B7",
        defaultFunction = "DPAD Left",
        defaultKeyCode = android.view.KeyEvent.KEYCODE_DPAD_LEFT
    ),
    B8(
        tag = "B8",
        defaultFunction = "DPAD Push",
        defaultKeyCode = android.view.KeyEvent.KEYCODE_DPAD_CENTER
    ),
    B9(
        tag = "B9",
        defaultFunction = "Game Button 3",
        defaultKeyCode = android.view.KeyEvent.KEYCODE_BUTTON_X
    );

    companion object {
        fun fromTag(tag: String): ArgunButton? = values().find { it.tag == tag }

        /** Parse a button event string like "B2DOWN" into a button and action. */
        fun parseEvent(event: String): Pair<ArgunButton, Boolean>? {
            if (event.length < 4) return null
            val buttonTag = event.substring(0, 2)
            val action = event.substring(2)
            val button = fromTag(buttonTag) ?: return null
            val isDown = action == "DOWN"
            return button to isDown
        }
    }
}