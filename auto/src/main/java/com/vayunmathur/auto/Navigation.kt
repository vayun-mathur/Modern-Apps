package com.vayunmathur.auto

import androidx.compose.runtime.Composable
import com.vayunmathur.auto.platform.AutoViewModel
import com.vayunmathur.auto.platform.PairingViewModel
import com.vayunmathur.auto.ui.AutoScreen
import com.vayunmathur.auto.ui.PairingScreen
import com.vayunmathur.auto.ui.PinnedAppsScreen
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack

@Composable
@androidx.car.app.annotations.ExperimentalCarApi
fun Navigation(viewModel: AutoViewModel, pairingViewModel: PairingViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Home)
    MainNavigation(backStack) {
        entry<Route.Home> {
            AutoScreen(
                viewModel = viewModel,
                onPairing = { backStack.add(Route.Pairing) },
                onPinnedApps = { backStack.add(Route.PinnedApps) },
            )
        }
        entry<Route.Pairing> {
            PairingScreen(viewModel = pairingViewModel, onNavigateBack = { backStack.pop() })
        }
        entry<Route.PinnedApps> {
            PinnedAppsScreen(onNavigateBack = { backStack.pop() })
        }
    }
}
