package com.vayunmathur.communicate.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vayunmathur.communicate.Route
import com.vayunmathur.communicate.data.call.InAppCallPhase
import com.vayunmathur.communicate.data.call.InAppCallRegistry
import com.vayunmathur.library.util.NavBackStack

/**
 * Keeps [Route.Call] on top of the stack exactly while a call is live.
 *
 * [InAppCallRegistry] stays the session engine: lines bind, ring, connect
 * and end without touching navigation, and this effect projects its phase
 * onto the stack. Leaving Idle pushes the call screen over whatever the
 * user was doing; returning to Idle pops it and reveals that screen again.
 * A back press that pops the call screen mid-call is undone by the same
 * projection (the phase is still live), so the call UI cannot be lost
 * while the call itself continues.
 */
@Composable
fun CallRouteEffect(backStack: NavBackStack<Route>) {
    val inAppCallState by InAppCallRegistry.state.collectAsState()
    val top = backStack.backStack.lastOrNull()
    LaunchedEffect(inAppCallState.phase, top) {
        syncCallRoute(backStack, inAppCallState.phase)
    }
}

/**
 * The phase-to-stack projection behind [CallRouteEffect], kept free of
 * composition so unit tests can drive it with a plain [NavBackStack].
 *
 * Idempotent: a live phase with the call already on top, and Idle with no
 * call on top, both leave the stack untouched.
 */
internal fun syncCallRoute(backStack: NavBackStack<Route>, phase: InAppCallPhase) {
    val top = backStack.backStack.lastOrNull()
    if (phase != InAppCallPhase.Idle) {
        when {
            top is Route.Call -> Unit
            // A deep link pushed over the call: bring the existing call
            // destination back on top instead of stacking a second one.
            backStack.backStack.any { it is Route.Call } -> backStack.reset(
                *backStack.backStack.filterNot { it is Route.Call }.toTypedArray(),
                Route.Call,
            )
            else -> backStack.add(Route.Call)
        }
    } else if (top is Route.Call) {
        backStack.pop()
    }
}
