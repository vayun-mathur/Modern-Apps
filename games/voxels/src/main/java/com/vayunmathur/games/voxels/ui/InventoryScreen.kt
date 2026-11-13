package com.vayunmathur.games.voxels.ui

import androidx.compose.ui.res.stringResource
import com.vayunmathur.games.voxels.R
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vayunmathur.games.voxels.util.VoxelsNative
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.AchievementsManager
import com.vayunmathur.library.ui.Text
import kotlin.math.roundToInt

private data class DragState(val from: Int, val pos: Offset, val id: Int, val count: Int)

private val NARROW_PANEL = 700.dp
private val SLOT_GAP = 4.dp
private val SLOT_MAX = 46.dp
private val SLOT_MIN = 26.dp

/** Slot edge for a 9-wide grid in [width]: as big as fits, between [SLOT_MIN] and [SLOT_MAX]. */
internal fun inventorySlotSize(width: Dp): Dp = ((width - SLOT_GAP * 8) / 9).coerceIn(SLOT_MIN, SLOT_MAX)

@Composable
fun InventoryOverlay(
    inventoryJson: String, recipesJson: String, blessingsJson: String, blessingCatalogJson: String,
    onClose: () -> Unit, startTab: Int = 0,
    // This world's achievement tier. Null until the manager has loaded from assets.
    worldAchievements: AchievementsManager? = null,
) {
    val inv = remember(inventoryJson) {
        try { voxelsJson.decodeFromString<InventoryState>(inventoryJson) } catch (_: Exception) { InventoryState() }
    }
    val slots = inv.slots
    // 0 Inventory, 1 Outfit, 2 Crafting, 3 Blessings, 4 Achievements
    var leftTab by remember { mutableStateOf(startTab) }
    var catTab by remember { mutableStateOf(0) }
    val bounds = remember { mutableStateMapOf<Int, Rect>() }
    var drag by remember { mutableStateOf<DragState?>(null) }
    val density = LocalDensity.current

    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f))
            .pointerInput(Unit) { detectTapGestures { onClose() } }
    ) {
        BoxWithConstraints(
            Modifier.align(Alignment.Center).fillMaxWidth(0.94f).fillMaxHeight(0.86f)
                .clip(RoundedCornerShape(14.dp)).background(Color(0xF01A1E1A))
                .pointerInput(Unit) { detectTapGestures { } } // swallow taps on the panel
                .padding(12.dp)
        ) {
        // Narrow landscape phones: a 3-wide catalog leaves the 9-wide grid room for usable slots.
        val narrow = maxWidth < NARROW_PANEL
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            // Left tabs (top→bottom: Crafting, Outfit, Inventory).
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TabButton(stringResource(R.string.crafting), leftTab == 2) { leftTab = 2 }
                TabButton(stringResource(R.string.blessings), leftTab == 3) { leftTab = 3 }
                TabButton(stringResource(R.string.achievements), leftTab == 4) { leftTab = 4 }
                TabButton(stringResource(R.string.outfit), leftTab == 1) { leftTab = 1 }
                TabButton(stringResource(R.string.inventory), leftTab == 0) { leftTab = 0 }
                Spacer(Modifier.weight(1f))
                TabButton(stringResource(UiR.string.close), false) { onClose() }
            }

            // Center content.
            Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
                when (leftTab) {
                    0 -> InventoryGrid(slots, bounds, onDragStart = { i, off ->
                            val s = slots.getOrNull(i) ?: InvSlot(0, 0)
                            if (s.id != 0) drag = DragState(i, (bounds[i]?.topLeft ?: Offset.Zero) + off, s.id, s.count)
                        }, onDragMove = { d -> drag = drag?.let { it.copy(pos = it.pos + d) } },
                        onDragEnd = {
                            drag?.let { dr ->
                                val target = bounds.entries.firstOrNull { it.value.contains(dr.pos) }?.key
                                if (target != null && target != dr.from) try { VoxelsNative.moveItem(dr.from, target) } catch (_: Exception) {}
                            }
                            drag = null
                        }, onDragCancel = { drag = null })
                    1 -> OutfitView(inv.armor)
                    3 -> BlessingsView(blessingsJson, blessingCatalogJson, slots)
                    4 -> AchievementsView(worldAchievements)
                    else -> CraftingTable(recipesJson)
                }
            }

            // Right catalog (creative): tap a block to add a stack. Eight 96 dp tabs don't fit the
            // 236 dp column, so the tab strip scrolls sideways.
            Column(Modifier.width(if (narrow) 160.dp else 236.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TabButton("Natural", catTab == 0) { catTab = 0 }
                    TabButton("Ores", catTab == 1) { catTab = 1 }
                    TabButton("Ocean", catTab == 3) { catTab = 3 }
                    TabButton("Items", catTab == 4) { catTab = 4 }
                    TabButton("Gear", catTab == 6) { catTab = 6 }
                    TabButton(stringResource(R.string.blessings), catTab == 5) { catTab = 5 }
                    TabButton("Music", catTab == 7) { catTab = 7 }
                    TabButton("Build", catTab == 2) { catTab = 2 }
                }
                Spacer(Modifier.height(8.dp))
                val cat = when (catTab) { 0 -> catalogNatural; 1 -> catalogOres; 3 -> catalogOcean; 4 -> catalogItems; 5 -> catalogBlessings; 6 -> catalogGear; 7 -> catalogMusic; else -> catalogBuilding }
                CatalogGrid(cat, columns = if (narrow) 3 else 4) { id -> try { VoxelsNative.giveBlock(id) } catch (_: Exception) {} }
            }
        }
        }

        // Floating dragged item.
        drag?.let { d ->
            val icon = rememberBlockIcon(d.id)
            val half = with(density) { 24.dp.toPx() }
            Box(Modifier.offset { IntOffset((d.pos.x - half).roundToInt(), (d.pos.y - half).roundToInt()) }.size(48.dp)) {
                if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
            }
        }
    }
}

@Composable
private fun TabButton(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.width(96.dp).height(40.dp).clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color(0xFF3A6B3A) else Color.White.copy(alpha = 0.10f))
            .border(1.dp, Color.White.copy(alpha = if (selected) 0.5f else 0.15f), RoundedCornerShape(8.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) { Text(label, color = Color.White.copy(0.95f), fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun SlotBox(index: Int, slot: InvSlot, bounds: MutableMap<Int, Rect>, slotSize: Dp,
                    onDragStart: (Int, Offset) -> Unit, onDragMove: (Offset) -> Unit,
                    onDragEnd: () -> Unit, onDragCancel: () -> Unit) {
    val icon = rememberBlockIcon(slot.id)
    Box(
        Modifier.size(slotSize).onGloballyPositioned { bounds[index] = it.boundsInWindow() }
            .clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.45f))
            .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
            .pointerInput(index) {
                detectDragGestures(
                    onDragStart = { off -> onDragStart(index, off) },
                    onDrag = { change, amount -> change.consume(); onDragMove(amount) },
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragCancel() }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        if (slot.id != 0) {
            if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(slotSize * 0.65f), filterQuality = FilterQuality.None)
            else Text(blockNames[slot.id]?.take(2) ?: "", color = Color.White, fontSize = 10.sp)
            if (slot.count > 1 && !isDurabilityItem(slot.id)) Box(Modifier.align(Alignment.BottomEnd)) { Text("${slot.count}", color = Color.White, fontSize = 10.sp) }
            if (isDurabilityItem(slot.id)) DurabilityBar(slot.id, slot.count, Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp))
        }
    }
}

@Composable
private fun InventoryGrid(slots: List<InvSlot>, bounds: MutableMap<Int, Rect>,
                          onDragStart: (Int, Offset) -> Unit, onDragMove: (Offset) -> Unit,
                          onDragEnd: () -> Unit, onDragCancel: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
    // Size the slots from the space this grid actually has, so 9 columns never clip.
    val slotSize = inventorySlotSize(maxWidth)
    fun cell(i: Int) = @Composable { SlotBox(i, slots.getOrNull(i) ?: InvSlot(0, 0), bounds, slotSize, onDragStart, onDragMove, onDragEnd, onDragCancel) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.inventory_drag_to_rearrange), color = Color.White.copy(0.6f), fontSize = 12.sp)
        Spacer(Modifier.height(4.dp))
        for (row in 0 until 3) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (col in 0 until 9) { cell(9 + row * 9 + col)() }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for (col in 0 until 9) { cell(col)() } // hotbar
        }
    }
    }
}

@Composable
private fun CatalogGrid(ids: List<Int>, columns: Int, onPick: (Int) -> Unit) {
    Column(
        Modifier.fillMaxHeight().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ids.chunked(columns).forEach { rowIds ->
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                rowIds.forEach { id ->
                    val icon = rememberBlockIcon(id)
                    Box(
                        Modifier.size(48.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black.copy(alpha = 0.4f))
                            .border(1.dp, Color.White.copy(alpha = 0.15f), RoundedCornerShape(6.dp))
                            .clickable { onPick(id) },
                        contentAlignment = Alignment.Center
                    ) {
                        if (icon != null) Image(bitmap = icon, contentDescription = blockNames[id], modifier = Modifier.size(32.dp), filterQuality = FilterQuality.None)
                        else Text(blockNames[id]?.take(2) ?: "", color = Color.White, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun OutfitView(armor: List<InvSlot>) {
    val labels = listOf("Helmet", "Chestplate", "Leggings", "Boots")
    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.equipped_armor), color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text(stringResource(R.string.hold_an_armor_piece_in_hand_to_equip_it), color = Color.White.copy(0.6f), fontSize = 12.sp)
        for (i in 0 until 4) {
            val s = armor.getOrNull(i) ?: InvSlot(0, 0)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(0.4f))
                        .border(1.dp, Color.White.copy(0.15f), RoundedCornerShape(8.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    val icon = if (s.id != 0) rememberBlockIcon(s.id) else null
                    if (icon != null) Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(34.dp), filterQuality = FilterQuality.None)
                }
                Text(if (s.id != 0) (blockNames[s.id] ?: "?") else "${labels[i]}: empty", color = Color.White.copy(if (s.id != 0) 0.95f else 0.5f))
            }
        }
    }
}

/// This world's achievements. The app-level tier is what the Games Hub shows; this is the one that
/// starts at zero in a new world, which is the whole point of tracking both.
@Composable
private fun AchievementsView(manager: AchievementsManager?) {
    if (manager == null) {
        Text(stringResource(R.string.achievements_unavailable), color = Color.White.copy(0.6f), fontSize = 12.sp)
        return
    }
    val statuses by manager.getAchievementStatuses().collectAsState(initial = emptyList())
    // Earned first, then whatever is closest to being earned.
    val ordered = remember(statuses) {
        statuses.sortedWith(
            compareByDescending<com.vayunmathur.library.util.AchievementStatus> { it.isUnlocked }
                .thenByDescending { it.progress.toFloat() / it.achievement.targetProgress.coerceAtLeast(1) }
        )
    }
    val earned = ordered.count { it.isUnlocked }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(R.string.achievements_earned, earned, ordered.size),
            color = Color.White.copy(0.75f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        )
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
               verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (st in ordered) {
                val done = st.isUnlocked
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                        .background(if (done) Color(0xFF2C4A2C) else Color.White.copy(0.06f))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(if (done) "\u2713" else "\u00b7", color = Color.White.copy(if (done) 0.9f else 0.35f), fontSize = 14.sp)
                    Column(Modifier.weight(1f)) {
                        Text(st.achievement.name, color = Color.White.copy(if (done) 0.95f else 0.6f), fontSize = 12.sp)
                        Text(st.achievement.description, color = Color.White.copy(0.45f), fontSize = 10.sp)
                    }
                    // Only the counting achievements have anything useful to show here.
                    if (!done && st.achievement.targetProgress > 1) {
                        Text("${st.progress}/${st.achievement.targetProgress}",
                             color = Color.White.copy(0.5f), fontSize = 10.sp)
                    }
                }
            }
        }
    }
}
