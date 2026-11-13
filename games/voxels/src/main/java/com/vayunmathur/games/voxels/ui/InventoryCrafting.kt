package com.vayunmathur.games.voxels.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.voxels.R
import com.vayunmathur.games.voxels.util.VoxelsNative
import com.vayunmathur.library.ui.Text

@Composable
internal fun CraftingTable(recipesJson: String) {
    val recipes = remember(recipesJson) {
        try {
            val arr = org.json.JSONArray(recipesJson)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Recipe(o.getInt("in"), o.getInt("inN"), o.optInt("in2", 0), o.optInt("in2N", 0), o.getInt("out"), o.getInt("outN"), o.optString("cat", "block"), o.optBoolean("known", true), o.optInt("requires", 0))
            }
        } catch (_: Exception) { emptyList() }
    }
    var sel by remember { mutableStateOf(0) }
    var cat by remember { mutableStateOf("block") }
    // Locked recipes are hidden by default so the list grows with the player, but they can be
    // browsed on demand: knowing what a tier leads to is half the reason to chase it.
    var showLocked by remember { mutableStateOf(false) }
    val r = recipes.getOrNull(sel)
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        // Left: product picker, filtered to one shelf so the list stays navigable.
        Column(Modifier.width(190.dp).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                recipeCategories.forEach { (key, label) ->
                    Box(
                        Modifier.clip(RoundedCornerShape(6.dp))
                            .background(if (cat == key) Color(0xFF3A6B3A) else Color.White.copy(0.08f))
                            .clickable { cat = key }.padding(horizontal = 8.dp, vertical = 5.dp)
                    ) { Text(label, color = Color.White.copy(0.9f), fontSize = 11.sp) }
                }
                Box(
                    Modifier.clip(RoundedCornerShape(6.dp))
                        .background(if (showLocked) Color(0xFF3A6B3A) else Color.White.copy(0.08f))
                        .clickable { showLocked = !showLocked }.padding(horizontal = 8.dp, vertical = 5.dp)
                ) { Text(stringResource(R.string.recipes_show_locked), color = Color.White.copy(0.9f), fontSize = 11.sp) }
            }
            Column(Modifier.fillMaxHeight().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                recipes.withIndex().filter { it.value.cat == cat && (it.value.known || showLocked) }.forEach { (i, rec) ->
                    val icon = rememberBlockIcon(rec.outId)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                            .background(if (i == sel) Color(0xFF3A6B3A) else Color.White.copy(alpha = 0.08f))
                            .clickable { sel = i }.padding(6.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (icon != null) {
                            Image(
                                bitmap = icon, contentDescription = null,
                                modifier = Modifier.size(26.dp).alpha(if (rec.known) 1f else 0.35f),
                                filterQuality = FilterQuality.None,
                            )
                        }
                        Text(
                            blockName(rec.outId) ?: "",
                            color = Color.White.copy(if (rec.known) 0.95f else 0.4f), fontSize = 12.sp,
                        )
                    }
                }
            }
        }
        // Right: the crafting table (2x2 grid) with the selected recipe placed + its product.
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.crafting_table), color = Color.White.copy(0.6f), fontSize = 12.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (row in 0 until 2) {
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (col in 0 until 2) {
                                val idx = row * 2 + col
                                when {
                                    r == null -> GridCell(0, 0)
                                    idx == 0 -> GridCell(r.inId, r.inN)
                                    idx == 1 && r.in2Id != 0 -> GridCell(r.in2Id, r.in2N)
                                    else -> GridCell(0, 0)
                                }
                            }
                        }
                    }
                }
                Text("→", color = Color.White.copy(0.8f), fontSize = 24.sp)
                GridCell(r?.outId ?: 0, r?.outN ?: 0)
            }
            // A locked recipe says what opens it, so the tree reads as a path rather than a wall.
            if (r != null && !r.known) {
                Text(
                    stringResource(R.string.recipe_requires, blockName(r.requires) ?: "?"),
                    color = Color(0xFFD9B36C), fontSize = 12.sp,
                )
            }
            val unlocked = r != null && r.known
            Box(
                Modifier.clip(RoundedCornerShape(8.dp))
                    .background(if (unlocked) Color(0xFF3A6B3A) else Color.White.copy(0.10f))
                    .clickable(enabled = unlocked) { try { VoxelsNative.craft(sel) } catch (_: Exception) {} }
                    .padding(horizontal = 24.dp, vertical = 8.dp)
            ) {
                Text(
                    stringResource(R.string.craft),
                    color = Color.White.copy(if (unlocked) 1f else 0.4f),
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun GridCell(id: Int, count: Int) {
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

private data class Recipe(
    val inId: Int, val inN: Int, val in2Id: Int, val in2N: Int,
    val outId: Int, val outN: Int, val cat: String,
    // Unlocked once `requires` has been crafted; 0 means available from the start.
    val known: Boolean,
    val requires: Int,
)

// Crafting shelves, in the order they appear as filter chips.
private val recipeCategories = listOf(
    "block" to "Blocks", "tool" to "Tools", "armor" to "Armor",
    "material" to "Materials", "food" to "Food", "blessing" to "Blessings",
)
