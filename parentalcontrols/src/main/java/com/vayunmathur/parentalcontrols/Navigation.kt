package com.vayunmathur.parentalcontrols

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.parentalcontrols.auth.ParentPin
import com.vayunmathur.parentalcontrols.domain.PinGateTarget
import com.vayunmathur.parentalcontrols.platform.SupervisionViewModel
import com.vayunmathur.parentalcontrols.ui.ExternalRouteEffect
import com.vayunmathur.parentalcontrols.ui.RechallengeEffect
import com.vayunmathur.parentalcontrols.ui.dashboardEntry
import com.vayunmathur.parentalcontrols.ui.gatedEntries
import com.vayunmathur.parentalcontrols.ui.pinGateEntry
import com.vayunmathur.parentalcontrols.ui.pinSetupEntry
import com.vayunmathur.parentalcontrols.ui.rememberInitialBackStack

/**
 * Navigation host for Parental Controls, with the PIN gate as a destination.
 *
 * Gate semantics carry over from the activity-per-screen design unchanged:
 * a gated editor is never on the stack under its gate (verification
 * replaces [Route.PinGate] with the destination, cancel pops or finishes),
 * a fresh install goes through [Route.PinSetup] first and only then the
 * gate, and backgrounding the host after content was verified re-challenges
 * on resume (see the lifecycle observer below). PinGateActivity remains a
 * separate activity for the lock screens, which start it for a result from
 * outside this host; it renders the same [PinGateScreen].
 */
@Composable
fun Navigation(
    viewModel: SupervisionViewModel,
    initialRoute: Route = Route.Dashboard,
    externalRoute: Route? = null,
    onExternalRouteConsumed: () -> Unit = {},
    onFinish: () -> Unit = {},
) {
    val context = LocalContext.current
    val pin = remember(context) { ParentPin.get(context) }
    val scope = rememberCoroutineScope()

    val initialTarget = PinGateTarget.forRoute(initialRoute)
    val backStack = rememberInitialBackStack(initialRoute, initialTarget, pin.isSet())

    // Non-screen continuations for the gate/setup destinations: what a passed
    // gate applies (the pending master-switch value) and which gate a finished
    // setup hands over to. Neither chooses a screen; the routes above do.
    val pinVerified = remember { mutableStateOf(false) }
    val pendingControls = remember { mutableStateOf<Boolean?>(null) }
    val pendingAfterSetup = remember { mutableStateOf(initialTarget) }
    val backgroundedAfterContent = remember { mutableStateOf(false) }

    RechallengeEffect(
        LocalLifecycleOwner.current, pinVerified, backgroundedAfterContent, backStack,
    )
    ExternalRouteEffect(
        externalRoute, backStack, pin, pinVerified, pendingAfterSetup, onExternalRouteConsumed,
    )

    MainNavigation(backStack) {
        dashboardEntry(viewModel, pin, backStack, pinVerified, pendingControls, pendingAfterSetup)
        gatedEntries(viewModel, pinVerified, backStack)
        pinSetupEntry(pin, scope, backStack, pendingAfterSetup, onFinish)
        pinGateEntry(viewModel, pin, scope, backStack, pinVerified, pendingControls, pendingAfterSetup, onFinish)
    }
}
