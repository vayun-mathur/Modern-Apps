package com.vayunmathur.launcher

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.vayunmathur.launcher.platform.ActivityBridge
import com.vayunmathur.launcher.platform.LauncherViewModel
import com.vayunmathur.launcher.platform.LocalActivityBridge
import com.vayunmathur.launcher.platform.LocalIconLoader
import com.vayunmathur.launcher.ui.HomePage
import com.vayunmathur.launcher.ui.SettingsPage
import com.vayunmathur.launcher.ui.components.LauncherDragController
import com.vayunmathur.launcher.ui.components.LocalLauncherDrag
import com.vayunmathur.library.util.DialogPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.OpenSettingsIfRequested

@Composable
fun Navigation(
    viewModel: LauncherViewModel,
    bridge: ActivityBridge,
    backStack: NavBackStack<Route>,
) {
    backStack.OpenSettingsIfRequested(Route.Settings)

    // One controller for the whole app, above the nav host, so it outlives anything opening over
    // the home screen while a drag is in flight.
    val dragController = remember { LauncherDragController() }

    CompositionLocalProvider(
        LocalActivityBridge provides bridge,
        LocalIconLoader provides viewModel,
        LocalLauncherDrag provides dragController,
    ) {
        // Transparent, so the wallpaper that Theme.Launcher's window exposes is actually visible.
        // The Scaffold inside MainNavigation otherwise paints an opaque colorScheme.background
        // straight over it.
        MainNavigation(backStack, containerColor = Color.Transparent) {
            entry<Route.Home> { HomePage(backStack, viewModel) }
            entry<Route.Settings>(metadata = DialogPage()) { SettingsPage(backStack, viewModel) }
        }
    }
}
