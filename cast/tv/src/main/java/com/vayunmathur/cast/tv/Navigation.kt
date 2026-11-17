package com.vayunmathur.cast.tv

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.vayunmathur.cast.tv.platform.ReceiverUiState
import com.vayunmathur.cast.tv.platform.receiverRouteFor
import com.vayunmathur.cast.tv.ui.ReceiverContent
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack

/**
 * The receiver's one-deep navigation host.
 *
 * The controller's phase is projected to a [Route] and the stack is reset
 * to that single destination whenever it changes: receiver states replace
 * each other rather than stacking, so there is deliberately no back
 * behaviour here. What the graph buys over the old `when (phase)` is typed
 * destination arguments carried with the destination itself, and restore —
 * a recreated Activity re-enters the destination the phase names, not a
 * hard-coded starting screen.
 *
 * `Mirroring` never reaches this host as a destination: the picture is
 * `MirrorActivity`, and [receiverRouteFor] keeps [Route.Connected]
 * underneath it.
 */
@Composable
fun Navigation(state: ReceiverUiState, modifier: Modifier = Modifier) {
    val target = receiverRouteFor(state.phase)
    val backStack = rememberNavBackStack<Route>(target)
    // The phase drives the destination. A mismatch also covers recreation,
    // where the retained stack may hold yesterday's phase.
    LaunchedEffect(target) {
        if (backStack.backStack.lastOrNull() != target) backStack.reset(target)
    }
    MainNavigation(backStack, containerColor = Color.Transparent) {
        entry<Route.Starting> { route ->
            ReceiverContent(route, state, modifier)
        }
        entry<Route.Ready> { route ->
            ReceiverContent(route, state, modifier)
        }
        entry<Route.Pairing> { route ->
            ReceiverContent(route, state, modifier)
        }
        entry<Route.Connected> { route ->
            ReceiverContent(route, state, modifier)
        }
        entry<Route.Failed> { route ->
            ReceiverContent(route, state, modifier)
        }
    }
}
