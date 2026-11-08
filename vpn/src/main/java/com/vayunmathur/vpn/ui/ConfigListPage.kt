package com.vayunmathur.vpn.ui

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.ui.SnackbarHostState
import com.vayunmathur.vpn.R
import com.vayunmathur.vpn.Route
import com.vayunmathur.vpn.data.VpnConfig
import com.vayunmathur.vpn.service.VpnTunnelService
import com.vayunmathur.vpn.platform.VpnViewModel
import kotlinx.coroutines.launch

@Composable
fun ConfigListPage(backStack: NavBackStack<Route>, vm: VpnViewModel) {
    val configs by vm.configs.collectAsState()
    val connectingId by vm.connectingId.collectAsState()
    val status by vm.status.collectAsState()
    val context = LocalContext.current
    val activity = context as? Activity
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val needActivityMsg = stringResource(R.string.need_activity_to_grant_vpn_permission)

    // System VPN consent runs through the Activity Result API: when the user
    // grants permission the same config is retried, which then connects
    // directly (previously the grant went nowhere and the user had to tap
    // Connect a second time).
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
    // The only way to add a tunnel is opening a .conf file via SAF.
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) vm.importFromUri(context, uri)
    }

    LaunchedEffect(status) {
        status?.let { snackbar.showSnackbar(it); vm.clearStatus() }
    }

    ConfigListContent(
        configs = configs,
        connectingId = connectingId,
        // A tunnel only counts as connected once the service is actually up; between the tap
        // and that point the row shows the spinner instead.
        activeId = connectingId?.takeIf { VpnTunnelService.isRunning },
        snackbar = snackbar,
        // Open a .conf file — only import option per user request.
        onImport = { filePicker.launch(arrayOf("*/*")) },
        onToggleConnect = { cfg ->
            if (connectingId != null && VpnTunnelService.isRunning) {
                vm.stopVpn()
            } else {
                if (activity != null) {
                    pendingConfig = cfg
                    vm.startVpn(cfg, permissionLauncher::launch)
                } else scope.launch {
                    snackbar.showSnackbar(needActivityMsg)
                }
            }
        },
        onOpen = { cfg ->
            if (backStack.last() is Route.Detail) backStack.setLast(Route.Detail(cfg.id))
            else backStack.add(Route.Detail(cfg.id))
        },
        onDelete = { cfg -> vm.delete(cfg) },
    )
}
