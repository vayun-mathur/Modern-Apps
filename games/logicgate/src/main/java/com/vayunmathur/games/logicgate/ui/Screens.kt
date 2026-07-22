package com.vayunmathur.games.logicgate.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.painterResource
import com.vayunmathur.games.logicgate.data.ChapterId
import com.vayunmathur.games.logicgate.data.ChipCategory
import com.vayunmathur.games.logicgate.data.ChipLibrary
import com.vayunmathur.games.logicgate.data.CircuitEvaluator
import com.vayunmathur.games.logicgate.data.LevelDef
import com.vayunmathur.games.logicgate.data.Levels
import com.vayunmathur.games.logicgate.util.LogicViewModel
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.util.NavBackStack
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
    val bestNands by viewModel.bestNands.collectAsState()
    val available = Levels.availableLevels(completed)
    val timelineItems = buildTimelineItems()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Logic Gate", fontWeight = FontWeight.Bold) },
                actions = {
                    IconButton(onClick = { backStack.add(com.vayunmathur.games.logicgate.Route.GameCenter) }) {
                        Icon(
                            painterResource(id = android.R.drawable.btn_star_big_on),
                            contentDescription = "Achievements"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            val sideOffset: Dp = 88.dp
            val nodeSize: Dp = 68.dp
            val connectorH: Dp = 38.dp
            val chapterH: Dp = 58.dp
            val scroll = rememberScrollState()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scroll),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Start with NAND. End with a computer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "Tap to play • Locked levels need prerequisites • Lines show dependencies",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF94A3B8),
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(Modifier.height(16.dp))

                for (idx in timelineItems.indices) {
                    val item = timelineItems[idx]
                    when (item) {
                        is TimelineItem.ChapterHeader -> {
                            // Connector from previous row into this chapter bar (merge)
                            if (idx > 0) {
                                val prev = timelineItems[idx - 1]
                                if (prev is TimelineItem.LevelRow) {
                                    // Determine if any next level depends on prev – always draw merge visually
                                    MergeIntoChapterConnector(
                                        fromRow = prev,
                                        sideOffset = sideOffset,
                                        height = connectorH,
                                        completed = completed
                                    )
                                }
                            }

                            ChapterDivider(
                                chapterId = item.chapterId,
                                completedCount = Levels.chapters.find { it.id == item.chapterId }?.levelIds?.count { it in completed } ?: 0,
                                totalCount = Levels.chapters.find { it.id == item.chapterId }?.levelIds?.size ?: 0,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(chapterH)
                                    .padding(horizontal = 12.dp)
                            )

                            // Connector out of chapter into next row
                            if (idx + 1 < timelineItems.size) {
                                val next = timelineItems[idx + 1]
                                if (next is TimelineItem.LevelRow) {
                                    BranchOutOfChapterConnector(
                                        toRow = next,
                                        sideOffset = sideOffset,
                                        height = connectorH,
                                        completed = completed
                                    )
                                }
                            }
                        }

                        is TimelineItem.LevelRow -> {
                            LevelRowContent(
                                row = item,
                                completed = completed,
                                available = available,
                                bestNands = bestNands,
                                nodeSize = nodeSize,
                                sideOffset = sideOffset,
                                onClickLevel = { lvlId ->
                                    if (lvlId in completed || lvlId in available) {
                                        backStack.add(com.vayunmathur.games.logicgate.Route.Game(lvlId))
                                    }
                                }
                            )

                            // Connector to next level row (skip if next is header)
                            if (idx + 1 < timelineItems.size) {
                                val next = timelineItems[idx + 1]
                                if (next is TimelineItem.LevelRow) {
                                    RowToRowConnector(
                                        fromRow = item,
                                        toRow = next,
                                        sideOffset = sideOffset,
                                        height = connectorH,
                                        completed = completed
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "${completed.size} / ${Levels.all.size} completed",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF7FD8BE)
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Progress is gated: you must finish prerequisites to unlock next. At most 2 paths run in parallel (NOR/XNOR, DMUX/MUX4, FULL_ADDER/ADDER_4). Each chapter starts with a thick bar.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                        )
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun ChapterDivider(chapterId: ChapterId, completedCount: Int, totalCount: Int, modifier: Modifier = Modifier) {
    val chapter = Levels.chapters.find { it.id == chapterId } ?: return
    Column(modifier = modifier, verticalArrangement = Arrangement.Center) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFF7FD8BE).copy(alpha = 0.95f))
        )
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${chapter.name.uppercase()} — ${chapter.desc}",
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                letterSpacing = 0.8.sp,
                color = Color(0xFF7FD8BE),
                modifier = Modifier.padding(start = 4.dp)
            )
            Text(
                "$completedCount/$totalCount",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF94A3B8)
            )
        }
    }
}

@Composable
private fun LevelRowContent(
    row: TimelineItem.LevelRow,
    completed: Set<String>,
    available: Set<String>,
    bestNands: Map<String, Int>,
    nodeSize: Dp,
    sideOffset: Dp,
    onClickLevel: (String) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (row.levelIds.size == 1) {
            LevelNode(
                levelId = row.levelIds[0],
                isCompleted = row.levelIds[0] in completed,
                isAvailable = row.levelIds[0] in available,
                bestNand = bestNands[row.levelIds[0]],
                size = nodeSize,
                onClick = onClickLevel
            )
        } else {
            row.levelIds.forEachIndexed { idx, lvlId ->
                LevelNode(
                    levelId = lvlId,
                    isCompleted = lvlId in completed,
                    isAvailable = lvlId in available,
                    bestNand = bestNands[lvlId],
                    size = nodeSize,
                    onClick = onClickLevel
                )
                if (idx == 0) Spacer(Modifier.width(56.dp))
            }
        }
    }
}

@Composable
private fun LevelNode(
    levelId: String,
    isCompleted: Boolean,
    isAvailable: Boolean,
    bestNand: Int?,
    size: Dp,
    onClick: (String) -> Unit
) {
    val def = Levels.byId[levelId] ?: return
    val isLocked = !isCompleted && !isAvailable

    val bg = when {
        isCompleted && (bestNand ?: 999) <= def.optimalNands -> Color(0xFF0F3D2E)
        isCompleted -> Color(0xFF1E3A2F)
        isAvailable -> Color(0xFF1B2E41)
        else -> Color(0xFF242A33)
    }
    val borderCol = when {
        isCompleted -> Color(0xFF22C55E)
        isAvailable -> Color(0xFF7FD8BE)
        else -> Color(0xFF4B5563)
    }
    val borderW = if (isAvailable) 3.5.dp else 2.dp

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(min = 96.dp)) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(bg)
                .border(borderW, borderCol, CircleShape)
                .clickable(enabled = !isLocked) { onClick(levelId) },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    def.displayName.take(7),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = when {
                        isLocked -> Color(0xFF6B7280)
                        isCompleted -> Color(0xFF86EFAC)
                        else -> Color.White
                    }
                )
                Spacer(Modifier.height(2.dp))
                if (isLocked) {
                    Text("LOCK", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF6B7280))
                } else if (isCompleted) {
                    val star = if ((bestNand ?: 999) <= def.optimalNands) "★★★" else if ((bestNand ?: 999) <= (def.optimalNands * 1.5).toInt()) "★★" else "★"
                    Text(star, fontSize = 10.sp, color = Color(0xFFFDE68A))
                } else {
                    Text("PLAY", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF7FD8BE))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            def.displayName,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (isLocked) Color(0xFF6B7280) else MaterialTheme.colorScheme.onBackground
        )
        when {
            isCompleted && bestNand != null -> {
                Text(
                    "${bestNand}N / ${def.optimalNands}N",
                    fontSize = 10.sp,
                    color = if (bestNand <= def.optimalNands) Color(0xFF22C55E) else Color(0xFF94A3B8)
                )
            }
            isLocked -> {
                val need = def.prereqs.joinToString(", ") { Levels.byId[it]?.displayName ?: it }
                Text(
                    "needs $need",
                    fontSize = 9.sp,
                    color = Color(0xFF6B7280),
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
            }
            isAvailable -> {
                Text(
                    "${def.optimalNands}N optimal",
                    fontSize = 10.sp,
                    color = Color(0xFF7FD8BE).copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
private fun RowToRowConnector(
    fromRow: TimelineItem.LevelRow,
    toRow: TimelineItem.LevelRow,
    sideOffset: Dp,
    height: Dp,
    completed: Set<String>
) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val cx = center.x
        val leftX = cx - sideOffset.toPx()
        val rightX = cx + sideOffset.toPx()

        fun xFor(id: String, row: TimelineItem.LevelRow): Float {
            return if (row.levelIds.size == 1) cx
            else if (row.levelIds[0] == id) leftX else rightX
        }

        // Draw each dependency edge fromRow -> toRow
        for (toId in toRow.levelIds) {
            val def = Levels.byId[toId] ?: continue
            val toX = xFor(toId, toRow)
            var hasEdge = false
            for (pr in def.prereqs) {
                if (pr in fromRow.levelIds) {
                    hasEdge = true
                    val fromX = xFor(pr, fromRow)
                    val bothDone = pr in completed && (toId in completed || pr in completed)
                    drawLine(
                        color = if (bothDone) Color(0xFF7FD8BE) else Color(0xFF374151),
                        start = Offset(fromX, 0f),
                        end = Offset(toX, size.height),
                        strokeWidth = 6f
                    )
                }
            }
            // If this row's only prereq is in fromRow but we already handled, nothing extra
            if (!hasEdge && fromRow.levelIds.size == 1 && toRow.levelIds.size == 1) {
                // fallback for linear chain where prereq is immediate predecessor
                val fromId = fromRow.levelIds[0]
                if (def.prereqs.contains(fromId)) {
                    drawLine(
                        color = if (fromId in completed) Color(0xFF7FD8BE) else Color(0xFF374151),
                        start = Offset(cx, 0f),
                        end = Offset(cx, size.height),
                        strokeWidth = 6f
                    )
                }
            }
        }
    }
}

@Composable
private fun MergeIntoChapterConnector(
    fromRow: TimelineItem.LevelRow,
    sideOffset: Dp,
    height: Dp,
    completed: Set<String>
) {
    Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
        val cx = center.x
        val leftX = cx - sideOffset.toPx()
        val rightX = cx + sideOffset.toPx()

        fun xFor(id: String): Float {
            return if (fromRow.levelIds.size == 1) cx
            else if (fromRow.levelIds[0] == id) leftX else rightX
        }

        for (id in fromRow.levelIds) {
            drawLine(
                color = if (id in completed) Color(0xFF7FD8BE) else Color(0xFF374151),
                start = Offset(xFor(id), 0f),
                end = Offset(cx, size.height),
                strokeWidth = 6f
            )
        }
    }
}

@Composable
private fun BranchOutOfChapterConnector(
    toRow: TimelineItem.LevelRow,
    sideOffset: Dp,
    height: Dp,
    completed: Set<String>
) {
    Canvas(modifier = Modifier.fillMaxWidth().height(height)) {
        val cx = center.x
        val leftX = cx - sideOffset.toPx()
        val rightX = cx + sideOffset.toPx()

        fun xFor(id: String): Float {
            return if (toRow.levelIds.size == 1) cx
            else if (toRow.levelIds[0] == id) leftX else rightX
        }

        for (id in toRow.levelIds) {
            val def = Levels.byId[id] ?: continue
            val unlocked = def.prereqs.all { it in completed }
            drawLine(
                color = if (unlocked) Color(0xFF7FD8BE) else Color(0xFF374151),
                start = Offset(cx, 0f),
                end = Offset(xFor(id), size.height),
                strokeWidth = 6f
            )
        }
    }
}

// --- TruthTable + Inventory (kept minimal, game screen) ---

@Composable
fun TruthTableView(
    level: LevelDef,
    failingRows: List<Int>,
    modifier: Modifier = Modifier
) {
    val target = ChipLibrary.get(level.targetChipId)
    val rows = if (level.inputs.size <= 10) {
        CircuitEvaluator.generateTruthTable(target, level.inputs.size, level.outputs.size, limit = 64)
    } else {
        val rnd = java.util.Random(1)
        val vecs = mutableListOf<List<Boolean>>().apply {
            add(List(level.inputs.size) { false })
            add(List(level.inputs.size) { true })
            repeat(20) { add(List(level.inputs.size) { rnd.nextBoolean() }) }
        }
        vecs.map { target.eval(it).take(level.outputs.size) }
    }
    val scroll = rememberScrollState()
    Column(modifier = modifier.verticalScroll(scroll).background(Color(0xFF0E141B), RoundedCornerShape(8.dp)).padding(8.dp)) {
        if (level.inputs.size <= 10) {
            Row {
                level.inputs.take(6).forEach { Text(it.take(3), Modifier.width(28.dp), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFF7DD3FC)) }
                if (level.inputs.size > 6) Text("…", Modifier.width(16.dp), fontSize = 9.sp, color = Color.Gray)
                Text(" | ", fontSize = 9.sp, color = Color.Gray)
                level.outputs.take(5).forEach { Text(it.take(4), Modifier.width(36.dp), fontSize = 9.sp, fontWeight = FontWeight.Bold, color = Color(0xFFFDE68A)) }
            }
            Spacer(Modifier.height(4.dp))
            rows.forEachIndexed { idx, outVals ->
                val isFail = idx in failingRows
                val bg = if (isFail) Color(0x44FF4444) else Color.Transparent
                Row(Modifier.fillMaxWidth().background(bg, RoundedCornerShape(4.dp)).padding(vertical = 1.dp)) {
                    for (b in 0 until minOf(level.inputs.size, 6)) {
                        val v = ((idx shr b) and 1) == 1
                        Text(if (v) "1" else "0", Modifier.width(28.dp), fontSize = 10.sp, color = Color.White)
                    }
                    if (level.inputs.size > 6) Text("…", Modifier.width(16.dp), fontSize = 10.sp)
                    Text(" | ", fontSize = 10.sp, color = Color.Gray)
                    outVals.take(5).forEach { v ->
                        Text(if (v) "1" else "0", Modifier.width(36.dp), fontSize = 10.sp, color = if (isFail) Color(0xFFFF8A80) else Color(0xFF86EFAC))
                    }
                    if (isFail) Text(" ✗", fontSize = 10.sp, color = Color(0xFFFF8A80))
                }
            }
        } else {
            Text("Sampled ${rows.size} cases", fontSize = 9.sp, color = Color.Gray)
            Spacer(Modifier.height(6.dp))
            rows.take(24).forEachIndexed { idx, outVals ->
                val isFail = idx in failingRows
                Row(Modifier.background(if (isFail) Color(0x44FF4444) else Color.Transparent, RoundedCornerShape(4.dp)).padding(vertical = 1.dp)) {
                    outVals.forEach { v ->
                        Text(if (v) "1" else "0", Modifier.width(18.dp), fontSize = 10.sp, color = if (isFail) Color(0xFFFF8A80) else Color(0xFF86EFAC))
                    }
                    if (isFail) Text(" ✗", fontSize = 10.sp, color = Color(0xFFFF8A80))
                }
            }
        }
        if (failingRows.isNotEmpty()) Spacer(Modifier.height(6.dp))
        if (failingRows.isNotEmpty()) Text("${failingRows.size} failing", fontSize = 10.sp, color = Color(0xFFFF8A80))
    }
}

@Composable
fun InventoryBar(
    allowed: List<String>,
    unlockedChips: Set<String>,
    onAddGate: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier.background(Color(0xFF151E28), RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)).padding(8.dp)) {
        Text("Tap chip to place • Tap dot→dot to wire • Long-press to delete", fontSize = 10.sp, color = Color(0xFF94A3B8), modifier = Modifier.padding(bottom = 6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(allowed.filter { it in unlockedChips }.sortedBy { ChipLibrary.get(it).nandCost }) { chipId ->
                val def = ChipLibrary.get(chipId)
                val col = when (def.category) {
                    ChipCategory.PRIMITIVE -> Color(0xFF1E3A4A)
                    ChipCategory.FOUNDATION -> Color(0xFF14532D)
                    ChipCategory.ROUTING -> Color(0xFF713F12)
                    ChipCategory.ARITH -> Color(0xFF7C2D12)
                    ChipCategory.MEMORY -> Color(0xFF3B0764)
                    ChipCategory.CPU -> Color(0xFF881337)
                }
                Box(
                    modifier = Modifier.background(col, RoundedCornerShape(8.dp)).clickable { onAddGate(chipId) }.padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(def.displayName, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("${def.nandCost}N ${def.inputs.size}→${def.outputs.size}", fontSize = 9.sp, color = Color.White.copy(alpha = 0.6f))
                    }
                }
            }
        }
    }
}
