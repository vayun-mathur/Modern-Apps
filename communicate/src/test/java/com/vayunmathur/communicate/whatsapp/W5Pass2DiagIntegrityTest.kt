package com.vayunmathur.communicate.whatsapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDiag
import com.vayunmathur.communicate.data.whatsapp.registration.RegistrationIntegrity
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/**
 * Pass-2 slice-1 (whatsapp): the diagnostics sink and the pure registration-integrity
 * field builders, which need no socket, auth, or Android keystore.
 */
@RunWith(AndroidJUnit4::class)
class W5Pass2DiagIntegrityTest {

    @BeforeTest
    fun setUp() {
        WhatsAppDiag.clear()
    }

    @Test
    fun diagLogAppendsTimestampedLinesAndClears() {
        WhatsAppDiag.log("TAG1", "first message")
        WhatsAppDiag.log("TAG2", "second message")
        val entries = WhatsAppDiag.log.value
        assertEquals(2, entries.size)
        assertTrue(entries[0].endsWith("TAG1  first message"), entries[0])
        assertTrue(entries[1].endsWith("TAG2  second message"), entries[1])
        // Timestamp prefix is HH:mm:ss.SSS (12 chars) followed by a space.
        assertTrue(entries[0].length > 13 && entries[0][12] == ' ', entries[0])
        WhatsAppDiag.clear()
        assertTrue(WhatsAppDiag.log.value.isEmpty())
    }

    @Test
    fun diagLogCapsAtThreeHundredEntries() {
        repeat(305) { WhatsAppDiag.log("CAP", "entry $it") }
        val entries = WhatsAppDiag.log.value
        assertEquals(300, entries.size)
        assertTrue(entries.first().endsWith("entry 5"), entries.first())
        assertTrue(entries.last().endsWith("entry 304"), entries.last())
    }

    @Test
    fun diagPreviewFormatsHexWithTruncation() {
        assertEquals("0B[]", WhatsAppDiag.preview(byteArrayOf()))
        assertEquals("3B[00ff7f]", WhatsAppDiag.preview(byteArrayOf(0, -1, 127)))
        val long = ByteArray(30) { it.toByte() }
        val preview = WhatsAppDiag.preview(long)
        assertTrue(preview.startsWith("30B[000102"), preview)
        assertTrue(preview.endsWith("…]"), preview)
        // A custom cap truncates earlier without an ellipsis when exactly at the size.
        assertEquals("4B[01020304]", WhatsAppDiag.preview(byteArrayOf(1, 2, 3, 4), max = 4))
        assertTrue(WhatsAppDiag.preview(byteArrayOf(1, 2, 3, 4), max = 2).endsWith("…]"))
    }

    @Test
    fun integrityAidIsStableBase64Sha256() {
        val aid = RegistrationIntegrity.aidOf("android-id-123")
        assertEquals(aid, RegistrationIntegrity.aidOf("android-id-123"))
        assertTrue(aid != RegistrationIntegrity.aidOf("android-id-124"))
        // SHA-256 is 32 bytes -> 44-char base64 with padding.
        assertEquals(44, aid.length)
        assertTrue(aid.endsWith("="))
    }

    @Test
    fun integrityDerivedFields() {
        val hash = RegistrationIntegrity.permissionsHashOf(listOf("perm.a", "perm.b"))
        assertEquals(hash, RegistrationIntegrity.permissionsHashOf(listOf("perm.a", "perm.b")))
        assertTrue(hash != RegistrationIntegrity.permissionsHashOf(listOf("perm.a")))
        assertTrue(RegistrationIntegrity.tField(0L).isNotBlank())
        assertFalse(RegistrationIntegrity.tField(0L) == RegistrationIntegrity.tField(86_400L))
        assertEquals(
            """{"em":"QUJD"}""",
            RegistrationIntegrity.nativeSignalsJson("QUJD"),
        )
    }

    @Test
    fun integrityEmulationAndAutomationJson() {
        val clean = RegistrationIntegrity.emulationJson(virtio = false, vboxsf = false)
        val dirty = RegistrationIntegrity.emulationJson(virtio = true, vboxsf = true)
        assertEquals("""{"sv":false,"sb":false}""", clean)
        assertEquals("""{"sv":true,"sb":true}""", dirty)
        assertEquals("""{"sv":true,"sb":false}""", RegistrationIntegrity.emulationJson(virtio = true, vboxsf = false))
        val automation = RegistrationIntegrity.automationJson(
            mockPackages = true, multiUser = false, ae = 1L, ap = 2L, ai = 3L,
        )
        assertEquals("""{"mp":true,"mu":false,"ae":1,"ap":2,"ai":3}""", automation)
    }
}
