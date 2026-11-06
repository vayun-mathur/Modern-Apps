package com.vayunmathur.communicate.whatsapp

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.communicate.data.whatsapp.WhatsAppAuthData
import com.vayunmathur.communicate.data.whatsapp.registration.RegistrationKeys
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/**
 * Slice 1 (w5): RegistrationKeys.bundleFields — the /v2 E2E key bundle.
 * Robolectric: bundleFields decodes via RegEncoding.b64Url (android.util.Base64).
 * Uses a synthetic scaffold (no native keygen) so assertions are deterministic.
 */
@RunWith(AndroidJUnit4::class)
class W5Slice1RegBundleTest {

    private fun b64Std(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    private fun scaffold(
        registrationId: Int = 1234,
        signedPreKeyId: Int = 1,
        pqId: Int = 0,
        pqPub: String = "",
        pqSig: String = "",
    ) = WhatsAppAuthData(
        phoneNumber = "+15550001",
        pushName = "",
        wid = "",
        noisePrivateKey = b64Std(ByteArray(32) { 1 }),
        noisePublicKey = b64Std(ByteArray(32) { 2 }),
        identityPrivateKey = b64Std(ByteArray(32) { 3 }),
        identityPublicKey = b64Std(ByteArray(32) { 4 }),
        registrationId = registrationId,
        signedPreKeyId = signedPreKeyId,
        signedPreKeyPublic = b64Std(ByteArray(32) { 5 }),
        signedPreKeyPrivate = b64Std(ByteArray(32) { 6 }),
        signedPreKeySignature = b64Std(ByteArray(64) { 7 }),
        pqLastResortKeyId = pqId,
        pqLastResortPublic = pqPub,
        pqLastResortSecret = "",
        pqLastResortSignature = pqSig,
    )

    private fun b64Url(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    @Test
    fun bundleFields_classicSeven_presentAndUrlSafe() {
        val fields = RegistrationKeys.bundleFields(scaffold())
        assertEquals(
            setOf("e_regid", "e_keytype", "e_ident", "e_skey_id", "e_skey_val", "e_skey_sig", "authkey"),
            fields.keys,
        )
        // e_regid = 4-byte BE of 1234, url-b64 no padding
        assertEquals(b64Url(byteArrayOf(0, 0, 0x04.toByte(), 0xD2.toByte())), fields["e_regid"])
        // e_keytype = b64url(0x05)
        assertEquals(b64Url(byteArrayOf(0x05)), fields["e_keytype"])
        // e_skey_id = 3-byte BE of 1
        assertEquals(b64Url(byteArrayOf(0, 0, 1)), fields["e_skey_id"])
        assertEquals(b64Url(ByteArray(32) { 4 }), fields["e_ident"])
        assertEquals(b64Url(ByteArray(32) { 2 }), fields["authkey"])
        fields.values.forEach { v ->
            assertFalse(v.contains('+') || v.contains('/') || v.contains('='), v)
        }
    }

    @Test
    fun bundleFields_pqAllOrNone() {
        // no PQ by default
        assertFalse(RegistrationKeys.bundleFields(scaffold()).keys.any { it.startsWith("e_pq_") })
        // partial PQ (id set, missing sig) is omitted entirely
        val partial = scaffold(pqId = 1, pqPub = b64Std(ByteArray(1568) { 9 }))
        assertFalse(RegistrationKeys.bundleFields(partial).keys.any { it.startsWith("e_pq_") })
        // full triple is emitted
        val full = scaffold(
            pqId = 1,
            pqPub = b64Std(ByteArray(1568) { 9 }),
            pqSig = b64Std(ByteArray(64) { 8 }),
        )
        val fields = RegistrationKeys.bundleFields(full)
        assertEquals(b64Url(byteArrayOf(0, 0, 1)), fields["e_pq_last_resort_id"])
        assertEquals(b64Url(ByteArray(1568) { 9 }), fields["e_pq_last_resort_val"])
        assertEquals(b64Url(ByteArray(64) { 8 }), fields["e_pq_last_resort_sig"])
    }

    @Test
    fun bundleFields_regIdBoundary() {
        // max 0x3FFF in 4-byte BE
        val fields = RegistrationKeys.bundleFields(scaffold(registrationId = 0x3FFF))
        assertEquals(b64Url(byteArrayOf(0, 0, 0x3F, 0xFF.toByte())), fields["e_regid"])
    }

    @Test
    fun emitPqLastResort_defaultsOff() {
        assertFalse(RegistrationKeys.EMIT_PQ_LAST_RESORT)
    }
}
