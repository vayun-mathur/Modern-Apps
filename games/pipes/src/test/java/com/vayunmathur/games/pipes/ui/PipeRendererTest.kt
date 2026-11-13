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

    @Test fun pipeLabelAnchorsLandOnThePipe() {
        val rect = Rect(Offset(10f, 10f), Size(100f, 100f))
        assertEquals(rect.center, pipeLabelAnchor(rect, setOf(Direction.LEFT, Direction.RIGHT)))
        assertEquals(rect.center, pipeLabelAnchor(rect, setOf(Direction.UP)))
        for (corner in listOf(
            setOf(Direction.UP, Direction.RIGHT), setOf(Direction.UP, Direction.LEFT),
            setOf(Direction.DOWN, Direction.RIGHT), setOf(Direction.DOWN, Direction.LEFT),
        )) {
            val a = pipeLabelAnchor(rect, corner)
            val bmp = bitmapDraw { drawPipeSegment(rect, corner, Color.Red) }
            assertEquals(android.graphics.Color.RED, bmp.getPixel(a.x.toInt(), a.y.toInt()), "corner=$corner anchor=$a")
            // Mid-stroke: half a cell from the arc's corner, i.e. on the arc's centre line.
            val corner0 = Offset(
                if (Direction.RIGHT in corner) rect.right else rect.left,
                if (Direction.DOWN in corner) rect.bottom else rect.top,
            )
            assertEquals(50f, (a - corner0).getDistance(), 0.01f)
        }
    }

    @Test fun smallLabelDrawsAtAGivenAnchor() {
        val rect = Rect(Offset(10f, 10f), Size(100f, 100f))
        val bmp = bitmapDraw { drawColorLabel(rect, 1, ColorLabelPaint(), center = Offset(85f, 35f), textScale = 0.32f) }
        var ink = 0; var stray = 0
        for (x in 10 until 110) for (y in 10 until 110) {
            if (bmp.getPixel(x, y) == android.graphics.Color.TRANSPARENT) continue
            if (x in 65..105 && y in 15..55) ink++ else stray++
        }
        assertTrue(ink > 10, "ink=$ink"); assertEquals(0, stray)
    }

    @Test fun theColoursLevelsUseAreClearlyApart() {
        // Levels use up to 17 colours. "Redmean" RGB distance: the old order had Blue and
        // Light Blue at 54 among the first 17; the reordered palette keeps every pair >= 70.
        fun dist(a: Color, b: Color): Double {
            val r1 = a.red * 255; val r2 = b.red * 255; val rm = (r1 + r2) / 2
            val dr = r1 - r2; val dg = (a.green - b.green) * 255; val db = (a.blue - b.blue) * 255
            return kotlin.math.sqrt(((2 + rm / 256) * dr * dr + 4 * dg * dg + (2 + (255 - rm) / 256) * db * db).toDouble())
        }
        val used = PIPE_COLORS.take(17)
        for (i in used.indices) for (j in i + 1 until used.size) {
            assertTrue(dist(used[i], used[j]) >= 70.0, "colours $i and $j are too close")
        }
        // The classic first four stay put so small boards keep their familiar colours.
        assertEquals(listOf(Color(0xFFE53935), Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFFFDD835)), PIPE_COLORS.take(4))
    }
}
