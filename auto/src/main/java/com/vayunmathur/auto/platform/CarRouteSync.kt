package com.vayunmathur.auto.platform

import com.vayunmathur.auto.Route
import com.vayunmathur.library.util.NavBackStack

/** The car destination a selected app id names; null is the home grid. */
fun carRouteFor(selectedAppId: String?): Route =
    selectedAppId?.let { Route.App(it) } ?: Route.LauncherHome

/**
 * Projects a selected app id onto the car-side stack, kept free of
 * composition so unit tests can drive it with a plain [NavBackStack].
 *
 * The stack is [Route.LauncherHome] plus at most one [Route.App]:
 * selecting an app pushes it (or replaces the open one — switching apps
 * is a swap, not a descent), and clearing the selection pops back home.
 * Idempotent while the selection is unchanged.
 */
fun syncCarRoute(backStack: NavBackStack<Route>, selectedAppId: String?) {
    val target = carRouteFor(selectedAppId)
    val top = backStack.backStack.lastOrNull()
    if (top == target) return
    when {
        target is Route.LauncherHome -> backStack.reset(Route.LauncherHome)
        top is Route.LauncherHome -> backStack.add(target)
        top is Route.App -> backStack.setLast(target)
        else -> backStack.reset(Route.LauncherHome, target)
    }
}
