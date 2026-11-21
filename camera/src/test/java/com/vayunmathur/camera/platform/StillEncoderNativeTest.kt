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
    fun encodeAvifRejectsMismatchedInput() {
        if (!StitchNative.isAvailable) return
        assertEquals(null, StitchNative.encodeAvif(ByteArray(10), 64, 48, 80f, 10))
    }
}
