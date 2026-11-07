package com.vayunmathur.communicate.signal

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.google.protobuf.ByteString
import com.vayunmathur.communicate.data.signal.SignalAuthData
import com.vayunmathur.communicate.data.signal.SignalClient
import com.vayunmathur.communicate.data.signal.SignalContact
import com.vayunmathur.communicate.data.signal.SignalDatabase
import com.vayunmathur.communicate.data.signal.acceptIdentityChange
import com.vayunmathur.communicate.data.signal.displayNameFor
import com.vayunmathur.communicate.data.signal.conversationIdFor
import com.vayunmathur.communicate.data.signal.linkPniToAci
import com.vayunmathur.communicate.data.signal.pendingIdentityChange
import com.vayunmathur.communicate.data.signal.registration.SignalRegistrationKeys
import com.vayunmathur.communicate.data.signal.reportIdentityChange
import com.vayunmathur.communicate.data.signal.safetyNumber
import com.vayunmathur.communicate.data.signal.e2e.SignalE2E
import com.vayunmathur.communicate.data.signal.e2e.ensureLocalPreKeys
import com.vayunmathur.communicate.data.signal.e2e.storedIdentityKey
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
import org.whispersystems.signalservice.internal.push.SignalServiceProtos

/**
 * Pass 3: SignalClient identity flows - safety numbers over the presenting
 * key, accept-only-the-shown-key, and PNI-link guards, with two real
 * libsignal identities in in-memory stores.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignalIdentitySignalPass3Test {

    private lateinit var dbA: SignalDatabase
    private lateinit var dbB: SignalDatabase
    private lateinit var authA: SignalAuthData
    private lateinit var authB: SignalAuthData
    private lateinit var e2eA: SignalE2E
    private lateinit var e2eB: SignalE2E

    private val bobAci = "7c1d9b4e-aaaa-4bbb-8ccc-ddddeeeeffff"
    private val aliceAci = "3f0e5a2c-1111-2222-3333-444455556666"

    @BeforeTest fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        dbA = Room.inMemoryDatabaseBuilder(ctx, SignalDatabase::class.java)
            .setDriver(BundledSQLiteDriver()).allowMainThreadQueries().build()
        dbB = Room.inMemoryDatabaseBuilder(ctx, SignalDatabase::class.java)
            .setDriver(BundledSQLiteDriver()).allowMainThreadQueries().build()
        authA = SignalRegistrationKeys.generate("+14155552671").authScaffold.copy(aci = aliceAci)
        authB = SignalRegistrationKeys.generate("+14155550000").authScaffold.copy(aci = bobAci)
        e2eA = SignalE2E(dbA, authA)
        e2eB = SignalE2E(dbB, authB)
        SignalClient.db = dbA
        SignalClient.e2e = e2eA
        SignalClient.authData = authA
        SignalClient.pendingIdentityChanges.clear()
        // Alice establishes a session with Bob so his identity is on record.
        val upload = e2eB.ensureLocalPreKeys()
        val signed = upload.signedPreKey!!
        val kyber = upload.lastResortKyber!!
        e2eA.processPreKeyBundle(
            bobAci, 1,
            SignalE2E.ParsedPreKeyBundle(
                registrationId = authB.effectiveAciRegId(),
                preKeyId = upload.oneTimeEcPreKeys.firstOrNull()?.id,
                preKeyPublic = upload.oneTimeEcPreKeys.firstOrNull()?.publicKey,
                signedPreKeyId = signed.id,
                signedPreKeyPublic = signed.publicKey,
                signedPreKeySignature = signed.signature ?: ByteArray(0),
                identityKey = android.util.Base64.decode(authB.effectiveAciPublic(), android.util.Base64.NO_WRAP),
                kyberPreKeyId = kyber.id,
                kyberPreKeyPublic = kyber.publicKey,
                kyberPreKeySignature = kyber.signature ?: ByteArray(0)))
    }

    @AfterTest fun tearDown() {
        SignalClient.db = null
        SignalClient.e2e = null
        SignalClient.authData = null
        SignalClient.pendingIdentityChanges.clear()
        dbA.close()
        dbB.close()
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    @Test fun safetyNumberUsesStoredThenPendingKey() = runTest {
        assertNotNull(e2eA.storedIdentityKey(bobAci), "bundle processing must store Bob's identity")
        val stored = SignalClient.safetyNumber(bobAci)
        assertNotNull(stored)
        // Unknown peer and missing e2e both yield null.
        assertNull(SignalClient.safetyNumber("nobody-aci"))
        val newKey = org.signal.libsignal.protocol.IdentityKeyPair.generate().publicKey.serialize()
        SignalClient.reportIdentityChange(bobAci, newKey)
        assertTrue(SignalClient.pendingIdentityChange(bobAci)!!.contentEquals(newKey))
        val presenting = SignalClient.safetyNumber(bobAci)
        assertNotNull(presenting)
        assertTrue(presenting != stored, "safety number must follow the presenting key")
    }

    @Test fun acceptOnlyTheShownKey() = runTest {
        val newKey = org.signal.libsignal.protocol.IdentityKeyPair.generate().publicKey.serialize()
        SignalClient.reportIdentityChange(bobAci, newKey)
        // Wrong hex is refused and the pending key stays.
        assertFalse(SignalClient.acceptIdentityChange(bobAci, "00ff"))
        assertNotNull(SignalClient.pendingIdentityChange(bobAci))
        // The shown key (case-insensitive hex) is accepted and archived.
        assertTrue(SignalClient.acceptIdentityChange(bobAci, hex(newKey).uppercase()))
        assertNull(SignalClient.pendingIdentityChange(bobAci))
        assertTrue(e2eA.storedIdentityKey(bobAci)!!.contentEquals(newKey))
        // Nothing pending: further accepts are false.
        assertFalse(SignalClient.acceptIdentityChange(bobAci, hex(newKey)))
    }

    @Test fun identityGuardsWithoutE2e() = runTest {
        SignalClient.e2e = null
        assertNull(SignalClient.safetyNumber(bobAci))
        assertFalse(SignalClient.acceptIdentityChange(bobAci, "00"))
        SignalClient.linkPniToAci(bobAci, SignalServiceProtos.PniSignatureMessage.getDefaultInstance())
        SignalClient.e2e = e2eA
        // Missing stored identities: link attempt returns without touching contacts.
        SignalClient.linkPniToAci(bobAci, SignalServiceProtos.PniSignatureMessage.getDefaultInstance())
    }

    @Test fun displayAndConversationFallbacks() = runTest {
        // No contact rows: both fall back to the raw service id.
        assertEquals(bobAci, SignalClient.displayNameFor(bobAci))
        assertEquals(bobAci, SignalClient.conversationIdFor(bobAci))
        dbA.contactDao().upsert(
            SignalContact(
                aci = bobAci, phoneE164 = "+14155550000", displayName = "Bob"))
        assertEquals("Bob", SignalClient.displayNameFor(bobAci))
        assertEquals("+14155550000", SignalClient.conversationIdFor(bobAci))
        // A contact found by PNI also resolves.
        dbA.contactDao().upsert(
            SignalContact(
                aci = "aci-other", phoneE164 = "+1999", displayName = "", pni = "PNI:pni-1"))
        assertEquals("+1999", SignalClient.displayNameFor("PNI:pni-1"))
    }
}
