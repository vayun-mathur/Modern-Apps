package com.vayunmathur.auto

import android.content.Intent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.vayunmathur.auto.network.UsbConnector
import com.vayunmathur.auto.platform.AutoViewModel
import com.vayunmathur.auto.platform.MaosRoleStatus
import com.vayunmathur.auto.platform.PairingViewModel
import com.vayunmathur.auto.platform.TransportState
import com.vayunmathur.auto.service.ProjectionService
import com.vayunmathur.library.ui.MaActivity

@androidx.car.app.annotations.ExperimentalCarApi
class MainActivity : MaActivity() {
    private val viewModel: AutoViewModel by viewModels()
    private val pairingViewModel: PairingViewModel by viewModels()

    override fun onCreateBeforeContent() {
        // Start listening as soon as the app is opened. A head unit can attach at any time
        // and the service is what holds the socket.
        ProjectionService.start(this)
        // The role read is platform state, not session state: seed it at launch so the
        // pairing screen renders the MAOS capabilities without waiting for a session.
        // BootReceiver re-seeds it after reboot.
        TransportState.publishRole(MaosRoleStatus.isProjectionRoleHeld(this))
        // A plug-in launches the app through the accessory filter; the USB bring-up
        // starts from that same intent.
        intent?.let { UsbConnector.onAccessoryIntent(this, it) }
    }

    @Composable
    override fun Content() {
        Navigation(viewModel, pairingViewModel)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Single-top relaunch on plug-in while already open: same USB entry point.
        UsbConnector.onAccessoryIntent(this, intent)
    }
}
