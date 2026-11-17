package com.vayunmathur.notes.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.ink.brush.Brush
import androidx.ink.brush.StockBrushes
import androidx.compose.foundation.shape.RoundedCornerShape
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconKeyboardArrowDown
import com.vayunmathur.library.ui.IconKeyboardArrowUp
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ink.InkCanvasView
import com.vayunmathur.library.ink.deserialize
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.notes.data.NoteBlock
import java.io.File

@Composable
fun ImageBlock(
    block: NoteBlock.Image,
    file: File,
    onResize: (Float) -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        AsyncImage(
            model = file,
            contentDescription = null,
            modifier = Modifier
                .fillMaxWidth(block.widthFraction)
                .clip(RoundedCornerShape(8.dp)),
        )
        BlockControls(onMoveUp = onMoveUp, onMoveDown = onMoveDown, onDelete = onDelete) {
            // Step image width between quarter and full width.
            IconButton(onClick = { onResize((block.widthFraction - 0.25f).coerceAtLeast(0.25f)) }) {
                IconKeyboardArrowDown()
            }
            IconButton(onClick = { onResize((block.widthFraction + 0.25f).coerceAtMost(1f)) }) {
                IconKeyboardArrowUp()
            }
        }
    }
}



// A read-only ink preview needs some brush, but strokes carry their own; this is unused for drawing.
val previewBrush by lazy {
    Brush.createWithColorIntArgb(StockBrushes.pressurePen(), 0xFF000000.toInt(), 6f, 0.1f)
}
