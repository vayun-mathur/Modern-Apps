package com.vayunmathur.games.wordmaker.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BoardZoomTest {
    private val board = Size(370f, 222f)   // 10 x 6 cells at 37 px
    private val portrait = Size(300f, 500f)
    private val landscape = Size(700f, 200f)

    @Test fun fitNeverUpscalesAndFollowsTheBox() {
        assertEquals(1f, BoardZoom.fitScale(board, Size(1000f, 1000f)))
        assertEquals(300f / 370f, BoardZoom.fitScale(board, portrait))
        assertEquals(200f / 222f, BoardZoom.fitScale(board, landscape))
    }

    @Test fun zoomIsClampedBetweenFitAndMax() {
        val fit = BoardZoom.fitScale(board, portrait)
        val zoomedOut = BoardZoom().transformed(fit, board, portrait, Offset.Zero, zoomChange = 0.1f, panChange = Offset.Zero)
        assertEquals(fit, zoomedOut.scale(fit))
        val zoomedIn = BoardZoom().transformed(fit, board, portrait, Offset.Zero, zoomChange = 50f, panChange = Offset.Zero)
        assertEquals(BoardZoom.maxZoom(fit), zoomedIn.zoom)
        assertTrue(zoomedIn.scale(fit) >= 2f - 1e-4f, "max zoom reaches at least 2x natural size")
    }

    @Test fun panCannotPushTheBoardOffScreen() {
        val fit = BoardZoom.fitScale(board, portrait)
        // At the fit the board is no wider than the box: it stays centred however far you drag.
        val atFit = BoardZoom().transformed(fit, board, portrait, Offset.Zero, 1f, Offset(5000f, -5000f))
        assertEquals(0f, atFit.pan.x, 0.01f)
        assertEquals(0f, atFit.pan.y, 0.01f)
        // Zoomed 2x: it may move only until its edge meets the box edge.
        val zoomed = BoardZoom(zoom = 2f).transformed(fit, board, portrait, Offset.Zero, 1f, Offset(5000f, 5000f))
        val scaled = board.width * fit * 2f
        assertEquals((scaled - portrait.width) / 2, zoomed.pan.x, 0.01f)
        assertEquals(0f, zoomed.pan.y, 0.01f) // 222 * 0.81 * 2 = 360 < 500: still fits vertically
    }

    @Test fun pinchKeepsThePointUnderTheFingersFixed() {
        val fit = 1f
        val big = Size(1000f, 1000f)
        val centroid = Offset(100f, 50f)
        val z = BoardZoom().transformed(fit, Size(2000f, 2000f), big, centroid, zoomChange = 1.5f, panChange = Offset.Zero)
        // Board point under the centroid before: centroid (pan 0, scale 1). After: pan + centroid' * 1.5.
        assertEquals(centroid.x, z.pan.x + centroid.x * 1.5f, 0.01f)
        assertEquals(centroid.y, z.pan.y + centroid.y * 1.5f, 0.01f)
    }

    @Test fun rotationReclampsAStaleZoomAndPan() {
        val portraitFit = BoardZoom.fitScale(board, portrait)
        val zoomed = BoardZoom().transformed(portraitFit, board, portrait, Offset.Zero, 2f, Offset(10_000f, 0f))
        // In landscape the fit changes and the old pan would push the board too far.
        val landscapeFit = BoardZoom.fitScale(board, landscape)
        val view = zoomed.clampedTo(landscapeFit, board, landscape)
        val maxX = maxOf(0f, (board.width * view.scale(landscapeFit) - landscape.width) / 2)
        assertTrue(kotlin.math.abs(view.pan.x) <= maxX + 0.01f)
        assertEquals(zoomed.zoom, view.zoom)
    }
}
