package com.argun.mapper.model


import android.view.KeyEvent
/**
 * ARGUN physical button definitions.
 *
 * Protocol: button events arrive as ASCII strings on fff3 notifications, in the
 * form "B{N}DOWN" / "B{N}UP". The pistol-grip trigger is the exception — see
 * [TRIGGER].
 *
 * Physical mapping measured from a real AR003, not from the vendor's notes. The
 * "D-pad" is actually a free-moving stick, and the four unmarked face buttons
 * form a cross around it:
 *
 *   TRIGGER = pistol grip   (reported as the handshake, not a B-number)
 *   B5      = stick up
 *   B4      = stick right
 *   B7      = stick down
 *   B6      = stick left
 *   B2      = face button, top of cross
 *   B3      = face button, right of cross
 *   B9      = face button, bottom of cross
 *   B8      = face button, left of cross
 *
 * The stick's physical movement is continuous, but the firmware only reports the
 * four cardinals: a diagonal emits nothing at all, and a stick pushed straight in
 * emits nothing either. Those two motions have no payload, so there is nothing to
 * map for them.
 *
 * The face buttons are not labelled ABXY on the gun — the A/B/X/Y names below are
 * this app's own choice, matching the cross layout above.
 */
enum class ArgunButton(
    val tag: String,
    val defaultFunction: String,
    val defaultKeyCode: Int
) {
    B2(
        tag = "B2",
        defaultFunction = "Face Y (cross up)",
        defaultKeyCode = android.view.KeyEvent.KEYCODE_BUTTON_Y
    ),
    B3(
        tag = "B3",
        defaultFunction = "Face B (cross right)",
        defaultKeyCode = android.view.KeyEvent.KEYCODE_BUTTON_B
    ),
    B4(
        tag = "B4",
        defaultFunction = "Stick Right",
        defaultKeyCode = android.view.KeyEvent.KEYCODE_DPAD_RIGHT
    ),
    B5(
        tag = "B5",
        defaultFunction = "Stick Up",
        defaultKeyCode = android.view.KeyEvent.KEYCODE_DPAD_UP
    ),
    B6(
        tag = "B6",
        defaultFunction = "Stick Left",
        defaultKeyCode = android.view.KeyEvent.KEYCODE_DPAD_LEFT
    ),
    B7(
        tag = "B7",
        defaultFunction = "Stick Down",
        defaultKeyCode = android.view.KeyEvent.KEYCODE_DPAD_DOWN
    ),
    B8(
        tag = "B8",
        defaultFunction = "Face A (cross left)",
        defaultKeyCode = android.view.KeyEvent.KEYCODE_BUTTON_A
    ),
    B9(
        tag = "B9",
        defaultFunction = "Face X (cross bottom)",
        defaultKeyCode = android.view.KeyEvent.KEYCODE_BUTTON_X
    ),

    /**
     * The pistol-grip trigger, which has no `B{N}` payload of its own.
     *
     * This gun reports the trigger as the 16-byte handshake `ARGun KeyPressed`
     * followed by an all-zero payload on release, instead of the `B2DOWN`/`B2UP`
     * form every other button uses. It is a pseudo-button: [tag] is deliberately
     * not a `B{N}` name so it can never be confused with a real device button,
     * and no device ever sends this string.
     */
    TRIGGER(
        tag = "TRIGGER",
        defaultFunction = "Trigger",
        defaultKeyCode = android.view.KeyEvent.KEYCODE_BUTTON_A
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