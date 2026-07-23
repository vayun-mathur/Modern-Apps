package com.vayunmathur.games.logicgate.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.logicgate.data.ChapterId
import com.vayunmathur.games.logicgate.data.ChipCategory
import com.vayunmathur.games.logicgate.data.ChipLibrary
import com.vayunmathur.games.logicgate.data.CircuitEvaluator
import com.vayunmathur.games.logicgate.data.LevelDef
import com.vayunmathur.games.logicgate.data.Levels
import com.vayunmathur.games.logicgate.util.LogicViewModel
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text as LibText
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.util.NavBackStack
import kotlin.math.hypot
import kotlin.math.min

private sealed class TimelineItem {
    data class ChapterHeader(val chapterId: ChapterId) : TimelineItem()
    data class LevelRow(val levelIds: List<String>) : TimelineItem()
}

private fun buildTimelineItems(): List<TimelineItem> {
    val items = mutableListOf<TimelineItem>()
    var lastChapter: ChapterId? = null
    for (row in Levels.timelineRows) {
        if (row.isEmpty()) continue
        val firstId = row.first()
        val chapter = Levels.byId[firstId]?.chapter ?: continue
        if (chapter != lastChapter) {
            items.add(TimelineItem.ChapterHeader(chapter))
            lastChapter = chapter
        }
        items.add(TimelineItem.LevelRow(row))
    }
    return items
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProgressionScreen(
    backStack: NavBackStack<com.vayunmathur.games.logicgate.Route>,
    viewModel: LogicViewModel
) {
    val completed by viewModel.completedIds.collectAsState()
    val available = Levels.availableLevels(completed)
    val timelineItems = buildTimelineItems()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { LibText(text = "Logic Gate", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { backStack.add(com.vayunmathur.games.logicgate.Route.GameCenter) }) {
                        Icon(painterResource(id = android.R.drawable.btn_star_big_on), contentDescription = "Achievements")
                    }
                }
            )
        }
    ) { paddingValues ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(paddingValues).background(MaterialTheme.colorScheme.background)) {
            val calculatedSide = (maxWidth * 0.22f).coerceIn(48.dp, 96.dp)
            val sideOffset: Dp = calculatedSide
            val nodeSize: Dp = 68.dp
            val connectorH: Dp = 38.dp
            val chapterH: Dp = 58.dp
            val scroll = rememberScrollState()
            Column(modifier = Modifier.fillMaxSize().verticalScroll(scroll), horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(modifier = Modifier.height(12.dp))
                LibText(text = "Start with NAND. End with a computer.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f), modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(modifier = Modifier.height(4.dp))
                LibText(text = "Tap to play • Lines show dependencies • 36 levels", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8), modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(modifier = Modifier.height(16.dp))
                for (idx in timelineItems.indices) {
                    val item = timelineItems[idx]
                    when (item) {
                        is TimelineItem.ChapterHeader -> {
                            if (idx > 0) {
                                val prev = timelineItems[idx - 1]
                                if (prev is TimelineItem.LevelRow) MergeIntoChapterConnector(prev, sideOffset, connectorH, completed)
                            }
                            ChapterDivider(item.chapterId, Levels.chapters.find { it.id == item.chapterId }?.levelIds?.count { it in completed } ?: 0, Levels.chapters.find { it.id == item.chapterId }?.levelIds?.size ?: 0, Modifier.fillMaxWidth().height(chapterH).padding(horizontal = 12.dp))
                            if (idx + 1 < timelineItems.size) {
                                val next = timelineItems[idx + 1]
                                if (next is TimelineItem.LevelRow) BranchOutOfChapterConnector(next, sideOffset, connectorH, completed)
                            }
                        }
                        is TimelineItem.LevelRow -> {
                            LevelRowContent(item, completed, available, nodeSize) { lvlId ->
                                if (lvlId in completed || lvlId in available) backStack.add(com.vayunmathur.games.logicgate.Route.Game(lvlId))
                            }
                            if (idx + 1 < timelineItems.size) {
                                val next = timelineItems[idx + 1]
                                if (next is TimelineItem.LevelRow) RowToRowConnector(item, next, sideOffset, connectorH, completed)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(24.dp))
                Card(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        LibText(text = "${completed.size} / ${Levels.all.size} completed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color(0xFF7FD8BE))
                        Spacer(modifier = Modifier.height(8.dp))
                        LibText(text = "FOUNDATION 6 • ROUTING+BUS 12 • ARITH 6 • MEMORY 8 • CPU 4 = 36. Bus: 1b green thin 0xFF7ED8B6 2.7px, 4b orange 0xFFF59E0B 4.2px, 8b blue 0xFF60A5FA 6.0px. Ghost dashed yellow.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun ChapterDivider(chapterId: ChapterId, completedCount: Int, totalCount: Int, modifier: Modifier = Modifier) {
    val chapter = Levels.chapters.find { it.id == chapterId } ?: return
    Column(modifier = modifier, verticalArrangement = Arrangement.Center) {
        Box(modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF7FD8BE).copy(alpha = 0.95f)))
        Spacer(modifier = Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            LibText(text = "${chapter.name.uppercase()} — ${chapter.desc}", fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 0.8.sp, color = Color(0xFF7FD8BE), modifier = Modifier.padding(start = 4.dp).weight(1f))
            LibText(text = "$completedCount/$totalCount", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF94A3B8))
        }
    }
}

@Composable
private fun LevelRowContent(row: TimelineItem.LevelRow, completed: Set<String>, available: Set<String>, nodeSize: Dp, onClickLevel: (String) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        if (row.levelIds.size == 1) LevelNode(row.levelIds[0], row.levelIds[0] in completed, row.levelIds[0] in available, nodeSize, onClickLevel)
        else row.levelIds.forEachIndexed { idx, lvlId ->
            LevelNode(lvlId, lvlId in completed, lvlId in available, nodeSize, onClickLevel)
            if (idx == 0) Spacer(modifier = Modifier.width(56.dp))
        }
    }
}

@Composable
private fun LevelNode(levelId: String, isCompleted: Boolean, isAvailable: Boolean, size: Dp, onClick: (String) -> Unit) {
    val def = Levels.byId[levelId] ?: return
    val isLocked = !isCompleted && !isAvailable
    val bg = when { isCompleted -> Color(0xFF1E3A2F); isAvailable -> Color(0xFF1B2E41); else -> Color(0xFF242A33) }
    val borderCol = when { isCompleted -> Color(0xFF22C55E); isAvailable -> Color(0xFF7FD8BE); else -> Color(0xFF4B5563) }
    val borderW = if (isAvailable) 3.5.dp else 2.dp
    val targetDef = try { ChipLibrary.get(def.targetChipId) } catch (_: Exception) { null }
    val busW = targetDef?.dominantBusWidth() ?: def.inputWidths.maxOrNull() ?: 1
    val busColor = when (busW) { 4 -> Color(0xFFF59E0B); 8 -> Color(0xFF60A5FA); else -> Color(0xFF7ED8B6) }
    Column(modifier = Modifier.widthIn(min = 96.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.size(size).clip(CircleShape).background(bg).border(borderW, borderCol, CircleShape).clip(CircleShape).clickable(enabled = !isLocked) { onClick(levelId) }, contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LibText(text = def.displayName.take(10), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = when { isLocked -> Color(0xFF6B7280); isCompleted -> Color(0xFF86EFAC); else -> Color.White })
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (busW > 1) Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(busColor))
                    when {
                        isLocked -> LibText(text = "LOCK", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color(0xFF6B7280))
                        isCompleted -> LibText(text = "DONE", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color(0xFF22C55E))
                        else -> LibText(text = "PLAY", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = Color(0xFF7FD8BE))
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(5.dp))
        LibText(text = def.displayName, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (isLocked) Color(0xFF6B7280) else MaterialTheme.colorScheme.onBackground)
        if (busW > 1) {
            Spacer(modifier = Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(busColor))
                LibText(text = "${busW}b ${if (busW == 8) "BUS" else "nibble"}", fontSize = 8.sp, color = busColor.copy(alpha = 0.85f))
            }
        }
    }
}

@Composable
private fun RowToRowConnector(fromRow: TimelineItem.LevelRow, toRow: TimelineItem.LevelRow, sideOffset: Dp, height: Dp, completed: Set<String>) {
    Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
        val cx = center.x; val leftX = cx - sideOffset.toPx(); val rightX = cx + sideOffset.toPx()
        fun xFor(id: String, row: TimelineItem.LevelRow) = if (row.levelIds.size == 1) cx else if (row.levelIds[0] == id) leftX else rightX
        for (toId in toRow.levelIds) {
            val def = Levels.byId[toId] ?: continue; val toX = xFor(toId, toRow)
            for (pr in def.prereqs) if (pr in fromRow.levelIds) {
                val fromX = xFor(pr, fromRow)
                drawLine(if (pr in completed) Color(0xFF7FD8BE) else Color(0xFF374151), Offset(fromX, 0f), Offset(toX, size.height), strokeWidth = 6f)
            }
        }
    }
}

@Composable
private fun MergeIntoChapterConnector(fromRow: TimelineItem.LevelRow, sideOffset: Dp, height: Dp, completed: Set<String>) {
    Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
        val cx = center.x; val leftX = cx - sideOffset.toPx(); val rightX = cx + sideOffset.toPx()
        fun xFor(id: String) = if (fromRow.levelIds.size == 1) cx else if (fromRow.levelIds[0] == id) leftX else rightX
        for (id in fromRow.levelIds) drawLine(if (id in completed) Color(0xFF7FD8BE) else Color(0xFF374151), Offset(xFor(id), 0f), Offset(cx, size.height), strokeWidth = 6f)
    }
}

@Composable
private fun BranchOutOfChapterConnector(toRow: TimelineItem.LevelRow, sideOffset: Dp, height: Dp, completed: Set<String>) {
    Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
        val cx = center.x; val leftX = cx - sideOffset.toPx(); val rightX = cx + sideOffset.toPx()
        fun xFor(id: String) = if (toRow.levelIds.size == 1) cx else if (toRow.levelIds[0] == id) leftX else rightX
        for (id in toRow.levelIds) {
            val def = Levels.byId[id] ?: continue
            val unlocked = def.prereqs.all { it in completed }
            drawLine(if (unlocked) Color(0xFF7FD8BE) else Color(0xFF374151), Offset(cx, 0f), Offset(xFor(id), size.height), strokeWidth = 6f)
        }
    }
}

@Composable
fun TruthTableView(level: LevelDef, failingRows: List<Int>, modifier: Modifier = Modifier) {
    val target = try { ChipLibrary.get(level.targetChipId) } catch (_: Exception) { null }
    if (target == null) {
        Column(modifier = modifier.background(Color(0xFF0E141B), RoundedCornerShape(8.dp)).padding(8.dp)) {
            LibText(text = "Unknown target chip ${level.targetChipId}", fontSize = 10.sp, color = Color(0xFFFF8A80))
        }
        return
    }
    val totalInBits = level.totalInputBits
    val totalOutBits = level.totalOutputBits

    val rnd = java.util.Random(1)
    val displayLimit = if (totalInBits <= 10) minOf(64, (1 shl totalInBits)) else 28
    val inputVectors: List<List<Boolean>> = if (totalInBits <= 10) {
        (0 until (1 shl totalInBits)).take(displayLimit).map { c -> List(totalInBits) { i -> ((c shr i) and 1) == 1 } }
    } else {
        val vs = mutableListOf<List<Boolean>>()
        vs.add(List(totalInBits) { false })
        vs.add(List(totalInBits) { true })
        vs.add(List(totalInBits) { it % 2 == 0 })
        vs.add(List(totalInBits) { it % 3 == 0 })
        while (vs.size < displayLimit) vs.add(List(totalInBits) { rnd.nextBoolean() })
        vs
    }
    val outputRows: List<List<Boolean>> = inputVectors.map { vec -> target.eval(vec).take(totalOutBits) }

    val scroll = rememberScrollState()
    val hScroll = rememberScrollState()
    Column(modifier = modifier.verticalScroll(scroll).background(Color(0xFF0E141B), RoundedCornerShape(8.dp)).padding(8.dp)) {
        LibText(text = "${level.displayName} • ${totalInBits}b in [${level.inputWidths.joinToString(",")}] → ${totalOutBits}b out [${level.outputWidths.joinToString(",")}] • target ${target.displayName}", fontSize = 9.sp, color = Color(0xFF64748B))
        if (totalInBits > 10) LibText(text = "Sampled $displayLimit / ${1 shl minOf(totalInBits, 20)}+ cases (bus needs JOIN_8/SPLIT_8)", fontSize = 8.sp, color = Color.Gray)
        Spacer(modifier = Modifier.height(6.dp))
        Row(modifier = Modifier.horizontalScroll(hScroll), verticalAlignment = Alignment.CenterVertically) {
            level.inputs.forEachIndexed { idx, name ->
                val w = level.inputWidth(idx)
                val col = when (w) { 4 -> Color(0xFFF59E0B); 8 -> Color(0xFF60A5FA); else -> Color(0xFF7DD3FC) }
                Row(modifier = Modifier.widthIn(min = if (w == 1) 28.dp else 68.dp).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(col))
                    Spacer(modifier = Modifier.width(2.dp))
                    LibText(text = if (w == 1) name.take(4) else "$name[$w]", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = col)
                }
            }
            LibText(text = " | ", fontSize = 9.sp, color = Color.Gray, modifier = Modifier.padding(horizontal = 4.dp))
            level.outputs.forEachIndexed { idx, name ->
                val w = level.outputWidth(idx)
                val col = when (w) { 4 -> Color(0xFFF59E0B); 8 -> Color(0xFF60A5FA); else -> Color(0xFFFDE68A) }
                Row(modifier = Modifier.widthIn(min = if (w == 1) 36.dp else 72.dp).padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(5.dp).clip(CircleShape).background(col))
                    Spacer(modifier = Modifier.width(2.dp))
                    LibText(text = if (w == 1) name.take(5) else "$name[$w]", fontSize = 8.sp, fontWeight = FontWeight.Bold, color = col)
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        outputRows.forEachIndexed { vecIdx, outVals ->
            val isFail = vecIdx in failingRows
            val bg = if (isFail) Color(0x44FF4444) else Color.Transparent
            Row(modifier = Modifier.fillMaxWidth().background(bg, RoundedCornerShape(4.dp)).padding(vertical = 2.dp).horizontalScroll(hScroll), verticalAlignment = Alignment.CenterVertically) {
                val flatIn = inputVectors[vecIdx]
                level.inputs.forEachIndexed { inTermIdx, _ ->
                    val off = level.inputBitOffset(inTermIdx)
                    val w = level.inputWidth(inTermIdx)
                    val slice = if (off + w <= flatIn.size) flatIn.slice(off until off + w) else List(w) { false }
                    if (w == 1) {
                        LibText(text = if (slice[0]) "1" else "0", modifier = Modifier.width(28.dp), fontSize = 10.sp, color = Color.White)
                    } else {
                        val intVal = ChipLibrary.bitsToInt(slice)
                        val bin = slice.reversed().joinToString("") { if (it) "1" else "0" }
                        val decStr = if (w == 4) " ${intVal}h" else " $intVal"
                        LibText(text = "$bin$decStr", modifier = Modifier.widthIn(min = 68.dp).padding(end = 4.dp), fontSize = 9.sp, color = Color(0xFF7DD3FC))
                    }
                }
                LibText(text = " | ", fontSize = 10.sp, color = Color.Gray, modifier = Modifier.padding(horizontal = 2.dp))
                level.outputs.forEachIndexed { outTermIdx, _ ->
                    val off = level.outputBitOffset(outTermIdx)
                    val w = level.outputWidth(outTermIdx)
                    val slice = if (off + w <= outVals.size) outVals.slice(off until off + w) else List(w) { false }
                    if (w == 1) {
                        LibText(text = if (slice[0]) "1" else "0", modifier = Modifier.width(if (outVals.size <= 5) 36.dp else 24.dp), fontSize = 10.sp, color = if (isFail) Color(0xFFFF8A80) else Color(0xFF86EFAC))
                    } else {
                        val intVal = ChipLibrary.bitsToInt(slice)
                        val bin = slice.reversed().joinToString("") { if (it) "1" else "0" }.take(8)
                        LibText(text = "$bin $intVal", modifier = Modifier.widthIn(min = 72.dp).padding(end = 4.dp), fontSize = 9.sp, color = if (isFail) Color(0xFFFF8A80) else Color(0xFF86EFAC))
                    }
                }
                if (isFail) LibText(text = " ✗", fontSize = 10.sp, color = Color(0xFFFF8A80))
            }
        }
        if (failingRows.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp)); LibText(text = "${failingRows.size} failing • check wiring width match JOIN_8/SPLIT_8", fontSize = 10.sp, color = Color(0xFFFF8A80))
        }
        if (level.id == "SR_LATCH") {
            Spacer(modifier = Modifier.height(6.dp)); LibText(text = "SR latch: 2 NOR cross-coupled feedback – cycle expected, structural pass enabled", fontSize = 8.sp, color = Color(0xFFF59E0B))
        }
    }
}

@Composable
fun InventoryBar(
    allowed: List<String>,
    unlockedChips: Set<String>,
    onChipDragStart: (chipId: String, global: Offset) -> Unit,
    onChipDrag: (chipId: String, global: Offset) -> Unit,
    onChipDrop: (chipId: String, global: Offset) -> Unit,
    modifier: Modifier = Modifier
) {
    var draggingChip by remember { mutableStateOf<String?>(null) }
    val rowScroll = rememberScrollState()
    Column(modifier = modifier.background(Color(0xFF131C26), RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp)).padding(8.dp)) {
        LibText(text = "Inventory: 1b green thin • 4b orange • 8b blue • 0N JOIN_8/SPLIT_8 converters cut tedium. Drag chip onto board.", fontSize = 9.sp, color = Color(0xFF94A3B8), modifier = Modifier.padding(bottom = 8.dp))
        Row(
            modifier = if (draggingChip != null) Modifier else Modifier.horizontalScroll(rowScroll),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            allowed.filter { it in unlockedChips }.sortedBy { ChipLibrary.get(it).nandCost }.forEach { chipId ->
                DraggableChipItem(
                    chipId = chipId,
                    onDragStart = { id, g -> draggingChip = id; onChipDragStart(id, g) },
                    onDrag = { id, g -> onChipDrag(id, g) },
                    onDrop = { id, g -> draggingChip = null; onChipDrop(id, g) }
                )
            }
        }
    }
}

@Composable
private fun DraggableChipItem(
    chipId: String,
    onDragStart: (String, Offset) -> Unit,
    onDrag: (String, Offset) -> Unit,
    onDrop: (String, Offset) -> Unit
) {
    val def = ChipLibrary.get(chipId)
    val baseCol = when (def.category) {
        ChipCategory.PRIMITIVE -> Color(0xFF1E3A4A)
        ChipCategory.FOUNDATION -> Color(0xFF14532D)
        ChipCategory.ROUTING -> Color(0xFF713F12)
        ChipCategory.BUS -> Color(0xFF3B2E4A)
        ChipCategory.ARITH -> Color(0xFF7C2D12)
        ChipCategory.MEMORY -> Color(0xFF3B0764)
        ChipCategory.CPU -> Color(0xFF881337)
    }
    val busW = def.dominantBusWidth()
    val busColor = when (busW) { 4 -> Color(0xFFF59E0B); 8 -> Color(0xFF60A5FA); else -> Color(0xFF7ED8B6) }
    val borderColor = if (def.isBus) busColor.copy(alpha = 0.85f) else Color.Transparent
    var chipPosInRoot by remember { mutableStateOf(Offset.Zero) }
    val chipPosRef by rememberUpdatedState(chipPosInRoot)
    var isDragging by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .onGloballyPositioned { c -> chipPosInRoot = c.positionInRoot() }
            .background(baseCol, RoundedCornerShape(9.dp))
            .border(if (isDragging) 2.dp else if (def.isBus) 1.5.dp else 0.dp, if (isDragging) Color.White else borderColor, RoundedCornerShape(9.dp))
            .pointerInput(chipId) {
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDownGlobal()
                        val startGlobal = chipPosRef + down.position
                        var dragActive = false
                        var curGlobal = startGlobal
                        while (true) {
                            val ev = awaitPointerEvent()
                            val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                            if (ch.changedToUpIgnoreConsumed()) {
                                if (dragActive) onDrop(chipId, curGlobal)
                                if (isDragging) isDragging = false
                                break
                            }
                            val total = ch.position - down.position
                            curGlobal = startGlobal + total
                            if (!dragActive && total.getDistance() > 14f) {
                                dragActive = true; isDragging = true; onDragStart(chipId, curGlobal)
                            }
                            if (dragActive) { ch.consume(); onDrag(chipId, curGlobal) }
                        }
                    }
                }
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (def.isBus || busW > 1) Box(modifier = Modifier.size(7.dp).clip(CircleShape).background(busColor))
                LibText(text = def.displayName, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                LibText(text = "${def.inputs.size}→${def.outputs.size} • ${def.nandCost}N", fontSize = 9.sp, color = Color.White.copy(alpha = 0.65f))
                if (busW > 1) LibText(text = "• ${busW}b", fontSize = 8.sp, color = busColor)
            }
        }
    }
}

private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.awaitFirstDownGlobal(): androidx.compose.ui.input.pointer.PointerInputChange {
    while (true) {
        val ev = awaitPointerEvent()
        if (ev.type == androidx.compose.ui.input.pointer.PointerEventType.Press) {
            val d = ev.changes.firstOrNull { !it.isConsumed } ?: continue
            return d
        }
    }
}
private fun Offset.getDistance(): Float = hypot(x, y)
