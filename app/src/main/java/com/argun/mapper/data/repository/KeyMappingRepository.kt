package com.argun.mapper.data.repository

import android.util.Log
import android.view.KeyEvent
import com.argun.mapper.data.dao.ButtonBindingDao
import com.argun.mapper.data.dao.SavedDeviceDao
import com.argun.mapper.data.entity.ButtonBinding
import com.argun.mapper.data.entity.SavedDevice
import com.argun.mapper.model.ArgunDevice
import com.argun.mapper.model.KeyMapping
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Stores and retrieves ARGUN button -> phone key mappings.
 */
class KeyMappingRepository(
    private val dao: ButtonBindingDao,
    private val deviceDao: SavedDeviceDao
) {

    /** All persisted mappings, keyed by ARGUN button tag (e.g. "B4"). */
    val allBindings: Flow<List<KeyMapping>> =
        dao.getAllBindings().map { list -> list.map { it.toMapping() } }

    suspend fun getKeyCode(button: String): Int? = dao.getKeyCodeFor(button)

    suspend fun getMapping(button: String): KeyMapping? =
        dao.getBinding(button)?.toMapping()

    suspend fun insert(mapping: KeyMapping) = dao.insert(mapping.toEntity())

    /**
     * Upserts by primary key; [insert] already REPLACEs, so this is the
     * single call sites should use.
     */
    suspend fun upsert(mapping: KeyMapping) = dao.insert(mapping.toEntity())

    suspend fun clearAll() = dao.clearAll()

    suspend fun saveDefaultMappings() {
        clearAll()
        DEFAULTS.forEach { (button, mapping) ->
            insert(mapping)
        }
        Log.d(TAG, "Saved ${DEFAULTS.size} default mappings")
    }

    private fun ButtonBinding.toMapping() = KeyMapping(
        argunButton = argunButton,
        phoneKeyCode = phoneKeyCode,
        phoneKeyName = phoneKeyName,
        action = action
    )

    private fun KeyMapping.toEntity() = ButtonBinding(
        argunButton = argunButton,
        phoneKeyCode = phoneKeyCode,
        phoneKeyName = phoneKeyName,
        action = action
    )

    /** Devices the user has connected to before. */
    val savedDevices: Flow<List<SavedDevice>> = deviceDao.getAll()

    suspend fun mostRecentDevice(): SavedDevice? = deviceDao.getMostRecent()

    suspend fun rememberDevice(device: ArgunDevice) {
        deviceDao.upsert(
            SavedDevice(
                address = device.address,
                name = device.name,
                lastSeen = System.currentTimeMillis()
            )
        )
    }

    suspend fun forgetDevice(address: String) = deviceDao.forget(address)

    suspend fun forgetAllDevices() = deviceDao.forgetAll()

    companion object {
        private const val TAG = "KeyMappingRepo"

        /**
         * Factory defaults, measured from a real AR003.
         *
         * The stick is not a D-pad and the face buttons form an unlabelled cross,
         * so the vendor's B2..B9 ordering does not match the physical layout — B5
         * is stick up and B4 is stick right, and the cross reads B2 up, B3 right,
         * B9 bottom, B8 left. The trigger has no B-number at all; it arrives as a
         * handshake string, so it gets its own pseudo-button.
         *
         * Users can override any of these from the mapping screen.
         */
        val DEFAULTS: Map<String, KeyMapping> = mapOf(
            "B2" to KeyMapping("B2", KeyEvent.KEYCODE_BUTTON_Y, "Face Y (cross up)"),
            "B3" to KeyMapping("B3", KeyEvent.KEYCODE_BUTTON_B, "Face B (cross right)"),
            "B4" to KeyMapping("B4", KeyEvent.KEYCODE_DPAD_RIGHT, "Stick Right"),
            "B5" to KeyMapping("B5", KeyEvent.KEYCODE_DPAD_UP, "Stick Up"),
            "B6" to KeyMapping("B6", KeyEvent.KEYCODE_DPAD_LEFT, "Stick Left"),
            "B7" to KeyMapping("B7", KeyEvent.KEYCODE_DPAD_DOWN, "Stick Down"),
            "B8" to KeyMapping("B8", KeyEvent.KEYCODE_BUTTON_A, "Face A (cross left)"),
            "B9" to KeyMapping("B9", KeyEvent.KEYCODE_BUTTON_X, "Face X (cross bottom)"),
            "TRIGGER" to KeyMapping("TRIGGER", KeyEvent.KEYCODE_BUTTON_A, "Trigger (A)")
        )
    }
}