package com.vayunmathur.parentalcontrols.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.parentalcontrols.Route
import com.vayunmathur.parentalcontrols.auth.ParentPin
import com.vayunmathur.parentalcontrols.domain.PinGateTarget

/** The stack a cold start lands on: a gated route starts at its gate (or setup). */
@Composable
internal fun rememberInitialBackStack(
    initialRoute: Route,
    initialTarget: PinGateTarget?,
    pinIsSet: Boolean,
): NavBackStack<Route> = when {
    initialTarget != null && pinIsSet ->
        rememberNavBackStack<Route>(Route.PinGate(initialTarget))
    initialTarget != null ->
        rememberNavBackStack<Route>(Route.PinSetup)
    initialRoute == Route.PinSetup ->
        rememberNavBackStack<Route>(Route.Dashboard, Route.PinSetup)
    initialRoute == Route.Dashboard ->
        rememberNavBackStack<Route>(Route.Dashboard)
    else -> rememberNavBackStack<Route>(Route.Dashboard, initialRoute)
}

/**
 * Re-challenge on return, mirroring the former PinGatedActivity: backgrounding after
 * verified content was shown revokes the verification, and a gated top
 * destination is swapped back to its gate before it can render again.
 */
@Composable
internal fun RechallengeEffect(
    lifecycleOwner: LifecycleOwner,
    pinVerified: MutableState<Boolean>,
    backgroundedAfterContent: MutableState<Boolean>,
    backStack: NavBackStack<Route>,
) {
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE ->
                    if (pinVerified.value) backgroundedAfterContent.value = true
                Lifecycle.Event.ON_RESUME -> if (backgroundedAfterContent.value) {
                    backgroundedAfterContent.value = false
                    pinVerified.value = false
                    val top = backStack.last()
                    PinGateTarget.forRoute(top)?.let { backStack.setLast(Route.PinGate(it)) }
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
}

/** Applies a route handed in from outside the host, through the gate when gated. */
@Composable
internal fun ExternalRouteEffect(
    externalRoute: Route?,
    backStack: NavBackStack<Route>,
    pin: ParentPin,
    pinVerified: MutableState<Boolean>,
    pendingAfterSetup: MutableState<PinGateTarget?>,
    onExternalRouteConsumed: () -> Unit,
) {
    LaunchedEffect(externalRoute) {
        val route = externalRoute ?: return@LaunchedEffect
        when {
            route == Route.Dashboard -> backStack.reset(Route.Dashboard)
            PinGateTarget.forRoute(route) != null ->
                requestGated(backStack, pin, pinVerified, pendingAfterSetup, PinGateTarget.forRoute(route)!!)
            route == Route.PinSetup -> requestSetup(backStack, pendingAfterSetup, null)
            else -> backStack.add(route)
        }
        onExternalRouteConsumed()
    }
}

/** Opens [target] behind its gate, or behind PIN setup when no PIN exists yet. */
internal fun requestGated(
    backStack: NavBackStack<Route>,
    pin: ParentPin,
    pinVerified: MutableState<Boolean>,
    pendingAfterSetup: MutableState<PinGateTarget?>,
    target: PinGateTarget,
) {
    pinVerified.value = false
    if (pin.isSet()) {
        backStack.add(Route.PinGate(target))
    } else {
        pendingAfterSetup.value = target
        backStack.add(Route.PinSetup)
    }
}

/** Opens PIN setup, handing over to [after]'s gate when it completes. */
internal fun requestSetup(
    backStack: NavBackStack<Route>,
    pendingAfterSetup: MutableState<PinGateTarget?>,
    after: PinGateTarget?,
) {
    pendingAfterSetup.value = after
    backStack.add(Route.PinSetup)
}

/** Leaves a gate/setup destination: finishes the host at the graph root. */
internal fun leaveGate(backStack: NavBackStack<Route>, onFinish: () -> Unit) {
    if (backStack.backStack.size <= 1) onFinish() else backStack.pop()
}

/** Redirects an unverified gated entry back to its gate before it can render. */
@Composable
internal fun GateRedirect(pinVerified: Boolean, backStack: NavBackStack<Route>, target: PinGateTarget) {
    if (!pinVerified) {
        LaunchedEffect(Unit) { backStack.setLast(Route.PinGate(target)) }
    }
}
