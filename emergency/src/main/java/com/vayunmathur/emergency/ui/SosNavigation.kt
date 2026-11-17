package com.vayunmathur.emergency.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.emergency.Route
import com.vayunmathur.emergency.platform.SosCoordinator
import com.vayunmathur.library.util.FullscreenPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack

/** The one-destination SOS host inside SosActivity. */
@Composable
fun SosNavigation(coordinator: SosCoordinator) {
    val backStack = rememberNavBackStack<Route>(Route.Sos)
    MainNavigation(backStack) {
        entry<Route.Sos>(metadata = FullscreenPage()) {
            val state by coordinator.state.collectAsStateWithLifecycle()
            SosScreen(state = state, actions = coordinator)
        }
    }
}
