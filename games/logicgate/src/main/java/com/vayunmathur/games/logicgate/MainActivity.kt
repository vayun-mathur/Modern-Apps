package com.vayunmathur.games.logicgate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.games.logicgate.data.ChipLibrary
import com.vayunmathur.games.logicgate.data.Levels
import com.vayunmathur.games.logicgate.ui.CircuitCanvas
import com.vayunmathur.games.logicgate.ui.InventoryBar
import com.vayunmathur.games.logicgate.ui.LogicGateTheme
import com.vayunmathur.games.logicgate.ui.ProgressionScreen
import com.vayunmathur.games.logicgate.ui.TruthTableView
import com.vayunmathur.games.logicgate.util.AppBackupAgent
import com.vayunmathur.games.logicgate.util.EvalStatus
import com.vayunmathur.games.logicgate.util.LogicViewModel
import com.vayunmathur.library.ui.AchievementNotification
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.GameCenterScreen
import com.vayunmathur.library.ui.IconNavigation
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
    @Serializable data object Progression : Route
    @Serializable data class Game(val levelId: String) : Route
    @Serializable data object GameCenter : Route
}

@Composable
fun Navigation(viewModel: LogicViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.Progression)
    val newAchievement by viewModel.achievementsManager.newAchievement.collectAsState()
    Box(modifier = Modifier.fillMaxSize()) {
        MainNavigation(backStack) {
            entry<Route.Progression> { ProgressionScreen(backStack, viewModel) }
            entry<Route.Game> { GameScreen(backStack, viewModel, it.levelId) }
            entry<Route.GameCenter> {
                GameCenterScreen(backupAgent = AppBackupAgent(), manager = viewModel.achievementsManager, onBack = { backStack.pop() })
            }
        }
        newAchievement?.let { AchievementNotification(it) { viewModel.dismissAchievement() } }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GameScreen(backStack: NavBackStack<Route>, viewModel: LogicViewModel, levelId: String) {
    val level = Levels.get(levelId)
    val uiState by viewModel.uiState.collectAsState()
    val unlocked by viewModel.unlockedChips.collectAsState()
    val completed by viewModel.completedIds.collectAsState()

    LaunchedEffect(levelId) { viewModel.selectLevel(levelId) }
    val isCurrent = uiState.currentLevelId == levelId

    var canvasRect by remember { mutableStateOf(Rect.Zero) }
    var draggingChipId by remember { mutableStateOf<String?>(null) }
    var draggingChipGlobal by remember { mutableStateOf(Offset.Zero) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "${level.displayName}  ${if (levelId in completed) "✓" else ""}", fontSize = 16.sp) },
                navigationIcon = { IconNavigation(backStack) },
                actions = {
                    Row(modifier = Modifier.padding(end = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { viewModel.toggleTruthTable() }) { Text(text = if (uiState.showTruthTable) "Hide TT" else "Show TT", fontSize = 10.sp) }
                    }
                }
            )
        },
        bottomBar = {
            if (isCurrent) {
                InventoryBar(
                    allowed = level.allowedChipIds,
                    unlockedChips = unlocked,
                    onChipDragStart = { chipId, global ->
                        draggingChipId = chipId
                        draggingChipGlobal = global
                    },
                    onChipDrag = { chipId, global ->
                        draggingChipId = chipId
                        draggingChipGlobal = global
                    },
                    onChipDrop = { chipId, global ->
                        val local = Offset(global.x - canvasRect.left, global.y - canvasRect.top)
                        if (canvasRect.contains(global)) {
                            // Free placement – wherever finger lifted, no grid snap
                            viewModel.addGateAt(chipId, local.x, local.y)
                        }
                        draggingChipId = null
                        draggingChipGlobal = Offset.Zero
                    },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    ) { padding ->
        if (!isCurrent) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = androidx.compose.ui.Alignment.Center) { Text(text = "Loading...") }
            return@Scaffold
        }

        Column(modifier = Modifier.fillMaxSize().padding(padding).background(Color(0xFF0B1218))) {
            val statusText = when (val s = uiState.evalStatus) {
                is EvalStatus.Ok -> if (s.isFullyCorrect) "✓ CORRECT — ${level.unlocksChipId?.let { "Unlocked $it!" } ?: "Done!"}"
                else "${s.passingRows}/${s.totalRows} passing — ${s.failingRows.size} failing"
                is EvalStatus.Error -> "Error: ${s.msg}"
                is EvalStatus.Cycle -> "Cycle: ${s.ids.take(3).joinToString()}"
                else -> "Drag chip onto board to place • Drag gates & I/O to move • Drag output dot → input dot to wire • Tap wire to delete • Long-press gate to delete"
            }
            val statusColor = when {
                uiState.evalStatus is EvalStatus.Ok && (uiState.evalStatus as EvalStatus.Ok).isFullyCorrect -> Color(0xFF22C55E)
                uiState.evalStatus is EvalStatus.Ok && (uiState.evalStatus as EvalStatus.Ok).passingRows > 0 -> Color(0xFFFBBF24)
                else -> Color(0xFF94A3B8)
            }

            Box(modifier = Modifier.fillMaxWidth().background(Color(0xFF16202B)).padding(horizontal = 12.dp, vertical = 7.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = statusText, fontSize = 11.sp, color = statusColor)
                        Text(text = level.description, fontSize = 10.sp, color = Color(0xFF94A3B8))
                        if (level.hint.isNotEmpty()) Text(text = "Hint: ${level.hint}", fontSize = 10.sp, color = Color(0xFF64748B))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = { viewModel.clearCircuit() }) { Text(text = "Clear", fontSize = 10.sp) }
                        if (uiState.wiringFrom != null) Button(onClick = { viewModel.cancelWiring() }) { Text(text = "Cancel Wire", fontSize = 10.sp) }
                        Card(modifier = Modifier.padding(start = 4.dp)) { Text(text = " ${uiState.circuit.gates.size} gates ", fontSize = 11.sp, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)) }
                    }
                }
            }

            // Canvas container – NO scroll wrapper that would steal drags. Full freeform.
            BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
                val wide = maxWidth > maxHeight
                Box(
                    modifier = Modifier.fillMaxSize().onGloballyPositioned { coords ->
                        val pos = coords.positionInRoot()
                        canvasRect = Rect(pos, Size(coords.size.width.toFloat(), coords.size.height.toFloat()))
                    }
                ) {
                    if (wide) {
                        Row(modifier = Modifier.fillMaxSize()) {
                            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                CircuitCanvas(
                                    level = level,
                                    gates = uiState.circuit.gates,
                                    wires = uiState.circuit.wires,
                                    outputMaps = uiState.circuit.outputMappings,
                                    inputPositions = uiState.circuit.inputPositions,
                                    outputPositions = uiState.circuit.outputPositions,
                                    wiringFrom = uiState.wiringFrom,
                                    onCreateWire = { f, t -> viewModel.createWire(f, t) },
                                    onStartWiring = { viewModel.startWiring(it) },
                                    onCancelWiring = { viewModel.cancelWiring() },
                                    onGateMove = { id, x, y -> viewModel.onGateMoved(id, x, y) },
                                    onInputTermMove = { idx, x, y -> viewModel.onInputMoved(idx, x, y) },
                                    onOutputTermMove = { idx, x, y -> viewModel.onOutputMoved(idx, x, y) },
                                    onGateDelete = { viewModel.removeGate(it) },
                                    onWireDelete = { viewModel.removeWire(it) },
                                    onOutputMapDelete = { viewModel.removeOutputMapping(it) },
                                    dragGhostLineEnd = uiState.dragGhostLineEnd,
                                    onGhostLine = { viewModel.updateGhostLine(it) },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            if (uiState.showTruthTable) {
                                val failing = (uiState.evalStatus as? EvalStatus.Ok)?.failingRows ?: emptyList()
                                TruthTableView(level, failing, modifier = Modifier.width(210.dp).fillMaxHeight().background(Color(0xFF0E141B)))
                            }
                        }
                    } else {
                        Column(modifier = Modifier.fillMaxSize()) {
                            if (uiState.showTruthTable) {
                                val failing = (uiState.evalStatus as? EvalStatus.Ok)?.failingRows ?: emptyList()
                                TruthTableView(level, failing, modifier = Modifier.fillMaxWidth().height(180.dp).background(Color(0xFF0E141B)))
                            }
                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                CircuitCanvas(
                                    level = level,
                                    gates = uiState.circuit.gates,
                                    wires = uiState.circuit.wires,
                                    outputMaps = uiState.circuit.outputMappings,
                                    inputPositions = uiState.circuit.inputPositions,
                                    outputPositions = uiState.circuit.outputPositions,
                                    wiringFrom = uiState.wiringFrom,
                                    onCreateWire = { f, t -> viewModel.createWire(f, t) },
                                    onStartWiring = { viewModel.startWiring(it) },
                                    onCancelWiring = { viewModel.cancelWiring() },
                                    onGateMove = { id, x, y -> viewModel.onGateMoved(id, x, y) },
                                    onInputTermMove = { idx, x, y -> viewModel.onInputMoved(idx, x, y) },
                                    onOutputTermMove = { idx, x, y -> viewModel.onOutputMoved(idx, x, y) },
                                    onGateDelete = { viewModel.removeGate(it) },
                                    onWireDelete = { viewModel.removeWire(it) },
                                    onOutputMapDelete = { viewModel.removeOutputMapping(it) },
                                    dragGhostLineEnd = uiState.dragGhostLineEnd,
                                    onGhostLine = { viewModel.updateGhostLine(it) },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }

                    if (draggingChipId != null) {
                        val isOver = canvasRect.contains(draggingChipGlobal)
                        val localGhost = Offset(draggingChipGlobal.x - canvasRect.left, draggingChipGlobal.y - canvasRect.top)
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val ghW = 116f
                            val ghH = 54f
                            val lt = Offset(localGhost.x - ghW / 2f, localGhost.y - ghH / 2f)
                            drawRoundRect(
                                color = if (isOver) Color(0xFFD1FAE5).copy(alpha = 0.92f) else Color(0xFF4B5563).copy(alpha = 0.62f),
                                topLeft = lt,
                                size = Size(ghW, ghH),
                                cornerRadius = CornerRadius(10f, 10f)
                            )
                            drawRoundRect(
                                color = if (isOver) Color(0xFF22C55E) else Color.White.copy(alpha = 0.28f),
                                topLeft = lt,
                                size = Size(ghW, ghH),
                                cornerRadius = CornerRadius(10f, 10f),
                                style = Stroke(width = if (isOver) 2.5f else 1.2f)
                            )
                        }
                    }
                }
            }
        }
    }
}
