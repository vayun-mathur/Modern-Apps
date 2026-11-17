@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.photos.ui

import android.app.Activity
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconSave
import com.vayunmathur.library.ui.IconUndo
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.ResultEffect
import com.vayunmathur.photos.R
import com.vayunmathur.photos.data.DodgeBurnStroke
import com.vayunmathur.photos.data.DodgeBurnStrokes
import com.vayunmathur.photos.data.DrawingTool
import com.vayunmathur.photos.data.HealMode
import com.vayunmathur.photos.data.HealingStroke
import com.vayunmathur.photos.data.HealingStrokes
import com.vayunmathur.photos.data.SmudgeStroke
import com.vayunmathur.photos.data.SmudgeStrokes
import com.vayunmathur.photos.data.applyHealingToBitmap
import com.vayunmathur.photos.data.applyToBitmap
import com.vayunmathur.photos.platform.ExportFormat
import com.vayunmathur.photos.platform.PhotoEditViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditPhotoPage(
    backStack: NavBackStack<EditRoute>,
    photoEditViewModel: PhotoEditViewModel,
    id: Long,
    initialUri: String? = null,
) {
    val vm = photoEditViewModel
    val context = LocalActivity.current!!
    LaunchedEffect(id, initialUri) { vm.loadPhoto(id, initialUri) }
    val photo by vm.photo.collectAsState()

    val state = rememberEditPhotoEditorState(vm, context)

    ResultEffect<DrawingSettingsResult>("drawing_settings") { result ->
        var changed = false
        state.selectedTextId?.let { tid ->
            val index = state.texts.indexOfFirst { it.id == tid }
            if (index != -1) {
                state.texts[index] = state.texts[index].copy(color = result.color, fontSize = result.thickness)
                changed = true
            }
        }
        if (!changed) {
            state.activeTool = result.tool
            when (result.tool) {
                DrawingTool.Pen -> { state.penColor = Color(result.color); state.penSize = result.thickness }
                DrawingTool.Highlighter -> {
                    state.highlighterColor = Color(result.color); state.highlighterSize = result.thickness; state.highlighterOpacity = result.opacity
                }
                DrawingTool.Text -> { state.penColor = Color(result.color); state.textFontSize = result.thickness }
                else -> {}
            }
        }
    }

    LaunchedEffect(photo?.uri) {
        val uri = photo?.uri?.toUri() ?: return@LaunchedEffect
        vm.decode(uri)
    }

    EditPhotoModeEffect(state)

    val writePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) vm.saver.onWritePermissionGranted()
        else vm.saver.onWritePermissionDenied()
    }
    val writePermissionRequest by vm.writePermissionRequest.collectAsState()
    LaunchedEffect(writePermissionRequest) {
        writePermissionRequest?.let { writePermissionLauncher.launch(IntentSenderRequest.Builder(it).build()) }
    }

    AppScaffold(
        title = { Text(stringResource(R.string.title_edit_photo), maxLines = 1) },
        onNavigateBack = { context.finish() },
        actions = {
            val isDrawing = state.activeCategory == ToolCategory.Draw
            val strokeUndo = isDrawing && state.inkStrokes.isNotEmpty()
            val strokeRedo = isDrawing && state.redoStrokes.isNotEmpty()
            val canUndo by vm.canUndo.collectAsState()
            val canRedo by vm.canRedo.collectAsState()
            IconButton(
                onClick = {
                    if (strokeUndo) state.redoStrokes.add(state.inkStrokes.removeAt(state.inkStrokes.size - 1))
                    else vm.undo()
                },
                enabled = strokeUndo || canUndo,
            ) { IconUndo() }
            IconButton(
                onClick = {
                    if (strokeRedo) state.inkStrokes.add(state.redoStrokes.removeAt(state.redoStrokes.size - 1))
                    else vm.redo()
                },
                enabled = strokeRedo || canRedo,
            ) {
                Text("↻", fontSize = 20.sp)
            }
            Box {
                IconButton(onClick = { state.showSaveMenu = true }) { IconSave() }
                DropdownMenu(expanded = state.showSaveMenu, onDismissRequest = { state.showSaveMenu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_save)) },
                        onClick = { state.showSaveMenu = false; state.doSave(false) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_save_as_copy)) },
                        onClick = { state.showSaveMenu = false; state.doSave(true, ExportFormat.Jpeg) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.export_as_png)) },
                        onClick = { state.showSaveMenu = false; state.doSave(true, ExportFormat.Png) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.export_as_webp)) },
                        onClick = { state.showSaveMenu = false; state.doSave(true, ExportFormat.Webp) },
                    )
                }
            }
        },
        scrollBehavior = appBarScrollBehavior(),
    ) { paddingValues ->
        val layoutDirection = LocalLayoutDirection.current
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = paddingValues.calculateStartPadding(layoutDirection),
                    end = paddingValues.calculateEndPadding(layoutDirection),
                )
                .background(MaterialTheme.colorScheme.background),
        ) {
            EditPhotoCanvas(state, topPaddingDp = paddingValues.calculateTopPadding())

            // Commit healing stroke
            LaunchedEffect(state.currentHealingPoints.size) {
                if (state.currentHealingPoints.isNotEmpty() && state.editorMode == EditorMode.Healing) {
                    kotlinx.coroutines.delay(300)
                    val sx = state.healingSourceX; val sy = state.healingSourceY
                    if (sx != null && sy != null && state.currentHealingPoints.isNotEmpty()) {
                        val stroke = HealingStroke(sx, sy, state.currentHealingPoints, state.healingBrushSize, HealMode.Heal)
                        vm.pixel.applyToActivePixelLayer { HealingStrokes(listOf(stroke)).applyHealingToBitmap(it) }
                        state.currentHealingPoints = emptyList()
                    }
                }
            }

            // Commit dodge/burn/smudge stroke
            LaunchedEffect(state.retouchPoints.size) {
                if (state.retouchPoints.isNotEmpty() && (state.editorMode == EditorMode.DodgeBurn || state.editorMode == EditorMode.Smudge)) {
                    kotlinx.coroutines.delay(300)
                    val pts = state.retouchPoints
                    if (pts.isNotEmpty()) {
                        when (state.editorMode) {
                            EditorMode.DodgeBurn -> {
                                val s = DodgeBurnStroke(pts, state.dodgeBurnMode, exposure = 0.5f, brushSize = state.brushSize)
                                vm.pixel.applyToActivePixelLayer { DodgeBurnStrokes(listOf(s)).applyToBitmap(it) }
                            }
                            EditorMode.Smudge -> {
                                val s = SmudgeStroke(pts, strength = 0.5f, brushSize = state.brushSize)
                                vm.pixel.applyToActivePixelLayer { SmudgeStrokes(listOf(s)).applyToBitmap(it) }
                            }
                            else -> {}
                        }
                        state.retouchPoints = emptyList()
                    }
                }
            }

            EditPhotoBottomControls(state, backStack)
        }
    }

    EditPhotoTextDialog(
        texts = state.texts,
        textToEdit = state.textToEdit,
        onDismiss = { state.textToEdit = null },
    )
}
