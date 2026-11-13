package com.vayunmathur.games.hub

import androidx.activity.viewModels
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.games.hub.ui.components.AvatarIcon
import com.vayunmathur.games.hub.ui.components.profileTabAvatarSymbol
import com.vayunmathur.library.ui.IconDashboard
import com.vayunmathur.library.ui.IconEmoji
import com.vayunmathur.library.ui.IconEmojiEvents
import com.vayunmathur.library.ui.IconSportsEsports
import com.vayunmathur.library.ui.PagerTab
import com.vayunmathur.library.ui.TabStyle
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.games.hub.data.GamesHubRepository
import com.vayunmathur.games.hub.BuildConfig
import com.vayunmathur.games.hub.ui.screens.AchievementsScreen
import com.vayunmathur.games.hub.ui.screens.ActivityFeedScreen
import com.vayunmathur.games.hub.ui.screens.DashboardPage
import com.vayunmathur.games.hub.ui.screens.GameDetailScreen
import com.vayunmathur.games.hub.ui.screens.GamesListPage
import com.vayunmathur.games.hub.ui.screens.PlayOnTvScreen
import com.vayunmathur.games.hub.ui.screens.ProfilePage
import com.vayunmathur.games.hub.ui.screens.SettingsScreen
import com.vayunmathur.games.hub.ui.screens.YuuEditorPage
import com.vayunmathur.games.hub.ui.screens.YuuPage
import com.vayunmathur.games.hub.viewmodel.GameHubViewModel
import com.vayunmathur.games.hub.viewmodel.GameHubViewModelFactory
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.MorphPage
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

class MainActivity : MaActivity() {

    private val vm: GameHubViewModel by viewModels {
        GameHubViewModelFactory(application, GamesHubRepository.get(application))
    }

    @Composable
    override fun Content() {
        HubNavigation(vm)
    }
}

@Serializable
sealed interface MainRoute : NavKey {
    @Serializable data object Main : MainRoute
    @Serializable data class GameDetail(val gameId: String) : MainRoute
    @Serializable data class AchievementsForGame(val gameId: String) : MainRoute
    @Serializable data object Activity : MainRoute
    @Serializable data object Settings : MainRoute
    /** The phone-as-controller screen (hub hosts the SDK ControllerPad). */
    @Serializable data object PlayOnTv : MainRoute
    @Serializable data object YuuEditorNew : MainRoute
    @Serializable data class YuuEditorEdit(val yuuId: Long) : MainRoute
}

@Composable
fun HubNavigation(
    viewModel: GameHubViewModel
) {
    val backStack = rememberNavBackStack<MainRoute>(MainRoute.Main)

    MainNavigation(backStack) {
        entry<MainRoute.Main>(metadata = ListPage()) {
            HubTabs(viewModel = viewModel, backStack = backStack)
        }
        entry<MainRoute.GameDetail>(metadata = ListDetailPage() + MorphPage()) { route ->
            GameDetailScreen(
                gameId = route.gameId,
                viewModel = viewModel,
                backStack = backStack
            )
        }
        entry<MainRoute.AchievementsForGame> { route ->
            AchievementsScreen(viewModel = viewModel, initialGameFilter = route.gameId)
        }
        entry<MainRoute.Activity> {
            ActivityFeedScreen(
                viewModel = viewModel,
                backStack = backStack,
                onGameClick = { gid -> backStack.add(MainRoute.GameDetail(gid)) }
            )
        }
        entry<MainRoute.Settings> {
            SettingsScreen(
                viewModel = viewModel,
                backStack = backStack
            )
        }
        entry<MainRoute.PlayOnTv> {
            PlayOnTvScreen(backStack = backStack)
        }
        entry<MainRoute.YuuEditorNew> {
            YuuEditorPage(
                viewModel = viewModel,
                editingId = null,
                onDismiss = { backStack.pop() },
            )
        }
        entry<MainRoute.YuuEditorEdit> { route ->
            YuuEditorPage(
                viewModel = viewModel,
                editingId = route.yuuId,
                onDismiss = { backStack.pop() },
            )
        }
    }
}

@Composable
private fun HubTabs(
    viewModel: GameHubViewModel,
    backStack: NavBackStack<MainRoute>,
) {
    val pagerState = rememberPagerState(pageCount = { if (BuildConfig.DEV_BUILD) 5 else 4 })
    val scope = rememberCoroutineScope()
    val profile by viewModel.profileFlow.collectAsStateWithLifecycle()
    // Home and Games are adjacent pages showing the same games (recentlyPlayed is a subset of the
    // full list), and the pager composes both while a swipe is in flight - so "hub-game-<id>" would
    // have two live origins. settledPage rather than currentPage: currentPage flips at the halfway
    // point of a scroll, which would leave both pages claiming the key mid-swipe.
    val tabs = listOf(
        PagerTab(stringResource(R.string.tab_home), { IconDashboard() }) {
            DashboardPage(
                viewModel = viewModel,
                onGameClick = { gameId -> backStack.add(MainRoute.GameDetail(gameId)) },
                onProfileClick = { scope.launch { pagerState.animateScrollToPage(3) } },
                onActivityClick = { backStack.add(MainRoute.Activity) },
                onGamesClick = { scope.launch { pagerState.animateScrollToPage(1) } },
                onControllerClick = { backStack.add(MainRoute.PlayOnTv) },
                ownsGameMorphKeys = pagerState.settledPage == 0
            )
        },
        PagerTab(stringResource(R.string.tab_games), { IconSportsEsports() }) {
            GamesListPage(
                viewModel = viewModel,
                onGameClick = { gameId -> backStack.add(MainRoute.GameDetail(gameId)) },
                ownsGameMorphKeys = pagerState.settledPage == 1
            )
        },
        PagerTab(stringResource(R.string.tab_achievements), { IconEmojiEvents() }) {
            AchievementsScreen(viewModel = viewModel)
        },
        PagerTab(
            stringResource(R.string.tab_profile),
            { AvatarIcon(symbol = profileTabAvatarSymbol(profile?.avatarSymbol)) },
        ) {
            ProfilePage(viewModel = viewModel)
        },
        // Dev-only: the Yuu avatar plaza is still experimental. R8 strips the
        // block from release builds via the repo-wide BuildConfig.DEV_BUILD flag.
        if (BuildConfig.DEV_BUILD) {
            PagerTab(stringResource(R.string.tab_yuu), { IconEmoji() }) {
                YuuPage(
                    viewModel = viewModel,
                    onCreateYuu = { backStack.add(MainRoute.YuuEditorNew) },
                    onEditYuu = { id -> backStack.add(MainRoute.YuuEditorEdit(id)) },
                )
            }
        } else null,
    ).filterNotNull()
    TabbedPagerScaffold(tabs = tabs, pagerState = pagerState, tabStyle = TabStyle.BottomNav)
}
