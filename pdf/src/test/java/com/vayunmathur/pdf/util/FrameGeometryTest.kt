package com.vayunmathur.pdf.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrameGeometryTest {
    @Test fun grayscaleUsesLumaWeights() {
        // Pure red: 255*77 shr 8 = 76, green: 150*255 shr8=149, blue: 29, white 255
        val out = grayscale(intArrayOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFFFFFFFF.toInt(), 0xFF000000.toInt()))
        assertEquals(listOf(76, 149, 28, 255, 0), out.toList())
    }

    @Test fun gaussianBlurKeepsUniformFieldAndZeroesBorder() {
        val w = 10; val h = 10
        val src = IntArray(w * h) { 159 }
        val out = gaussianBlur(src, w, h)
        assertEquals(159, out[5 * w + 5])
        assertEquals(0, out[0]) // border not convolved
    }

    @Test fun adaptiveThresholdMarksDarkPixelInBrightField() {
        val w = 12; val h = 12
        val gray = IntArray(w * h) { 200 }
        gray[6 * w + 6] = 0
        val out = adaptiveThreshold(gray, w, h)
        assertTrue(out[6 * w + 6])
        assertTrue(!out[0])
    }

    @Test fun dilateSpreadsToCrossNeighbours() {
        val w = 5; val h = 5
        val edges = BooleanArray(w * h); edges[2 * w + 2] = true
        dilate(edges, w, h)
        assertTrue(edges[2 * w + 2]); assertTrue(edges[1 * w + 2]); assertTrue(edges[3 * w + 2])
        assertTrue(edges[2 * w + 1]); assertTrue(edges[2 * w + 3])
        assertTrue(!edges[1 * w + 1]) // diagonal not dilated
    }

    @Test fun morphCloseFillsSinglePixelHole() {
        val w = 7; val h = 7
        val bin = BooleanArray(w * h) { true }; bin[3 * w + 3] = false
        morphClose(bin, w, h)
        assertTrue(bin[3 * w + 3])
    }

    @Test fun traceContoursFindsSquareBoundary() {
        val w = 20; val h = 20
        val bin = BooleanArray(w * h)
        for (y in 5..14) for (x in 5..14) bin[y * w + x] = true
        val contours = traceContours(bin, w, h)
        assertTrue(contours.isNotEmpty())
        assertTrue(contours.first().size >= 4)
        assertTrue(contours.first().all { (x, y) -> x in 5..14 && y in 5..14 })
    }

    @Test fun traceContoursEmptyForBlank() {
        assertTrue(traceContours(BooleanArray(100), 10, 10).isEmpty())
    }

    @Test fun findBestQuadDetectsFilledRectangle() {
        val w = 40; val h = 40
        val bin = BooleanArray(w * h)
        for (y in 8..31) for (x in 8..31) bin[y * w + x] = true
        val quad = findBestQuad(bin, w, h)
        assertNotNull(quad)
        // Normalized corners near the rectangle (contour is boundary, approx may inset slightly)
        assertTrue(quad.topLeft.x < 0.35f && quad.topLeft.y < 0.35f, quad.toString())
        assertTrue(quad.bottomRight.x > 0.65f && quad.bottomRight.y > 0.65f, quad.toString())
    }

    @Test fun findBestQuadNullForBlankAndTiny() {
        assertNull(findBestQuad(BooleanArray(40 * 40), 40, 40))
        val w = 40; val h = 40; val bin = BooleanArray(w * h); bin[20 * w + 20] = true
        assertNull(findBestQuad(bin, w, h))
    }

    @Test fun contourTracerRejectsVisitedAndInterior() {
        val w = 10; val h = 10; val bin = BooleanArray(w * h) { true }
        val visited = BooleanArray(w * h)
        val tracer = ContourTracer(bin, w, h, visited, intArrayOf(1, 1, 0, -1, -1, -1, 0, 1), intArrayOf(0, 1, 1, 1, 0, -1, -1, -1))
        assertNull(tracer.traceFrom(5, 5)) // interior: left neighbour foreground
    }
}
