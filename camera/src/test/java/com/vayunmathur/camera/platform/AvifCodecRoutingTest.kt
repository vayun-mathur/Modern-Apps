package com.vayunmathur.camera.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The hardware-AVIF routing rule: only an `image/avif` encoder counts.
 * Pixel-class devices ship a (software) `video/av01` encoder only — that
 * must never be mistaken for a still encoder.
 */
class AvifCodecRoutingTest {

    private fun videoAv1(name: String = "c2.google.av1.encoder") =
        AvifCodecEntry(name, AV1_VIDEO_MIME, hardwareAccelerated = false, maxWidth = 1920, maxHeight = 1080)

    @Test
    fun videoAv1EncoderAloneIsNotAStillEncoder() {
        val entries = listOf(videoAv1())
        assertNull(AvifCodecRouting.stillEncoder(entries))
        assertFalse(AvifCodecRouting.hasHardwareStillEncoder(entries))
    }

    @Test
    fun noEncodersMeansSoftwarePath() {
        assertNull(AvifCodecRouting.stillEncoder(emptyList()))
        assertFalse(AvifCodecRouting.hasHardwareStillEncoder(emptyList()))
    }

    @Test
    fun imageAvifEncoderIsSelectedOverVideoAv1() {
        val hw = AvifCodecEntry("c2.vendor.avif.encoder", AVIF_IMAGE_MIME, true, 8192, 8192)
        val entries = listOf(videoAv1(), hw)
        assertEquals(hw, AvifCodecRouting.stillEncoder(entries))
        assertTrue(AvifCodecRouting.hasHardwareStillEncoder(entries))
    }

    @Test
    fun softwareImageAvifEncoderIsUsableButNotHardware() {
        val sw = AvifCodecEntry("c2.android.avif.encoder", AVIF_IMAGE_MIME, false, null, null)
        assertEquals(sw, AvifCodecRouting.stillEncoder(listOf(sw)))
        assertFalse(AvifCodecRouting.hasHardwareStillEncoder(listOf(sw)))
    }

    @Test
    fun sizeCapIsRespectedAndUnknownCapAccepts() {
        val capped = AvifCodecEntry("enc", AVIF_IMAGE_MIME, true, 4000, 3000)
        assertTrue(AvifCodecRouting.fitsSize(capped, 4000, 3000))
        assertFalse(AvifCodecRouting.fitsSize(capped, 4080, 3072))
        val uncapped = AvifCodecEntry("enc", AVIF_IMAGE_MIME, true, null, null)
        assertTrue(AvifCodecRouting.fitsSize(uncapped, 4080, 3072))
    }
}
