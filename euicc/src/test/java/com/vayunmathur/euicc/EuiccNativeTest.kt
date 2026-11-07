package com.vayunmathur.euicc

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class EuiccNativeTest {
    @Test fun `transmitApdu without channel throws`() {
        EuiccNative.activeChannel = null
        assertFailsWith<IllegalStateException> {
            EuiccNative.transmitApdu(byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00))
        }
    }

    @Test fun `transmitApdu forwards to active channel`() {
        var seen: ByteArray? = null
        EuiccNative.activeChannel = { cmd -> seen = cmd; byteArrayOf(0x90.toByte(), 0x00) }
        try {
            val out = EuiccNative.transmitApdu(byteArrayOf(1, 2, 3))
            assertEquals(listOf(0x90.toByte(), 0x00.toByte()), out.toList())
            assertEquals(listOf(1.toByte(), 2.toByte(), 3.toByte()), seen!!.toList())
        } finally {
            EuiccNative.activeChannel = null
        }
    }

    @Test fun `native version returns non-blank string`() {
        val v = EuiccNative.nativeVersion()
        assertNotNull(v)
        assertTrue(v.isNotBlank(), "version was: $v")
    }

    @Test fun `native getters without open channel do not return profiles`() {
        EuiccNative.activeChannel = null
        // No ISD-R channel is open, so the native core has no transport; it
        // must report failure (null) rather than fabricated data.
        val eid = runCatching { EuiccNative.nativeGetEid() }.getOrNull()
        assertTrue(eid == null || eid.isBlank(), "unexpected eid: $eid")
    }
}
