package com.vayunmathur.games.kart

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vayunmathur.games.kart.platform.KartViewModel
import com.vayunmathur.games.kart.platform.RacePhase
import com.vayunmathur.games.kart.ui.KartGamePage
import com.vayunmathur.library.util.FullscreenPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.rememberNavBackStack

@Composable
fun Navigation(viewModel: KartViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Lobby)
    KartRouteEffect(backStack, viewModel)
    MainNavigation(backStack) {
        entry<Route.Lobby>(metadata = FullscreenPage()) { KartGamePage(viewModel) }
        entry<Route.Race>(metadata = FullscreenPage()) { KartGamePage(viewModel) }
        entry<Route.Results>(metadata = FullscreenPage()) { KartGamePage(viewModel) }
    }
}

/**
 * Keeps the stack in step with the race exactly while a race runs.
 *
 * [KartViewModel.racePhase] stays the race engine's state; this effect
 * projects it onto destinations: Lobby is the root, Countdown/Racing
 * show [Route.Race] (the countdown renders inside it), and Results
 * replaces Race so Back from the results lands on the lobby instead of
 * a finished race.
 */
@Composable
private fun KartRouteEffect(backStack: NavBackStack<Route>, viewModel: KartViewModel) {
    val phase by viewModel.racePhase.collectAsState()
    val top = backStack.backStack.lastOrNull()
    LaunchedEffect(phase, top) {
        syncKartRoute(backStack, phase)
    }
}

/**
 * The phase-to-stack projection behind [KartRouteEffect], kept free of
 * composition so unit tests can drive it with a plain [NavBackStack].
 * Idempotent: a phase whose destination is already on top leaves the
 * stack untouched.
 */
internal fun syncKartRoute(backStack: NavBackStack<Route>, phase: RacePhase) {
    val top = backStack.backStack.lastOrNull()
    when (phase) {
        is RacePhase.Lobby -> if (top != Route.Lobby) backStack.reset(Route.Lobby)
        is RacePhase.Countdown, is RacePhase.Racing -> when (top) {
            Route.Race -> Unit
            Route.Results -> backStack.setLast(Route.Race)
            else -> backStack.add(Route.Race)
        }
        is RacePhase.Results -> when (top) {
            Route.Results -> Unit
            Route.Race -> backStack.setLast(Route.Results)
            else -> backStack.add(Route.Results)
        }
    }
}
