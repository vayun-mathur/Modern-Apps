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
import androidx.compose.runtime.mutableFloatStateOf
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

/**
 * Full-screen handwriting editor for a single ink block. Supports pen /
 * highlighter / eraser, color and stroke-width selection, and undo / redo.
 * Returns the finished strokes through [onDone], or discards edits via [onCancel].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InkEditor(
    initialStrokes: List<SerializedStroke>,
    onDone: (List<SerializedStroke>) -> Unit,
    onCancel: () -> Unit,
) {
    val strokes = remember { mutableStateListOf<Stroke>().apply { addAll(initialStrokes.map { it.deserialize() }) } }
    val redoStack = remember { mutableStateListOf<Stroke>() }

    var tool by remember { mutableStateOf(InkTool.Pen) }
    var color by remember { mutableStateOf(inkColors.first()) }
    var width by remember { mutableFloatStateOf(6f) }

    val brush = remember(tool, color, width) {
        val family = if (tool == InkTool.Highlighter) StockBrushes.highlighter() else StockBrushes.pressurePen()
        Brush.createWithColorIntArgb(
            family = family,
            colorIntArgb = color.toArgb(),
            size = width,
            epsilon = 0.1f,
        )
    }

    AppScaffold(
        title = stringResource(R.string.drawing),
        onClose = onCancel,
        actions = {
            IconButton(onClick = { onDone(strokes.map { it.serialize() }) }) { IconCheck() }
        },
        bottomBar = {
            InkToolbar(
                tool = tool,
                onToolChange = { tool = it },
                color = color,
                onColorChange = { color = it },
                width = width,
                onWidthChange = { width = it },
                canUndo = strokes.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
                onUndo = { if (strokes.isNotEmpty()) redoStack.add(strokes.removeAt(strokes.size - 1)) },
                onRedo = { if (redoStack.isNotEmpty()) strokes.add(redoStack.removeAt(redoStack.size - 1)) },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            InkCanvasView(
                currentBrush = brush,
                finishedStrokes = strokes.toList(),
                onStrokeFinished = { strokes.add(it); redoStack.clear() },
                onStrokeErased = { strokes.remove(it) },
                eraserMode = tool == InkTool.Eraser,
                enabled = true,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
