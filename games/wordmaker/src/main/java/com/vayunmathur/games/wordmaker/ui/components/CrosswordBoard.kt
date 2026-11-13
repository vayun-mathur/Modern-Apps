package com.vayunmathur.games.wordmaker.ui.components

import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
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

        val boardWidth = 37.dp * numCols
        val boardHeight = 37.dp * numRows

        val initialScale = remember(crosswordData, maxWidth, maxHeight) {
            val scaleX = if (boardWidth.value > 0) maxWidth / boardWidth else 1f
            val scaleY = if (boardHeight.value > 0) maxHeight / boardHeight else 1f
            minOf(scaleX, scaleY, 1f)
        }

        var scale by remember(crosswordData) { mutableFloatStateOf(initialScale) }
        var offset by remember(crosswordData) { mutableStateOf(Offset.Zero) }
        var boxCenter by remember { mutableStateOf(Offset.Zero) }

        // Ensure parent knows the current scale, especially the initial one
        LaunchedEffect(scale) {
            scaleUpdated(scale)
        }

        val state = rememberTransformableState { centroid, zoomChange, offsetChange, _ ->
            scale *= zoomChange
            // Keep the board point under the gesture centroid fixed while zooming.
            // The graphicsLayer lives on the centered child (default centre
            // transformOrigin), so pivot relative to the container centre, then
            // apply the two-finger drag.
            val d = centroid - boxCenter
            offset = d - (d - offset) * zoomChange + offsetChange
            scaleUpdated(scale)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .onGloballyPositioned { boxCenter = Offset(it.size.width / 2f, it.size.height / 2f) }
                .transformable(state = state)
        ) {
            Column(
                modifier = Modifier.align(Alignment.Center)
                    .wrapContentSize(align = Alignment.TopStart, unbounded = true)
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    ) // 3. Apply transformations
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

/** A cell's letter. */
private val CELL_GLYPH = 18.dp
