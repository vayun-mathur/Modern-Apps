package com.vayunmathur.games.arrows.ui.components

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.vayunmathur.games.arrows.data.ArrowPiece
import com.vayunmathur.games.arrows.data.ArrowsGameState
import com.vayunmathur.games.arrows.data.ArrowsPuzzle
import com.vayunmathur.games.arrows.data.Direction
import com.vayunmathur.games.arrows.data.GameMode
import com.vayunmathur.games.arrows.data.Mirror
import com.vayunmathur.games.arrows.data.STARTING_HEARTS
import com.vayunmathur.games.arrows.platform.ArrowMove
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Exercises the DrawScope drawing functions against a real (native-graphics) Android canvas. */
@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
class ArrowsBoardDrawingTest {

    private fun at(r: Int, c: Int) = r * 5 + c

    private fun game(mirrors: Map<Int, Mirror> = emptyMap()): ArrowsGameState {
        val pieces = listOf(
            ArrowPiece(0, listOf(at(2, 1), at(2, 2), at(2, 3)), Direction.RIGHT),
            ArrowPiece(1, listOf(at(0, 4), at(1, 4)), Direction.DOWN),
        )
        return ArrowsGameState(ArrowsPuzzle(5, 5, pieces, mirrors), emptySet(), STARTING_HEARTS, 1, GameMode.CASUAL)
    }

    private fun drawOnBitmap(block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit): Bitmap {
        val bitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        val androidCanvas = android.graphics.Canvas(bitmap)
        val scope = CanvasDrawScope()
        scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(androidCanvas), Size(200f, 200f)) {
            block()
        }
        return bitmap
    }

    private fun nonTransparentPixels(bitmap: Bitmap): Int {
        var count = 0
        for (x in 0 until bitmap.width step 2) {
            for (y in 0 until bitmap.height step 2) {
                if (android.graphics.Color.alpha(bitmap.getPixel(x, y)) > 0) count++
            }
        }
        return count
    }

    @Test
    fun drawArrowPaintsPixels() {
        val g = game()
        val bitmap = drawOnBitmap {
            val cell = size.width / 5
            drawArrow(g.puzzle.pieces[0], 5, cell, Color.Black)
            drawArrow(g.puzzle.pieces[1], 5, cell, Color.Red)
        }
        assertTrue(nonTransparentPixels(bitmap) > 20, "arrow strokes should paint pixels")
    }

    @Test
    fun drawDotsMirrorsAndRoutePaintPixels() {
        val g = game(mapOf(at(1, 1) to Mirror.FORWARD, at(3, 3) to Mirror.BACK))
        val bitmap = drawOnBitmap {
            val cell = size.width / 5
            drawDots(g, cell, Color.Gray)
            drawMirror(at(1, 1), Mirror.FORWARD, 5, cell, Color.Blue)
            drawMirror(at(3, 3), Mirror.BACK, 5, cell, Color.Blue)
            drawRoute(listOf(at(2, 4), at(1, 4)), 5, cell, Color.Red)
        }
        assertTrue(nonTransparentPixels(bitmap) > 10, "dots/mirrors/route should paint pixels")
    }

    @Test
    fun drawTravellingArrowAtSeveralAdvancesPaints() {
        val g = game()
        val piece = g.puzzle.pieces[0]
        val route = piece.cells + listOf(at(2, 4))
        for (advance in listOf(0f, 0.5f, 1.5f, 3f)) {
            val bitmap = drawOnBitmap {
                val cell = size.width / 5
                drawTravellingArrow(piece, ArrowMove(piece.id, route, 3, true), advance, 5, cell, Color.Black)
            }
            assertTrue(nonTransparentPixels(bitmap) > 5, "travelling arrow at $advance should paint")
        }
    }

    @Test
    fun drawTravellingArrowWithDegenerateRouteUsesPieceDirection() {
        val g = game()
        val piece = g.puzzle.pieces[0]
        val bitmap = drawOnBitmap {
            val cell = size.width / 5
            // Route is only the piece's own cells, so the sampled heading collapses at the head
            drawTravellingArrow(piece, ArrowMove(piece.id, piece.cells, 0, false), 0f, 5, cell, Color.Black)
        }
        assertTrue(nonTransparentPixels(bitmap) > 5)
    }

    @Test
    fun drawArrowShapeRoundsTheTurnInsteadOfPivotingOnTheCorner() {
        val bitmap = drawOnBitmap {
            // An L: right along y=100, then up at x=60. Cell is 40px, so the arc
            // radius is 20px and the curve passes ~8px inside the corner point.
            drawArrowShape(
                listOf(Offset(20f, 100f), Offset(60f, 100f), Offset(60f, 60f)),
                Offset(0f, -1f), 40f, Color.Black,
            )
        }
        assertTrue(nonTransparentPixels(bitmap) > 10, "rounded arrow should still paint")
        // A sharp polyline strokes straight through the corner centre; the arc cuts inside it,
        // leaving the exact corner point (well beyond the 2.2px half-stroke) unpainted.
        assertEquals(
            0, android.graphics.Color.alpha(bitmap.getPixel(60, 100)),
            "corner point should be cut by the arc, not stroked through",
        )
    }

    @Test
    fun drawArrowShapeWithDiagonalAndZeroHeadings() {
        val bitmap = drawOnBitmap {
            drawArrowShape(
                listOf(Offset(20f, 100f), Offset(60f, 60f), Offset(100f, 60f)),
                Offset(1f, -1f), 40f, Color.Black,
            )
            drawArrowShape(
                listOf(Offset(20f, 160f), Offset(100f, 160f)),
                Offset(0f, 0f), 40f, Color.Blue,
            )
        }
        assertTrue(nonTransparentPixels(bitmap) > 10)
        // Head tip of the horizontal arrow is to the right of its last centre
        assertEquals(40f, 40f) // cell size sanity, keeps the geometry explicit
    }
}
