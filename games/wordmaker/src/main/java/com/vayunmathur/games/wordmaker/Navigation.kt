package com.vayunmathur.games.wordmaker

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.games.wordmaker.data.GameMode
import com.vayunmathur.games.wordmaker.data.LevelDataStore
import com.vayunmathur.games.wordmaker.platform.WordMakerAchievementsManager
import com.vayunmathur.games.wordmaker.platform.WordMakerViewModel
import com.vayunmathur.games.wordmaker.ui.CompetitiveLobbyPage
import com.vayunmathur.games.wordmaker.ui.SettingsPage
import com.vayunmathur.games.wordmaker.ui.WordMakerGameLoader
import com.vayunmathur.library.ui.GameCenterScreen
import com.vayunmathur.library.util.AchievementsManager
import com.vayunmathur.library.util.FullscreenPage
import com.vayunmathur.library.util.GameHubSessionHook
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.openSettingsIfRequested
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun Navigation(viewModel: WordMakerViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Game)
    backStack.openSettingsIfRequested(Route.Settings)
    WordMakerRouteEffect(backStack, viewModel)
    GameHubSessionHook("wordmaker", "Wordmaker")
    MainNavigation(backStack) {
        entry<Route.Game>(metadata = FullscreenPage()) {
            WordMakerGameLoader(backStack, viewModel)
        }
        entry<Route.CompetitiveLobby>(metadata = FullscreenPage()) {
            CompetitiveLobbyPage(
                viewModel = viewModel,
                onOpenGameCenter = { backStack.add(Route.GameCenter) },
                onOpenSettings = { backStack.add(Route.Settings) },
            )
        }
        entry<Route.GameCenter> {
            val achievementsManager = rememberAchievementsManager(viewModel.levelDataStore)
            if (achievementsManager != null) {
                GameCenterScreen(
                    manager = achievementsManager,
                    onBack = { backStack.pop() },
                )
            }
        }
        entry<Route.Settings> { SettingsPage(viewModel = viewModel, onBack = { backStack.pop() }) }
    }
}

/**
 * Swaps the board and the competitive lobby as destinations.
 *
 * The mode and the competitive session stay in the ViewModel; this
 * effect projects them onto the stack. Competitive with no level
 * active is [Route.CompetitiveLobby], everything else is [Route.Game].
 * The swap is a reset, not a push: the two are alternatives for the same
 * slot (as the loader's mode swap was), so Back never steps from a live
 * board into the lobby or vice versa. Destinations pushed over either
 * (game center, settings) are left alone and re-synced on pop.
 */
@Composable
private fun WordMakerRouteEffect(backStack: NavBackStack<Route>, viewModel: WordMakerViewModel) {
    val gameMode by viewModel.gameMode.collectAsState()
    val competitiveActive by viewModel.competitiveActive.collectAsState()
    val top = backStack.backStack.lastOrNull()
    LaunchedEffect(gameMode, competitiveActive, top) {
        syncWordMakerRoute(backStack, gameMode, competitiveActive)
    }
}

/**
 * The mode-to-stack projection behind [WordMakerRouteEffect], kept free
 * of composition so unit tests can drive it with a plain [NavBackStack].
 */
internal fun syncWordMakerRoute(
    backStack: NavBackStack<Route>,
    gameMode: GameMode,
    competitiveActive: Boolean,
) {
    val target: Route =
        if (gameMode == GameMode.COMPETITIVE && !competitiveActive) Route.CompetitiveLobby
        else Route.Game
    when (backStack.backStack.lastOrNull()) {
        target -> Unit
        Route.Game, Route.CompetitiveLobby -> backStack.reset(target)
        else -> Unit
    }
}

@Composable
fun rememberAchievementsManager(levelDataStore: LevelDataStore): AchievementsManager? {
    val context = LocalContext.current
    val state = produceState<AchievementsManager?>(initialValue = null, context, levelDataStore) {
        value = withContext(Dispatchers.IO) {
            val json = context.assets.open("achievements.json")
                .bufferedReader()
                .use { it.readText() }
            WordMakerAchievementsManager(context, json, levelDataStore)
        }
    }
    return state.value
}
