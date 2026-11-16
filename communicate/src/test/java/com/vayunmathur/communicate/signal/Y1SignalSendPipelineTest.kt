package com.vayunmathur.communicate.signal

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.signal.SignalAuthData
import com.vayunmathur.communicate.data.signal.SignalClient
import com.vayunmathur.communicate.data.signal.SignalContact
import com.vayunmathur.communicate.data.signal.SignalConversation
import com.vayunmathur.communicate.data.signal.SignalDatabase
import com.vayunmathur.communicate.data.signal.SignalEvent
import com.vayunmathur.communicate.data.signal.SignalGroups
import com.vayunmathur.communicate.data.signal.SignalProtocol
import com.vayunmathur.communicate.data.signal.e2e.SignalE2E
import com.vayunmathur.communicate.data.signal.e2e.ensureLocalPreKeys
import com.vayunmathur.communicate.data.signal.ensureLocalPreKeys as clientEnsureLocalPreKeys
import com.vayunmathur.communicate.data.signal.establishSession
import com.vayunmathur.communicate.data.signal.groupContextFor
import com.vayunmathur.communicate.data.signal.putMessages
import com.vayunmathur.communicate.data.signal.registration.SignalRegistrationKeys
import com.vayunmathur.communicate.data.signal.resolveDestinationAci
import com.vayunmathur.communicate.data.signal.sendContent
import com.vayunmathur.communicate.data.signal.sendMessage
import com.vayunmathur.communicate.data.signal.transport.SignalPayload
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.whispersystems.signalservice.internal.push.SignalServiceProtos

/**
 * Y1: SignalSendPipeline + SignalClient send core.
 *
 * Offline (e2e = null / socket = null) every send must fail closed with a
 * SendFailed and no cached row; with a real two-party E2E session the
 * encrypt step genuinely runs and only the final PUT fails (no server),
 * so the message is still not cached and still reports SendFailed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Y1SignalSendPipelineTest {

    private lateinit var db: SignalDatabase
    private lateinit var dbBob: SignalDatabase
    private val selfAci = "aaaaaaaa-1111-2222-3333-444455556666"
    private val peer = "3f0e5a2c-1111-2222-3333-444455556666"
    private val bobAci = "7c1d9b4e-aaaa-bbbb-cccc-ddddeeeeffff"

    @BeforeTest
    fun open() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, SignalDatabase::class.java)
            .setDriver(BundledSQLiteDriver()).allowMainThreadQueries().build()
        dbBob = Room.inMemoryDatabaseBuilder(ctx, SignalDatabase::class.java)
            .setDriver(BundledSQLiteDriver()).allowMainThreadQueries().build()
        SignalClient.db = db
        SignalClient.e2e = null
        SignalClient.authData = SignalAuthData(phoneNumber = "+14155552671", aci = selfAci, password = "secret")
        SignalClient.socket = null
        SignalClient.unauthSocket = null
        SignalClient.appContext = null
        SignalClient.preKeysPrepared.set(false)
        SignalClient.pendingIdentityChanges.clear()
    }

    @AfterTest
    fun close() {
        SignalClient.db = null
        SignalClient.authData = null
        SignalClient.e2e = null
        SignalClient.appContext = null
        SignalClient.pendingIdentityChanges.clear()
        db.close()
        dbBob.close()
    }

    private fun content(text: String = "hi", ts: Long = 42L): SignalServiceProtos.Content =
        SignalServiceProtos.Content.newBuilder()
            .setDataMessage(SignalServiceProtos.DataMessage.newBuilder().setBody(text).setTimestamp(ts))
            .build()

    private fun editContent(targetTs: Long = 42L, newBody: String = "fixed"): SignalServiceProtos.Content =
        SignalPayload.buildContentForEdit(
            targetSentTimestamp = targetTs,
            newDataMessage = SignalPayload.buildDataMessage(body = newBody, timestamp = 99L),
        )

    // ---- sendContent destination handling ----

    @Test
    fun sendContent_blankAndWhitespaceDestinationsFail() = runTest {
        assertFalse(SignalClient.sendContent("", content()))
        assertFalse(SignalClient.sendContent("   ", content()))
        assertFalse(SignalClient.sendContent("\t\n", content()))
    }

    @Test
    fun sendContent_oneToOneWithoutStoreFailsClosed() = runTest {
        // ACI, PNI and phone-shaped destinations all reach the store check and fail.
        assertFalse(SignalClient.sendContent(peer, content()))
        assertFalse(SignalClient.sendContent("PNI:$peer", content()))
        assertFalse(SignalClient.sendContent("+14155552671", content()))
    }

    @Test
    fun sendContent_editContentWithoutStoreFailsClosed() = runTest {
        // EditMessage carries its own DataMessage timestamp; the pipeline must
        // accept the shape and still fail only at the missing store.
        assertFalse(SignalClient.sendContent(peer, editContent()))
        assertFalse(SignalClient.sendContent(peer, SignalServiceProtos.Content.getDefaultInstance()))
    }

    @Test
    fun sendContent_groupWithoutParticipantsFails() = runTest {
        assertFalse(SignalClient.sendContent("group:no-such-group", content()))
        db.conversationDao().upsert(
            SignalConversation(chatId = "group:empty", isGroup = true, participants = ""),
        )
        assertFalse(SignalClient.sendContent("group:empty", content()))
        db.conversationDao().upsert(
            SignalConversation(chatId = "group:blanks", isGroup = true, participants = " , , "),
        )
        assertFalse(SignalClient.sendContent("group:blanks", content()))
    }

    @Test
    fun sendContent_groupWithParticipantsFansOutAndFailsWithoutStore() = runTest {
        // Participants are trimmed and blanks dropped; each fan-out attempt
        // fails at the missing protocol store, so the group send is false.
        db.conversationDao().upsert(
            SignalConversation(
                chatId = "group:fanout", isGroup = true,
                participants = " $peer , , $bobAci ",
            ),
        )
        assertFalse(SignalClient.sendContent("group:fanout", content()))
        // Legacy "group-" prefix takes the same fan-out branch.
        db.conversationDao().upsert(
            SignalConversation(chatId = "group-legacy", isGroup = true, participants = peer),
        )
        assertFalse(SignalClient.sendContent("group-legacy", content()))
    }

    @Test
    fun sendContent_groupParticipantThatIsItselfAGroupIdDoesNotRecurseForever() = runTest {
        db.conversationDao().upsert(
            SignalConversation(chatId = "group:outer", isGroup = true, participants = "group:inner"),
        )
        db.conversationDao().upsert(
            SignalConversation(chatId = "group:inner", isGroup = true, participants = peer),
        )
        // Outer expands to "group:inner"; that sendEncryptedTo call fails at
        // the missing store before any recursive group handling.
        assertFalse(SignalClient.sendContent("group:outer", content()))
    }

    // ---- groupContextFor ----

    @Test
    fun groupContextFor_returnsStoredKeyAndRevisionForRealGroup() = runTest {
        val (masterKey, _) = SignalGroups.generateMasterKeyAndSecretParams()
        val convId = SignalProtocol.toConversationId("", masterKey)
        db.conversationDao().upsert(
            SignalConversation(
                chatId = convId, isGroup = true, name = "G",
                groupMasterKey = masterKey, groupRevision = 9,
            ),
        )
        val (key, rev) = SignalClient.groupContextFor(convId)
        assertNotNull(key)
        assertTrue(key.contentEquals(masterKey))
        assertEquals(9, rev)
    }

    @Test
    fun groupContextFor_nullsForNonGroupUnknownAndKeylessGroups() = runTest {
        assertEquals(null to null, SignalClient.groupContextFor(peer))
        assertEquals(null to null, SignalClient.groupContextFor("+14155552671"))
        assertEquals(null to null, SignalClient.groupContextFor("group:unknown"))
        db.conversationDao().upsert(SignalConversation(chatId = "group:keyless", isGroup = true))
        assertEquals(null to null, SignalClient.groupContextFor("group:keyless"))
    }

    // ---- resolveDestinationAci / knownServiceIdFor ----

    @Test
    fun resolveDestinationAci_passesThroughServiceIdsVerbatim() = runTest {
        assertEquals(peer, SignalClient.resolveDestinationAci(peer))
        assertEquals(peer.uppercase(), SignalClient.resolveDestinationAci(peer.uppercase()))
        assertEquals("PNI:$peer", SignalClient.resolveDestinationAci("PNI:$peer"))
        assertEquals(peer, SignalClient.resolveDestinationAci("  $peer  "))
        assertNull(SignalClient.resolveDestinationAci(""))
        assertNull(SignalClient.resolveDestinationAci("   "))
    }

    @Test
    fun resolveDestinationAci_resolvesPhoneFromContactTable() = runTest {
        // ACI known: addressed by ACI.
        db.contactDao().upsert(
            SignalContact(aci = peer, phoneE164 = "+14155550001", onSignal = true),
        )
        assertEquals(peer, SignalClient.resolveDestinationAci("+14155550001"))
        // Only PNI known (aci column holds the phone fallback): addressed by PNI.
        db.contactDao().upsert(
            SignalContact(aci = "+14155550002", phoneE164 = "+14155550002", onSignal = true, pni = bobAci),
        )
        assertEquals("PNI:$bobAci", SignalClient.resolveDestinationAci("+14155550002"))
        // PNI already prefixed is not double-prefixed.
        db.contactDao().upsert(
            SignalContact(aci = "+14155550003", phoneE164 = "+14155550003", onSignal = true, pni = "PNI:$bobAci"),
        )
        assertEquals("PNI:$bobAci", SignalClient.resolveDestinationAci("+14155550003"))
    }

    @Test
    fun resolveDestinationAci_rejectsNotOnSignalAndUnknownNumbers() = runTest {
        db.contactDao().upsert(
            SignalContact(aci = peer, phoneE164 = "+14155550004", onSignal = false, pni = bobAci),
        )
        assertNull(SignalClient.resolveDestinationAci("+14155550004"))
        db.contactDao().upsert(
            SignalContact(aci = "+14155550005", phoneE164 = "+14155550005", onSignal = true, pni = ""),
        )
        assertNull(SignalClient.resolveDestinationAci("+14155550005"))
        assertNull(SignalClient.resolveDestinationAci("+19999999999"))
    }

    // ---- sendMessage cache / event effects ----

    @Test
    fun sendMessage_blankBodiesReturnNullWithoutEventOrCache() = runTest {
        assertNull(SignalClient.sendMessage(peer, ""))
        assertNull(SignalClient.sendMessage(peer, "   "))
        assertTrue(db.cachedMessageDao().getForConversation(peer).isEmpty())
    }

    @Test
    fun sendMessage_offlineEmitsSendFailedWithIdAndCachesNothing() = runTest {
        val seen = mutableListOf<SignalEvent>()
        val job = launch(Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        val id = SignalClient.sendMessage(peer, "hello offline")
        job.cancel()
        assertNull(id)
        val failed = seen.filterIsInstance<SignalEvent.SendFailed>()
        assertEquals(1, failed.size)
        assertEquals(peer, failed[0].conversationId)
        assertNotNull(failed[0].messageId)
        assertTrue(failed[0].errorMessage.contains("could not send"))
        assertTrue(db.cachedMessageDao().getForConversation(peer).isEmpty())
    }

    @Test
    fun sendMessage_unknownGroupReportsUnknownGroupAndCachesNothing() = runTest {
        val seen = mutableListOf<SignalEvent>()
        val job = launch(Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        val conv = "group:${"ab".repeat(16)}"
        assertNull(SignalClient.sendMessage(conv, "hi group"))
        job.cancel()
        val failed = seen.filterIsInstance<SignalEvent.SendFailed>()
        assertEquals(1, failed.size)
        assertEquals("unknown group", failed[0].errorMessage)
        assertEquals(conv, failed[0].conversationId)
        assertTrue(db.cachedMessageDao().getForConversation(conv).isEmpty())
    }

    @Test
    fun sendMessage_knownGroupWithoutParticipantsFailsAndCachesNothing() = runTest {
        val (masterKey, _) = SignalGroups.generateMasterKeyAndSecretParams()
        val convId = SignalProtocol.toConversationId("", masterKey)
        db.conversationDao().upsert(
            SignalConversation(
                chatId = convId, isGroup = true, name = "Empty",
                participants = "", groupMasterKey = masterKey, groupRevision = 3,
            ),
        )
        val seen = mutableListOf<SignalEvent>()
        val job = launch(Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        assertNull(SignalClient.sendMessage(convId, "anyone there?"))
        job.cancel()
        val failed = seen.filterIsInstance<SignalEvent.SendFailed>()
        assertEquals(1, failed.size)
        assertTrue(failed[0].errorMessage.contains("could not send"))
        assertTrue(db.cachedMessageDao().getForConversation(convId).isEmpty())
        // The stored group row is untouched by the failed send.
        assertEquals(3, db.conversationDao().getConversation(convId)?.groupRevision)
    }

    @Test
    fun sendMessage_knownGroupWithParticipantsButNoStoreFails() = runTest {
        val (masterKey, _) = SignalGroups.generateMasterKeyAndSecretParams()
        val convId = SignalProtocol.toConversationId("", masterKey)
        db.conversationDao().upsert(
            SignalConversation(
                chatId = convId, isGroup = true, participants = peer,
                groupMasterKey = masterKey, groupRevision = 0,
            ),
        )
        assertNull(SignalClient.sendMessage(convId, "fan out"))
        assertTrue(db.cachedMessageDao().getForConversation(convId).isEmpty())
    }

    // ---- pre-key preparation guard ----

    @Test
    fun ensureLocalPreKeys_withoutStoreResetsGuardForRetry() = runTest {
        SignalClient.clientEnsureLocalPreKeys()
        assertFalse(SignalClient.preKeysPrepared.get())
        // A second call is not swallowed by the guard: it retries and resets again.
        SignalClient.clientEnsureLocalPreKeys()
        assertFalse(SignalClient.preKeysPrepared.get())
    }

    // ---- putMessages transport fallback ----

    @Test
    fun putMessages_withoutSocketFallsBackToRestAndFails() = runTest {
        val body = SignalPayload.buildPutMessagesBody(
            peer,
            listOf(SignalPayload.OutgoingPushMessage(1, 1, 1234, byteArrayOf(1, 2, 3))),
            timestamp = 42L,
        )
        val outcome = SignalClient.putMessages(peer, body, accessKey = null)
        assertTrue(outcome is SignalClient.SendOutcome.Failed, "got $outcome")
    }

    @Test
    fun putMessages_withAccessKeyStillFailsClosedOffline() = runTest {
        val body = SignalPayload.buildPutMessagesBody(
            peer,
            listOf(SignalPayload.OutgoingPushMessage(1, 1, 1234, byteArrayOf(4, 5))),
            timestamp = 43L,
        )
        // Sealed path is tried first (no unauth socket), then identified, then REST.
        val outcome = SignalClient.putMessages(peer, body, accessKey = ByteArray(16) { 1 })
        assertTrue(outcome is SignalClient.SendOutcome.Failed, "got $outcome")
    }

    // ---- real E2E: encryption runs, delivery fails, nothing cached ----

    private fun aliceAndBob(): Pair<SignalE2E, SignalE2E> {
        val authA = SignalRegistrationKeys.generate("+14155552671").authScaffold.copy(aci = selfAci)
        val authB = SignalRegistrationKeys.generate("+14155550000").authScaffold.copy(aci = bobAci)
        val alice = SignalE2E(db, authA)
        val bob = SignalE2E(dbBob, authB)
        // Alice consumes Bob's real bundle, so she holds a genuine session.
        val upload = bob.ensureLocalPreKeys()
        val signed = upload.signedPreKey ?: error("no signed")
        val kyber = upload.lastResortKyber ?: error("no kyber")
        alice.processPreKeyBundle(
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
                kyberPreKeySignature = kyber.signature ?: ByteArray(0),
            ),
        )
        SignalClient.authData = authA
        SignalClient.e2e = alice
        return alice to bob
    }

    @Test
    fun sendContent_withRealSessionEncryptsButDeliveryFailsOffline() = runTest {
        val (alice, _) = aliceAndBob()
        assertTrue(alice.hasSession(bobAci, 1))
        // Encryption for Bob's device succeeds; PUT has no socket and the
        // REST fallback cannot reach a server in the test environment.
        assertFalse(SignalClient.sendContent(bobAci, content("real crypto, no server", ts = 777L)))
        assertTrue(db.cachedMessageDao().getForConversation(bobAci).isEmpty())
    }

    @Test
    fun sendMessage_withRealSessionStillCachesNothingOnDeliveryFailure() = runTest {
        aliceAndBob()
        val seen = mutableListOf<SignalEvent>()
        val job = launch(Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        val id = SignalClient.sendMessage(bobAci, "encrypted but undeliverable")
        job.cancel()
        assertNull(id)
        assertEquals(1, seen.filterIsInstance<SignalEvent.SendFailed>().size)
        assertTrue(seen.filterIsInstance<SignalEvent.MessageUpdate>().isEmpty())
        assertTrue(db.cachedMessageDao().getForConversation(bobAci).isEmpty())
    }

    @Test
    fun establishSession_unknownPeerReturnsFalse() = runTest {
        val (alice, _) = aliceAndBob()
        // No session exists for `peer`; fetching its bundle offline fails.
        assertFalse(SignalClient.establishSession(alice, peer))
        assertFalse(alice.hasSession(peer, 1))
    }
}
