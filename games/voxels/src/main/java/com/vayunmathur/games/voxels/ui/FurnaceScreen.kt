package com.vayunmathur.games.voxels.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.voxels.R
import com.vayunmathur.games.voxels.util.VoxelsNative
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Text

data class SmeltRecipe(
    val inId: Int, val inN: Int, val in2Id: Int, val in2N: Int,
    val outId: Int, val outN: Int, val secs: Float, val blast: Boolean
)

data class SmeltState(val active: Boolean, val recipe: Int, val progress: Float, val fuel: Float)

fun parseSmeltRecipes(json: String): List<SmeltRecipe> = try {
    val arr = org.json.JSONArray(json)
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        SmeltRecipe(
            o.getInt("in"), o.getInt("inN"), o.optInt("in2", 0), o.optInt("in2N", 0),
            o.getInt("out"), o.getInt("outN"), o.getDouble("secs").toFloat(), o.optBoolean("blast", false)
        )
    }
} catch (_: Exception) { emptyList() }

fun parseSmeltState(json: String): SmeltState = try {
    val o = org.json.JSONObject(json)
    SmeltState(
        o.optBoolean("active", false), o.optInt("recipe", 0),
        o.optDouble("progress", 0.0).toFloat(), o.optDouble("fuel", 0.0).toFloat()
    )
} catch (_: Exception) { SmeltState(false, 0, 0f, 0f) }

/**
 * Furnace screen. A Blast Furnace additionally unlocks the alloy recipes, which are hidden on a
 * plain furnace so the upgrade is visible rather than just implied.
 */
@Composable
fun FurnaceOverlay(smeltingJson: String, smeltJson: String, isBlast: Boolean, onClose: () -> Unit) {
    val all = remember(smeltingJson) { parseSmeltRecipes(smeltingJson) }
    // Keep the original indices: startSmelt() addresses recipes by their position in the table.
    val visible = remember(all, isBlast) { all.withIndex().filter { isBlast || !it.value.blast } }
    val state = parseSmeltState(smeltJson)
    var sel by remember { mutableStateOf(visible.firstOrNull()?.index ?: 0) }
    val recipe = all.getOrNull(sel)

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
            .pointerInput(Unit) { detectTapGestures { onClose() } }
    ) {
        Column(
            Modifier.align(Alignment.Center).fillMaxWidth(0.9f).fillMaxHeight(0.84f)
                .clip(RoundedCornerShape(14.dp)).background(Color(0xF01A1E1A))
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (isBlast) R.string.blast_furnace else R.string.furnace),
                    color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White.copy(0.10f))
                        .clickable { onClose() }.padding(horizontal = 18.dp, vertical = 8.dp)
                ) { Text(stringResource(UiR.string.close), color = Color.White, fontSize = 14.sp) }
            }

            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(
                    Modifier.width(190.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    visible.forEach { (idx, rec) ->
                        val icon = rememberBlockIcon(rec.outId)
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                .background(if (idx == sel) Color(0xFF6B4A2A) else Color.White.copy(alpha = 0.08f))
                                .clickable { sel = idx }.padding(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(26.dp), filterQuality = FilterQuality.None)
                            Text(blockNames[rec.outId] ?: "", color = Color.White.copy(0.95f), fontSize = 12.sp)
                        }
                    }
                }

                Column(
                    Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SmeltCell(recipe?.inId ?: 0, recipe?.inN ?: 0)
                            if (recipe != null && recipe.in2Id != 0) SmeltCell(recipe.in2Id, recipe.in2N)
                        }
                        Text("→", color = Color.White.copy(0.8f), fontSize = 24.sp)
                        SmeltCell(recipe?.outId ?: 0, recipe?.outN ?: 0)
                    }

                    val running = state.active && state.recipe == sel
                    // The engine refuses to switch a job mid-burn, so say so rather than offering a
                    // button that would do nothing.
                    val busyElsewhere = state.active && state.recipe != sel
                    MeterBar(stringResource(R.string.smelting_progress), if (running) state.progress else 0f, Color(0xFFE08A3C))
                    MeterBar(stringResource(R.string.fuel), if (state.active) state.fuel else 0f, Color(0xFF3C9AE0))
                    Text(
                        stringResource(if (busyElsewhere) R.string.furnace_busy else R.string.furnace_fuel_hint),
                        color = Color.White.copy(0.55f), fontSize = 11.sp
                    )

                    Box(
                        Modifier.clip(RoundedCornerShape(8.dp))
                            .background(
                                when {
                                    running -> Color(0xFF7A3A3A)
                                    busyElsewhere -> Color.White.copy(0.10f)
                                    else -> Color(0xFF3A6B3A)
                                }
                            )
                            .clickable(enabled = !busyElsewhere) {
                                try {
                                    if (running) VoxelsNative.stopSmelt() else VoxelsNative.startSmelt(sel, isBlast)
                                } catch (_: Exception) {}
                            }
                            .padding(horizontal = 26.dp, vertical = 9.dp)
                    ) {
                        Text(
                            stringResource(
                                when {
                                    running -> UiR.string.stop
                                    busyElsewhere -> R.string.busy
                                    else -> R.string.smelt
                                }
                            ),
                            color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SmeltCell(id: Int, count: Int) {
    val icon = if (id != 0) rememberBlockIcon(id) else null
    Box(
        Modifier.size(50.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.4f))
            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(34.dp), filterQuality = FilterQuality.None)
        if (count > 1) Box(Modifier.align(Alignment.BottomEnd).padding(1.dp)) { Text("$count", color = Color.White, fontSize = 11.sp) }
    }
}

@Composable
private fun MeterBar(label: String, frac: Float, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(label, color = Color.White.copy(0.6f), fontSize = 11.sp)
        Box(Modifier.width(190.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(0.55f))) {
            Box(Modifier.fillMaxWidth(frac.coerceIn(0f, 1f)).fillMaxHeight().background(color))
        }
    }
}

/**
 * Chest screen: tap a chest slot to take the stack, tap an inventory slot to store it.
 */
@Composable
fun ChestOverlay(containerJson: String, inventoryJson: String, onClose: () -> Unit) {
    val chest = remember(containerJson) {
        try {
            val arr = org.json.JSONObject(containerJson).getJSONArray("slots")
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                InvSlot(o.getInt("id"), o.getInt("count"))
            }
        } catch (_: Exception) { emptyList() }
    }
    val inv = remember(inventoryJson) {
        try {
            voxelsJson
                .decodeFromString<InventoryState>(inventoryJson)
        } catch (_: Exception) { InventoryState() }
    }

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
            .pointerInput(Unit) { detectTapGestures { onClose() } }
    ) {
        // ~490 dp of slots don't fit a ~400 dp landscape screen: the panel is capped to the screen
        // (minus a margin) and its slot grids scroll under a fixed title row.
        Column(
            Modifier.align(Alignment.Center).padding(vertical = 8.dp).fillMaxWidth(0.88f)
                .clip(RoundedCornerShape(14.dp)).background(Color(0xF01A1E1A))
                .pointerInput(Unit) { detectTapGestures { } }
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.chest), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White.copy(0.10f))
                        .clickable { onClose() }.padding(horizontal = 18.dp, vertical = 8.dp)
                ) { Text(stringResource(UiR.string.close), color = Color.White, fontSize = 14.sp) }
            }
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
            Text(stringResource(R.string.chest_tap_hint), color = Color.White.copy(0.55f), fontSize = 11.sp)

            for (row in 0 until 3) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (col in 0 until 9) {
                        val i = row * 9 + col
                        TapSlot(chest.getOrNull(i) ?: InvSlot(0, 0)) {
                            try { VoxelsNative.containerTake(i) } catch (_: Exception) {}
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.inventory), color = Color.White.copy(0.6f), fontSize = 12.sp)
            for (row in 0 until 3) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (col in 0 until 9) {
                        val i = 9 + row * 9 + col
                        TapSlot(inv.slots.getOrNull(i) ?: InvSlot(0, 0)) {
                            try { VoxelsNative.containerPut(i) } catch (_: Exception) {}
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (col in 0 until 9) {
                    TapSlot(inv.slots.getOrNull(col) ?: InvSlot(0, 0)) {
                        try { VoxelsNative.containerPut(col) } catch (_: Exception) {}
                    }
                }
            }
            }
        }
    }
}

@Composable
private fun TapSlot(slot: InvSlot, onTap: () -> Unit) {
    val icon = rememberBlockIcon(slot.id)
    Box(
        Modifier.size(42.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.45f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .clickable(enabled = slot.id != 0) { onTap() },
        contentAlignment = Alignment.Center
    ) {
        if (slot.id != 0) {
            if (icon != null) Image(bitmap = icon, contentDescription = blockNames[slot.id], modifier = Modifier.size(28.dp), filterQuality = FilterQuality.None)
            else Text(blockNames[slot.id]?.take(2) ?: "", color = Color.White, fontSize = 10.sp)
            if (slot.count > 1 && !isDurabilityItem(slot.id)) {
                Box(Modifier.align(Alignment.BottomEnd)) { Text("${slot.count}", color = Color.White, fontSize = 10.sp) }
            }
            if (isDurabilityItem(slot.id)) DurabilityBar(slot.id, slot.count, Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp))
        }
    }
}
