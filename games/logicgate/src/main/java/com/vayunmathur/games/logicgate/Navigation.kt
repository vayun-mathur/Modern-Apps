package com.vayunmathur.games.logicgate

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.vayunmathur.games.logicgate.platform.LogicViewModel
import com.vayunmathur.games.logicgate.ui.GamePage
import com.vayunmathur.games.logicgate.ui.ProgressionPage
import com.vayunmathur.library.ui.AchievementNotification
import com.vayunmathur.library.ui.GameCenterScreen
import com.vayunmathur.library.util.FullscreenPage
import com.vayunmathur.library.util.GameHubComposeHook
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack

@Composable
fun Navigation(viewModel: LogicViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Progression)
    val newAchievement by viewModel.achievementsManager.newAchievement.collectAsState()
    GameHubComposeHook("logicgate", viewModel.achievementsManager)
    Box(modifier = Modifier.fillMaxSize()) {
        MainNavigation(backStack) {
            entry<Route.Progression> { ProgressionPage(backStack, viewModel) }
            entry<Route.Game>(metadata = FullscreenPage()) { GamePage(backStack, viewModel, it.levelId) }
            entry<Route.GameCenter> {
                GameCenterScreen(
                    manager = viewModel.achievementsManager,
                    onBack = { backStack.pop() },
                )
            }
        }
        newAchievement?.let { AchievementNotification(it) { viewModel.dismissAchievement() } }
    }
}
