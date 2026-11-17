package com.vayunmathur.games.hub

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.games.hub.ui.components.AvatarIcon
import com.vayunmathur.games.hub.ui.components.profileTabAvatarSymbol
import com.vayunmathur.library.ui.IconDashboard
import com.vayunmathur.library.ui.IconEmoji
import com.vayunmathur.library.ui.IconEmojiEvents
import com.vayunmathur.library.ui.IconSportsEsports
import com.vayunmathur.library.ui.RouteTab
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.games.hub.data.GamesHubRepository
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
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.SiblingPage
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.serialization.Serializable

/** Main Activity: a ma activity implementation for main activity. */
class MainActivity : MaActivity() {

    private val vm: GameHubViewModel by viewModels {
        GameHubViewModelFactory(application, GamesHubRepository.get(application))
    }

    @Composable
    override fun Content() {
        HubNavigation(vm)
    }
}

/** Contract for main route. */
@Serializable
sealed interface MainRoute : NavKey {
    /** Hub dashboard route. */
    @Serializable data object Dashboard : MainRoute
    /** Games list route. */
    @Serializable data object Games : MainRoute
    /** All-games achievements route. */
    @Serializable data object Achievements : MainRoute
    /** Player profile route. */
    @Serializable data object Profile : MainRoute
    /** Yuu companion route. */
    @Serializable data object Yuu : MainRoute
    /** Detail route for the game identified by [gameId]. */
    @Serializable data class GameDetail(val gameId: String) : MainRoute
    /** Achievements route for the game identified by [gameId]. */
    @Serializable data class AchievementsForGame(val gameId: String) : MainRoute
    /** Activity feed route. */
    @Serializable data object Activity : MainRoute
    /** Hub settings route. */
    @Serializable data object Settings : MainRoute
    /** The phone-as-controller screen (hub hosts the SDK ControllerPad). */
    @Serializable data object PlayOnTv : MainRoute
    /** Route to create a new Yuu. */
    @Serializable data object YuuEditorNew : MainRoute
    /** Route to edit the Yuu identified by [yuuId]. */
    @Serializable data class YuuEditorEdit(val yuuId: Long) : MainRoute
}

@Composable
fun HubNavigation(
    viewModel: GameHubViewModel
) {
    // Dashboard is the start tab (the pager's page 0), so a fresh or
    // deep-linked launch with no tab named lands where it always did.
    val backStack = rememberNavBackStack<MainRoute>(MainRoute.Dashboard)
    val profile by viewModel.profileFlow.collectAsStateWithLifecycle()

    // Home and Games show the same games (recentlyPlayed is a subset of the
    // full list), so "hub-game-<id>" would have two live origins if both
    // tabs were composed at once. The pager avoided that with settledPage;
    // sibling routes are never composed together outside a tab-switch
    // crossfade, and only the settled tab is on top, so each tab entry
    // owns its morph keys (the default) with no pager page to compare.
    val tabs = buildList<RouteTab<MainRoute>> {
        add(RouteTab(MainRoute.Dashboard, stringResource(R.string.tab_home)) { IconDashboard() })
        add(RouteTab(MainRoute.Games, stringResource(R.string.tab_games)) { IconSportsEsports() })
        add(RouteTab(MainRoute.Achievements, stringResource(R.string.tab_achievements)) { IconEmojiEvents() })
        add(RouteTab(MainRoute.Profile, stringResource(R.string.tab_profile)) {
            AvatarIcon(symbol = profileTabAvatarSymbol(profile?.avatarSymbol))
        })
        // Dev-only: the Yuu avatar plaza is still experimental. R8 strips the
        // block from release builds via the repo-wide BuildConfig.DEV_BUILD flag.
        if (BuildConfig.DEV_BUILD) {
            add(RouteTab(MainRoute.Yuu, stringResource(R.string.tab_yuu)) { IconEmoji() })
        }
    }

    TabbedPagerScaffold(backStack = backStack, tabs = tabs) {
        MainNavigation(backStack) {
            entry<MainRoute.Dashboard>(metadata = ListPage() + SiblingPage()) {
                DashboardPage(
                    viewModel = viewModel,
                    onGameClick = { gameId -> backStack.add(MainRoute.GameDetail(gameId)) },
                    onProfileClick = { backStack.reset(MainRoute.Profile) },
                    onActivityClick = { backStack.add(MainRoute.Activity) },
                    onGamesClick = { backStack.reset(MainRoute.Games) },
                    onControllerClick = { backStack.add(MainRoute.PlayOnTv) },
                )
            }
            entry<MainRoute.Games>(metadata = ListPage() + SiblingPage()) {
                GamesListPage(
                    viewModel = viewModel,
                    onGameClick = { gameId -> backStack.add(MainRoute.GameDetail(gameId)) },
                )
            }
            entry<MainRoute.Achievements>(metadata = SiblingPage()) {
                AchievementsScreen(viewModel = viewModel)
            }
            entry<MainRoute.Profile>(metadata = SiblingPage()) {
                ProfilePage(viewModel = viewModel)
            }
            if (BuildConfig.DEV_BUILD) {
                entry<MainRoute.Yuu>(metadata = SiblingPage()) {
                    YuuPage(
                        viewModel = viewModel,
                        onCreateYuu = { backStack.add(MainRoute.YuuEditorNew) },
                        onEditYuu = { id -> backStack.add(MainRoute.YuuEditorEdit(id)) },
                    )
                }
            }
            detailEntries(viewModel, backStack)
        }
    }
}


/** The pushed (non-tab) destinations of the hub graph. */
private fun com.vayunmathur.library.util.EntryProviderScope<MainRoute>.detailEntries(
    viewModel: GameHubViewModel,
    backStack: com.vayunmathur.library.util.NavBackStack<MainRoute>,
) {
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
