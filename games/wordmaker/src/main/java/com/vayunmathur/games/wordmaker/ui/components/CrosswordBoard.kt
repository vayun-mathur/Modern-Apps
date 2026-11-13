package com.vayunmathur.games.wordmaker.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.games.wordmaker.data.CrosswordData

@Composable
fun CrosswordBoard(
    foundWords: Set<String>,
    revealedHints: Set<Pair<Int, Int>>,
    crosswordData: CrosswordData,
    /** Found words to leave blank for now: their letters are still flying in. */
    hiddenWords: Set<String>,
    onCellPositioned: (position: Pair<Int, Int>, offset: Offset) -> Unit,
    onCellClicked: (row: Int, col: Int) -> Unit,
    scaleUpdated: (Float) -> Unit
) {
    val allCharPositions = mutableMapOf<Pair<Int, Int>, Char>()
    crosswordData.letterPositions.forEach { (word, occurrences) ->
        for (positions in occurrences) {
            val isFound = word in foundWords && word !in hiddenWords
            word.forEachIndexed { index, char ->
                val pos = positions[index]
                if (isFound || (pos in revealedHints && pos !in allCharPositions)) {
                    allCharPositions[pos] = char
                }
            }
        }
    }
    // The board is a fixed-size graphic that pinch-zoom scales as a whole, so the letters are
    // sized in dp: 18 sp clipped inside the 35 dp cells at large font scales.
    val size = CELL_SIZE
    val fontSize = with(LocalDensity.current) { CELL_GLYPH.toSp() }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clip(RectangleShape)
    ) {
        val numRows = crosswordData.gridStructure.size
        val numCols = if (numRows > 0) crosswordData.gridStructure[0].length else 0

        val density = LocalDensity.current
        val board = with(density) { Size((CELL_PITCH * numCols).toPx(), (CELL_PITCH * numRows).toPx()) }
        val box = Size(
            if (constraints.hasBoundedWidth) constraints.maxWidth.toFloat() else board.width,
            if (constraints.hasBoundedHeight) constraints.maxHeight.toFloat() else board.height,
        )
        // Recomputed from the current size on every layout, so after a rotation the board is
        // fitted to the new box instead of keeping a scale meant for the old one. The user's
        // zoom is stored relative to this fit and re-clamped to the new size.
        val fit = BoardZoom.fitScale(board, box)
        var zoom by remember(crosswordData) { mutableStateOf(BoardZoom()) }

        // Tell the parent the current scale, especially the initial one. Observed with
        // snapshotFlow and applied in the graphicsLayer block below, so the zoom is never read
        // in composition: a pinch redraws the board instead of recomposing every cell.
        val currentScaleUpdated by rememberUpdatedState(scaleUpdated)
        LaunchedEffect(fit, board, box) {
            snapshotFlow { zoom.clampedTo(fit, board, box).scale(fit) }
                .collect { currentScaleUpdated(it) }
        }

        val state = rememberTransformableState { centroid, zoomChange, offsetChange, _ ->
            // Zoom is clamped between the fit and BoardZoom.maxZoom, and the pan so the board
            // cannot be pushed off-screen. The graphicsLayer pivots on the board centre, which
            // sits on the box centre, so the centroid is taken relative to the box centre.
            zoom = zoom.clampedTo(fit, board, box).transformed(
                fit = fit,
                board = board,
                box = box,
                centroid = centroid - Offset(box.width / 2, box.height / 2),
                zoomChange = zoomChange,
                panChange = offsetChange,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                // Double-tap anywhere off a found word puts the board back to fit-to-screen.
                // Outside transformable on purpose: the tap detector consumes the down, and
                // transformable abandons a gesture whose events were consumed before it saw them.
                .pointerInput(crosswordData) {
                    detectTapGestures(onDoubleTap = { zoom = BoardZoom() })
                }
                .transformable(state = state)
        ) {
            Column(
                modifier = Modifier.align(Alignment.Center)
                    // Centred even when larger than the box, so the layer's pivot (the board
                    // centre) is the box centre the zoom maths assumes.
                    .wrapContentSize(align = Alignment.Center, unbounded = true)
                    .graphicsLayer {
                        val view = zoom.clampedTo(fit, board, box)
                        val scale = view.scale(fit)
                        scaleX = scale
                        scaleY = scale
                        translationX = view.pan.x
                        translationY = view.pan.y
                    }
            ) {
                crosswordData.gridStructure.forEachIndexed { y, rowString ->
                    Row {
                        rowString.forEachIndexed { x, char ->
                            if (char != '.') {
                                val letter = allCharPositions[Pair(y, x)]
                                SurfaceText(
                                    Modifier.padding(1.dp)
                                        .onGloballyPositioned {
                                            onCellPositioned(Pair(y, x), it.localToRoot(Offset.Zero))
                                        }.clickable(enabled = letter != null) {
                                            onCellClicked(y, x)
                                        },
                                    RoundedCornerShape(4.dp),
                                    if (letter != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    letter?.toString() ?: " ",
                                    Modifier,
                                    FontWeight.Bold, fontSize, size,
                                    textColor = if (letter != null) MaterialTheme.colorScheme.onPrimary else Color.Unspecified,
                                    lineHeight = TILE_GLYPH_LINE_HEIGHT,
                                )
                            } else {
                                Box(Modifier.padding(1.dp).size(size))
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A crossword cell (excluding its 1 dp margin). */
private val CELL_SIZE = 35.dp

/** A cell plus its margin on both sides: the grid's pitch. */
private val CELL_PITCH = 37.dp

/** A cell's letter. */
private val CELL_GLYPH = 18.dp
