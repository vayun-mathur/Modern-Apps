package com.vayunmathur.games.pipes.ui

import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PipeRendererTest {

    private fun bitmapDraw(block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit): Bitmap {
        val bmp = Bitmap.createBitmap(120, 120, Bitmap.Config.ARGB_8888)
        val scope = CanvasDrawScope()
        scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(AndroidCanvas(bmp)), Size(120f, 120f), block)
        return bmp
    }

    @Test fun hexDirectionSectors() {
        assertEquals(HexDirection.E, hexDirectionBetween(0f, 0f, 10f, 0f))
        assertEquals(HexDirection.SE, hexDirectionBetween(0f, 0f, 5f, 9f))
        assertEquals(HexDirection.SW, hexDirectionBetween(0f, 0f, -5f, 9f))
        assertEquals(HexDirection.W, hexDirectionBetween(0f, 0f, -10f, 0f))
        assertEquals(HexDirection.NW, hexDirectionBetween(0f, 0f, -5f, -9f))
        assertEquals(HexDirection.NE, hexDirectionBetween(0f, 0f, 5f, -9f))
        // Slightly below the negative x-axis is still the W sector (angle ~174 degrees)
        assertEquals(HexDirection.W, hexDirectionBetween(0f, 0f, -10f, 1f))
    }

    @Test fun pipeSegmentPaintsExpectedPixels() {
        val rect = Rect(Offset(10f, 10f), Size(100f, 100f))
        val straight = bitmapDraw { drawPipeSegment(rect, setOf(Direction.LEFT, Direction.RIGHT), Color.Red) }
        assertEquals(android.graphics.Color.RED, straight.getPixel(60, 60))
        assertEquals(android.graphics.Color.RED, straight.getPixel(15, 60))
        val corner = bitmapDraw { drawPipeSegment(rect, setOf(Direction.UP, Direction.RIGHT), Color.Blue) }
        assertEquals(android.graphics.Color.BLUE, corner.getPixel(85, 35))
        val single = bitmapDraw { drawPipeSegment(rect, setOf(Direction.DOWN), Color.Green) }
        assertEquals(android.graphics.Color.GREEN, single.getPixel(60, 90))
        val tee = bitmapDraw { drawPipeSegment(rect, setOf(Direction.UP, Direction.DOWN, Direction.LEFT), Color.Yellow) }
        assertEquals(android.graphics.Color.YELLOW, tee.getPixel(60, 60))
        val empty = bitmapDraw { drawPipeSegment(rect, emptySet(), Color.Red) }
        assertEquals(android.graphics.Color.TRANSPARENT, empty.getPixel(60, 60))
    }

    @Test fun allCornerOrientationsAndArmsPaint() {
        val rect = Rect(Offset(10f, 10f), Size(100f, 100f))
        // Each corner arc passes through the centre quadrant between its two arms
        assertEquals(android.graphics.Color.RED, bitmapDraw { drawPipeSegment(rect, setOf(Direction.UP, Direction.LEFT), Color.Red) }.getPixel(35, 35))
        assertEquals(android.graphics.Color.RED, bitmapDraw { drawPipeSegment(rect, setOf(Direction.DOWN, Direction.RIGHT), Color.Red) }.getPixel(85, 85))
        assertEquals(android.graphics.Color.RED, bitmapDraw { drawPipeSegment(rect, setOf(Direction.DOWN, Direction.LEFT), Color.Red) }.getPixel(35, 85))
        // Vertical straight pipe paints top-to-bottom at the centre column only
        val vert = bitmapDraw { drawPipeSegment(rect, setOf(Direction.UP, Direction.DOWN), Color.Blue) }
        assertEquals(android.graphics.Color.BLUE, vert.getPixel(60, 15))
        assertEquals(android.graphics.Color.BLUE, vert.getPixel(60, 105))
        // Single arms reach their own edge and not the opposite one
        assertEquals(android.graphics.Color.GREEN, bitmapDraw { drawPipeSegment(rect, setOf(Direction.UP), Color.Green) }.getPixel(60, 15))
        assertEquals(android.graphics.Color.GREEN, bitmapDraw { drawPipeSegment(rect, setOf(Direction.LEFT), Color.Green) }.getPixel(15, 60))
        assertEquals(android.graphics.Color.GREEN, bitmapDraw { drawPipeSegment(rect, setOf(Direction.RIGHT), Color.Green) }.getPixel(105, 60))
        assertEquals(android.graphics.Color.TRANSPARENT, bitmapDraw { drawPipeSegment(rect, setOf(Direction.UP), Color.Green) }.getPixel(60, 105))
    }

    @Test fun cellAndMarkerPainting() {
        val rect = Rect(Offset(10f, 10f), Size(100f, 100f))
        val cell = bitmapDraw { drawEmptyCell(rect) }
        assertTrue(cell.getPixel(12, 12) != android.graphics.Color.TRANSPARENT)
        val ball = bitmapDraw { drawEndpointBall(rect, Color.Magenta) }
        assertEquals(android.graphics.Color.MAGENTA, ball.getPixel(60, 60))
        val hex = bitmapDraw { drawHexCell(Offset(60f, 60f), 40f) }
        assertTrue(hex.getPixel(60, 60) != android.graphics.Color.TRANSPARENT)
        val hexPipe = bitmapDraw { drawHexPipeSegment(Offset(60f, 60f), 40f, setOf(HexDirection.E, HexDirection.W), Color.Cyan) }
        assertEquals(android.graphics.Color.CYAN, hexPipe.getPixel(60, 60))
        assertEquals(android.graphics.Color.CYAN, hexPipe.getPixel(95, 60))
        val hexEmpty = bitmapDraw { drawHexPipeSegment(Offset(60f, 60f), 40f, emptySet(), Color.Cyan) }
        assertEquals(android.graphics.Color.TRANSPARENT, hexEmpty.getPixel(60, 60))
        val hump = bitmapDraw { drawBridgeHump(rect, Color.Red) }
        assertEquals(android.graphics.Color.RED, hump.getPixel(60, 60))
        val portal = bitmapDraw { drawPortalRing(rect) }
        assertTrue(portal.getPixel(60, 16) != android.graphics.Color.TRANSPARENT)
        for (dir in Direction.entries) {
            val marker = bitmapDraw { drawWrapMarker(rect, dir, Color.Green) }
            assertTrue(marker.getPixel(60, 60) == android.graphics.Color.TRANSPARENT) // marker sits at the edge
        }
        assertEquals(android.graphics.Color.GREEN, bitmapDraw { drawWrapMarker(rect, Direction.LEFT, Color.Green) }.getPixel(26, 60))
        assertEquals(android.graphics.Color.GREEN, bitmapDraw { drawWrapMarker(rect, Direction.DOWN, Color.Green) }.getPixel(60, 94))
    }

    @Test fun colorLabelPaintsLetterForBrightAndDarkColors() {
        val rect = Rect(Offset(10f, 10f), Size(100f, 100f))
        // Yellow (index 3, bright -> black text) and blue (index 2, dark -> white text) both render
        val bright = bitmapDraw { drawColorLabel(rect, 3) }
        val dark = bitmapDraw { drawColorLabel(rect, 2) }
        var brightInk = 0; var darkInk = 0
        for (x in 20 until 100) for (y in 20 until 100) {
            if (bright.getPixel(x, y) != android.graphics.Color.TRANSPARENT) brightInk++
            if (dark.getPixel(x, y) != android.graphics.Color.TRANSPARENT) darkInk++
        }
        assertTrue(brightInk > 20, "brightInk=$brightInk")
        assertTrue(darkInk > 20, "darkInk=$darkInk")
        // Index wraps modulo the palette / alphabet
        bitmapDraw { drawColorLabel(rect, 26 + 3) }
    }

    @Test fun pipeColorsAreDistinct() {
        assertEquals(24, PIPE_COLORS.size)
        assertEquals(PIPE_COLORS.size, PIPE_COLORS.toSet().size)
    }
}
