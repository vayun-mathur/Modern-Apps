package com.vayunmathur.notes.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.Icon
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.Stroke
import com.vayunmathur.library.ui.IconBrush
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconDraw
import com.vayunmathur.library.ui.IconEraser
import com.vayunmathur.library.ui.IconForward
import com.vayunmathur.library.ui.IconUndo
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ink.InkCanvasView
import com.vayunmathur.library.ink.SerializedStroke
import com.vayunmathur.library.ink.deserialize
import com.vayunmathur.library.ink.serialize
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.notes.R
import com.vayunmathur.notes.Route
import com.vayunmathur.notes.data.Note
import com.vayunmathur.notes.data.NoteBlock
import com.vayunmathur.notes.data.NoteBody
import com.vayunmathur.notes.data.body
import com.vayunmathur.notes.data.randomBlockId
import com.vayunmathur.notes.data.withBody
import com.vayunmathur.notes.platform.NotesViewModel

internal enum class InkTool { Pen, Highlighter, Eraser }

/**
 * Preset ink colors offered by the drawing toolbar.
 *
 * Deliberately excludes pure black and pure white: strokes are serialized as
 * fixed ARGB, so either one disappears against the themed canvas in one of the
 * two themes. The greys at each end stay legible in both.
 */
internal val inkColors = listOf(
    Color(0xFF37474F), Color(0xFFB0BEC5), Color(0xFFE53935), Color(0xFFFB8C00),
    Color(0xFFFDD835), Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFF8E24AA),
)

/**
 * Route binder for [Route.InkEditor]: loads the note with [noteId] through the
 * ViewModel, hands the strokes of the ink block with [blockId] to [InkEditor],
 * and saves back through the same editable-note binding [NotePage] uses.
 *
 * A [blockId] that is not in the note yet is a new drawing (see
 * `NotePage`'s addDrawing): confirming inserts it at the end of the note with
 * a trailing text block; cancelling inserts nothing. Confirming an existing
 * block replaces its strokes in place. Either way, confirming or cancelling
 * (including the scaffold close / system back, which pops the route) returns
 * to the note without saving on cancel, as the in-page editor did before.
 */
@Composable
fun InkEditorPage(
    backStack: NavBackStack<Route>,
    notesViewModel: NotesViewModel,
    noteId: Long,
    blockId: String,
) {
    var note by notesViewModel.editableNote(noteId) { Note(0, "", "") }

    // Wait for the stored note (mirrors NotePage): rendering InkEditor with
    // the default note first would seed its strokes from emptyList() and the
    // later database value would never be adopted by its remember.
    if (noteId != 0L && note.id == 0L) return

    val ink = note.body().blocks.firstOrNull { it.id == blockId } as? NoteBlock.Ink

    InkEditor(
        initialStrokes = ink?.strokes ?: emptyList(),
        onDone = { newStrokes ->
            val blocks = note.body().blocks.toMutableList()
            val i = blocks.indexOfFirst { it.id == blockId }
            if (i >= 0) {
                val existing = blocks[i] as? NoteBlock.Ink
                blocks[i] = existing?.copy(strokes = newStrokes)
                    ?: NoteBlock.Ink(newStrokes, id = blockId)
            } else {
                blocks.add(NoteBlock.Ink(newStrokes, id = blockId))
                // Keep a text block after inserted media so the user can keep
                // typing below it (same rule as NotePage.insertBlock).
                blocks.add(NoteBlock.Text("", randomBlockId()))
            }
            note = note.withBody(NoteBody(blocks))
            backStack.pop()
        },
        onCancel = { backStack.pop() },
    )
}


@Composable
internal fun InkToolbar(
    tool: InkTool,
    onToolChange: (InkTool) -> Unit,
    color: Color,
    onColorChange: (Color) -> Unit,
    width: Float,
    onWidthChange: (Float) -> Unit,
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToolButton(active = tool == InkTool.Pen, onClick = { onToolChange(InkTool.Pen) }) { IconDraw() }
                ToolButton(active = tool == InkTool.Highlighter, onClick = { onToolChange(InkTool.Highlighter) }) { IconBrush() }
                ToolButton(active = tool == InkTool.Eraser, onClick = { onToolChange(InkTool.Eraser) }) { IconEraser() }
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    inkColors.forEach { swatch ->
                        Box(
                            Modifier
                                .size(28.dp)
                                .background(swatch, CircleShape)
                                .border(
                                    width = if (swatch == color) 3.dp else 1.dp,
                                    color = if (swatch == color) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    shape = CircleShape,
                                )
                                .clickable { onColorChange(swatch) },
                        )
                    }
                }
                IconButton(onClick = onUndo, enabled = canUndo) { IconUndo() }
                IconButton(onClick = onRedo, enabled = canRedo) { IconForward() }
            }
            Slider(
                value = width,
                onValueChange = onWidthChange,
                valueRange = 2f..40f,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
internal fun ToolButton(active: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    IconButton(onClick = onClick) {
        androidx.compose.runtime.CompositionLocalProvider(
            com.vayunmathur.library.ui.LocalContentColor provides tint,
        ) { content() }
    }
}
