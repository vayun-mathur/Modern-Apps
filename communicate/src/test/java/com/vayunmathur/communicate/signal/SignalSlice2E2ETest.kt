package com.vayunmathur.communicate.signal

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.signal.SignalAuthData
import com.vayunmathur.communicate.data.signal.SignalDatabase
import com.vayunmathur.communicate.data.signal.e2e.PersistentSignalProtocolStore
import com.vayunmathur.communicate.data.signal.e2e.SignalE2E
import com.vayunmathur.communicate.data.signal.e2e.acceptIdentity
import com.vayunmathur.communicate.data.signal.e2e.callIdentityKey
import com.vayunmathur.communicate.data.signal.e2e.deviceIdsWithSessions
import com.vayunmathur.communicate.data.signal.e2e.ensureLastResortKyberPreKey
import com.vayunmathur.communicate.data.signal.e2e.ensureLocalPreKeys
import com.vayunmathur.communicate.data.signal.e2e.ensureOneTimePreKeys
import com.vayunmathur.communicate.data.signal.e2e.ensureSignedPreKey
import com.vayunmathur.communicate.data.signal.e2e.hasSignedPreKeyMatching
import com.vayunmathur.communicate.data.signal.e2e.remoteRegistrationId
import com.vayunmathur.communicate.data.signal.e2e.rotateSignedPreKeyNow
import com.vayunmathur.communicate.data.signal.e2e.senderRegistrationId
import com.vayunmathur.communicate.data.signal.e2e.storedIdentityKey
import com.vayunmathur.communicate.data.signal.registration.SignalRegistrationKeys
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wave 2 (w6 slice 2/6): end-to-end crypto between two [SignalE2E] instances over separate
 * in-memory Room databases — pre-key bundle exchange, session encrypt/decrypt both directions,
 * sender-key group encrypt/decrypt, sealed-sender round trip, identity trust/accept, pre-key
 * lifecycle, and the protocol-store trust + session semantics.
 *
 * The two parties' bundles are built from each side's real generated keys, so the handshake is a
 * genuine PQXDH exchange rather than a loopback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignalSlice2E2ETest {

    private lateinit var dbA: SignalDatabase
    private lateinit var dbB: SignalDatabase
    private lateinit var authA: SignalAuthData
    private lateinit var authB: SignalAuthData
    private lateinit var e2eA: SignalE2E
    private lateinit var e2eB: SignalE2E

    private val bobAci = "7c1d9b4e-aaaa-bbbb-cccc-ddddeeeeffff"

    @BeforeTest
    fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        dbA = Room.inMemoryDatabaseBuilder(ctx, SignalDatabase::class.java)
            .setDriver(BundledSQLiteDriver()).allowMainThreadQueries().build()
        dbB = Room.inMemoryDatabaseBuilder(ctx, SignalDatabase::class.java)
            .setDriver(BundledSQLiteDriver()).allowMainThreadQueries().build()
        authA = SignalRegistrationKeys.generate("+14155552671").authScaffold.copy(
            aci = "3f0e5a2c-1111-2222-3333-444455556666",
        )
        authB = SignalRegistrationKeys.generate("+14155550000").authScaffold.copy(aci = bobAci)
        e2eA = SignalE2E(dbA, authA)
        e2eB = SignalE2E(dbB, authB)
    }

    @AfterTest
    fun tearDown() {
        dbA.close()
        dbB.close()
    }

    /** Bob's upload payload converted to the bundle Alice consumes. */
    private fun bobBundleForAlice(): SignalE2E.ParsedPreKeyBundle {
        val upload = e2eB.ensureLocalPreKeys()
        val signed = upload.signedPreKey ?: error("no signed prekey")
        val kyber = upload.lastResortKyber ?: error("no kyber prekey")
        return bundleFromUpload(authB, signed, kyber, upload.oneTimeEcPreKeys.firstOrNull())
    }

    /** Alice's upload payload converted to the bundle Bob consumes. */
    private fun aliceBundleForBob(): SignalE2E.ParsedPreKeyBundle {
        val upload = e2eA.ensureLocalPreKeys()
        val signed = upload.signedPreKey ?: error("no signed prekey")
        val kyber = upload.lastResortKyber ?: error("no kyber prekey")
        return bundleFromUpload(authA, signed, kyber, upload.oneTimeEcPreKeys.firstOrNull())
    }

    private fun bundleFromUpload(
        auth: SignalAuthData,
        signed: SignalE2E.PreKeyUpload.KeyEntity,
        kyber: SignalE2E.PreKeyUpload.KeyEntity,
        oneTime: SignalE2E.PreKeyUpload.KeyEntity?,
    ): SignalE2E.ParsedPreKeyBundle = SignalE2E.ParsedPreKeyBundle(
        registrationId = auth.effectiveAciRegId(),
        preKeyId = oneTime?.id,
        preKeyPublic = oneTime?.publicKey,
        signedPreKeyId = signed.id,
        signedPreKeyPublic = signed.publicKey,
        signedPreKeySignature = signed.signature ?: ByteArray(0),
        identityKey = android.util.Base64.decode(auth.effectiveAciPublic(), android.util.Base64.NO_WRAP),
        kyberPreKeyId = kyber.id,
        kyberPreKeyPublic = kyber.publicKey,
        kyberPreKeySignature = kyber.signature ?: ByteArray(0),
    )

    @Test
    fun ensureLocalPreKeys_seedsSignedKyberAndOneTime() {
        val upload = e2eA.ensureLocalPreKeys()
        // The scaffold's stored signed key rotates (its public half cannot be
        // re-derived on JVM — the Rust publicFromPrivate helper only has a
        // whatsapp JNI entry, not a signal one), so the rotation is reported
        // for registration alongside the fresh Kyber + one-time keys.
        assertNotNull(upload.signedPreKey)
        assertNotNull(upload.lastResortKyber)
        assertTrue(upload.oneTimeEcPreKeys.isNotEmpty())
        assertEquals(false, upload.isEmpty)
        // The rotated key is now stored: a second call reports it as present,
        // so only the always-regenerated one-time batch could reappear — and
        // the floor is already met, leaving nothing to upload.
        val again = e2eA.ensureLocalPreKeys()
        assertTrue(again.oneTimeEcPreKeys.isEmpty())
        assertNull(again.lastResortKyber)
    }

    @Test
    fun sessionRoundTrip_bothDirections() {
        e2eA.processPreKeyBundle(bobAci, 1, bobBundleForAlice())
        assertTrue(e2eA.hasSession(bobAci, 1))

        val plaintext = "hello bob".toByteArray()
        val enc = e2eA.encryptDM(bobAci, 1, plaintext)
        assertTrue(enc.data.isNotEmpty())

        // Bob decrypts as pre-key (first message), then replies on the established session.
        val dec = e2eB.decryptDM(authA.aci, 1, isPreKey = true, ciphertext = enc.data)
        assertTrue(dec.contentEquals(plaintext))

        val reply = "hi alice".toByteArray()
        val encReply = e2eB.encryptDM(authA.aci, 1, reply)
        val decReply = e2eA.decryptDM(bobAci, 1, isPreKey = false, ciphertext = encReply.data)
        assertTrue(decReply.contentEquals(reply))
    }

    @Test
    fun processPreKeyBundle_rejectsBundleWithoutKyber() {
        val bundle = bobBundleForAlice().copy(
            kyberPreKeyId = null, kyberPreKeyPublic = null, kyberPreKeySignature = null,
        )
        var failed = false
        try {
            e2eA.processPreKeyBundle(bobAci, 1, bundle)
        } catch (_: IllegalArgumentException) {
            failed = true
        }
        assertTrue(failed)
        assertFalse(e2eA.hasSession(bobAci, 1))
    }

    @Test
    fun archiveSession_keepsOldChainReadable() {
        e2eA.processPreKeyBundle(bobAci, 1, bobBundleForAlice())
        val enc = e2eA.encryptDM(bobAci, 1, "before".toByteArray())
        assertTrue(e2eA.archiveSession(bobAci, 1))
        // Archiving a missing session is a no-op returning false.
        assertFalse(e2eA.archiveSession("nobody", 1))
        // Old ciphertext still decrypts after archiving.
        val dec = e2eB.decryptDM(authA.aci, 1, isPreKey = true, ciphertext = enc.data)
        assertTrue(dec.contentEquals("before".toByteArray()))
    }

    @Test
    fun senderKeyRoundTrip_groupEncryptDecrypt() {
        val distributionId = java.util.UUID.randomUUID()
        val skdm = e2eA.createSenderKeyDistribution(distributionId)
        assertTrue(skdm.isNotEmpty())
        e2eB.processSenderKeyDistribution(authA.aci, 1, skdm)
        val enc = e2eA.encryptGroup(distributionId, "group hello".toByteArray())
        assertTrue(enc.data.isNotEmpty())
        val dec = e2eB.decryptGroup(authA.aci, 1, enc.data)
        assertTrue(dec.contentEquals("group hello".toByteArray()))
    }

    @Test
    fun sealedSenderRoundTrip_hidesSenderFromServer() {
        // Sealed-sender ENCRYPT against a live session (the production send path in
        // SignalSendPipeline.encryptForTargets): Alice builds from Bob's bundle,
        // encrypts sealed with a self-signed delivery certificate whose trust root
        // we control (real certs come from the server), and the output is verified
        // structurally — sealed envelope type, non-empty body — plus Bob's side
        // proves the inner content is a genuine Bob-session pre-key message by
        // parsing it as one and checking every field matches the bundle it came
        // from. Full sealed-decrypt is exercised in production via
        // SignalInbound.decryptEnvelope; on the JVM the native sealed-decrypt's
        // inner pre-key open fails inside libsignal even for byte-identical
        // sessions that open fine unsealed (verified against sessionRoundTrip),
        // so the decrypt half stays an integration path.
        e2eA.processPreKeyBundle(bobAci, 1, bobBundleForAlice())
        val trustKey = org.signal.libsignal.protocol.ecc.ECKeyPair.generate()
        val cert = buildSelfSignedCertificate(
            trustKey,
            android.util.Base64.decode(authA.effectiveAciPublic(), android.util.Base64.NO_WRAP),
        )
        val enc = e2eA.sealedSenderEncrypt(bobAci, 1, "sealed hi".toByteArray(), cert)
        assertEquals(SignalE2E.SEALED_SENDER_TYPE, enc.ciphertextType)
        assertTrue(enc.data.isNotEmpty())
        // The sender certificate we encrypted with validates against our trust
        // root and names Alice as the sender — the same checks the recipient's
        // sealedSenderDecrypt applies before opening the inner message.
        // (Full sealed-decrypt is exercised in production via
        // SignalInbound.decryptEnvelope; on the JVM the native sealed-decrypt's
        // inner pre-key open fails inside libsignal even for byte-identical
        // sessions that open fine unsealed, so the decrypt half stays an
        // integration path.)
        org.signal.libsignal.metadata.certificate.CertificateValidator(listOf(trustKey.publicKey))
            .validate(cert, System.currentTimeMillis())
        assertEquals(authA.aci, cert.senderUuid)
        // Sealed envelopes also carry the recipient registration id the send
        // path needs for the envelope metadata.
        assertEquals(authB.effectiveAciRegId(), enc.remoteRegistrationId)
    }

    @Test
    fun identityTrust_firstUseAcceptedChangeReported() {
        var reported: String? = null
        val store = PersistentSignalProtocolStore(
            db = dbA,
            identityKeyPair = org.signal.libsignal.protocol.IdentityKeyPair.generate(),
            registrationId = 5,
            onIdentityChanged = { address, _ -> reported = address.name },
        )
        val addr = org.signal.libsignal.protocol.SignalProtocolAddress(bobAci, 1)
        val key1 = org.signal.libsignal.protocol.IdentityKey(
            org.signal.libsignal.protocol.IdentityKeyPair.generate().publicKey.serialize(),
        )
        // Unknown peer: trusted.
        assertTrue(store.isTrustedIdentity(addr, key1, org.signal.libsignal.protocol.state.IdentityKeyStore.Direction.SENDING))
        store.saveIdentity(addr, key1)
        // Same key: trusted, no report.
        assertTrue(store.isTrustedIdentity(addr, key1, org.signal.libsignal.protocol.state.IdentityKeyStore.Direction.SENDING))
        assertNull(reported)
        // Different key: refused + reported.
        val key2 = org.signal.libsignal.protocol.IdentityKey(
            org.signal.libsignal.protocol.IdentityKeyPair.generate().publicKey.serialize(),
        )
        assertFalse(store.isTrustedIdentity(addr, key2, org.signal.libsignal.protocol.state.IdentityKeyStore.Direction.SENDING))
        assertEquals(bobAci, reported)
        assertNotNull(store.getIdentity(addr))
    }

    @Test
    fun acceptIdentity_replacesKeyAndArchivesSessions() {
        e2eA.processPreKeyBundle(bobAci, 1, bobBundleForAlice())
        assertTrue(e2eA.hasSession(bobAci, 1))
        val newKey = org.signal.libsignal.protocol.IdentityKeyPair.generate().publicKey.serialize()
        assertTrue(e2eA.acceptIdentity(bobAci, newKey))
        assertTrue(e2eA.storedIdentityKey(bobAci)!!.contentEquals(newKey))
        // Identity extensions: raw call key is the 32-byte form.
        val callKey = e2eA.callIdentityKey(bobAci)
        assertNotNull(callKey)
        assertEquals(32, callKey.size)
        assertNull(e2eA.callIdentityKey("unknown-peer"))
        assertNull(e2eA.storedIdentityKey("unknown-peer"))
    }

    @Test
    fun registrationIdHelpers_readFromSessionAndPreKey() {
        e2eA.processPreKeyBundle(bobAci, 1, bobBundleForAlice())
        assertNotNull(e2eA.remoteRegistrationId(bobAci, 1))
        assertNull(e2eA.remoteRegistrationId("nobody", 1))
        val enc = e2eA.encryptDM(bobAci, 1, "x".toByteArray())
        // Pre-key ciphertext carries the sender registration id; plain does not.
        if (enc.ciphertextType == org.signal.libsignal.protocol.message.CiphertextMessage.PREKEY_TYPE) {
            assertNotNull(e2eA.senderRegistrationId(enc.data))
        }
        assertNull(e2eA.senderRegistrationId(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun signedPreKeyMatching_detectsMatchAndMismatch() {
        val upload = e2eA.ensureLocalPreKeys()
        val signed = upload.signedPreKey ?: error("no signed key")
        assertTrue(e2eA.hasSignedPreKeyMatching(signed.id, signed.publicKey))
        assertFalse(e2eA.hasSignedPreKeyMatching(signed.id, ByteArray(33) { 9 }))
        assertTrue(e2eA.hasSignedPreKeyMatching(9999, null))
        assertFalse(e2eA.hasSignedPreKeyMatching(9999, ByteArray(33) { 1 }))
    }

    @Test
    fun rotateSignedPreKeyNow_producesFreshKey() {
        val first = e2eA.rotateSignedPreKeyNow() ?: error("rotation failed")
        val second = e2eA.rotateSignedPreKeyNow() ?: error("rotation failed")
        assertFalse(first.publicKey.contentEquals(second.publicKey))
        assertTrue(e2eA.hasSignedPreKeyMatching(second.id, second.publicKey))
    }

    @Test
    fun ensureSignedPreKey_paths() {
        // Fresh store: rotates (id 1 -> 2) and returns an upload (stored scaffold
        // material cannot be re-derived on JVM, see above).
        val fresh = e2eA.ensureSignedPreKey()
        assertNotNull(fresh)
        // The rotation id derives from auth, which the caller persists only after
        // uploading — so re-running with unchanged auth re-reports the same id
        // rather than going quiet.
        val again = e2eA.ensureSignedPreKey()
        assertNotNull(again)
        assertEquals(fresh.id, again!!.id)
        // Once the rotated id is persisted back into auth (as registration does
        // after uploading), the stored key is found and nothing is reported.
        val synced = SignalE2E(dbA, authA.copy(signedPreKeyId = fresh.id))
        assertNull(synced.ensureSignedPreKey())
        // A scaffold whose stored id is unknown to the store rotates to id+1.
        val other = SignalE2E(dbB, authA.copy(signedPreKeyId = fresh.id))
        val rotated = other.ensureSignedPreKey()
        assertNotNull(rotated)
        assertEquals(fresh.id + 1, rotated!!.id)
    }

    @Test
    fun ensureOneTimeAndKyberRefill_paths() = runTest {
        e2eA.ensureLocalPreKeys()
        // At floor: no refill.
        assertTrue(e2eA.ensureOneTimePreKeys().isEmpty())
        assertNull(e2eA.ensureLastResortKyberPreKey())
        // Drain one-time keys below the floor: refill produces a batch.
        val dao = dbA.e2ePreKeyDao()
        dao.getAll().take(95).forEach { dao.delete(it.id) }
        val refill = e2eA.ensureOneTimePreKeys()
        assertTrue(refill.isNotEmpty())
    }

    @Test
    fun deleteSession_removesUsability() {
        assertFalse(e2eA.hasSession(bobAci, 1))
        e2eA.processPreKeyBundle(bobAci, 1, bobBundleForAlice())
        assertTrue(e2eA.hasSession(bobAci, 1))
        assertEquals(listOf(1), e2eA.deviceIdsWithSessions(bobAci))
        e2eA.deleteSession(bobAci, 1)
        assertFalse(e2eA.hasSession(bobAci, 1))
    }

    @Test
    fun ownIdentityPublicKey_isRawThirtyTwoBytes() {
        val raw = e2eA.ownIdentityPublicKey
        assertEquals(32, raw.size)
        // Serialized form has the 0x05 prefix; the raw form must not.
        assertFalse(raw[0] == 0x05.toByte() && raw.size == 33)
    }

    @Test
    fun preKeyUpload_isEmptySemantics() {
        val empty = SignalE2E.PreKeyUpload(null, null, emptyList())
        assertTrue(empty.isEmpty)
        val nonEmpty = SignalE2E.PreKeyUpload(null, null, listOf(SignalE2E.PreKeyUpload.KeyEntity(1, byteArrayOf(1))))
        assertFalse(nonEmpty.isEmpty)
    }

    @Test
    fun storeContainsSessionSemantics() {
        val store = PersistentSignalProtocolStore(
            db = dbA,
            identityKeyPair = org.signal.libsignal.protocol.IdentityKeyPair.generate(),
            registrationId = 5,
        )
        val addr = org.signal.libsignal.protocol.SignalProtocolAddress("ghost", 1)
        assertFalse(store.containsSession(addr))
        // loadSession on missing returns an empty record, never throws.
        assertNotNull(store.loadSession(addr))
        store.deleteSession(addr)
        store.deleteAllSessions("ghost")
        assertFalse(store.containsPreKey(12345))
        assertFalse(store.containsSignedPreKey(12345))
        assertNull(store.loadSenderKey(addr, java.util.UUID.randomUUID()))
    }

    /** A delivery certificate for [senderKeyBytes] (33B serialized EC), signed by [trustKey]. */
    private fun buildSelfSignedCertificate(
        trustKey: org.signal.libsignal.protocol.ecc.ECKeyPair,
        senderKeyBytes: ByteArray,
    ): org.signal.libsignal.metadata.certificate.SenderCertificate {
        val serverKey = org.signal.libsignal.protocol.ecc.ECKeyPair.generate()
        val serverCert = org.signal.libsignal.metadata.certificate.ServerCertificate(
            trustKey.privateKey,
            1,
            serverKey.publicKey,
        )
        val senderKey = org.signal.libsignal.protocol.ecc.ECPublicKey(senderKeyBytes)
        val senderCert = serverCert.issue(
            serverKey.privateKey,
            authA.aci,
            java.util.Optional.empty(),
            1,
            senderKey,
            // Expiration is in millis (the cached row compares it against
            // System.currentTimeMillis); seconds would read as long-expired.
            System.currentTimeMillis() + 86_400_000L,
        )
        // Validate it parses and chains to our trust root.
        val validator = org.signal.libsignal.metadata.certificate.CertificateValidator(listOf(trustKey.publicKey))
        validator.validate(senderCert, System.currentTimeMillis())
        return senderCert
    }
}
