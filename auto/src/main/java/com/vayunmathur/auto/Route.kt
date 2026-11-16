package com.vayunmathur.auto

import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import kotlinx.serialization.Serializable

/**
 * Auto's destinations, phone-side and car-side.
 *
 * The phone side ([Home]/[Pairing]/[PinnedApps]) is only somewhere to see
 * whether a car is attached and how it is attached. The interesting
 * interface lives on the car's display: its launcher is a stack of
 * [LauncherHome] under at most one [App], synced from
 * `CarLauncherState.selectedApp` (which stays the session's selection
 * engine — the projection session focus reads). Hosted apps keep their
 * own template stacks inside [App]; the car stack only decides home grid
 * versus one hosted app, never what that app shows.
 */
@Serializable
sealed interface Route : NavKey {
    /** Connection state and how to attach a car. */
    @Serializable
    data object Home : Route

    /** USB + wireless bring-up, the winning transport, and the MAOS role. */
    @Serializable
    data object Pairing : Route

    /** The car dock pin picker: which car apps sit in the bottom bar. */
    @Serializable
    data object PinnedApps : Route

    /** The car launcher's home grid. Root of the car-side stack. */
    @Serializable
    data object LauncherHome : Route

    /** One hosted car app, by `DiscoveredApp.id`. */
    @Serializable
    data class App(val appId: String) : Route
}

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
