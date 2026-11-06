package com.vayunmathur.camera.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pure-JVM tests for [PanoXmp]: GPano packet content and APP1 injection. */
class PanoXmpTest {

    private fun info() = PanoInfo(
        projectionType = "equirectangular",
        fullWidth = 8000,
        fullHeight = 4000,
        croppedWidth = 8000,
        croppedHeight = 4000,
        croppedLeft = 0,
        croppedTop = 0,
    )

    @Test fun packetCarriesAllFields() {
        val xmp = PanoXmp.buildGPanoXmp(info())
        assertTrue(xmp.contains("GPano:ProjectionType=\"equirectangular\""), xmp)
        assertTrue(xmp.contains("GPano:FullPanoWidthPixels=\"8000\""), xmp)
        assertTrue(xmp.contains("GPano:FullPanoHeightPixels=\"4000\""), xmp)
        assertTrue(xmp.contains("GPano:CroppedAreaImageWidthPixels=\"8000\""), xmp)
        assertTrue(xmp.contains("GPano:CroppedAreaImageHeightPixels=\"4000\""), xmp)
        assertTrue(xmp.contains("GPano:CroppedAreaLeftPixels=\"0\""), xmp)
        assertTrue(xmp.contains("GPano:CroppedAreaTopPixels=\"0\""), xmp)
        assertTrue(xmp.contains("GPano:UsePanoramaViewer=\"True\""), xmp)
        assertTrue(xmp.startsWith("<?xpacket begin="), xmp)
        assertTrue(xmp.endsWith("<?xpacket end=\"w\"?>"), xmp)
    }

    @Test fun packetReflectsCrop() {
        val cropped = info().copy(croppedLeft = 100, croppedTop = 50, croppedWidth = 4000)
        val xmp = PanoXmp.buildGPanoXmp(cropped)
        assertTrue(xmp.contains("GPano:CroppedAreaLeftPixels=\"100\""), xmp)
        assertTrue(xmp.contains("GPano:CroppedAreaTopPixels=\"50\""), xmp)
        assertTrue(xmp.contains("GPano:CroppedAreaImageWidthPixels=\"4000\""), xmp)
        // Full dimensions stay untouched.
        assertTrue(xmp.contains("GPano:FullPanoWidthPixels=\"8000\""), xmp)
    }

    @Test fun injectAfterSoi() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x11, 0x22, 0x33)
        val out = PanoXmp.injectXmp(jpeg, "<x/>")
        // SOI preserved, APP1 marker next, original tail intact.
        assertEquals(0xFF.toByte(), out[0])
        assertEquals(0xD8.toByte(), out[1])
        assertEquals(0xFF.toByte(), out[2])
        assertEquals(0xE1.toByte(), out[3])
        assertEquals(0x11.toByte(), out[out.size - 3])
        assertEquals(0x22.toByte(), out[out.size - 2])
        assertEquals(0x33.toByte(), out[out.size - 1])
        // Payload round-trips: namespace + xmp present.
        val text = out.toString(Charsets.ISO_8859_1)
        assertTrue(text.contains("http://ns.adobe.com/xap/1.0/"), text)
        assertTrue(text.contains("<x/>"), text)
    }

    @Test fun injectLengthFieldCoversPayload() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x00)
        val xmp = "Z".repeat(100)
        val out = PanoXmp.injectXmp(jpeg, xmp)
        val nsLen = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.UTF_8).size
        val expected = nsLen + 100 + 2
        val actual = ((out[4].toInt() and 0xFF) shl 8) or (out[5].toInt() and 0xFF)
        assertEquals(expected, actual)
    }

    @Test fun nonJpegReturnedUnchanged() {
        val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47)
        assertTrue(PanoXmp.injectXmp(png, "<x/>").contentEquals(png))
        assertTrue(PanoXmp.injectXmp(ByteArray(0), "<x/>").contentEquals(ByteArray(0)))
        assertTrue(PanoXmp.injectXmp(byteArrayOf(0xFF.toByte()), "<x/>").size == 1)
    }
}
