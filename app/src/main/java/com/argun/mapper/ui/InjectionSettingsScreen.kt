package com.argun.mapper.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.argun.mapper.input.InjectionMode
import com.argun.mapper.input.PrivilegeDetector
import com.argun.mapper.input.PrivilegeStatus

/**
 * Explains how key events will reach the system, and lets the user switch route.
 *
 * This screen exists because injection genuinely depends on device privileges:
 * without one of the routes below the app can decode ARGUN presses but Android will
 * not let it deliver them anywhere.
 */
@Composable
fun InjectionSettingsCard(
    state: MainUiState,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val privileges: PrivilegeStatus = state.privileges

    // Probe once, on first appearance, so the user isn't hit with a root dialog
    // before they've even seen the settings screen.
    LaunchedEffect(state.privileges.rootManagerInstalled) {
        viewModel.refreshInjectionState()
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Input delivery", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(
                "How ARGUN button presses reach your games.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))

            // Current state
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.injectionWorking) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = "Working",
                        tint = Color(0xFF2E7D32),
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = "Not working",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text(state.injectionDetail, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(Modifier.height(12.dp))

            // Routes are always selectable. Disabling them was the bug: the radio went grey
            // and the user had no way to pick a route they had just granted.
            ModeOption(
                label = "Bluetooth HID gamepad (HOGP)",
                description = if (privileges.hogpSupported) {
                    "No permission needed — recommended"
                } else {
                    "Not supported on this device"
                },
                selected = state.injectionMode == InjectionMode.HOGP,
                enabled = true,
                onSelect = { viewModel.setInjectionMode(InjectionMode.HOGP) }
            )
            ModeOption(
                label = "Root (su)",
                description = if (privileges.rootAvailable) "Available" else "Not granted",
                selected = state.injectionMode == InjectionMode.ROOT,
                enabled = true,
                onSelect = { viewModel.setInjectionMode(InjectionMode.ROOT) }
            )
            ModeOption(
                label = "Permission (adb / Shizuku)",
                description = if (privileges.injectEventsGranted) "Granted" else "Not granted",
                selected = state.injectionMode == InjectionMode.PERMISSION,
                enabled = true,
                onSelect = { viewModel.setInjectionMode(InjectionMode.PERMISSION) }
            )
            ModeOption(
                label = "Disabled",
                description = "Decode presses in-app only.",
                selected = state.injectionMode == InjectionMode.NONE,
                enabled = true,
                onSelect = { viewModel.setInjectionMode(InjectionMode.NONE) }
            )

            Spacer(Modifier.height(12.dp))

            // Root needs a live permission grant, which detection alone cannot
            // trigger — the root manager only shows its dialog when `su` is
            // actually executed.
            if (PrivilegeDetector.findSuBinary() != null) {
                Button(
                    onClick = { viewModel.requestRootGrant() },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Grant root access") }
                Spacer(Modifier.height(8.dp))
            }

            Button(
                onClick = { viewModel.enableInjectionAutomatically() },
                modifier = Modifier.fillMaxWidth()
            ) { Text("Enable automatically") }

            Spacer(Modifier.height(16.dp))
            DetectionSummary(privileges, state.injectionMode)
        }
    }
}

@Composable
private fun ModeOption(
    label: String,
    description: String,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
        Spacer(Modifier.width(4.dp))
        Column {
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                }
            )
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DetectionSummary(privileges: PrivilegeStatus, mode: InjectionMode) {
    Column {
        Text("Detected on this device", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        DetectionRow("BLE peripheral (HOGP)", privileges.hogpSupported)
        DetectionRow("Root access", privileges.rootAvailable)
        DetectionRow("Root manager installed", privileges.rootManagerInstalled)
        DetectionRow("Shizuku installed", privileges.shizukuInstalled)
        DetectionRow("Shizuku running", privileges.shizukuRunning)
        DetectionRow("INJECT_EVENTS granted", privileges.injectEventsGranted)

        if (mode == InjectionMode.HOGP && privileges.hogpSupported) {
            // HOGP needs one manual step the app cannot do for the user: pairing.
            Spacer(Modifier.height(12.dp))
            Text(
                "One-time setup: open Settings › Bluetooth, tap \"ARGUN Mapper Gamepad\" " +
                    "to pair, then connect your ARGUN. No root and no adb needed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (!privileges.canBeEnabled) {
            Spacer(Modifier.height(12.dp))
            Text(
                "None of these are available, so button presses will be shown in the app " +
                    "but cannot reach games. To enable: root this device, or connect via adb " +
                    "and run the grant command below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "adb shell pm grant com.argun.mapper android.permission.INJECT_EVENTS",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp)
            )
        } else if (!privileges.rootAvailable && !privileges.injectEventsGranted) {
            Spacer(Modifier.height(12.dp))
            Text(
                if (privileges.shizukuRunning) {
                    "Shizuku is running. Use Shizuku's app list to grant INJECT_EVENTS to " +
                        "ARGUN Mapper, then choose \"Permission\" above."
                } else {
                    "Grant superuser access to ARGUN Mapper in your root manager, then " +
                        "tap \"Enable automatically\"."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DetectionRow(label: String, ok: Boolean) {
    Row(
        modifier = Modifier.padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Text(
            if (ok) "✓" else "✗",
            color = if (ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(20.dp)
        )
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}