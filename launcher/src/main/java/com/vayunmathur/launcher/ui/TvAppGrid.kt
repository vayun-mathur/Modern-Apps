package com.vayunmathur.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.animatedDp
import com.vayunmathur.launcher.domain.TvRowOrder
import com.vayunmathur.launcher.platform.TvHomeState
import com.vayunmathur.launcher.ui.components.TvBannerTile

/**
 * The app banner grid: six 16:9 tiles per row like the tv-detox reference.
 *
 * A plain flow of tiles, not a LazyVerticalGrid: this grid lives inside the screen's
 * layout, and a lazy container measures with infinite max height there, which Compose
 * forbids (fatal IllegalStateException on launch). A TV row holds at most a few dozen
 * static tiles, so laziness buys nothing here.
 *
 * Fits without scrolling: at most [MAX_ROWS] rows are shown, and tiles size from the
 * available height via [BoxWithConstraints] so the grid always fits its weighted slot.
 *
 * Reorder behaviour (the part that has to feel right from a sofa):
 * - every tile owns a stable [FocusRequester] keyed by package, so focus identity survives
 *   list moves instead of sticking to the old index;
 * - while a tile is held ([TvHomeState.movingKey]), every move re-requests focus on the
 *   held tile's requester, so the cursor travels *with* the tile instead of snapping back
 *   to where it was picked up;
 * - each tile animates its own horizontal offset toward its current slot on the platform
 *   reorder spec, so neighbours visibly slide aside instead of teleporting — the eye
 *   follows the held tile because everything else glides out of its way.
 */
@Composable
fun TvAppGrid(
    state: TvHomeState,
    onPressTile: (String) -> Unit,
    onOpenEditMenu: (String) -> Unit,
    onMoveKey: (String, Int) -> Unit,
) {
    // Stable per-package focus owners: a reorder changes positions, not identities, so the
    // held tile keeps its requester and focus can chase it. Populated outside composition
    // (a getOrPut during composition trips RememberInComposition lint); composition only
    // reads.
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    val firstFocus = remember { FocusRequester() }
    val tiles = remember(state.rowApps) { state.rowApps.take(MAX_ROWS * TvRowOrder.PER_ROW) }
    LaunchedEffect(tiles) {
        for (tile in tiles) {
            requesters.getOrPut(tile.packageName) {
                if (tile == tiles.firstOrNull()) firstFocus else FocusRequester()
            }
        }
    }
    val movingKey = state.movingKey
    LaunchedEffect(movingKey, state.rowApps) {
        if (movingKey != null) {
            runCatching { requesters.getOrPut(movingKey) { FocusRequester() }.requestFocus() }
        }
    }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        // Caption reserve under each row + gaps, so tiles size from what is left.
        val captionReserve = 28.dp
        val gap = 12.dp
        val rows = tiles.chunked(TvRowOrder.PER_ROW)
        val tileH = ((maxHeight - captionReserve * rows.size - gap * (rows.size - 1).coerceAtLeast(0)) / rows.size.coerceAtLeast(1))
            .coerceAtLeast(0.dp)
        val tileW = (maxWidth - gap * (TvRowOrder.PER_ROW - 1)) / TvRowOrder.PER_ROW
        // 16:9 width-driven height, but never taller than the slot allows.
        val artH = (tileW * 9f / 16f).coerceAtMost(tileH)
        // Slot pitch: every tile in a row is absolutely positioned at pitch * index inside
        // a fixed Box and animates there on Motion.reorder() — so when the held tile moves,
        // it glides right while the displaced neighbour glides left. A Row would snap them.
        val pitch = tileW + gap
        Column(
            verticalArrangement = Arrangement.spacedBy(gap),
            modifier = Modifier.fillMaxWidth(),
        ) {
            for (row in rows) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(artH + captionReserve),
                ) {
                    for ((indexInRow, tile) in row.withIndex()) {
                        val targetX = animatedDp(pitch * indexInRow)
                        key(tile.packageName) {
                            Box(
                                modifier = Modifier
                                    .width(tileW)
                                    .height(artH + captionReserve)
                                    .offset(x = targetX),
                            ) {
                                val held = movingKey == tile.packageName
                                TvBannerTile(
                                    tile = tile,
                                    tileWidth = tileW,
                                    artHeight = artH,
                                    moving = held,
                                    // While one tile is held, every other tile dims: the held
                                    // one is the only bright thing on screen.
                                    dimmed = movingKey != null && !held,
                                    focusRequester = requesters[tile.packageName] ?: firstFocus,
                                    onPress = { onPressTile(tile.packageName) },
                                    onEdit = { onOpenEditMenu(tile.packageName) },
                                    onMove = { step -> onMoveKey(tile.packageName, step) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    // Cold start: D-pad does nothing until something holds focus, so hand it to the first
    // tile once the row arrives.
    LaunchedEffect(state.rowApps.isNotEmpty(), state.loading) {
        if (state.rowApps.isNotEmpty() && !state.loading) {
            runCatching { firstFocus.requestFocus() }
        }
    }
}

private const val MAX_ROWS = 2

