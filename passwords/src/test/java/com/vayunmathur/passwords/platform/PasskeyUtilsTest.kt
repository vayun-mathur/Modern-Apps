package com.vayunmathur.passwords.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PasskeyUtilsTest {

    @Test fun b64UrlRoundTripsWithoutPadding() {
        val data = byteArrayOf(0, 1, 2, 250.toByte(), 251.toByte(), 255.toByte())
        val encoded = PasskeyUtils.encodeB64Url(data)
        assertTrue(!encoded.contains('='))
        assertTrue(!encoded.contains('+') && !encoded.contains('/'))
        assertEquals(data.toList(), PasskeyUtils.decodeB64Url(encoded).toList())
    }

    @Test fun credentialIdsAre16RandomBytes() {
        val a = PasskeyUtils.generateCredentialId()
        val b = PasskeyUtils.generateCredentialId()
        assertEquals(16, a.size)
        assertTrue(!a.contentEquals(b))
    }

    @Test fun authenticatorDataLayout() {
        val data = PasskeyUtils.buildAuthenticatorData("example.com", signCount = 0x01020304)
        // 32-byte rpId hash + 1 flag byte + 4-byte big-endian sign count.
        assertEquals(37, data.size)
        assertEquals(listOf(1, 2, 3, 4), data.takeLast(4).map { it.toInt() and 0xFF })
        // UP|UV|BE|BS set by default.
        assertEquals(0x01 or 0x04 or 0x08 or 0x10, data[32].toInt() and 0xFF)
    }

    @Test fun authenticatorDataFlagsOffAndAttested() {
        val data = PasskeyUtils.buildAuthenticatorData(
            "example.com", userPresent = false, userVerified = false,
            backupEligible = false, backupState = false, attestedCredentialData = true,
        )
        assertEquals(0x40, data[32].toInt() and 0xFF)
    }

    @Test fun aaguidIs16Bytes() {
        assertEquals(16, PasskeyUtils.SAME_DEVICE_AAGUID.size)
    }
}
