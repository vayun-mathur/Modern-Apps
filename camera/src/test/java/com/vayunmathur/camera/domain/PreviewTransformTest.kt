package com.vayunmathur.camera.domain

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * FILL (centre-crop) transform math: stream aspect vs view aspect vs
 * rotation. The regression is stretch, so every case asserts the
 * no-stretch invariant (both matrix column norms equal the uniform
 * scale) plus the exact crop geometry for the known device pairings.
 */
class PreviewTransformTest {

    private fun compute(
        streamW: Int, streamH: Int, viewW: Int, viewH: Int,
        sensor: Int = 90, display: Int = 0, facing: LensFacing? = LensFacing.BACK,
    ) = PreviewTransform.compute(streamW, streamH, viewW, viewH, sensor, display, facing)

    @Test fun matchingAspectsFillExactlyWithoutCrop() {
        // Back sensor 90 portrait: 1440x1080 sensor frame displays as
        // 1080x1440 (3:4); a 3:4 box (previewRatioForWindow in portrait)
        // is filled exactly, scale 1 after the 90-degree rotation.
        val t = assertNotNull(compute(1440, 1080, 1080, 1440))
        assertEquals(90, t.rotationDegrees)
        assertEquals(0.75f, t.displayedAspect, 0.001f)
        assertEquals(1f, t.scale, 0.001f)
        assertTrue(PreviewTransform.isUniformScale(t))
        // Buffer corners land on view corners (no crop, no letterbox).
        assertEquals(1080f to 0f, t.map(0f, 0f).let { (x, y) -> x to y })
        val (x, y) = t.map(1440f, 1080f)
        assertEquals(0f, x, 0.001f)
        assertEquals(1440f, y, 0.001f)
    }

    @Test fun narrowerBoxCentreCropsUniformlyNeverStretches() {
        // Same 3:4 displayed stream into a 9:16 still-crop box
        // (1080x1920): FILL scales by max(1, 1920/1440)=4/3 uniformly and
        // crops the sides symmetrically. The old no-transform path scaled
        // x by 1080/1440 and y by 1920/1080 — different factors (stretch).
        val t = assertNotNull(compute(1440, 1080, 1080, 1920))
        assertEquals(4f / 3f, t.scale, 0.001f)
        assertTrue(PreviewTransform.isUniformScale(t))
        // Displayed stream after scale is 1440x1920: 180 px cropped per side.
        val (left, _) = t.map(720f, 540f) // buffer centre stays view centre
        assertEquals(540f, left, 0.001f)
        val (topLeftX, topLeftY) = t.map(0f, 0f)
        assertEquals(1260f, topLeftX, 0.001f) // 540 + 4/3*540, rotated frame
        assertEquals(0f, topLeftY, 0.001f)
        // Rotated 90 degrees: the linear part lives on the anti-diagonal.
        assertEquals(0f, t.a, 0.001f)
        assertEquals(4f / 3f, abs(t.b), 0.001f)
        assertEquals(4f / 3f, abs(t.c), 0.001f)
    }

    @Test fun landscapeDisplayRotationUnswapsAspect() {
        // Display rotated 90 (landscape window): relative rotation 0, the
        // 16:9 stream displays unswapped and fills a 16:9 box exactly.
        val t = assertNotNull(compute(1920, 1080, 1920, 1080, display = 90))
        assertEquals(0, t.rotationDegrees)
        assertEquals(16f / 9f, t.displayedAspect, 0.001f)
        assertEquals(1f, t.scale, 0.001f)
        assertTrue(PreviewTransform.isUniformScale(t))
        assertEquals(0f to 0f, t.map(0f, 0f))
        assertEquals(1920f to 1080f, t.map(1920f, 1080f))
    }

    @Test fun frontLensUsesFrontRotationFormula() {
        // Front sensor 270 portrait: relative 270, aspect swaps like back.
        val t = assertNotNull(
            compute(1440, 1080, 1080, 1440, sensor = 270, facing = LensFacing.FRONT),
        )
        assertEquals(270, t.rotationDegrees)
        assertEquals(0.75f, t.displayedAspect, 0.001f)
        assertTrue(PreviewTransform.isUniformScale(t))
        // Centre invariant holds for every rotation.
        val (cx, cy) = t.map(720f, 540f)
        assertEquals(540f, cx, 0.001f)
        assertEquals(720f, cy, 0.001f)
    }

    @Test fun everyRotationKeepsCentreAndUniformScale() {
        for (display in listOf(0, 90, 180, 270)) {
            val t = assertNotNull(compute(1920, 1080, 1000, 1400, display = display))
            assertTrue(PreviewTransform.isUniformScale(t), "display=$display")
            val (cx, cy) = t.map(960f, 540f)
            assertEquals(500f, cx, 0.001f)
            assertEquals(700f, cy, 0.001f)
            // Fill: the scaled displayed frame covers the view on both axes.
            assertTrue(t.scale * t.displayedWidth >= 1000f - 0.01f)
            assertTrue(t.scale * t.displayedHeight >= 1400f - 0.01f)
        }
    }

    @Test fun deviceBoxFillsExactlyFromNativeAspectStream() {
        // Pixel 9 Pro portrait pairing after stream selection picks the
        // sensor's native 4:3: 1440x1080 stream into the 1344x1792 (3:4)
        // TextureView fills exactly — scale 1344/1080 on both axes, no
        // crop. (The square 1080x1080 stream this replaces needed scale
        // 1.659 and cropped 25% of each axis.)
        val t = assertNotNull(compute(1440, 1080, 1344, 1792))
        assertEquals(90, t.rotationDegrees)
        assertEquals(1344f / 1080f, t.scale, 0.001f)
        assertTrue(PreviewTransform.isUniformScale(t))
        val (x0, y0) = t.map(0f, 0f)
        assertEquals(1344f, x0, 0.001f)
        assertEquals(0f, y0, 0.001f)
        val (x1, y1) = t.map(1440f, 1080f)
        assertEquals(0f, x1, 0.001f)
        assertEquals(1792f, y1, 0.001f)
    }

    @Test fun degenerateSizesHaveNoTransform() {
        assertNull(compute(0, 1080, 1080, 1440))
        assertNull(compute(1440, 1080, 0, 1440))
        assertNull(compute(1440, 1080, 1080, 0))
    }
}
