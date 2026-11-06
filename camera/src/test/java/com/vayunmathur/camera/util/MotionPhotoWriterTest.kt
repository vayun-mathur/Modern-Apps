package com.vayunmathur.camera.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pure-JVM tests for [MotionPhotoWriter]: XMP packet and assembly layout. */
class MotionPhotoWriterTest {

    @Test fun xmpDeclaresOffset() {
        val xmp = MotionPhotoWriter.buildMotionPhotoXmp(12345)
        assertTrue(xmp.contains("GCamera:MotionPhoto=\"1\""), xmp)
        assertTrue(xmp.contains("GCamera:MicroVideo=\"1\""), xmp)
        assertTrue(xmp.contains("GCamera:MicroVideoOffset=\"12345\""), xmp)
        assertTrue(xmp.contains("GCamera:MicroVideoPresentationTimestampUs=\"-1\""), xmp)
    }

    @Test fun assembleAppendsMp4AfterJpeg() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0x01, 0x02)
        val mp4 = byteArrayOf(0x00, 0x00, 0x00, 0x08, 0x66, 0x74, 0x79, 0x70)
        val out = MotionPhotoWriter.assemble(jpeg, mp4)
        // SOI first, MP4 trailer last.
        assertEquals(0xFF.toByte(), out[0])
        assertEquals(0xD8.toByte(), out[1])
        assertTrue(out.takeLast(8).toByteArray().contentEquals(mp4))
        // Declared offset equals the MP4 length.
        val text = out.toString(Charsets.ISO_8859_1)
        assertTrue(text.contains("GCamera:MicroVideoOffset=\"8\""), text)
    }

    @Test fun assembleNonJpegKeepsTrailer() {
        val blob = byteArrayOf(0x01, 0x02, 0x03)
        val mp4 = byteArrayOf(0x0A, 0x0B)
        val out = MotionPhotoWriter.assemble(blob, mp4)
        assertTrue(out.takeLast(2).toByteArray().contentEquals(mp4))
        assertTrue(out.size == blob.size + mp4.size)
    }
}
