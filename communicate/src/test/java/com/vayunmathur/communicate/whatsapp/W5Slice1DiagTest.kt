package com.vayunmathur.communicate.whatsapp

import com.vayunmathur.communicate.data.whatsapp.WhatsAppDiag
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice 1 (w5): WhatsAppDiag log buffer + byte preview.
 * `log()` also forwards to library Log (android.util.Log stub throws on JVM without
 * Robolectric)... so these tests exercise [preview] and [clear] only, plus log-flow
 * behavior is covered via preview. NOTE: log() calls Log.status which needs Android;
 * keep this JVM-pure.
 */
class W5Slice1DiagTest {

    @Test
    fun preview_emptyBytes() {
        assertEquals("0B[]", WhatsAppDiag.preview(ByteArray(0)))
    }

    @Test
    fun preview_shortBytes_fullHex() {
        assertEquals("3B[0102ff]", WhatsAppDiag.preview(byteArrayOf(1, 2, 0xFF.toByte())))
    }

    @Test
    fun preview_longBytes_truncatedWithEllipsis() {
        val data = ByteArray(30) { it.toByte() }
        val out = WhatsAppDiag.preview(data, max = 24)
        assertTrue(out.startsWith("30B["), out)
        assertTrue(out.endsWith("…]"), out)
        // 24 bytes -> 48 hex chars inside
        assertEquals(48, out.removePrefix("30B[").removeSuffix("…]").length)
    }

    @Test
    fun preview_exactMax_noEllipsis() {
        val data = ByteArray(24) { 0xAB.toByte() }
        val out = WhatsAppDiag.preview(data)
        assertTrue(out.endsWith("]") && !out.contains("…"), out)
        assertEquals("24B[" + "ab".repeat(24) + "]", out)
    }

    @Test
    fun clear_emptiesLogFlow() {
        WhatsAppDiag.clear()
        assertEquals(emptyList(), WhatsAppDiag.log.value)
    }
}
