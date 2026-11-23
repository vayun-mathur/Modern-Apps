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

    // --- TextureView form (the applied transform) ----------------------
    // setTransform is a view-pixel PLACEMENT, not a sampling map: HWUI
    // draws a TextureView layer as Concat(layerTransform, textureMatrix)
    // with textureMatrix already mapping buffer pixels onto the view
    // rect (Layer.draw, libs/hwui). 1c8a688eb supplied the inverse fill
    // scale in texture pixels (0.893 at an exact aspect match): as a
    // placement that shrinks the content about the origin, so the
    // preview occupied only the top-left of the box with the right and
    // bottom strips undrawn (owner report after 1c8a688eb). The applied
    // matrix must instead rescale the already view-filling content
    // about the view centre by >= 1 per axis: identity when the aspects
    // match, a centred cover-crop when they do not. It must still carry
    // NO rotation (textureRel stays 0): the stream's own texture
    // transform already applied the sensor quarter-turn.

    private fun texture(
        streamW: Int, streamH: Int, viewW: Int, viewH: Int,
        sensor: Int = 90, display: Int = 0, facing: LensFacing? = LensFacing.BACK,
    ) = PreviewTransform.computeTextureAffine(streamW, streamH, viewW, viewH, sensor, display, facing)

    /**
     * The displayed-texture point a view point samples under [t]:
     * invert the placement to the base (identity) rendering, then undo
     * TextureView's base stretch from view pixels to displayed pixels.
     */
    private fun sampled(
        t: PreviewTransform.Affine, viewW: Int, viewH: Int, x: Float, y: Float,
    ): Pair<Float, Float> {
        val baseX = (x - t.tx) / t.a
        val baseY = (y - t.ty) / t.d
        return (baseX * t.displayedWidth / viewW) to (baseY * t.displayedHeight / viewH)
    }

    /** Cover + centred invariants for one applied texture transform. */
    private fun assertCoverCentred(t: PreviewTransform.Affine, viewW: Int, viewH: Int) {
        assertEquals(0, t.rotationDegrees)
        assertEquals(0f, t.b, 0.001f)
        assertEquals(0f, t.c, 0.001f)
        // Cover, never contain: the placement only ever enlarges the
        // base-stretched content, and one axis stays at 1 (no crop on
        // the axis that set the fill scale).
        assertTrue(t.a >= 1f - 0.001f && t.d >= 1f - 0.001f, "a=${t.a} d=${t.d}")
        assertTrue(minOf(t.a, t.d) <= 1.001f, "a=${t.a} d=${t.d}")
        // Effective displayed -> view scale is uniform (no stretch):
        // cover factor composed with the base stretch is `scale` on
        // both axes.
        assertEquals(t.scale, t.a * viewW / t.displayedWidth, 0.001f)
        assertEquals(t.scale, t.d * viewH / t.displayedHeight, 0.001f)
        // Centre is a fixed point of the placement, and samples the
        // displayed centre.
        val (cx, cy) = t.map(viewW / 2f, viewH / 2f)
        assertEquals(viewW / 2f, cx, 0.01f)
        assertEquals(viewH / 2f, cy, 0.01f)
        val (scx, scy) = sampled(t, viewW, viewH, viewW / 2f, viewH / 2f)
        assertEquals(t.displayedWidth / 2f, scx, 0.01f)
        assertEquals(t.displayedHeight / 2f, scy, 0.01f)
        // View corners sample INSIDE the displayed frame on both axes,
        // and the crop is symmetric: opposite edges sum to the full
        // displayed size (equal amounts cropped from opposite sides).
        val (left, top) = sampled(t, viewW, viewH, 0f, 0f)
        val (right, bottom) = sampled(t, viewW, viewH, viewW.toFloat(), viewH.toFloat())
        assertTrue(left >= -0.01f && top >= -0.01f, "left=$left top=$top")
        assertTrue(right <= t.displayedWidth + 0.01f, "right=$right")
        assertTrue(bottom <= t.displayedHeight + 0.01f, "bottom=$bottom")
        assertEquals(t.displayedWidth, left + right, 0.01f)
        assertEquals(t.displayedHeight, top + bottom, 0.01f)
    }

    @Test fun textureFormIsIdentityForKomodoExactFillBackLens() {
        // Komodo portrait: 1600x1200 stream displays as 1200x1600 in a
        // 1344x1792 (3:4) view. fillScale = 1.12 on both axes, so the
        // base stretch is already the uniform fill and the applied
        // matrix must be exactly identity. The pre-fix matrix was
        // 0.893 about the origin: content drawn at 1200x1600 in the
        // top-left, 144 px (right) and 192 px (bottom) undrawn.
        val placement = assertNotNull(compute(1600, 1200, 1344, 1792, sensor = 90))
        assertEquals(90, placement.rotationDegrees)
        assertEquals(1.12f, placement.scale, 0.001f)
        val texture = assertNotNull(texture(1600, 1200, 1344, 1792, sensor = 90))
        assertEquals(0, texture.rotationDegrees)
        assertEquals(
            listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            texture.toMatrixValues().toList(),
        )
        assertEquals(1200f, texture.displayedWidth, 0.001f)
        assertEquals(1600f, texture.displayedHeight, 0.001f)
        assertCoverCentred(texture, 1344, 1792)
        // Exact fill: view corners sample the displayed-frame corners.
        assertEquals(0f to 0f, sampled(texture, 1344, 1792, 0f, 0f))
        val (x1, y1) = sampled(texture, 1344, 1792, 1344f, 1792f)
        assertEquals(1200f, x1, 0.01f)
        assertEquals(1600f, y1, 0.01f)
    }

    @Test fun textureFormHasNoRotationForFrontLensEither() {
        // Front sensor 270: the relative rotation differs, the applied
        // matrix must not — the stream texture already turned the frame.
        val texture = assertNotNull(
            texture(1600, 1200, 1344, 1792, sensor = 270, facing = LensFacing.FRONT),
        )
        assertEquals(0, texture.rotationDegrees)
        val back = assertNotNull(texture(1600, 1200, 1344, 1792, sensor = 90))
        assertEquals(back.toMatrixValues().toList(), texture.toMatrixValues().toList())
    }

    @Test fun textureFormCentreCropsForSixteenNineBox() {
        // 1440x1080 stream (displayed 1080x1440, 4:3) into a 9:16 box
        // (1080x1920): cover scales the base content by 4/3 in x about
        // the centre (tx = -180), y untouched. Sampled window is the
        // centred 810-wide slice of the displayed frame.
        val texture = assertNotNull(texture(1440, 1080, 1080, 1920))
        assertEquals(4f / 3f, texture.a, 0.001f)
        assertEquals(1f, texture.d, 0.001f)
        assertEquals(-180f, texture.tx, 0.01f)
        assertEquals(0f, texture.ty, 0.01f)
        assertCoverCentred(texture, 1080, 1920)
        val (left, top) = sampled(texture, 1080, 1920, 0f, 0f)
        assertEquals(135f, left, 0.01f)
        assertEquals(0f, top, 0.01f)
        val (right, bottom) = sampled(texture, 1080, 1920, 1080f, 1920f)
        assertEquals(945f, right, 0.01f)
        assertEquals(1440f, bottom, 0.01f)
    }

    @Test fun textureFormCentreCropsForSquareBox() {
        // Same displayed 1200x1600 frame into a 1:1 box (1200x1200):
        // cover scales y by 4/3 about the centre (ty = -200), x at 1.
        val texture = assertNotNull(texture(1600, 1200, 1200, 1200))
        assertEquals(1f, texture.a, 0.001f)
        assertEquals(4f / 3f, texture.d, 0.001f)
        assertEquals(0f, texture.tx, 0.01f)
        assertEquals(-200f, texture.ty, 0.01f)
        assertCoverCentred(texture, 1200, 1200)
        val (_, top) = sampled(texture, 1200, 1200, 0f, 0f)
        assertEquals(200f, top, 0.01f)
        val (_, bottom) = sampled(texture, 1200, 1200, 1200f, 1200f)
        assertEquals(1400f, bottom, 0.01f)
    }

    @Test fun textureFormCentreCropsWideStreamIntoFourThreeBox() {
        // 1920x1080 stream displays as 1080x1920 (9:16) in a 3:4 box
        // (1440x1920): cover crops top/bottom symmetrically instead.
        val texture = assertNotNull(texture(1920, 1080, 1440, 1920))
        assertEquals(1f, texture.a, 0.001f)
        assertEquals(4f / 3f, texture.d, 0.001f)
        assertEquals(-320f, texture.ty, 0.01f)
        assertCoverCentred(texture, 1440, 1920)
    }

    @Test fun textureFormCoverCentredForBothLensesAndChosenAspects() {
        // 1600x1200 stream (displayed 1200x1600) behind each selectable
        // portrait box: 4:3 exact, 16:9 and 1:1 centre-cropped. Both
        // lenses yield the identical unrotated applied matrix.
        val boxes = listOf(1200 to 1600, 900 to 1600, 1200 to 1200)
        for ((viewW, viewH) in boxes) {
            val back = assertNotNull(texture(1600, 1200, viewW, viewH, sensor = 90))
            val front = assertNotNull(
                texture(1600, 1200, viewW, viewH, sensor = 270, facing = LensFacing.FRONT),
            )
            assertEquals(
                back.toMatrixValues().toList(), front.toMatrixValues().toList(),
                "view=${viewW}x$viewH",
            )
            assertCoverCentred(back, viewW, viewH)
            assertCoverCentred(front, viewW, viewH)
        }
    }

    @Test fun textureFormLandscapeDisplayIsIdentityAtExactFill() {
        // Display rotated 90: relative rotation 0, the 16:9 stream
        // displays unswapped and fills a 16:9 view exactly.
        val texture = assertNotNull(
            texture(1920, 1080, 1920, 1080, sensor = 90, display = 90),
        )
        assertEquals(0, texture.rotationDegrees)
        assertEquals(
            listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f),
            texture.toMatrixValues().toList(),
        )
        assertCoverCentred(texture, 1920, 1080)
    }

    @Test fun textureFormDegenerateSizesHaveNoTransform() {
        assertNull(texture(0, 1080, 1080, 1440))
        assertNull(texture(1440, 1080, 0, 1440))
    }
}
