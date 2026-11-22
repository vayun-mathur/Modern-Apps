package com.vayunmathur.camera.domain

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test

class UltraHdrIntentPolicyTest {

    @Test fun supportedLensWantsUltraHdrLikeCameraX() {
        assertEquals(UltraHdrIntent.ULTRA_HDR, UltraHdrIntentPolicy.resolve(jpegRSupported = true))
        assertTrue(UltraHdrIntentPolicy.wantsUltraHdr(jpegRSupported = true))
    }

    @Test fun unsupportedLensFallsBackToPlainJpeg() {
        assertEquals(UltraHdrIntent.PLAIN_JPEG, UltraHdrIntentPolicy.resolve(jpegRSupported = false))
        assertFalse(UltraHdrIntentPolicy.wantsUltraHdr(jpegRSupported = false))
    }
}
