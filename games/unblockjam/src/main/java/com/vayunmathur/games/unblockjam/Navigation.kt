package com.vayunmathur.games.unblockjam

import android.Manifest
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.unblockjam.data.DailyLevelGenerator
import com.vayunmathur.games.unblockjam.data.LevelPack
import com.vayunmathur.games.unblockjam.platform.DailyProgress
import com.vayunmathur.games.unblockjam.platform.GameActions
import com.vayunmathur.games.unblockjam.platform.GameUiState
import com.vayunmathur.games.unblockjam.platform.SettingsActions
import com.vayunmathur.games.unblockjam.platform.SettingsUiState
import com.vayunmathur.games.unblockjam.platform.UnblockJamViewModel
import com.vayunmathur.games.unblockjam.ui.UnblockJamTheme
import com.vayunmathur.games.unblockjam.ui.components.GameBoard
import com.vayunmathur.library.ui.AchievementNotification
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.DailyReminderSettingsSection
import com.vayunmathur.library.ui.DetailScaffold
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.GameCenterScreen
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconStar
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.rememberPermissionRequest
import com.vayunmathur.library.ui.game.DailyChallengeCard
import com.vayunmathur.library.ui.game.GameTopBarActions
import com.vayunmathur.library.ui.game.LevelPickerBox
import com.vayunmathur.library.ui.game.MovesBox
import com.vayunmathur.library.util.GameHubComposeHook
import com.vayunmathur.library.util.LevelStats
import com.vayunmathur.library.util.FullscreenPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.openSettingsIfRequested
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.library.ui.R as UiR

@Composable
fun Navigation(viewModel: UnblockJamViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.PackSelector)
    backStack.openSettingsIfRequested(Route.Settings)
    val newAchievement by viewModel.achievementsManager.newAchievement.collectAsState()

    GameHubComposeHook("unblockjam", viewModel.achievementsManager)

    Box(Modifier.fillMaxSize()) {
        MainNavigation(backStack) {
            entry<Route.PackSelector> {
                PackPage(
                    backStack,
                    viewModel,
                    onOpenGameCenter = { backStack.add(Route.GameCenter) },
                    onOpenSettings = { backStack.add(Route.Settings) },
                )
            }
            entry<Route.LevelSelector> {
                val pack = LevelPack.PACKS[it.packIndex]
                UnblockJamTheme(pack = pack) {
                    LevelPage(backStack, viewModel, it.packIndex)
                }
            }
            entry<Route.Game>(metadata = FullscreenPage()) {
                val pack = LevelPack.PACKS[it.packIndex]
                UnblockJamTheme(pack = pack) {
                    GamePage(backStack, viewModel, it.packIndex, it.levelIndex)
                }
            }
            entry<Route.DailySelector> {
                DailyLevelPage(backStack, viewModel)
            }
            entry<Route.DailyGame>(metadata = FullscreenPage()) {
                GamePage(backStack, viewModel, UnblockJamViewModel.DAILY_PACK_INDEX, it.levelIndex)
            }
            entry<Route.GameCenter> {
                GameCenterScreen(
                    manager = viewModel.achievementsManager,
                    onBack = { backStack.pop() }
                )
            }
            entry<Route.Settings> {
                // The board palette maps colorScheme.primary to the board surface, so it is a
                // near-match for the background and unreadable as the accent shared settings rows
                // paint their section headings with.
                DynamicTheme {
                    SettingsPage(viewModel, onBack = { backStack.pop() })
                }
            }
        }

        newAchievement?.let {
            AchievementNotification(it) {
                viewModel.dismissAchievementNotification()
            }
        }
    }
}
/** Binds [UnblockJamViewModel] to the stateless [PackScreen]. */
@Composable
fun PackPage(
    backStack: NavBackStack<Route>,
    viewModel: UnblockJamViewModel,
    onOpenGameCenter: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val dailyCompleted by viewModel.dailyCompleted.collectAsState()
    val dailyDay by viewModel.dailyDay.collectAsState()
    val dailyStreak by viewModel.dailyStreak.collectAsState()

    PackScreen(
        packNames = LevelPack.PACKS.map { it.name },
        daily = DailyProgress(
            day = dailyDay,
            completed = dailyCompleted,
            total = DailyLevelGenerator.LEVELS_PER_DAY,
            streak = dailyStreak,
        ),
        onOpenPack = { backStack.add(Route.LevelSelector(it)) },
        onOpenDaily = { backStack.add(Route.DailySelector) },
        onOpenGameCenter = onOpenGameCenter,
        onOpenSettings = onOpenSettings,
    )
}

/** Binds [UnblockJamViewModel] to the stateless [SettingsScreen]. */
@Composable
fun SettingsPage(viewModel: UnblockJamViewModel, onBack: () -> Unit) {
    val reminderEnabled by viewModel.reminderEnabled.collectAsState()
    val reminderMinutes by viewModel.reminderMinutesOfDay.collectAsState()

    val requestNotifications = rememberPermissionRequest(Manifest.permission.POST_NOTIFICATIONS)

    SettingsScreen(
        state = SettingsUiState(
            reminderEnabled = reminderEnabled,
            reminderHour = (reminderMinutes / 60).toInt(),
            reminderMinute = (reminderMinutes % 60).toInt(),
        ),
        actions = object : SettingsActions {
            override fun setReminderEnabled(enabled: Boolean) {
                // Asked for lazily, on opt-in only, so the screen never blocks on a prompt.
                if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    requestNotifications()
                }
                viewModel.setReminderEnabled(enabled)
            }

            override fun setReminderTime(hour: Int, minute: Int) =
                viewModel.setReminderTime(hour, minute)
        },
        onBack = onBack,
    )
}

/** The settings screen, ViewModel-free so a preview can render it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(state: SettingsUiState, actions: SettingsActions, onBack: () -> Unit) {
    DetailScaffold(
        title = stringResource(UiR.string.settings),
        onNavigateBack = onBack,
        scrollBehavior = appBarScrollBehavior(),
    ) {
        DailyReminderSettingsSection(
            enabled = state.reminderEnabled,
            hour = state.reminderHour,
            minute = state.reminderMinute,
            onEnabledChange = { actions.setReminderEnabled(it) },
            onTimeChange = { hour, minute -> actions.setReminderTime(hour, minute) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackScreen(
    packNames: List<String>,
    onOpenPack: (Int) -> Unit,
    onOpenGameCenter: () -> Unit,
    daily: DailyProgress? = null,
    onOpenDaily: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
) {
    AppScaffold(
        title = stringResource(R.string.pack_selector),
        actions = {
            GameTopBarActions(
                onOpenGameCenter = onOpenGameCenter,
                onOpenSettings = onOpenSettings,
            )
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { paddingValues ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = paddingValues + PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 0.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            daily?.let {
                item {
                    DailyChallengeCard(
                        day = it.day,
                        completed = it.completed,
                        total = it.total,
                        streak = it.streak,
                        onOpen = onOpenDaily,
                    )
                }
            }
            itemsIndexed(packNames) { index, name ->
                Card(Modifier.clickable{
                    onOpenPack(index)
                }, colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)) {
                    Box(Modifier.fillMaxWidth().padding(8.dp)) {
                        Text(name, Modifier.align(Alignment.Center), style = MaterialTheme.typography.displayMedium)
                    }
                }
            }
        }
    }
}

/** Binds [UnblockJamViewModel] to today's daily pack, reusing the pack level grid. */
@Composable
fun DailyLevelPage(backStack: NavBackStack<Route>, viewModel: UnblockJamViewModel) {
    val dailyPack by viewModel.dailyPack.collectAsState()
    val dailyStats by viewModel.dailyStats.collectAsState()

    // Generates on first open, and again if the day rolled over while the app was backgrounded.
    LaunchedEffect(Unit) { viewModel.refreshDaily() }

    val pack = dailyPack
    if (pack == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        return
    }
    LevelScreen(
        pack = pack,
        levelStats = dailyStats,
        title = stringResource(UiR.string.daily_challenge),
        onOpenLevel = { backStack.add(Route.DailyGame(it)) }
    )
}

/** Binds [UnblockJamViewModel] to the stateless [LevelScreen]. */
@Composable
fun LevelPage(backStack: NavBackStack<Route>, viewModel: UnblockJamViewModel, packIndex: Int) {
    val levelStats by viewModel.levelStats.collectAsState()
    LevelScreen(
        pack = LevelPack.PACKS[packIndex],
        levelStats = levelStats,
        onOpenLevel = { backStack.add(Route.Game(packIndex, it)) }
    )
}

/**
 * The puzzle grid for one pack, with no dependency on the ViewModel so it can be rendered
 * from a `@Preview` — see `src/screenshotTest`, which is where the store listing images
 * come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LevelScreen(
    pack: LevelPack,
    levelStats: Map<String, LevelStats>,
    onOpenLevel: (Int) -> Unit,
    title: String = stringResource(R.string.level_selector)
) {
    AppScaffold(title = title, scrollBehavior = appBarScrollBehavior()) { paddingValues ->
        LazyVerticalGrid(
            GridCells.Adaptive(88.dp),
            Modifier.fillMaxSize(),
            contentPadding = paddingValues + PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 0.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(pack.levels) { index, levelData ->
                Card(
                    Modifier.fillMaxWidth().clickable { onOpenLevel(index) },
                    colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)
                ) {
                    Box(Modifier.fillMaxSize().padding(8.dp)) {
                        Text("${index + 1}", Modifier.align(Alignment.Center))
                        val levelStat = levelStats[levelData.id]
                        Box(
                            Modifier.size(20.dp).align(Alignment.CenterEnd),
                            Alignment.Center
                        ) {
                            when {
                                levelStat == null -> return@Box
                                levelStat.bestScore <= levelData.optimalMoves -> IconStar()
                                else -> IconCheck()
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Binds [UnblockJamViewModel] to the stateless [GameScreen]. */
@Composable
fun GamePage(backStack: NavBackStack<Route>, viewModel: UnblockJamViewModel, packIndex: Int, levelIndex: Int) {
    val uiState by viewModel.uiState.collectAsState()
    val packStats by viewModel.levelStats.collectAsState()
    val dailyStats by viewModel.dailyStats.collectAsState()
    val dailyPack by viewModel.dailyPack.collectAsState()

    val isDaily = packIndex == UnblockJamViewModel.DAILY_PACK_INDEX
    val levels = if (isDaily) dailyPack?.levels else LevelPack.PACKS[packIndex].levels
    val levelStats = if (isDaily) dailyStats else packStats

    // Restoring straight onto a daily level can outrun pack generation.
    LaunchedEffect(isDaily) { if (isDaily) viewModel.refreshDaily() }

    LaunchedEffect(packIndex, levelIndex, levels) {
        viewModel.loadLevel(packIndex, levelIndex)
    }

    val startingLevelData = levels?.getOrNull(levelIndex)
    if (levels == null || startingLevelData == null) {
        Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator() }
        return
    }

    val isReady = uiState.packIndex == packIndex &&
            uiState.levelIndex == levelIndex &&
            uiState.currentLevelData != null
    val currentLevelData = if (isReady) uiState.currentLevelData!! else startingLevelData
    val currentLevelStats = levelStats[startingLevelData.id]

    // Everything except level navigation is the ViewModel's; that one is the back stack's.
    val actions = remember(viewModel, backStack, packIndex, levels.lastIndex) {
        object : GameActions by viewModel {
            override fun onLevelChange(newIndex: Int) {
                val clamped = newIndex.coerceIn(0, levels.lastIndex)
                backStack.setLast(
                    if (isDaily) Route.DailyGame(clamped) else Route.Game(packIndex, clamped)
                )
            }
        }
    }

    GameScreen(
        state = GameUiState(
            levelData = currentLevelData,
            levelIndex = levelIndex,
            maxLevelIndex = levels.lastIndex,
            moves = if (isReady) viewModel.getCurrentMoves() else 0,
            bestScore = currentLevelStats?.bestScore,
            isCompleted = currentLevelStats != null,
            isLevelWon = isReady && uiState.isLevelWon,
            canUndo = isReady && uiState.history.isNotEmpty()
        ),
        actions = actions,
        onBack = { backStack.pop() }
    )
}

/**
 * The playable board, with no dependency on the ViewModel so it can be rendered from a
 * `@Preview` — see `src/screenshotTest`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameScreen(state: GameUiState, actions: GameActions, onBack: () -> Unit) {
    AppScaffold(
        title = {},
        onNavigateBack = onBack,
        scrollBehavior = appBarScrollBehavior(),
    ) { innerPadding ->
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(16.dp)
            ) {
                if (maxWidth > maxHeight) {
                    LandscapeGameLayout(state, actions)
                } else {
                    PortraitGameLayout(state, actions)
                }
            }
        }
    }
}

@Composable
private fun GameInfoBoxes(state: GameUiState, actions: GameActions) {
    LevelPickerBox(
        levelIndex = state.levelIndex,
        maxLevelIndex = state.maxLevelIndex,
        isCompleted = state.isCompleted,
        onLevelChange = actions::onLevelChange,
        // unblockjam calls its levels puzzles.
        title = stringResource(R.string.level),
    )
    MovesBox(
        moves = state.moves,
        bestScore = state.bestScore,
        optimalMoves = state.levelData.optimalMoves
    )
}

@Composable
private fun GameActionButtons(state: GameUiState, actions: GameActions) {
    // While playing show Undo/Restart; once solved they're replaced by the
    // "next level" button in the same row.
    if (!state.isLevelWon) {
        Button(
            onClick = { actions.onUndo() },
            enabled = state.canUndo
        ) {
            Text(stringResource(UiR.string.undo))
        }
        Button(
            onClick = { actions.onRestart() },
            enabled = state.canUndo
        ) {
            Text(stringResource(UiR.string.restart))
        }
    } else if (state.levelIndex < state.maxLevelIndex) {
        Button(onClick = { actions.onLevelChange(state.levelIndex + 1) }) {
            Text(stringResource(R.string.next_level))
        }
    }
}

@Composable
private fun LandscapeGameLayout(state: GameUiState, actions: GameActions) {
    Row(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.weight(1f).fillMaxHeight(),
            contentAlignment = Alignment.Center
        ) {
            GameBoard(
                levelData = state.levelData,
                onLevelChanged = actions::onBlockMoved,
                onLevelWon = actions::onLevelWon,
                isLevelWon = state.isLevelWon,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            GameInfoBoxes(state, actions)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GameActionButtons(state, actions)
            }
        }
    }
}

@Composable
private fun PortraitGameLayout(state: GameUiState, actions: GameActions) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically
        ) {
            GameInfoBoxes(state, actions)
        }
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            GameBoard(
                levelData = state.levelData,
                onLevelChanged = actions::onBlockMoved,
                onLevelWon = actions::onLevelWon,
                isLevelWon = state.isLevelWon,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            GameActionButtons(state, actions)
        }
    }
}
