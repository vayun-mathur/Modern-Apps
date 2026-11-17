package com.vayunmathur.games.pipes

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.vayunmathur.games.pipes.platform.PipesViewModel
import com.vayunmathur.games.pipes.ui.DailyLevelScreen
import com.vayunmathur.games.pipes.ui.GameScreen
import com.vayunmathur.games.pipes.ui.LevelScreen
import com.vayunmathur.games.pipes.ui.PackScreen
import com.vayunmathur.games.pipes.ui.SettingsPage
import com.vayunmathur.library.ui.AchievementNotification
import com.vayunmathur.library.ui.GameCenterScreen
import com.vayunmathur.library.util.GameHubComposeHook
import com.vayunmathur.library.util.FullscreenPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.OpenSettingsIfRequested
import com.vayunmathur.library.util.rememberNavBackStack

@Composable
fun Navigation(viewModel: PipesViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.PackSelector)
    backStack.OpenSettingsIfRequested(Route.Settings)
    val newAchievement by viewModel.achievementsManager.newAchievement.collectAsState()
    GameHubComposeHook("pipes", viewModel.achievementsManager)
    Box(Modifier.fillMaxSize()) {
        MainNavigation(backStack) {
            entry<Route.PackSelector> {
                PackScreen(
                    backStack,
                    viewModel,
                    onOpenGameCenter = { backStack.add(Route.GameCenter) },
                )
            }
            entry<Route.LevelSelector> { LevelScreen(backStack, viewModel, it.packIndex) }
            entry<Route.Game>(metadata = FullscreenPage()) {
                GameScreen(backStack, viewModel, it.packIndex, it.levelIndex)
            }
            entry<Route.DailySelector> { DailyLevelScreen(backStack, viewModel) }
            entry<Route.DailyGame>(metadata = FullscreenPage()) {
                GameScreen(backStack, viewModel, PipesViewModel.DAILY_PACK_INDEX, it.levelIndex)
            }
            entry<Route.GameCenter> {
                GameCenterScreen(
                    manager = viewModel.achievementsManager,
                    onBack = { backStack.pop() },
                )
            }
            entry<Route.Settings> { SettingsPage(backStack, viewModel) }
        }
        newAchievement?.let { AchievementNotification(it) { viewModel.dismissAchievementNotification() } }
    }
}
