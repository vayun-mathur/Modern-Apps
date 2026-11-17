package com.vayunmathur.emergency

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.emergency.platform.EmergencyViewModel
import com.vayunmathur.emergency.ui.EditInfoPage
import com.vayunmathur.emergency.ui.ViewInfoScreen
import com.vayunmathur.library.util.DetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack

/**
 * Navigation host for the info surfaces: view and edit.
 *
 * The SOS countdown is not hosted here: it keeps its own activity (see
 * SosActivity in the manifest) and renders through [com.vayunmathur.emergency.ui.SosNavigation], so the
 * shared graph has all three destinations without the countdown inheriting
 * this host's task or the host inheriting the countdown's lock-screen,
 * single-instance, no-history semantics.
 */
@Composable
fun Navigation(
    viewModel: EmergencyViewModel,
    initialRoute: Route = Route.ViewInfo,
    externalRoute: Route? = null,
    onExternalRouteConsumed: () -> Unit = {},
) {
    val backStack = if (initialRoute == Route.ViewInfo) {
        rememberNavBackStack<Route>(Route.ViewInfo)
    } else {
        rememberNavBackStack<Route>(Route.ViewInfo, initialRoute)
    }

    LaunchedEffect(externalRoute) {
        val route = externalRoute ?: return@LaunchedEffect
        when (route) {
            Route.ViewInfo -> backStack.reset(Route.ViewInfo)
            Route.EditInfo -> backStack.reset(Route.ViewInfo, Route.EditInfo)
            Route.Sos -> Unit // never routed into this host; SosActivity owns it
        }
        onExternalRouteConsumed()
    }

    MainNavigation(backStack) {
        entry<Route.ViewInfo>(metadata = ListPage()) {
            val state by viewModel.state.collectAsStateWithLifecycle()
            ViewInfoScreen(state = state)
        }
        entry<Route.EditInfo>(metadata = DetailPage()) {
            EditInfoPage(viewModel = viewModel)
        }
    }
}

