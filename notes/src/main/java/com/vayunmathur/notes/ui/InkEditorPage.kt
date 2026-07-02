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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDraw
import com.vayunmathur.library.ui.IconEraser
import com.vayunmathur.library.ui.IconForward
import com.vayunmathur.library.ui.IconUndo
import com.vayunmathur.library.ui.InkCanvasView
import com.vayunmathur.library.util.SerializedStroke
import com.vayunmathur.library.util.deserialize
import com.vayunmathur.library.util.serialize
import com.vayunmathur.notes.R

private enum class InkTool { Pen, Highlighter, Eraser }

/** Preset ink colors offered by the drawing toolbar. */
private val inkColors = listOf(
    Color.Black, Color.White, Color(0xFFE53935), Color(0xFFFB8C00),
    Color(0xFFFDD835), Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFF8E24AA),
)

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
    var color by remember { mutableStateOf(Color.Black) }
    var width by remember { mutableStateOf(6f) }

    val brush = remember(tool, color, width) {
        val family = if (tool == InkTool.Highlighter) StockBrushes.highlighter() else StockBrushes.pressurePen()
        Brush.createWithColorIntArgb(
            family = family,
            colorIntArgb = color.toArgb(),
            size = width,
            epsilon = 0.1f,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.drawing)) },
                navigationIcon = { IconButton(onClick = onCancel) { IconClose() } },
                actions = {
                    IconButton(onClick = { onDone(strokes.map { it.serialize() }) }) { IconCheck() }
                },
            )
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

@Composable
private fun InkToolbar(
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
private fun ToolButton(active: Boolean, onClick: () -> Unit, content: @Composable () -> Unit) {
    val tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    IconButton(onClick = onClick) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.compose.material3.LocalContentColor provides tint,
        ) { content() }
    }
}
