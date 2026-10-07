package com.argun.mapper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.argun.mapper.model.ArgunButton
import com.argun.mapper.model.ArgunDevice

/**
 * Root composable. Chooses between the scanner and the mapping screen.
 */
@Composable
fun ArgunMapperApp(
    viewModel: MainViewModel,
    onScanRequested: () -> Unit,
    onDeviceSelected: (ArgunDevice) -> Unit,
    onReconnectSaved: () -> Unit,
    onForgetDevice: (String) -> Unit,
    showMapping: Boolean,
    onBackToScanner: () -> Unit,
    showRationale: Boolean,
    onRationaleDismissed: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()

    if (showRationale) {
        AlertDialog(
            onDismissRequest = onRationaleDismissed,
            title = { Text("Permission required") },
            text = { Text("ARGUN Mapper needs Bluetooth permission to find and connect to your ARGUN.") },
            confirmButton = {
                TextButton(onClick = onRationaleDismissed) { Text("OK") }
            }
        )
    }

    if (showMapping) {
        MappingScreen(
            state = state,
            viewModel = viewModel,
            onBack = onBackToScanner
        )
    } else {
        ConnectScreen(
            state = state,
            onScan = onScanRequested,
            onDeviceSelected = onDeviceSelected,
            onReconnectSaved = onReconnectSaved,
            onForgetDevice = onForgetDevice
        )
    }
}

@Composable
private fun ConnectScreen(
    state: MainUiState,
    onScan: () -> Unit,
    onDeviceSelected: (ArgunDevice) -> Unit,
    onReconnectSaved: () -> Unit,
    onForgetDevice: (String) -> Unit
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "ARGUN Mapper",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Map your ARGUN to any Android game",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(32.dp))

            if (state.scanning) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
            }

            if (state.devices.isEmpty()) {
                if (state.savedDevices.isNotEmpty()) {
                    SavedDevicesList(
                        devices = state.savedDevices,
                        onReconnect = onReconnectSaved,
                        onForget = onForgetDevice
                    )
                    Spacer(Modifier.height(16.dp))
                }
                Button(onClick = onScan, enabled = !state.scanning) {
                    Text(if (state.scanning) "Scanning…" else "Scan for ARGUN")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.devices, key = { it.address }) { device ->
                        DeviceRow(device = device, onClick = { onDeviceSelected(device) })
                    }
                }
            }

            if (state.statusMessage.isNotBlank()) {
                Spacer(Modifier.height(24.dp))
                Text(
                    text = state.statusMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = if (state.bluetoothAvailable) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            }
        }
    }
}

@Composable
private fun DeviceRow(device: ArgunDevice, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(
                        if (device.rssi > -60) Color(0xFF4CAF50) else Color(0xFFFF9800),
                        CircleShape
                    )
            )
            Spacer(Modifier.size(16.dp))
            Column {
                Text(device.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${device.address} · ${device.rssi} dBm",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun MappingScreen(
    state: MainUiState,
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    var editing by remember { mutableStateOf<ArgunButton?>(null) }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Button Mapping",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = state.connectedDevice?.let { "Connected to ${it.name}" } ?: "Not connected",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
            }

            items(ArgunButton.values().toList(), key = { it.tag }) { button ->
                MappingRow(
                    button = button,
                    mappedName = viewModel.keyNameFor(button),
                    isLastPressed = state.lastPressedButton == button,
                    onEdit = { editing = button },
                    onTest = { viewModel.testButton(button) }
                )
            }

            item {
                Spacer(Modifier.height(16.dp))
                InjectionSettingsCard(state = state, viewModel = viewModel)
            }

            item {
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = viewModel::restoreDefaults,
                        modifier = Modifier.weight(1f)
                    ) { Text("Restore Defaults") }

                    if (state.connectedDevice != null) {
                        Button(
                            onClick = viewModel::disconnect,
                            modifier = Modifier.weight(1f)
                        ) { Text("Disconnect") }
                    } else {
                        Button(onClick = onBack, modifier = Modifier.weight(1f)) {
                            Text("Back")
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    editing?.let { button ->
        KeyPickerDialog(
            button = button,
            onDismiss = { editing = null },
            onSelect = { code, name ->
                viewModel.setMapping(button, code, name)
                editing = null
            }
        )
    }
}

@Composable
private fun MappingRow(
    button: ArgunButton,
    mappedName: String,
    isLastPressed: Boolean,
    onEdit: () -> Unit,
    onTest: () -> Unit
) {
    Card(
        onClick = onEdit,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isLastPressed) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = button.tag,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.size(width = 40.dp, height = 24.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(button.defaultFunction, style = MaterialTheme.typography.titleSmall)
                Text(
                    "→ $mappedName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (isLastPressed) {
                Text(
                    "pressed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = onTest,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text("Test", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun SavedDevicesList(
    devices: List<com.argun.mapper.data.entity.SavedDevice>,
    onReconnect: () -> Unit,
    onForget: (String) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            "Previously used",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        devices.forEach { saved ->
            Card(
                onClick = onReconnect,
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(saved.name, style = MaterialTheme.typography.titleSmall)
                        Text(
                            saved.address,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    TextButton(onClick = { onForget(saved.address) }) {
                        Text("Forget", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}