package com.vayunmathur.camera.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StillFormatRoutingTest {

    @Test
    fun avifHonouredWhenNativeAvailable() {
        assertEquals(
            StillFormat.AVIF,
            StillFormatRouting.effectiveFormat(StillFormat.AVIF, nativeAvailable = true, motionPhoto = false),
        )
    }

    @Test
    fun avifFallsBackToJpegWhenNativeMissing() {
        assertEquals(
            StillFormat.JPEG,
            StillFormatRouting.effectiveFormat(StillFormat.AVIF, nativeAvailable = false, motionPhoto = false),
        )
    }

    @Test
    fun motionPhotosAlwaysStayJpeg() {
        assertEquals(
            StillFormat.JPEG,
            StillFormatRouting.effectiveFormat(StillFormat.AVIF, nativeAvailable = true, motionPhoto = true),
        )
    }

    @Test
    fun jpegStaysJpeg() {
        assertEquals(
            StillFormat.JPEG,
            StillFormatRouting.effectiveFormat(StillFormat.JPEG, nativeAvailable = true, motionPhoto = false),
        )
    }

    /** One JPEG header segment: `FF <marker> <len:u16be> <payload>` (len includes itself). */
    private fun segment(marker: Int, payload: ByteArray): ByteArray {
        val len = payload.size + 2
        return byteArrayOf(
            0xFF.toByte(), marker.toByte(), (len shr 8).toByte(), len.toByte()
        ) + payload
    }

    /** Minimal JPEG skeleton: SOI, the given header segments, then SOS. */
    private fun jpeg(vararg segments: ByteArray): ByteArray =
        byteArrayOf(0xFF.toByte(), 0xD8.toByte()) +
            segments.fold(byteArrayOf()) { acc, s -> acc + s } +
            byteArrayOf(0xFF.toByte(), 0xDA.toByte())

    @Test
    fun plainSingleNeedsInMemoryOnlyForEffectiveAvif() {
        // AVIF plain single: must leave the JPEG file path or it lands .jpg.
        assertTrue(StillFormatRouting.plainSingleNeedsInMemory(StillFormat.AVIF, nativeAvailable = true))
        // AVIF with no native encoder honestly falls back to the JPEG file path.
        assertFalse(StillFormatRouting.plainSingleNeedsInMemory(StillFormat.AVIF, nativeAvailable = false))
        // JPEG single is unchanged: file path, EXIF/Ultra HDR preserved.
        assertFalse(StillFormatRouting.plainSingleNeedsInMemory(StillFormat.JPEG, nativeAvailable = true))
    }

    @Test
    fun ultraHdrDetectedFromGainMapHeaders() {
        val xmp = segment(
            0xE1,
            "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\"/>"
                .toByteArray(Charsets.ISO_8859_1),
        )
        assertTrue(StillFormatRouting.isUltraHdrJpeg(jpeg(xmp)))
        val mpf = segment(0xE2, "MP Extension\u0000".toByteArray(Charsets.ISO_8859_1))
        assertTrue(StillFormatRouting.isUltraHdrJpeg(jpeg(mpf)))
        // Plain JPEG, and non-JPEG bytes, are not Ultra HDR.
        assertFalse(StillFormatRouting.isUltraHdrJpeg(jpeg(segment(0xE0, "JFIF\u0000".toByteArray()))))
        assertFalse(StillFormatRouting.isUltraHdrJpeg(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun exifTiffExtractedFromApp1AndMissingExifIsNull() {
        val tiff = "II*\u0000GPSMARK".toByteArray(Charsets.ISO_8859_1)
        val xmpFirst = jpeg(
            segment(0xE1, "http://ns.adobe.com/xap/1.0/\u0000<x/>".toByteArray(Charsets.ISO_8859_1)),
            segment(0xE1, "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff),
        )
        assertEquals(tiff.toList(), StillFormatRouting.extractExifTiff(xmpFirst)?.toList())
        assertEquals(null, StillFormatRouting.extractExifTiff(jpeg(segment(0xE0, "JFIF\u0000".toByteArray()))))
        assertEquals(null, StillFormatRouting.extractExifTiff(byteArrayOf(9, 9)))
    }

    @Test
    fun stillSaveRoutePerEntryFormatAndSource() {
        // AVIF-selected, plain source: every pre-encoded entry (Camera2
        // direct, ring direct/reprocess, night extension) goes through
        // the EXIF-carrying encoder.
        assertEquals(
            StillFormatRouting.StillSaveRoute.PROCESSED_ENCODER,
            StillFormatRouting.stillSaveRoute(StillFormat.AVIF, ultraHdrSource = false),
        )
        // Ultra HDR sources stay JPEG_R under either selection.
        assertEquals(
            StillFormatRouting.StillSaveRoute.ULTRA_HDR_JPEG_BYTES,
            StillFormatRouting.stillSaveRoute(StillFormat.AVIF, ultraHdrSource = true),
        )
        assertEquals(
            StillFormatRouting.StillSaveRoute.ULTRA_HDR_JPEG_BYTES,
            StillFormatRouting.stillSaveRoute(StillFormat.JPEG, ultraHdrSource = true),
        )
        // JPEG-selected stays on the stamped-JPEG landing, untouched.
        assertEquals(
            StillFormatRouting.StillSaveRoute.JPEG_BYTES,
            StillFormatRouting.stillSaveRoute(StillFormat.JPEG, ultraHdrSource = false),
        )
    }

    @Test
    fun avifPrecludesMotionPhotoDiversionOnlyWhenEffective() {
        assertTrue(StillFormatRouting.avifPrecludesMotionPhoto(StillFormat.AVIF, nativeAvailable = true))
        // No native encoder: AVIF honestly falls back to JPEG, so the
        // Motion Photo diversion is unchanged.
        assertFalse(StillFormatRouting.avifPrecludesMotionPhoto(StillFormat.AVIF, nativeAvailable = false))
        assertFalse(StillFormatRouting.avifPrecludesMotionPhoto(StillFormat.JPEG, nativeAvailable = true))
    }

    @Test
    fun extensionAndMimeMatchContainer() {
        assertEquals("jpg", StillFormatRouting.extension(StillFormat.JPEG))
        assertEquals("image/jpeg", StillFormatRouting.mimeType(StillFormat.JPEG))
        assertEquals("avif", StillFormatRouting.extension(StillFormat.AVIF))
        assertEquals("image/avif", StillFormatRouting.mimeType(StillFormat.AVIF))
        assertEquals("IMG_20260101_120000_000.avif", StillFormatRouting.displayName("20260101_120000_000", StillFormat.AVIF))
    }
}
