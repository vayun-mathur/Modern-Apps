package com.vayunmathur.games.logicgate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.logicgate.data.ChipCategory
import com.vayunmathur.games.logicgate.data.ChipLibrary
import com.vayunmathur.games.logicgate.data.Levels
import com.vayunmathur.games.logicgate.data.LevelDef
import com.vayunmathur.games.logicgate.data.ChapterId
import com.vayunmathur.games.logicgate.data.CircuitEvaluator
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.collectAsState
import com.vayunmathur.games.logicgate.util.LogicViewModel
import com.vayunmathur.library.util.NavBackStack

@Composable
fun ChapterListScreen(
    backStack: NavBackStack<com.vayunmathur.games.logicgate.Route>,
    viewModel: LogicViewModel
) {
    val completed by viewModel.completedIds.collectAsState()
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text("Logic Gate", style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(vertical = 8.dp))
            Text("Start with NAND. End with a computer.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f))
            Spacer(Modifier.height(4.dp))
            Text("Tap input/output dots to wire • Drag gates to move • Long-press gate to delete", style = MaterialTheme.typography.labelSmall, color = Color(0xFF94A3B8))
            Spacer(Modifier.height(8.dp))
        }
        items(Levels.chapters) { chapter ->
            val doneCount = chapter.levelIds.count { it in completed }
            Card(
                modifier = Modifier.fillMaxWidth().clickable { backStack.add(com.vayunmathur.games.logicgate.Route.LevelList(chapter.id.name)) },
                colors = CardDefaults.cardColors(MaterialTheme.colorScheme.surface)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(chapter.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(chapter.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                    Text("$doneCount/${chapter.levelIds.size}", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        item {
            Spacer(Modifier.height(16.dp))
            Button(onClick = { backStack.add(com.vayunmathur.games.logicgate.Route.GameCenter) }, modifier = Modifier.fillMaxWidth()) {
                Text("Achievements")
            }
        }
    }
}

@Composable
fun LevelListScreen(
    chapterId: ChapterId,
    backStack: NavBackStack<com.vayunmathur.games.logicgate.Route>,
    viewModel: LogicViewModel
) {
    val chapter = Levels.chapters.find { it.id == chapterId } ?: return
    val completed by viewModel.completedIds.collectAsState()
    val best by viewModel.bestNands.collectAsState()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = { backStack.pop() }) { Text("Back") }
            Spacer(Modifier.width(12.dp))
            Text(chapter.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        }
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(chapter.levelIds) { lvlId ->
                val def = Levels.get(lvlId)
                val done = lvlId in completed
                val nands = best[lvlId]
                val isOptimal = done && (nands ?: 999) <= def.optimalNands
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { backStack.add(com.vayunmathur.games.logicgate.Route.Game(lvlId)) },
                    colors = CardDefaults.cardColors(
                        when {
                            isOptimal -> Color(0xFF1B3A2F)
                            done -> Color(0xFF1E2E24)
                            else -> MaterialTheme.colorScheme.surface
                        }
                    )
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(def.displayName, fontWeight = FontWeight.Bold, color = if (done) Color(0xFF7FD8BE) else MaterialTheme.colorScheme.onSurface)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                if (done) {
                                    val star = if (isOptimal) "★★★" else if ((nands ?: 999) <= def.optimalNands * 1.5) "★★" else "★"
                                    Text("$star ${nands ?: "?"}N / ${def.optimalNands}N", fontSize = 12.sp, color = Color(0xFF7FD8BE))
                                }
                                if (done) Text("✓", fontSize = 12.sp) else Text("○", fontSize = 12.sp, color = Color.Gray)
                            }
                        }
                        Text(def.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f), maxLines = 2)
                        if (def.flavor.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(def.flavor, fontSize = 11.sp, color = Color(0xFF9CA3AF), fontStyle = FontStyle.Italic)
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

@Composable
fun TruthTableView(
    level: LevelDef,
    failingRows: List<Int>,
    modifier: Modifier = Modifier
) {
    val target = ChipLibrary.get(level.targetChipId)
    val tableRows: List<List<Boolean>> = if (level.inputs.size <= 8) {
        CircuitEvaluator.generateTruthTable(target, level.inputs.size, level.outputs.size, limit = 64)
    } else {
        // sampled display
        val rnd = java.util.Random(1)
        val vecs = mutableListOf<List<Boolean>>()
        vecs.add(List(level.inputs.size) { false })
        vecs.add(List(level.inputs.size) { true })
        repeat(20) { vecs.add(List(level.inputs.size) { rnd.nextBoolean() }) }
        vecs.map { inp -> target.eval(inp).take(level.outputs.size) }
    }
    // Input labels for sampled mode: show index
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
            tableRows.forEachIndexed { idx, outVals ->
                val isFail = idx in failingRows
                val bg = if (isFail) Color(0x44FF4444) else Color.Transparent
                Row(
                    modifier = Modifier.fillMaxWidth().background(bg, RoundedCornerShape(4.dp)).padding(vertical = 1.dp)
                ) {
                    if (level.inputs.size <= 10) {
                        for (b in 0 until minOf(level.inputs.size, 6)) {
                            val v = ((idx shr b) and 1) == 1
                            Text(if (v) "1" else "0", Modifier.width(28.dp), fontSize = 10.sp, color = Color.White)
                        }
                        if (level.inputs.size > 6) Text("…", Modifier.width(16.dp), fontSize = 10.sp, color = Color.Gray)
                    }
                    Text(" | ", fontSize = 10.sp, color = Color.Gray)
                    outVals.take(5).forEach { v ->
                        Text(if (v) "1" else "0", Modifier.width(36.dp), fontSize = 10.sp, color = if (isFail) Color(0xFFFF8A80) else Color(0xFF86EFAC))
                    }
                    if (isFail) Text(" ✗", fontSize = 10.sp, color = Color(0xFFFF8A80))
                }
            }
        } else {
            Text("Truth table (sampled ${tableRows.size} cases from ${1 shl minOf(level.inputs.size, 20)} combos)", fontSize = 9.sp, color = Color.Gray)
            Spacer(Modifier.height(6.dp))
            tableRows.take(24).forEachIndexed { idx, outVals ->
                val isFail = idx in failingRows
                val bg = if (isFail) Color(0x44FF4444) else Color.Transparent
                Row(Modifier.background(bg, RoundedCornerShape(4.dp)).padding(vertical = 1.dp)) {
                    outVals.forEach { v ->
                        Text(if (v) "1" else "0", Modifier.width(18.dp), fontSize = 10.sp, color = if (isFail) Color(0xFFFF8A80) else Color(0xFF86EFAC))
                    }
                    if (isFail) Text(" ✗", fontSize = 10.sp, color = Color(0xFFFF8A80))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (failingRows.isNotEmpty()) {
            Text("${failingRows.size} failing case(s) highlighted", fontSize = 10.sp, color = Color(0xFFFF8A80))
        }
        if (level.inputs.size > 10) {
            Text("Large bus: validation samples 256 random cases", fontSize = 9.sp, color = Color.Gray)
        }
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
        Text("Tap chip to place  •  Tap dot → dot to wire  •  Long-press gate to delete", fontSize = 10.sp, color = Color(0xFF94A3B8), modifier = Modifier.padding(bottom = 6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(allowed.filter { it in unlockedChips }.sortedBy { ChipLibrary.get(it).nandCost }) { chipId ->
                val def = ChipLibrary.get(chipId)
                val color = when (def.category) {
                    ChipCategory.PRIMITIVE -> Color(0xFF1E3A4A)
                    ChipCategory.FOUNDATION -> Color(0xFF14532D)
                    ChipCategory.ROUTING -> Color(0xFF713F12)
                    ChipCategory.ARITH -> Color(0xFF7C2D12)
                    ChipCategory.MEMORY -> Color(0xFF3B0764)
                    ChipCategory.CPU -> Color(0xFF881337)
                }
                Box(
                    modifier = Modifier
                        .background(color, RoundedCornerShape(8.dp))
                        .clickable { onAddGate(chipId) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
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
