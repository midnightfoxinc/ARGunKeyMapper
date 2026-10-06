package com.argun.mapper.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import com.argun.mapper.ui.theme.ArgunMapperTheme
import com.argun.mapper.util.PermissionHelper

class MainActivity : ComponentActivity() {

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val allGranted = granted.values.all { it }
        if (allGranted) pendingScan = true
        else showRationale = true
    }

    private var pendingScan = false
    private var showRationale = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ArgunMapperTheme {
                val vm: MainViewModel = viewModel()
                var showMapping by remember { mutableStateOf(false) }

                ArgunMapperApp(
                    viewModel = vm,
                    onScanRequested = {
                        if (PermissionHelper.hasAllPermissions(this)) {
                            vm.startScan()
                        } else {
                            pendingScan = true
                            requestPermissions.launch(PermissionHelper.requiredPermissions())
                        }
                    },
                    onDeviceSelected = { device ->
                        vm.connect(device)
                        showMapping = true
                    },
                    showMapping = showMapping,
                    onBackToScanner = { showMapping = false },
                    showRationale = showRationale,
                    onRationaleDismissed = {
                        showRationale = false
                        pendingScan = false
                    }
                )

                // Kick off a scan once the permission dialog has been answered.
                if (pendingScan && PermissionHelper.hasAllPermissions(this)) {
                    pendingScan = false
                    vm.startScan()
                }
            }
        }
    }
}