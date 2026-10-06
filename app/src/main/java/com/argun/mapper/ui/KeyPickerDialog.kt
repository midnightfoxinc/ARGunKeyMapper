package com.argun.mapper.ui

import android.view.KeyEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.argun.mapper.model.ArgunButton

/**
 * A selectable Android key the ARGUN button can be mapped to.
 */
private data class KeyOption(
    val keyCode: Int,
    val label: String,
    val group: String
)

/**
 * Keys worth offering: D-pad, gamepad face buttons, shoulders, system/nav.
 * Ordered by group so the list reads naturally.
 */
private val KEY_OPTIONS: List<KeyOption> = listOf(
    // D-Pad
    KeyOption(KeyEvent.KEYCODE_DPAD_UP, "DPAD Up", "D-Pad"),
    KeyOption(KeyEvent.KEYCODE_DPAD_DOWN, "DPAD Down", "D-Pad"),
    KeyOption(KeyEvent.KEYCODE_DPAD_LEFT, "DPAD Left", "D-Pad"),
    KeyOption(KeyEvent.KEYCODE_DPAD_RIGHT, "DPAD Right", "D-Pad"),
    KeyOption(KeyEvent.KEYCODE_DPAD_CENTER, "DPAD Center", "D-Pad"),

    // Gamepad face buttons
    KeyOption(KeyEvent.KEYCODE_BUTTON_A, "Button A", "Game Buttons"),
    KeyOption(KeyEvent.KEYCODE_BUTTON_B, "Button B", "Game Buttons"),
    KeyOption(KeyEvent.KEYCODE_BUTTON_X, "Button X", "Game Buttons"),
    KeyOption(KeyEvent.KEYCODE_BUTTON_Y, "Button Y", "Game Buttons"),
    KeyOption(KeyEvent.KEYCODE_BUTTON_L1, "Button L1", "Game Buttons"),
    KeyOption(KeyEvent.KEYCODE_BUTTON_R1, "Button R1", "Game Buttons"),
    KeyOption(KeyEvent.KEYCODE_BUTTON_L2, "Button L2", "Game Buttons"),
    KeyOption(KeyEvent.KEYCODE_BUTTON_R2, "Button R2", "Game Buttons"),
    KeyOption(KeyEvent.KEYCODE_BUTTON_THUMBL, "Button L3 (Thumb)", "Game Buttons"),
    KeyOption(KeyEvent.KEYCODE_BUTTON_THUMBR, "Button R3 (Thumb)", "Game Buttons"),
    KeyOption(KeyEvent.KEYCODE_BUTTON_START, "Button Start", "Game Buttons"),
    KeyOption(KeyEvent.KEYCODE_BUTTON_SELECT, "Button Select", "Game Buttons"),
    KeyOption(KeyEvent.KEYCODE_BUTTON_MODE, "Button Mode", "Game Buttons"),

    // Navigation / system
    KeyOption(KeyEvent.KEYCODE_ENTER, "Enter", "Navigation"),
    KeyOption(KeyEvent.KEYCODE_BACK, "Back", "Navigation"),
    KeyOption(KeyEvent.KEYCODE_HOME, "Home", "Navigation"),
    KeyOption(KeyEvent.KEYCODE_MENU, "Menu", "Navigation"),
    KeyOption(KeyEvent.KEYCODE_SEARCH, "Search", "Navigation"),
    KeyOption(KeyEvent.KEYCODE_TAB, "Tab", "Navigation"),
    KeyOption(KeyEvent.KEYCODE_SPACE, "Space", "Navigation"),
    KeyOption(KeyEvent.KEYCODE_ESCAPE, "Escape", "Navigation")
)

/**
 * Lets the user pick which Android key an ARGUN button should emit.
 */
@Composable
fun KeyPickerDialog(
    button: ArgunButton,
    onDismiss: () -> Unit,
    onSelect: (keyCode: Int, keyName: String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query) {
        if (query.isBlank()) {
            KEY_OPTIONS
        } else {
            KEY_OPTIONS.filter {
                it.label.contains(query, ignoreCase = true) ||
                    it.group.contains(query, ignoreCase = true)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Map ${button.tag} (${button.defaultFunction})") },
        text = {
            Column {
                androidx.compose.material3.OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search keys") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
                ) {
                    val groups = filtered.groupBy { it.group }
                    groups.forEach { (group, options) ->
                        item(key = "header-$group") {
                            Text(
                                text = group,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        items(options, key = { it.keyCode }) { option ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSelect(option.keyCode, option.label) }
                                    .padding(vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(option.label, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = option.keyCode.toString(),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        item(key = "divider-$group") { HorizontalDivider() }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}