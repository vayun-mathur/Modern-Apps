package com.vayunmathur.games.logicgate

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.games.logicgate.platform.LogicViewModel
import com.vayunmathur.games.logicgate.ui.ComponentPage
import com.vayunmathur.games.logicgate.ui.GamePage
import com.vayunmathur.games.logicgate.ui.LocalChipNames
import com.vayunmathur.games.logicgate.ui.ProgressionPage
import com.vayunmathur.games.logicgate.ui.SandboxPage
import com.vayunmathur.library.ui.AchievementNotification
import com.vayunmathur.library.ui.GameCenterScreen
import com.vayunmathur.library.ui.IconMemory
import com.vayunmathur.library.ui.IconScience
import com.vayunmathur.library.util.BottomBarItem
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.FullscreenPage
import com.vayunmathur.library.util.GameHubComposeHook
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.SiblingPage
import com.vayunmathur.library.util.rememberNavBackStack

@Composable
fun Navigation(viewModel: LogicViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Progression)
    val newAchievement by viewModel.achievementsManager.newAchievement.collectAsState()
    val catalog by viewModel.catalog.collectAsState()
    val names = remember(catalog) { catalog.customs.associate { it.id to it.name } }
    GameHubComposeHook("logicgate", viewModel.achievementsManager)
    val pages: List<BottomBarItem<out Route>> = listOf(
        BottomBarItem(stringResource(R.string.lg_tab_computer), Route.Progression) { IconMemory() },
        BottomBarItem(stringResource(R.string.lg_tab_sandbox), Route.Sandbox) { IconScience() },
    )
    CompositionLocalProvider(LocalChipNames provides names) {
        Box(modifier = Modifier.fillMaxSize()) {
            MainNavigation(
                backStack,
                bottomBar = {
                    val cur = backStack.last()
                    if (cur == Route.Progression || cur == Route.Sandbox) BottomNavBar(backStack, pages, cur)
                },
            ) {
                entry<Route.Progression>(metadata = SiblingPage()) { ProgressionPage(backStack, viewModel) }
                entry<Route.Game>(metadata = FullscreenPage()) { GamePage(backStack, viewModel, it.levelId) }
                entry<Route.Sandbox>(metadata = SiblingPage()) { SandboxPage(backStack, viewModel) }
                entry<Route.Component>(metadata = FullscreenPage()) {
                    ComponentPage(backStack, viewModel, it.componentId)
                }
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
}
