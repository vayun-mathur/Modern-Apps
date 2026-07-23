package com.vayunmathur.games.hub

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.vayunmathur.games.hub.data.DB_NAME
import com.vayunmathur.games.hub.data.GamesHubDatabase
import com.vayunmathur.games.hub.ui.screens.AchievementsScreen
import com.vayunmathur.games.hub.ui.screens.ActivityFeedScreen
import com.vayunmathur.games.hub.ui.screens.DashboardScreen
import com.vayunmathur.games.hub.ui.screens.GameDetailScreen
import com.vayunmathur.games.hub.ui.screens.GamesListScreen
import com.vayunmathur.games.hub.ui.screens.ProfileScreen
import com.vayunmathur.games.hub.ui.screens.SettingsScreen
import com.vayunmathur.games.hub.viewmodel.GameHubViewModel
import com.vayunmathur.games.hub.viewmodel.GameHubViewModelFactory
import com.vayunmathur.library.room.buildDatabase
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.BottomBarItem
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.DatabaseHelper
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {

    private var dbConfigs: List<Pair<String, String>> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        var ready by mutableStateOf(false)

        val factory = GameHubViewModelFactory(
            application = application,
            database = application.buildDatabase<GamesHubDatabase>(dbName = DB_NAME).also {
                dbConfigs = try {
                    val pass = DatabaseHelper(this).getPassphrase()
                    listOf(DB_NAME to pass)
                } catch (_: Exception) {
                    emptyList()
                }
            }
        )
        val vm: GameHubViewModel by viewModels { factory }

        ready = true

        setContent {
            DynamicTheme {
                if (ready) {
                    HubNavigation(vm, dbConfigs)
                } else {
                    Box(Modifier.fillMaxSize())
                }
            }
        }
    }
}

@Serializable
sealed interface MainRoute : NavKey {
    @Serializable data object Dashboard : MainRoute
    @Serializable data object GamesList : MainRoute
    @Serializable data class GameDetail(val gameId: String) : MainRoute
    @Serializable data object Achievements : MainRoute
    @Serializable data class AchievementsForGame(val gameId: String) : MainRoute
    @Serializable data object Profile : MainRoute
    @Serializable data object Activity : MainRoute
    @Serializable data object Settings : MainRoute
}

@Composable
fun HubNavigation(
    viewModel: GameHubViewModel,
    dbConfigs: List<Pair<String, String>>
) {
    val backStack = rememberNavBackStack<MainRoute>(MainRoute.Dashboard)
    val current = backStack.last()

    val bottomBarItems: List<BottomBarItem<out MainRoute>> = listOf(
        BottomBarItem("Home", MainRoute.Dashboard) {
            Icon(Icons.Filled.Dashboard, contentDescription = "Home")
        },
        BottomBarItem("Games", MainRoute.GamesList) {
            Icon(Icons.Filled.SportsEsports, contentDescription = "Games")
        },
        BottomBarItem("Achievements", MainRoute.Achievements) {
            Icon(Icons.Filled.EmojiEvents, contentDescription = "Achievements")
        },
        BottomBarItem("Profile", MainRoute.Profile) {
            Icon(Icons.Filled.Person, contentDescription = "Profile")
        },
    )

    val showBottomBar = current is MainRoute.Dashboard
            || current is MainRoute.GamesList
            || current is MainRoute.Achievements
            || current is MainRoute.Profile

    MainNavigation(
        backStack = backStack,
        bottomBar = {
            if (showBottomBar) {
                BottomNavBar(backStack, bottomBarItems, current)
            }
        }
    ) {
        entry<MainRoute.Dashboard> {
            DashboardScreen(
                viewModel = viewModel,
                onGameClick = { gameId -> backStack.add(MainRoute.GameDetail(gameId)) },
                onProfileClick = { backStack.add(MainRoute.Profile) },
                onActivityClick = { backStack.add(MainRoute.Activity) },
                onGamesClick = { backStack.add(MainRoute.GamesList) },
                dbConfigs = dbConfigs,
                datastoreNames = listOf("datastore_default")
            )
        }
        entry<MainRoute.GamesList> {
            GamesListScreen(
                viewModel = viewModel,
                onGameClick = { gameId -> backStack.add(MainRoute.GameDetail(gameId)) }
            )
        }
        entry<MainRoute.GameDetail> { route ->
            GameDetailScreen(
                gameId = route.gameId,
                viewModel = viewModel,
                backStack = backStack
            )
        }
        entry<MainRoute.Achievements> {
            AchievementsScreen(viewModel = viewModel)
        }
        entry<MainRoute.AchievementsForGame> { route ->
            AchievementsScreen(viewModel = viewModel, initialGameFilter = route.gameId)
        }
        entry<MainRoute.Profile> {
            ProfileScreen(viewModel = viewModel)
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
                backStack = backStack,
                dbConfigs = dbConfigs,
                datastoreNames = listOf("datastore_default")
            )
        }
    }
}
