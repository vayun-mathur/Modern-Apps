package com.vayunmathur.library.image.decoders

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BitmapDecoderTest {

    @Test
    fun sizedThumbnailRequestSamplesDown() {
        // 4000x3000 photo into a 256px thumbnail: halves 2000/1500 -> 1000/750 -> 500/375,
        // next step would drop below the target.
        assertEquals(8, BitmapDecoder.sampleSizeFor(4000, 3000, 256, 256))
    }

    @Test
    fun unconstrainedHugePhotoIsCappedAtMaxDimension() {
        // Regression test for files #768: an "original size" decode of a huge photo
        // must not come back at full resolution (~500 MB bitmap -> Canvas crash).
        val sample = BitmapDecoder.sampleSizeFor(12000, 8000, -1, -1)
        assertTrue(sample > 1, "expected downsampling, got $sample")
        assertTrue(12000 / sample <= BitmapDecoder.MAX_ORIGINAL_DIMENSION)
        assertTrue(8000 / sample <= BitmapDecoder.MAX_ORIGINAL_DIMENSION)
    }

    @Test
    fun unconstrainedSmallPhotoIsUntouched() {
        assertEquals(1, BitmapDecoder.sampleSizeFor(1000, 800, -1, -1))
    }

    @Test
    fun sizedRequestSmallerThanSourceIsUntouched() {
        assertEquals(1, BitmapDecoder.sampleSizeFor(100, 100, 256, 256))
    }

    // No JPEG-fixture infra in this module, so the EXIF correction is tested
    // as the pure orientation math it is built from (values are the
    // ExifInterface.ORIENTATION_* ints 1-8).

    @Test
    fun exifNormalAndUnknownAreIdentity() {
        for (orientation in listOf(0, 1, -1, 99)) {
            val t = BitmapDecoder.exifTransformFor(orientation)
            assertEquals(0f, t.rotationDegrees, "orientation $orientation")
            assertFalse(t.mirrorHorizontal, "orientation $orientation")
            assertFalse(t.swapsDimensions, "orientation $orientation")
        }
    }

    @Test
    fun exifRotations() {
        // 6 = ROTATE_90, 3 = ROTATE_180, 8 = ROTATE_270.
        assertEquals(90f, BitmapDecoder.exifTransformFor(6).rotationDegrees)
        assertTrue(BitmapDecoder.exifTransformFor(6).swapsDimensions)
        assertFalse(BitmapDecoder.exifTransformFor(6).mirrorHorizontal)
        assertEquals(180f, BitmapDecoder.exifTransformFor(3).rotationDegrees)
        assertFalse(BitmapDecoder.exifTransformFor(3).swapsDimensions)
        assertEquals(270f, BitmapDecoder.exifTransformFor(8).rotationDegrees)
        assertTrue(BitmapDecoder.exifTransformFor(8).swapsDimensions)
    }

    @Test
    fun exifFlipsAreRotationPlusMirror() {
        // 2 = FLIP_HORIZONTAL, 4 = FLIP_VERTICAL (180 + mirror),
        // 5 = TRANSPOSE (90 + mirror), 7 = TRANSVERSE (270 + mirror).
        assertEquals(
            BitmapDecoder.ExifTransform(0f, true), BitmapDecoder.exifTransformFor(2),
        )
        assertEquals(
            BitmapDecoder.ExifTransform(180f, true), BitmapDecoder.exifTransformFor(4),
        )
        assertEquals(
            BitmapDecoder.ExifTransform(90f, true), BitmapDecoder.exifTransformFor(5),
        )
        assertTrue(BitmapDecoder.exifTransformFor(5).swapsDimensions)
        assertEquals(
            BitmapDecoder.ExifTransform(270f, true), BitmapDecoder.exifTransformFor(7),
        )
        assertTrue(BitmapDecoder.exifTransformFor(7).swapsDimensions)
    }
}
