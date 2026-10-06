package com.argun.mapper.data.repository

import android.util.Log
import android.view.KeyEvent
import com.argun.mapper.data.dao.ButtonBindingDao
import com.argun.mapper.data.entity.ButtonBinding
import com.argun.mapper.model.KeyMapping
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Stores and retrieves ARGUN button -> phone key mappings.
 */
class KeyMappingRepository(private val dao: ButtonBindingDao) {

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

    companion object {
        private const val TAG = "KeyMappingRepo"

        /**
         * Factory defaults, matching the physical layout of the ARGUN.
         * Users can override any of these from the mapping screen.
         */
        val DEFAULTS: Map<String, KeyMapping> = mapOf(
            "B2" to KeyMapping("B2", KeyEvent.KEYCODE_BUTTON_A, "Trigger (A)"),
            "B3" to KeyMapping("B3", KeyEvent.KEYCODE_BUTTON_B, "Game Button 2 (B)"),
            "B4" to KeyMapping("B4", KeyEvent.KEYCODE_DPAD_UP, "DPAD Up"),
            "B5" to KeyMapping("B5", KeyEvent.KEYCODE_DPAD_RIGHT, "DPAD Right"),
            "B6" to KeyMapping("B6", KeyEvent.KEYCODE_DPAD_DOWN, "DPAD Down"),
            "B7" to KeyMapping("B7", KeyEvent.KEYCODE_DPAD_LEFT, "DPAD Left"),
            "B8" to KeyMapping("B8", KeyEvent.KEYCODE_DPAD_CENTER, "DPAD Center"),
            "B9" to KeyMapping("B9", KeyEvent.KEYCODE_BUTTON_X, "Game Button 3 (X)")
        )
    }
}