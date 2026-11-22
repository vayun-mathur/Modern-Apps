package com.vayunmathur.camera.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Host-side AVIF round-trip through the real JNI bridge (host .so). */
class StillEncoderNativeTest {

    @Test
    fun encodeAvifProducesAvifHeader() {
        if (!StitchNative.isAvailable) return // host lib not built in this environment
        val width = 64
        val height = 48
        val rgba = ByteArray(width * height * 4) { i ->
            when (i % 4) {
                0 -> (i / 4 % 256).toByte()
                1 -> (i / 4 / width % 256).toByte()
                3 -> 0xFF.toByte()
                else -> 0x80.toByte()
            }
        }
        val encoded = StitchNative.encodeAvif(rgba, width, height, 80f, 10)
        assertNotNull(encoded)
        assertTrue(encoded.size > 12)
        assertEquals("ftypavif", String(encoded, 4, 8, Charsets.US_ASCII))
    }

    @Test
    fun encodeAvifExifMuxesExifItemIntoAvif() {
        if (!StitchNative.isAvailable) return
        val width = 64
        val height = 48
        val rgba = ByteArray(width * height * 4) { 0x80.toByte() }
        // Minimal TIFF (little-endian header) with a marker tail, as
        // buildAvifExifTiff would hand over.
        val tiff = "II*\u0000\u0008\u0000\u0000\u0000GPSMARK".toByteArray(Charsets.ISO_8859_1)
        val encoded = StitchNative.encodeAvifExif(rgba, width, height, 80f, 10, tiff)
        assertNotNull(encoded)
        assertEquals("ftypavif", String(encoded, 4, 8, Charsets.US_ASCII))
        // The Exif item type and the TIFF payload must be in the file
        // (iinf `Exif` infe + mdat item data); the Rust avif_tests prove
        // the iinf/iloc structure parses back box-for-box.
        val asText = String(encoded, Charsets.ISO_8859_1)
        assertTrue("Exif" in asText, "AVIF must carry an Exif item")
        assertTrue("GPSMARK" in asText, "AVIF Exif item must carry the TIFF payload")
        // A non-TIFF payload must be rejected, never muxed silently.
        assertEquals(null, StitchNative.encodeAvifExif(rgba, width, height, 80f, 10, byteArrayOf(1, 2, 3)))
    }

    @Test
    fun encodeAvifRejectsMismatchedInput() {
        if (!StitchNative.isAvailable) return
        assertEquals(null, StitchNative.encodeAvif(ByteArray(10), 64, 48, 80f, 10))
    }
}
