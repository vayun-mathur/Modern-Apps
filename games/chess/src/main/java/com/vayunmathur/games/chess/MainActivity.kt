package com.vayunmathur.games.chess

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.games.chess.auth.LichessAuthBridge
import com.vayunmathur.games.chess.platform.OnlineChessViewModel
import com.vayunmathur.games.chess.util.ChessViewModel
import com.vayunmathur.games.chess.util.LearnViewModel
import com.vayunmathur.games.chess.util.PuzzleViewModel
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.ui.AchievementNotification
import com.vayunmathur.library.ui.GameCenterScreen
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconOnline
import com.vayunmathur.library.ui.IconPlay
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.util.BottomBarItem
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.GameHubComposeHook
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.SiblingPage
import com.vayunmathur.library.util.rememberNavBackStack

/** Main Activity: a ma activity implementation for main activity. */
class MainActivity : MaActivity() {
    override fun onCreateBeforeContent() {
        // lichess.org serves public-CA certs; STANDARD covers its chain without system trust.
        NetworkClient.init(this, TrustBundle.STANDARD)
        // Cold start straight into the OAuth redirect (process died mid-sign-in): forward it like
        // onNewIntent would. Sign-in can't complete without the in-memory verifier, but the
        // attempt clears instead of hanging on "waiting for approval".
        intent?.let(LichessAuthBridge::onRedirect)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        LichessAuthBridge.onRedirect(intent)
    }

    @Composable
    override fun Content() {
        val backStack = rememberNavBackStack<Route>(Route.Game)
        val achievementsManager = rememberAchievementsManager()
        GameHubComposeHook("chess", achievementsManager)
        if (achievementsManager == null) {
            Box(Modifier.fillMaxSize())
            return
        }
        ChessNavHost(backStack, achievementsManager)
    }

    @Composable
    private fun ChessNavHost(
        backStack: com.vayunmathur.library.util.NavBackStack<Route>,
        achievementsManager: com.vayunmathur.library.util.AchievementsManager,
    ) {
        val newAchievement by achievementsManager.newAchievement.collectAsState()

        LaunchedEffect(Unit) {
            achievementsManager.checkExistingAchievements()
        }

        Box(Modifier.fillMaxSize()) {
            val pages: List<BottomBarItem<out Route>> = bottomBarPages()
            MainNavigation(
                backStack,
                bottomBar = {
                    val cur = backStack.last()
                    if (cur.isMainTabRoute()) {
                        BottomNavBar(backStack, pages, cur)
                    }
                }
            ) {
                entry<Route.Game>(metadata = SiblingPage()) {
                    GameEntry(backStack, achievementsManager)
                }
                entry<Route.Online>(metadata = SiblingPage()) {
                    val onlineViewModel: OnlineChessViewModel = viewModel()
                    ChessOnlineScreens(onlineViewModel)
                }
                entry<Route.Puzzles>(metadata = SiblingPage()) {
                    val puzzleViewModel: PuzzleViewModel = viewModel()
                    PuzzleScreen(puzzleViewModel)
                }
                entry<Route.Learn>(metadata = SiblingPage()) {
                    LearnHomeScreen(
                        onOpenStage = { cat, stage ->
                            backStack.add(Route.LearnStage(cat, stage))
                        }
                    )
                }
                entry<Route.LearnStage> { route ->
                    LearnStageEntry(backStack, route)
                }
                entry<Route.GameCenter> {
                    GameCenterScreen(
                        manager = achievementsManager,
                        onBack = { backStack.pop() }
                    )
                }
            }

            newAchievement?.let {
                AchievementNotification(it) {
                    achievementsManager.dismissNotification()
                }
            }
        }
    }

    @Composable
    private fun bottomBarPages(): List<BottomBarItem<out Route>> = listOf(
        BottomBarItem(
            stringResource(R.string.tab_offline),
            Route.Game,
        ) { IconPlay() },
        BottomBarItem(
            stringResource(R.string.tab_online),
            Route.Online,
        ) { IconOnline() },
        BottomBarItem(
            stringResource(R.string.tab_puzzles),
            Route.Puzzles,
        ) { Icon(painterResource(R.drawable.chess_knight_fill1_24px), null) },
        BottomBarItem(
            stringResource(R.string.tab_learn),
            Route.Learn,
        ) { Icon(painterResource(R.drawable.school_24px), null) }
    )

    @Composable
    private fun GameEntry(
        backStack: com.vayunmathur.library.util.NavBackStack<Route>,
        achievementsManager: com.vayunmathur.library.util.AchievementsManager,
    ) {
        val viewModel: ChessViewModel = viewModel()
        var showNewGameDialog by remember { mutableStateOf(false) }
        val aiAvailable by viewModel.aiAvailable.collectAsState()

        ChessGame(
            viewModel = viewModel,
            onNewGame = { showNewGameDialog = true },
            onOpenGameCenter = { backStack.add(Route.GameCenter) },
            achievementsManager = achievementsManager
        )

        if (showNewGameDialog) {
            NewGameDialog(
                onNewGame = {
                    viewModel.onNewGame(it)
                    showNewGameDialog = false
                },
                onDismiss = { showNewGameDialog = false },
                aiAvailable = aiAvailable
            )
        }
    }

    @Composable
    private fun LearnStageEntry(
        backStack: com.vayunmathur.library.util.NavBackStack<Route>,
        route: Route.LearnStage,
    ) {
        val learnViewModel: LearnViewModel = viewModel()
        LaunchedEffect(route.categoryKey, route.stageKey) {
            learnViewModel.loadStage(route.categoryKey, route.stageKey)
        }
        LearnStageScreen(
            viewModel = learnViewModel,
            onBack = { backStack.pop() },
            onOpenStage = { cat, stage ->
                backStack.setLast(Route.LearnStage(cat, stage))
            }
        )
    }
}

/** True for the four top-level tab destinations that show the bottom bar. */
private fun Route.isMainTabRoute(): Boolean = when (this) {
    is Route.Game, is Route.Online, is Route.Puzzles, is Route.Learn -> true
    else -> false
}
