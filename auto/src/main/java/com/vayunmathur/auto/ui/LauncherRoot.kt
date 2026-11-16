package com.vayunmathur.auto.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.auto.Route
import com.vayunmathur.auto.carRouteFor
import com.vayunmathur.auto.platform.CarLauncherState
import com.vayunmathur.auto.syncCarRoute
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.util.rememberNavBackStack

/**
 * The car launcher's Compose entry: body + bottom bar.
 *
 * The body is the car-side `Route` stack: [Route.LauncherHome] (the home
 * grid) with at most one [Route.App] over it. Which destination is showing
 * is decided by that stack, never by reading the selection directly:
 * `CarLauncherState.selectedApp` stays the selection engine the session
 * focus follows, and [syncCarRoute] projects it onto the stack (selecting
 * pushes/replaces the app, clearing pops home). Hosted apps keep their
 * own template stacks inside their destination. The bottom bar holds
 * home | pins | status and stays under either destination, as before.
 * The dock shows the pinned apps in stored order (stale ids filtered),
 * falling back to the first apps until the user picks — plus the open app
 * as a transient trailing icon when it isn't pinned, so an unpinned app
 * still reads selected in the bar. Selection writes into the shared state
 * so the session focus follows the tap. One public `@Composable` per file
 * throughout `ui/`.
 *
 * Plain `Surface + Column`, rendering the stack's top directly rather
 * than through `MainNavigation`: the car `Presentation` has no phone
 * chrome, activity or insets to scaffold around, and the bottom bar must
 * persist across the home/app swap. Driving-distraction behaviour is
 * unchanged — same grid, same dock, same hosted templates.
 */
@Composable
fun LauncherRoot(modifier: Modifier = Modifier) {
    val selected by CarLauncherState.selectedApp.collectAsStateWithLifecycle()
    val discovered by CarLauncherState.discovered.collectAsStateWithLifecycle()
    val pinnedIds by CarLauncherState.pinnedOrder.collectAsStateWithLifecycle()
    val actions by CarLauncherState.actions.collectAsStateWithLifecycle()
    val backStack = rememberNavBackStack<Route>(carRouteFor(selected))
    LaunchedEffect(selected) { syncCarRoute(backStack, selected) }
    val top = backStack.backStack.lastOrNull()
    val openAppId = (top as? Route.App)?.appId
    val pinned = remember(pinnedIds, discovered) {
        val byId = discovered.associateBy { it.id }
        val ordered = pinnedIds.mapNotNull { byId[it] }
        if (ordered.isNotEmpty()) ordered else discovered
    }
    val dock = remember(pinned, openAppId, discovered) {
        if (openAppId != null && pinned.none { it.id == openAppId }) {
            val open = discovered.firstOrNull { it.id == openAppId }
            if (open != null) pinned + open else pinned
        } else {
            pinned
        }
    }
    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            if (top is Route.App) {
                AppScreen(
                    appId = top.appId,
                    modifier = Modifier.weight(1f),
                )
            } else {
                LauncherHomeGrid(
                    apps = discovered,
                    onOpen = { CarLauncherState.selectApp(it) },
                    modifier = Modifier.weight(1f),
                )
            }
            LauncherBottomBar(
                pinned = dock,
                selectedId = openAppId,
                onHome = { CarLauncherState.selectApp(null) },
                // Tap the open app: home-reset it in place. Tap anything
                // else: open it where it was (each host session keeps its
                // own screen stack, so re-select resumes).
                onPin = { id ->
                    if (id == openAppId) {
                        discovered.firstOrNull { it.id == id }?.let {
                            actions.onAppHomeTap(it.component)
                        }
                    } else {
                        CarLauncherState.selectApp(id)
                    }
                },
            )
        }
    }
}
