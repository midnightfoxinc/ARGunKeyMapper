package com.argun.mapper.ble

import com.argun.mapper.model.ArgunButton

/**
 * HID report descriptor and input-report packing for HOGP mode.
 *
 * The report is a plain 4-byte gamepad input report:
 *
 * ```
 * byte 0   bits 0..3  D-pad hat switch (0=N .. 8=no direction)
 *          bits 4..7  padding
 * byte 1   bits 0..7  eight buttons, B2 -> bit 0 .. B9 -> bit 7
 * byte 2   consumer/aux, unused (zero)
 * byte 3   vendor, unused (zero)
 * ```
 *
 * The D-pad buttons (B4..B8) are reported *both* as hat-switch direction and as
 * ordinary buttons. Most games read one or the other; sending both means neither
 * has to know about the ARGUN's physical layout.
 */
object HidReport {

    /** HID service 0x1812. */
    const val HID_SERVICE_UUID = "00001812-0000-1000-8000-00805f9b34fb"

    /** HID Information characteristic 0x2A4A. */
    const val HID_INFO_UUID = "00002a4a-0000-1000-8000-00805f9b34fb"

    /** Report Map characteristic 0x2A4D. */
    const val REPORT_MAP_UUID = "00002a4d-0000-1000-8000-00805f9b34fb"

    /** Protocol Mode characteristic 0x2A4E. */
    const val PROTOCOL_MODE_UUID = "00002a4e-0000-1000-8000-00805f9b34fb"

    /** HID Control Point characteristic 0x2A4B (suspend/resume exit). */
    const val HID_CONTROL_POINT_UUID = "00002a4b-0000-1000-8000-00805f9b34fb"

    /** Report characteristic 0x2A4D, per the HID spec. */
    const val REPORT_UUID = "00002a4d-0000-1000-8000-00805f9b34fb"

    /** CCCD descriptor. */
    const val CCCD_UUID = "00002902-0000-1000-8000-00805f9b34fb"

    /** Report length in bytes. */
    const val REPORT_SIZE = 4

    /** Protocol mode byte meaning "report protocol" rather than boot keyboard. */
    const val PROTOCOL_MODE_REPORT = 0x01

    /** Hat-switch value used for "no direction" and for D-pad centre. */
    private const val HAT_NONE = 8

    // Usage Page / Usage shorthands for the descriptor below.
    private const val USAGE_PAGE_GENERIC_DESKTOP = 0x01
    private const val USAGE_PAGE_BUTTON = 0x09

    private const val TAG_INPUT = 0x80
    private const val TAG_OUTPUT = 0x90
    private const val TAG_COLLECTION = 0xA0
    private const val TAG_END_COLLECTION = 0xC0

    /**
     * HID report map describing a game pad with eight buttons and a hat switch.
     *
     * No report IDs are used, so the single [REPORT_SIZE]-byte payload in
     * [REPORT_UUID] is the whole input report.
     *
     * Written as ints and narrowed at the end: descriptor tags routinely exceed
     * 0x7F, so a literal `byteArrayOf(0x95, ...)` does not compile.
     */
    val REPORT_MAP: ByteArray = intArrayOf(
        // Usage Page (Generic Desktop), Usage (Game Pad)
        0x05, USAGE_PAGE_GENERIC_DESKTOP, 0x09, 0x05,
        // Collection (Application)
        0xA1, 0x01,

        // --- 8 buttons, 1 bit each ---
        // Usage Page (Button), Usage Min 1, Usage Max 8
        0x05, USAGE_PAGE_BUTTON, 0x19, 0x01, 0x29, 0x08,
        // Logical Min 0, Logical Max 1, Report Size 1, Report Count 8
        0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08,
        // Input (Data, Var, Abs)
        TAG_INPUT, 0x02,

        // --- D-pad hat switch, 4 bits ---
        // Usage Page (Generic Desktop), Usage (Hat Switch)
        0x05, USAGE_PAGE_GENERIC_DESKTOP, 0x09, 0x39,
        // Logical Min 0, Logical Max 8
        0x15, 0x00, 0x25, 0x08,
        // Report Size 4, Report Count 1, Input (Data, Var, Abs)
        0x75, 0x04, 0x95, 0x01, TAG_INPUT, 0x02,

        // --- pad the hat-switch nibble out to a full byte ---
        0x75, 0x04, 0x95, 0x01, TAG_INPUT, 0x01,

        // --- one output byte so hosts that probe for output have one ---
        0x09, 0x92, 0x15, 0x00, 0x26, 0xFF, 0x75, 0x08, 0x95, 0x01,
        TAG_OUTPUT, 0x02,

        // End Collection
        TAG_END_COLLECTION
    ).map { it.toByte() }.toByteArray()

    /** HID Information characteristic value: report mode, no suspend support. */
    val HID_INFORMATION: ByteArray = byteArrayOf(0x01, 0x00, 0x01, 0x00)

    /** Protocol Mode characteristic value: report protocol. */
    val PROTOCOL_MODE_VALUE: ByteArray = byteArrayOf(PROTOCOL_MODE_REPORT.toByte())

    /**
     * Bit position for a button inside the buttons byte of the report.
     *
     * B2 (trigger) is bit 0 through B9 at bit 7, matching the physical layout.
     */
    private fun buttonBit(button: ArgunButton): Int = button.ordinal

    /** Hat-switch direction for a D-pad button, or null if it is not a direction. */
    private fun hatFor(button: ArgunButton): Int? = when (button) {
        ArgunButton.B4 -> 0 // North
        ArgunButton.B5 -> 2 // East
        ArgunButton.B6 -> 4 // South
        ArgunButton.B7 -> 6 // West
        else -> null
    }

    /**
     * Packs the currently held buttons into a report.
     *
     * @param held the set of buttons currently down; empty means "all released".
     */
    fun buildReport(held: Set<ArgunButton>): ByteArray {
        val report = ByteArray(REPORT_SIZE)

        var buttons = 0
        held.forEach { buttons = buttons or (1 shl buttonBit(it)) }
        report[1] = buttons.toByte()

        // The hat switch can only carry one direction. D-pad centre and diagonals
        // have no direction of their own, so priority order decides; anything that
        // is not a cardinal direction falls back to "no direction".
        val hat = held.asSequence()
            .mapNotNull { hatFor(it) }
            .firstOrNull() ?: HAT_NONE
        report[0] = hat.toByte()

        return report
    }

    /** A report with everything released, sent on connect so the host starts clean. */
    fun idleReport(): ByteArray = buildReport(emptySet())
}