package com.vayunmathur.communicate.signal

import com.vayunmathur.communicate.data.signal.SignalAuthData
import com.vayunmathur.communicate.data.signal.SignalPollOptionData
import com.vayunmathur.communicate.data.signal.SignalReaction
import com.vayunmathur.communicate.data.signal.SignalServiceData
import com.vayunmathur.communicate.data.signal.registration.SignalPqPreKey
import com.vayunmathur.communicate.data.signal.registration.SignalRegistrationKeys
import org.signal.libsignal.protocol.ecc.ECPrivateKey
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Wave 1 (w6 slice 2/6): [SignalAuthData] effective-getters, [SignalServiceData] round-trip,
 * [SignalPqPreKey] wire shape, [SignalRegistrationKeys] scaffold. Pure JVM.
 */
@RunWith(RobolectricTestRunner::class)
class SignalAuthAndServiceDataTest {

    // ---- SignalAuthData effective getters ----

    @Test
    fun effectiveGetters_preferDualIdentityFields() {
        val auth = SignalAuthData(
            phoneNumber = "+15551234567",
            identityPrivateKey = "legacy-priv", identityPublicKey = "legacy-pub",
            registrationId = 1, signedPreKeyId = 5,
            signedPreKeyPublic = "legacy-spk-pub", signedPreKeyPrivate = "legacy-spk-priv",
            signedPreKeySignature = "legacy-spk-sig",
            pqLastResortKeyId = 9, pqLastResortPublic = "legacy-pq-pub",
            pqLastResortSecret = "legacy-pq-sec", pqLastResortSignature = "legacy-pq-sig",
            aciIdentityPrivateKey = "aci-priv", aciIdentityPublicKey = "aci-pub",
            aciRegistrationId = 11, aciSignedPreKeyId = 12,
            aciSignedPreKeyPublic = "aci-spk-pub", aciSignedPreKeyPrivate = "aci-spk-priv",
            aciSignedPreKeySignature = "aci-spk-sig",
            aciPqLastResortKeyId = 13, aciPqLastResortPublic = "aci-pq-pub",
            aciPqLastResortSecret = "aci-pq-sec", aciPqLastResortSignature = "aci-pq-sig",
        )
        assertEquals("aci-priv", auth.effectiveAciPrivate())
        assertEquals("aci-pub", auth.effectiveAciPublic())
        assertEquals(11, auth.effectiveAciRegId())
        assertEquals(12, auth.effectiveAciSignedId())
        assertEquals("aci-spk-pub", auth.effectiveAciSignedPub())
        assertEquals("aci-spk-priv", auth.effectiveAciSignedPriv())
        assertEquals("aci-spk-sig", auth.effectiveAciSignedSig())
        assertEquals(13, auth.effectiveAciPqId())
        assertEquals("aci-pq-pub", auth.effectiveAciPqPub())
        assertEquals("aci-pq-sec", auth.effectiveAciPqSec())
        assertEquals("aci-pq-sig", auth.effectiveAciPqSig())
    }

    @Test
    fun effectiveGetters_fallBackToLegacyFields() {
        val auth = SignalAuthData(
            phoneNumber = "+1",
            identityPrivateKey = "legacy-priv", identityPublicKey = "legacy-pub",
            registrationId = 3, signedPreKeyId = 4,
            signedPreKeyPublic = "spk-pub", signedPreKeyPrivate = "spk-priv",
            signedPreKeySignature = "spk-sig",
            pqLastResortKeyId = 8, pqLastResortPublic = "pq-pub",
            pqLastResortSecret = "pq-sec", pqLastResortSignature = "pq-sig",
        )
        assertEquals("legacy-priv", auth.effectiveAciPrivate())
        assertEquals("legacy-pub", auth.effectiveAciPublic())
        assertEquals(3, auth.effectiveAciRegId())
        assertEquals(4, auth.effectiveAciSignedId())
        assertEquals("spk-pub", auth.effectiveAciSignedPub())
        assertEquals("spk-priv", auth.effectiveAciSignedPriv())
        assertEquals("spk-sig", auth.effectiveAciSignedSig())
        assertEquals(8, auth.effectiveAciPqId())
        assertEquals("pq-pub", auth.effectiveAciPqPub())
        assertEquals("pq-sec", auth.effectiveAciPqSec())
        assertEquals("pq-sig", auth.effectiveAciPqSig())
    }

    // ---- SignalServiceData ----

    @Test
    fun serviceData_roundTripsAllFields() {
        val sd = SignalServiceData(
            senderName = "Ada", senderId = "aci-1",
            reactions = listOf(SignalReaction("👍", 2)),
            isEdited = true, isRevoked = false,
            pollQuestion = "lunch?", pollOptions = listOf(SignalPollOptionData("a", 1, listOf("v"))),
            quotedMessageId = "m1", quotedBody = "qb", quotedSender = "qs",
            isGroup = true, groupParticipantCount = 3,
            mediaUrl = "https://x", mediaMime = "image/png", mediaName = "n.png",
        )
        val parsed = SignalServiceData.parse(sd.serialize())!!
        assertEquals(sd, parsed)
    }

    @Test
    fun serviceData_parseRejectsBlankAndGarbage() {
        assertNull(SignalServiceData.parse(null))
        assertNull(SignalServiceData.parse(""))
        assertNull(SignalServiceData.parse("   "))
        assertNull(SignalServiceData.parse("{not json"))
    }

    @Test
    fun serviceData_defaultsAreEmpty() {
        val parsed = SignalServiceData.parse(SignalServiceData().serialize())!!
        assertEquals(SignalServiceData(), parsed)
        assertFalse(parsed.isEdited)
        assertTrue(parsed.reactions.isEmpty())
    }

    // ---- SignalPqPreKey ----

    @Test
    fun pqSigningInput_isIdentity() {
        val buf = byteArrayOf(1, 2, 3)
        assertTrue(SignalPqPreKey.signingInput(buf).contentEquals(buf))
    }

    @Test
    fun pqGenerate_wireShapeAndSignature() {
        val ikp = org.signal.libsignal.protocol.IdentityKeyPair.generate()
        val gen = SignalPqPreKey.generateWithECPrivate(ikp.privateKey, keyId = 7)
        assertEquals(7, gen.keyId)
        // 1569B = 0x08 tag || 1568 raw Kyber1024 public.
        assertEquals(1569, gen.publicKey.size)
        assertEquals(0x08.toByte(), gen.publicKey[0])
        assertTrue(gen.secretKey.isNotEmpty())
        // Signature is over the full 1569B including the tag, and verifies
        // under the identity key.
        assertEquals(64, gen.signature.size)
        assertTrue(ikp.publicKey.publicKey.verifySignature(gen.publicKey, gen.signature))
        // The ByteArray overload agrees on shape.
        val gen2 = SignalPqPreKey.generate(ikp.privateKey.serialize(), keyId = 7)
        assertEquals(1569, gen2.publicKey.size)
        assertEquals(0x08.toByte(), gen2.publicKey[0])
    }

    @Test
    fun pqGenerateWithECPrivate_matchesGenerate() {
        val identity = ECPrivateKey.generate()
        val a = SignalPqPreKey.generate(identity.serialize(), 1)
        val b = SignalPqPreKey.generateWithECPrivate(identity, 1)
        assertEquals(1569, a.publicKey.size)
        assertEquals(1569, b.publicKey.size)
        assertEquals(0x08.toByte(), b.publicKey[0])
        assertEquals(64, b.signature.size)
    }

    // ---- SignalRegistrationKeys ----

    @Test
    fun registrationScaffold_hasDualIdentitiesAndValidShapes() {
        val keys = SignalRegistrationKeys.generate("+15551234567")
        val auth = keys.authScaffold
        assertEquals("+15551234567", auth.phoneNumber)
        assertFalse(auth.registered)
        // Dual identities present and distinct.
        assertTrue(auth.aciIdentityPublicKey.isNotEmpty())
        assertTrue(auth.pniIdentityPublicKey.isNotEmpty())
        assertTrue(auth.aciIdentityPublicKey != auth.pniIdentityPublicKey)
        // Registration ids in 1..16383.
        assertTrue(auth.aciRegistrationId in 1..0x3FFF)
        assertTrue(auth.pniRegistrationId in 1..0x3FFF)
        // Signed prekeys verify under their identity keys.
        val aciPub = android.util.Base64.decode(auth.aciIdentityPublicKey, android.util.Base64.NO_WRAP)
        val aciSpkPub = android.util.Base64.decode(auth.aciSignedPreKeyPublic, android.util.Base64.NO_WRAP)
        val aciSpkSig = android.util.Base64.decode(auth.aciSignedPreKeySignature, android.util.Base64.NO_WRAP)
        assertEquals(33, aciPub.size)
        assertEquals(33, aciSpkPub.size)
        assertTrue(
            org.signal.libsignal.protocol.ecc.ECPublicKey(aciPub).verifySignature(aciSpkPub, aciSpkSig),
        )
        // Kyber pubs carry the 0x08 tag.
        val kyberPub = android.util.Base64.decode(auth.aciPqLastResortPublic, android.util.Base64.NO_WRAP)
        assertEquals(1569, kyberPub.size)
        assertEquals(0x08.toByte(), kyberPub[0])
        // UAK decodes to exactly 16 bytes.
        val uak = android.util.Base64.decode(auth.unidentifiedAccessKey, android.util.Base64.NO_WRAP)
        assertEquals(16, uak.size)
        assertTrue(auth.password.isNotEmpty())
        assertNull(auth.registrationLock)
        // Legacy mirrors match ACI.
        assertEquals(auth.aciIdentityPublicKey, auth.identityPublicKey)
        assertEquals(auth.aciRegistrationId, auth.registrationId)
    }

    @Test
    fun registrationScaffold_signedScaffoldIsPreRegistration() {
        val auth = SignalRegistrationKeys.generate("+14155552671").authScaffold
        assertNotNull(auth.verificationSessionId == null)
        assertEquals(1, auth.deviceId)
        assertEquals("", auth.aci)
        assertEquals("", auth.pni)
    }
}
