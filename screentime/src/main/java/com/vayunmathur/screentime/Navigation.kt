package com.vayunmathur.screentime

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.util.DetailPage
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.screentime.platform.ScreenTimeViewModel
import com.vayunmathur.screentime.ui.DashboardActions
import com.vayunmathur.screentime.ui.AppDetailsEntry
import com.vayunmathur.screentime.ui.DashboardScreen
import com.vayunmathur.screentime.ui.WindDownEntry

/** Navigation host for Screen Time: the dashboard and everything it opens. */
@Composable
fun Navigation(
    viewModel: ScreenTimeViewModel,
    initialRoute: Route = Route.Dashboard,
    externalRoute: Route? = null,
    onExternalRouteConsumed: () -> Unit = {},
) {
    val backStack = if (initialRoute == Route.Dashboard) {
        rememberNavBackStack<Route>(Route.Dashboard)
    } else {
        rememberNavBackStack<Route>(Route.Dashboard, initialRoute)
    }

    LaunchedEffect(externalRoute) {
        val route = externalRoute ?: return@LaunchedEffect
        when (route) {
            Route.Dashboard -> backStack.reset(Route.Dashboard)
            Route.WindDown -> backStack.reset(Route.Dashboard, Route.WindDown)
            is Route.AppDetails -> when {
                backStack.last() == route -> Unit
                backStack.last() is Route.AppDetails -> backStack.setLast(route)
                else -> backStack.add(route)
            }
        }
        onExternalRouteConsumed()
    }

    MainNavigation(backStack) {
        entry<Route.Dashboard>(metadata = ListPage()) {
            val state by viewModel.state.collectAsStateWithLifecycle()
            DashboardScreen(
                state = state,
                actions = DashboardActions(
                    onSetPeriod = viewModel::setPeriod,
                    onStep = viewModel::stepAnchor,
                    onOpenApp = { packageName -> backStack.add(Route.AppDetails(packageName)) },
                ),
            )
        }
        entry<Route.AppDetails>(metadata = ListDetailPage() + DetailPage()) { route ->
            AppDetailsEntry(viewModel, backStack, route)
        }
        entry<Route.WindDown>(metadata = DetailPage()) {
            WindDownEntry(viewModel)
        }
    }
}

