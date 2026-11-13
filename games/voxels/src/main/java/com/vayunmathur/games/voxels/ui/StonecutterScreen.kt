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

/** One stonecutter conversion, addressed by its index in the engine's table. */
data class CutRecipe(val index: Int, val inId: Int, val outId: Int, val outN: Int)

fun parseCuts(json: String): List<CutRecipe> = try {
    val arr = org.json.JSONArray(json)
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        CutRecipe(i, o.getInt("in"), o.getInt("out"), o.optInt("outN", 1))
    }
} catch (_: Exception) { emptyList() }

/**
 * Stonecutter: pick a block you are carrying on the left, then a shape to cut it into on the right.
 * Unlike the furnace this is instant and costs no fuel — it only reshapes.
 */
@Composable
fun StonecutterOverlay(cutsJson: String, inventoryJson: String, onClose: () -> Unit) {
    val cuts = remember(cutsJson) { parseCuts(cutsJson) }
    val inv = remember(inventoryJson) {
        try {
            voxelsJson
                .decodeFromString<InventoryState>(inventoryJson)
        } catch (_: Exception) { InventoryState() }
    }
    // Only offer materials the player actually has, so the list stays short and honest.
    val held = remember(inv, cuts) {
        cuts.map { it.inId }.distinct().filter { id -> inv.slots.any { it.id == id && it.count > 0 } }
    }
    var material by remember(held) { mutableStateOf(held.firstOrNull() ?: 0) }
    val options = remember(material, cuts) { cuts.filter { it.inId == material } }

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
                Text(stringResource(R.string.stonecutter), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 17.sp)
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.clip(RoundedCornerShape(8.dp)).background(Color.White.copy(0.10f))
                        .clickable { onClose() }.padding(horizontal = 18.dp, vertical = 8.dp)
                ) { Text(stringResource(UiR.string.close), color = Color.White, fontSize = 14.sp) }
            }

            if (held.isEmpty()) {
                Text(stringResource(R.string.stonecutter_empty), color = Color.White.copy(0.55f), fontSize = 12.sp)
                return@Column
            }

            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                // Materials on hand.
                Column(
                    Modifier.width(180.dp).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(stringResource(R.string.material), color = Color.White.copy(0.6f), fontSize = 12.sp)
                    held.forEach { id ->
                        val icon = rememberBlockIcon(id)
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                .background(if (id == material) Color(0xFF3A6B3A) else Color.White.copy(alpha = 0.08f))
                                .clickable { material = id }.padding(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(26.dp), filterQuality = FilterQuality.None)
                            Text(blockName(id) ?: "", color = Color.White.copy(0.95f), fontSize = 12.sp)
                        }
                    }
                }

                // Shapes that material can become.
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(stringResource(R.string.cut_into), color = Color.White.copy(0.6f), fontSize = 12.sp)
                    options.forEach { c ->
                        val icon = rememberBlockIcon(c.outId)
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                                .clickable { try { VoxelsNative.cut(c.index) } catch (_: Exception) {} }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(Modifier.size(30.dp), contentAlignment = Alignment.Center) {
                                if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(28.dp), filterQuality = FilterQuality.None)
                            }
                            Text(blockName(c.outId) ?: "", color = Color.White.copy(0.95f), fontSize = 13.sp)
                            Spacer(Modifier.weight(1f))
                            Text("×${c.outN}", color = Color.White.copy(0.7f), fontSize = 12.sp)
                        }
                    }
                }
            }
            Text(stringResource(R.string.stonecutter_hint), color = Color.White.copy(0.5f), fontSize = 11.sp)
        }
    }
}
