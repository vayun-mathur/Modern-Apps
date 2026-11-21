package com.vayunmathur.camera.domain

import kotlin.test.Test
import kotlin.test.assertEquals

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

    @Test
    fun extensionAndMimeMatchContainer() {
        assertEquals("jpg", StillFormatRouting.extension(StillFormat.JPEG))
        assertEquals("image/jpeg", StillFormatRouting.mimeType(StillFormat.JPEG))
        assertEquals("avif", StillFormatRouting.extension(StillFormat.AVIF))
        assertEquals("image/avif", StillFormatRouting.mimeType(StillFormat.AVIF))
        assertEquals("IMG_20260101_120000_000.avif", StillFormatRouting.displayName("20260101_120000_000", StillFormat.AVIF))
    }
}
