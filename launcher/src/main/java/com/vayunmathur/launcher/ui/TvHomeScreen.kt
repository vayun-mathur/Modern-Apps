package com.vayunmathur.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.launcher.ui.components.TvReorderHint

// RAW SCAFFOLD EXCEPTION: TV launcher fullscreen grid, no app bar.

/**
 * The TV home screen: clock header, app banner grid, bottom glass row, edit overlays.
 *
 * Paints its own warm gradient like the tv-detox reference — a TV home never shows the
 * wallpaper behind it, so this screen is opaque by design.
 *
 * No vertical scrolling by design: every slot is proportional, so the clock, two app rows
 * and the bottom row always fit one 1080p viewport with no up/down navigation. Rows beyond
 * the first two are not shown — the row holds what fits, like the reference.
 */
@Composable
fun TvHomeScreen(
    state: com.vayunmathur.launcher.platform.TvHomeState,
    onPressTile: (String) -> Unit,
    onOpenAppStore: () -> Unit,
    onOpenInput: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenNetworkSettings: () -> Unit,
    onOpenEditMenu: (String) -> Unit,
    onCloseEditMenu: () -> Unit,
    onStartMove: (String) -> Unit,
    onCancelMove: () -> Unit,
    onToggleReorder: () -> Unit,
    onDisarmReorder: () -> Unit,
    onMoveKey: (String, Int) -> Unit,
    onRemoveTile: (String) -> Unit,
    onOpenAddMenu: (Int) -> Unit,
    onPickAddCandidate: (String) -> Unit,
    onUninstallOrInfo: (String) -> Unit,
    onRequestHomeRole: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(TvHomeBackground),
    ) {
        // A home screen has nothing to go back to: Back closes overlays, cancels a hold, or
        // disarms reorder — and is otherwise a no-op (the tv-detox precedent). Order matters:
        // most specific first.
        BackHandler(enabled = state.editingKey != null) { onCloseEditMenu() }
        BackHandler(enabled = state.editingKey == null && state.movingKey != null) {
            onCancelMove()
        }
        BackHandler(
            enabled = state.editingKey == null && state.movingKey == null && state.reorderArmed,
        ) {
            onDisarmReorder()
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 96.dp, vertical = 48.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            TvClockHeader(
                onOpenNetworkSettings = onOpenNetworkSettings,
            )
            // Always composed at fixed height: hint swaps never move the grid.
            TvReorderHint(
                moving = state.movingKey != null,
                armed = state.reorderArmed,
            )
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                TvAppGrid(
                    state = state,
                    onPressTile = onPressTile,
                    onOpenEditMenu = onOpenEditMenu,
                    onMoveKey = onMoveKey,
                )
            }
            if (!state.loading) {
                TvBottomRow(
                    state = state,
                    onOpenAppStore = onOpenAppStore,
                    onOpenInput = onOpenInput,
                    onOpenSettings = onOpenSettings,
                    onToggleReorder = onToggleReorder,
                )
            }
            if (!state.isDefaultHome && !state.loading) {
                TextButton(onClick = onRequestHomeRole) {
                    Text(
                        text = "Set as default home screen",
                        style = MaterialTheme.typography.labelLarge,
                        color = Color(0xA8FFFFFF),
                    )
                }
            }
        }
        TvEditMenu(
            state = state,
            onDismiss = onCloseEditMenu,
            onStartMove = onStartMove,
            onReplace = { onOpenAddMenu(state.rowApps.indexOfFirst { it.packageName == state.editingKey }) },
            onRemove = { state.editingKey?.let(onRemoveTile) },
            onUninstallOrInfo = { state.editingKey?.let(onUninstallOrInfo) },
            onPickAddCandidate = onPickAddCandidate,
            onCloseAddMenu = onCloseEditMenu,
        )
    }
}

/** Static warm gradient drawn once by the background: no extra view, no animation. */
private val TvHomeBackground = Brush.verticalGradient(
    colors = listOf(
        Color(0xFF15100F),
        Color(0xFF0A0809),
        Color(0xFF050405),
    ),
)

