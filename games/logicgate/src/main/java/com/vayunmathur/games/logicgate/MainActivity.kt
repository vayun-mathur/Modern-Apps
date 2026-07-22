package com.vayunmathur.games.logicgate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.games.logicgate.data.Levels
import com.vayunmathur.games.logicgate.ui.ChapterListScreen
import com.vayunmathur.games.logicgate.ui.CircuitCanvas
import com.vayunmathur.games.logicgate.ui.InventoryBar
import com.vayunmathur.games.logicgate.ui.LogicGateTheme
import com.vayunmathur.games.logicgate.ui.TruthTableView
import com.vayunmathur.games.logicgate.util.AppBackupAgent
import com.vayunmathur.games.logicgate.util.EvalStatus
import com.vayunmathur.games.logicgate.util.LogicViewModel
import com.vayunmathur.library.ui.AchievementNotification
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.GameCenterScreen
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            LogicGateTheme {
                val vm: LogicViewModel = viewModel()
                Navigation(vm)
            }
        }
    }
}

@Serializable
sealed interface Route : NavKey {
    @Serializable data object Chapters : Route
    @Serializable data class LevelList(val chapterName: String) : Route
    @Serializable data class Game(val levelId: String) : Route
    @Serializable data object GameCenter : Route
}

@Composable
fun Navigation(viewModel: LogicViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Chapters)
    val newAchievement by viewModel.achievementsManager.newAchievement.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.nextLevelEvent.collect { nextId ->
            // auto advance? show snackbar-ish – for now do nothing auto, let user click next
        }
    }

    Box(Modifier.fillMaxSize()) {
        MainNavigation(backStack) {
            entry<Route.Chapters> {
                ChapterListScreen(backStack, viewModel)
            }
            entry<Route.LevelList> {
                val cid = try { com.vayunmathur.games.logicgate.data.ChapterId.valueOf(it.chapterName) } catch (_: Exception) { com.vayunmathur.games.logicgate.data.ChapterId.FOUNDATION }
                com.vayunmathur.games.logicgate.ui.LevelListScreen(cid, backStack, viewModel)
            }
            entry<Route.Game> {
                GameScreen(backStack, viewModel, it.levelId)
            }
            entry<Route.GameCenter> {
                GameCenterScreen(
                    backupAgent = AppBackupAgent(),
                    manager = viewModel.achievementsManager,
                    onBack = { backStack.pop() }
                )
            }
        }
        newAchievement?.let {
            AchievementNotification(it) { viewModel.dismissAchievement() }
        }
    }
}

@Composable
fun GameScreen(backStack: NavBackStack<Route>, viewModel: LogicViewModel, levelId: String) {
    val level = Levels.get(levelId)
    val uiState by viewModel.uiState.collectAsState()
    val unlocked by viewModel.unlockedChips.collectAsState()
    val completed by viewModel.completedIds.collectAsState()

    LaunchedEffect(levelId) {
        viewModel.selectLevel(levelId)
    }

    // If current loaded level mismatches, loading
    val isCurrent = uiState.currentLevelId == levelId

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${level.displayName}  ${if (levelId in completed) "✓" else ""}", fontSize = 16.sp) },
                navigationIcon = { IconNavigation(backStack) },
                actions = {
                    Button(onClick = { viewModel.toggleTruthTable() }, modifier = Modifier.padding(end = 4.dp)) {
                        Text(if (uiState.showTruthTable) "Hide TT" else "Show TT", fontSize = 11.sp)
                    }
                }
            )
        },
        bottomBar = {
            if (isCurrent) {
                InventoryBar(
                    allowed = level.allowedChipIds,
                    unlockedChips = unlocked,
                    onAddGate = { viewModel.addGate(it) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    ) { padding ->
        if (!isCurrent) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = androidx.compose.ui.Alignment.Center) {
                Text("Loading...")
            }
            return@Scaffold
        }

        Column(
            Modifier.fillMaxSize().padding(padding).background(Color(0xFF0B1218))
        ) {
            // Status bar
            val status = uiState.evalStatus
            val statusText = when (status) {
                is EvalStatus.Ok -> if (status.isFullyCorrect) "✓ CORRECT — ${uiState.circuit.gates.sumOf { com.vayunmathur.games.logicgate.data.ChipLibrary.get(it.chipId).nandCost }}N / ${level.optimalNands}N optimal" else "${status.passingRows}/${status.totalRows} passing — ${status.failingRows.size} failing"
                is EvalStatus.Error -> "Error: ${status.msg}"
                is EvalStatus.Cycle -> "Cycle detected: ${status.ids.take(3).joinToString()}"
                else -> "Wire inputs → gates → outputs"
            }
            val statusColor = when {
                status is EvalStatus.Ok && status.isFullyCorrect -> Color(0xFF22C55E)
                status is EvalStatus.Ok && status.passingRows > 0 -> Color(0xFFFBBF24)
                else -> Color(0xFF94A3B8)
            }
            Box(
                Modifier.fillMaxWidth().background(Color(0xFF16202B)).padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(statusText, fontSize = 12.sp, color = statusColor)
                        Text(level.description, fontSize = 10.sp, color = Color(0xFF94A3B8))
                        if (level.hint.isNotEmpty()) {
                            Text("Hint: ${level.hint}", fontSize = 10.sp, color = Color(0xFF64748B))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { viewModel.clearCircuit() }) { Text("Clear", fontSize = 10.sp) }
                        if (uiState.wiringFrom != null) {
                            Button(onClick = { viewModel.cancelWiring() }) { Text("Cancel Wire", fontSize = 10.sp) }
                        }
                        val nandCost = uiState.circuit.gates.sumOf { com.vayunmathur.games.logicgate.data.ChipLibrary.get(it.chipId).nandCost }
                        Card(modifier = Modifier.padding(start = 4.dp)) {
                            Text(" ${nandCost}N ", fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
                        }
                    }
                }
            }

            Row(Modifier.weight(1f).fillMaxWidth()) {
                // Canvas scrollable area
                Box(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())
                ) {
                    CircuitCanvas(
                        level = level,
                        gates = uiState.circuit.gates,
                        wires = uiState.circuit.wires,
                        outputMaps = uiState.circuit.outputMappings,
                        wiringFrom = uiState.wiringFrom,
                        onStartWiringOutput = { viewModel.startWiring(it) },
                        onCompleteWiringInput = { viewModel.completeWiring(it) },
                        onGateDrag = { id, x, y -> viewModel.onGateMoved(id, x, y) },
                        onGateLongPress = { id -> viewModel.removeGate(id) },
                        modifier = Modifier.fillMaxWidth().height(900.dp)
                    )
                }

                if (uiState.showTruthTable) {
                    val failing = (uiState.evalStatus as? EvalStatus.Ok)?.failingRows ?: emptyList()
                    TruthTableView(
                        level = level,
                        failingRows = failing,
                        modifier = Modifier.width(200.dp).fillMaxHeight().background(Color(0xFF0E141B))
                    )
                }
            }
        }
    }
}
