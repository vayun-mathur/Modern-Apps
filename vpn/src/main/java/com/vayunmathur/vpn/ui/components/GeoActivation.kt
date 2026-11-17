package com.vayunmathur.vpn.ui.components

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.vpn.data.VpnConfig
import com.vayunmathur.vpn.platform.VpnViewModel

/**
 * Remembers a `(VpnConfig) -> Unit` that activates a server through the app's
 * existing tunnel path ([VpnViewModel.startVpn]), running the system VPN
 * consent flow first when needed and retrying the same config after a grant —
 * the same pattern the tunnel list uses.
 */
@Composable
fun rememberServerActivation(vm: VpnViewModel): (VpnConfig) -> Unit {
    val context = LocalContext.current
    val activity = context as? Activity
    var pendingConfig by remember { mutableStateOf<VpnConfig?>(null) }
    lateinit var launchPermission: (Intent) -> Unit
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val granted = pendingConfig
        pendingConfig = null
        if (result.resultCode == Activity.RESULT_OK && granted != null) {
            vm.startVpn(granted, launchPermission)
        } else {
            vm.onVpnPermissionDenied()
        }
    }
    launchPermission = permissionLauncher::launch
    return remember(vm, activity) {
        { config: VpnConfig ->
            if (activity != null) {
                pendingConfig = config
                vm.startVpn(config, permissionLauncher::launch)
            }
        }
    }
}
