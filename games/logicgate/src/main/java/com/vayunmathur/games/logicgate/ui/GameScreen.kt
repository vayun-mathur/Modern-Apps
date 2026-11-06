package com.vayunmathur.games.logicgate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.toSize
import com.vayunmathur.games.logicgate.R
import com.vayunmathur.games.logicgate.domain.levels.LevelRegistry
import com.vayunmathur.games.logicgate.platform.GameUiState
import com.vayunmathur.games.logicgate.platform.LogicActions
import com.vayunmathur.games.logicgate.ui.canvas.CameraState
import com.vayunmathur.games.logicgate.ui.canvas.CircuitLayout
import com.vayunmathur.games.logicgate.ui.canvas.TrashZone
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.FadeVisibility
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconFullscreen
import com.vayunmathur.library.ui.IconInfo
import com.vayunmathur.library.ui.IconRedo
import com.vayunmathur.library.ui.IconUndo
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.rememberHaptics
import kotlin.math.roundToInt

/**
 * The editor, for a level or a sandbox component: the board fills the screen, with tests and the
 * chip tray beneath it. [onNext] opens the following level from the completion overlay;
 * [onDelete] deletes the open component.
 */
@Composable
fun GameScreen(
    state: GameUiState,
    actions: LogicActions,
    onBack: () -> Unit,
    onNext: (String) -> Unit,
    modifier: Modifier = Modifier,
    onDelete: () -> Unit = {},
) {
    val density = LocalDensity.current.density
    val cameraState = remember { CameraState(density) }
    val lookup = remember(state.catalog, state.io) { state.catalog.lookup(state.io) }
    val labels = rememberChipLabels()
    val level = state.level
    val number = level?.let { LevelRegistry.indexOf(it.id) + 1 } ?: 0
    var trayDrag by remember { mutableStateOf<TrayDrag?>(null) }
    val trash = remember { TrashZone() }
    val haptics = rememberHaptics()
    var rootOrigin by remember { mutableStateOf(Offset.Zero) }
    var canvasBounds by remember { mutableStateOf(Rect.Zero) }

    fun placeNear(chipId: String, world: Offset) =
        actions.placeChip(chipId, CircuitLayout(state.circuit, lookup, labels).freeSpot(chipId, world, lookup))

    AppScaffold(
        title = level?.let { stringResource(R.string.lg_level_title, number, stringResource(it.title)) } ?: state.sandbox?.name.orEmpty(),
        modifier = modifier,
        onNavigateBack = onBack,
        actions = {
            IconButton(onClick = actions::undo, enabled = state.canUndo) { IconUndo() }
            IconButton(onClick = actions::redo, enabled = state.canRedo) { IconRedo() }
            IconButton(onClick = { cameraState.fit(CircuitLayout(state.circuit, lookup, labels).bounds) }) { IconFullscreen() }
            IconButton(onClick = { actions.showBrief(true) }) { IconInfo() }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .onGloballyPositioned { rootOrigin = it.positionInRoot() },
        ) {
            Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .onGloballyPositioned { c ->
                            canvasBounds = Rect(c.positionInRoot(), c.size.toSize())
                            trash.canvasOrigin = canvasBounds.topLeft
                        },
                ) {
                    CircuitCanvas(state, cameraState, actions, trash)
                    SelectionBar(state, actions, Modifier.align(Alignment.TopCenter).padding(top = Spacing.sm))
                }
                Box(Modifier.onGloballyPositioned { trash.bounds = Rect(it.positionInRoot(), it.size.toSize()) }) {
                    Column {
                        TestPanel(state, actions)
                        ChipTray(
                            sections = state.tray,
                            defaultGroup = state.defaultGroup,
                            onTap = { id -> state.chips.firstOrNull { it.id == id }?.let(actions::showChipInfo) },
                            onDragUpdate = { drag ->
                                trayDrag = drag
                                if (trash.carry(drag?.root)) haptics.tick()
                            },
                            onDrop = { drop ->
                                if (canvasBounds.contains(drop.root)) {
                                    placeNear(drop.chipId, cameraState.camera.toWorld(drop.root - canvasBounds.topLeft))
                                }
                            },
                        )
                    }
                    TrashOverlay(trash, Modifier.matchParentSize())
                }
            }
            trayDrag?.let { DragGhost(chipLabel(it.chipId), it.root - rootOrigin) }
            if (state.won && level != null) {
                LevelCompleteOverlay(
                    state = state,
                    level = level,
                    onNext = state.nextLevelId?.let { id -> { onNext(id) } },
                    onDismiss = actions::dismissWin,
                )
            }
        }
    }
    if (state.showBrief) {
        if (level != null) {
            BriefSheet(level, number, onDismiss = { actions.showBrief(false) })
        } else {
            ComponentSheet(state, actions, onDelete = onDelete, onDismiss = { actions.showBrief(false) })
        }
    }
    state.infoChip?.let { chip ->
        ChipInfoSheet(
            chip = chip,
            group = state.catalog.custom(chip.id)?.group,
            onAdd = {
                placeNear(chip.id, cameraState.centerWorld())
                actions.showChipInfo(null)
            },
            onDismiss = { actions.showChipInfo(null) },
        )
    }
}

/** Covers the tray while a gate is carried, so it reads as the place to throw it away. */
@Composable
private fun TrashOverlay(trash: TrashZone, modifier: Modifier) {
    FadeVisibility(trash.armed, modifier) {
        val alpha = if (trash.hovering) TRASH_HOT_ALPHA else TRASH_ALPHA
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.error.copy(alpha = alpha)),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                IconDelete(tint = MaterialTheme.colorScheme.onError)
                Text(
                    stringResource(if (trash.hovering) R.string.lg_release_to_delete else R.string.lg_drag_to_delete),
                    color = MaterialTheme.colorScheme.onError,
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun DragGhost(label: String, at: Offset) {
    Surface(
        modifier = Modifier.offset { IntOffset(at.x.roundToInt() - GHOST_OFFSET, at.y.roundToInt() - GHOST_OFFSET * 2) },
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Text(label, Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm), style = MaterialTheme.typography.labelLarge)
    }
}

private const val GHOST_OFFSET = 48
private const val TRASH_ALPHA = 0.75f
private const val TRASH_HOT_ALPHA = 0.95f
