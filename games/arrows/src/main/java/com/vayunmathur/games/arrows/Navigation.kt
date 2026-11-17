package com.vayunmathur.games.arrows

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.vayunmathur.games.arrows.platform.ArrowsViewModel
import com.vayunmathur.games.arrows.ui.ArrowsGamePage
import com.vayunmathur.games.arrows.ui.SettingsPage
import com.vayunmathur.library.ui.AchievementNotification
import com.vayunmathur.library.ui.GameCenterScreen
import com.vayunmathur.library.util.GameHubComposeHook
import com.vayunmathur.library.util.FullscreenPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.OpenSettingsIfRequested
import com.vayunmathur.library.util.rememberNavBackStack

@Composable
fun Navigation(viewModel: ArrowsViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Game)
    // Launching from App Info's settings cog deep-links straight to Settings.
    backStack.OpenSettingsIfRequested(Route.Settings)
    val newAchievement by viewModel.achievementsManager.newAchievement.collectAsState()
    GameHubComposeHook("arrows", viewModel.achievementsManager)
    Box(Modifier.fillMaxSize()) {
        MainNavigation(backStack) {
            entry<Route.Game>(metadata = FullscreenPage()) { ArrowsGamePage(backStack, viewModel) }
            entry<Route.GameCenter> {
                GameCenterScreen(
                    manager = viewModel.achievementsManager,
                    onBack = { backStack.pop() },
                )
            }
            entry<Route.Settings> {
                SettingsPage(viewModel = viewModel, onBack = { backStack.pop() })
            }
        }
        newAchievement?.let {
            AchievementNotification(it) { viewModel.dismissAchievementNotification() }
        }
    }
}
