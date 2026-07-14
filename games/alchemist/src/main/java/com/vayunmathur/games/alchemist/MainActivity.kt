package com.vayunmathur.games.alchemist

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.games.alchemist.data.AlchemyItem
import com.vayunmathur.games.alchemist.ui.CollectionScreen
import com.vayunmathur.games.alchemist.ui.HomeScreen
import com.vayunmathur.games.alchemist.ui.ItemDetailsScreen
import com.vayunmathur.games.alchemist.ui.UnlockNotification
import com.vayunmathur.games.alchemist.util.AlchemistAchievementsManager
import com.vayunmathur.games.alchemist.util.AlchemistViewModel
import com.vayunmathur.games.alchemist.util.AppBackupAgent
import com.vayunmathur.library.ui.AchievementNotification
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.GameCenterScreen
import com.vayunmathur.library.util.AchievementsManager
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    private val viewModel: AlchemistViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DynamicTheme {
                Navigation(viewModel)
            }
        }
    }
}

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data object Home: Route
    @Serializable
    data class ItemDetails(val item: Int): Route
    @Serializable
    data object Collection: Route
    @Serializable
    data object GameCenter: Route
}

@Composable
fun Navigation(viewModel: AlchemistViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Home)
    val achievementsManager = rememberAchievementsManager()
    val newAchievement = achievementsManager?.newAchievement?.collectAsState()?.value

    var showingUnlock by remember { mutableStateOf(false) }
    var currentUnlocks by remember { mutableStateOf(emptyList<AlchemyItem>()) }

    LaunchedEffect(achievementsManager) {
        if (achievementsManager != null) {
            launch { achievementsManager.checkExistingAchievements() }
            viewModel.bindAchievements(achievementsManager)
        }
    }
    LaunchedEffect(viewModel) {
        viewModel.newUnlocksEvent.collectLatest { items ->
            currentUnlocks = items
            showingUnlock = true
            delay(3000)
            showingUnlock = false
        }
    }

    Box(Modifier.fillMaxSize()) {
        MainNavigation(backStack) {
            entry<Route.Home> {
                HomeScreen(
                    backStack,
                    viewModel,
                    onOpenCollection = { backStack.add(Route.Collection) },
                    onOpenGameCenter = { backStack.add(Route.GameCenter) }
                )
            }
            entry<Route.Collection> {
                CollectionScreen(backStack, viewModel)
            }
            entry<Route.ItemDetails> {
                ItemDetailsScreen(backStack, viewModel, it.item)
            }
            entry<Route.GameCenter> {
                achievementsManager?.let {
                    GameCenterScreen(
                        backupAgent = AppBackupAgent(),
                        manager = it,
                        onBack = { backStack.pop() }
                    )
                }
            }
        }

        newAchievement?.let { ach ->
            AchievementNotification(ach) {
                achievementsManager.dismissNotification()
            }
        }

        UnlockNotification(
            unlock = currentUnlocks,
            showing = showingUnlock
        )
    }
}

@Composable
fun rememberAchievementsManager(): AchievementsManager? {
    val context = LocalContext.current
    val state = produceState<AchievementsManager?>(initialValue = null, context) {
        value = withContext(Dispatchers.IO) {
            val json = context.assets.open("achievements.json").bufferedReader().use { it.readText() }
            AlchemistAchievementsManager(context, json)
        }
    }
    return state.value
}
