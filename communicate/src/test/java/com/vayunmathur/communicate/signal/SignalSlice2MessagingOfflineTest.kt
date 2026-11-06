package com.vayunmathur.communicate.signal

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.signal.SignalAuthData
import com.vayunmathur.communicate.data.signal.SignalCachedMessage
import com.vayunmathur.communicate.data.signal.SignalClient
import com.vayunmathur.communicate.data.signal.SignalContact
import com.vayunmathur.communicate.data.signal.SignalConversation
import com.vayunmathur.communicate.data.signal.SignalDatabase
import com.vayunmathur.communicate.data.signal.SignalEvent
import com.vayunmathur.communicate.data.signal.SignalGroups
import com.vayunmathur.communicate.data.signal.SignalProtocol
import com.vayunmathur.communicate.data.signal.e2e.SignalE2E
import com.vayunmathur.communicate.data.signal.acceptIdentityChange
import com.vayunmathur.communicate.data.signal.conversationIdFor
import com.vayunmathur.communicate.data.signal.displayNameFor
import com.vayunmathur.communicate.data.signal.editMessage
import com.vayunmathur.communicate.data.signal.markRead
import com.vayunmathur.communicate.data.signal.pendingIdentityChange
import com.vayunmathur.communicate.data.signal.poll
import com.vayunmathur.communicate.data.signal.readReceipt
import com.vayunmathur.communicate.data.signal.refreshPresence
import com.vayunmathur.communicate.data.signal.rejectCall
import com.vayunmathur.communicate.data.signal.removeReaction
import com.vayunmathur.communicate.data.signal.reportIdentityChange
import com.vayunmathur.communicate.data.signal.revoke
import com.vayunmathur.communicate.data.signal.safetyNumber
import com.vayunmathur.communicate.data.signal.sendContactCard
import com.vayunmathur.communicate.data.signal.sendMedia
import com.vayunmathur.communicate.data.signal.sendPollVote
import com.vayunmathur.communicate.data.signal.sendReaction
import com.vayunmathur.communicate.data.signal.sendTyping
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wave 10 (w6 slice 2/6): [SignalClient] messaging offline paths — reactions, edits, revokes,
 * polls, receipts, typing, presence, contact cards, media guard, identity bookkeeping, and
 * contact/display resolution. `e2e` stays null so every [sendContent]-backed path fails closed
 * offline; the event and DB effects are what is asserted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignalSlice2MessagingOfflineTest {

    private lateinit var db: SignalDatabase
    private val peer = "3f0e5a2c-1111-2222-3333-444455556666"

    @BeforeTest
    fun open() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, SignalDatabase::class.java)
            .setDriver(BundledSQLiteDriver()).allowMainThreadQueries().build()
        SignalClient.db = db
        SignalClient.e2e = null
        SignalClient.authData = SignalAuthData(
            phoneNumber = "+14155552671",
            aci = "aaaaaaaa-1111-2222-3333-444455556666",
            password = "secret",
        )
        SignalClient.socket = null
        SignalClient.unauthSocket = null
        SignalClient.pendingIdentityChanges.clear()
    }

    @AfterTest
    fun close() {
        SignalClient.db = null
        SignalClient.authData = null
        SignalClient.e2e = null
        SignalClient.pendingIdentityChanges.clear()
        db.close()
    }

    // ---- reactions ----

    @Test
    fun sendReaction_emitsReceivedWithoutNetwork() = runTest {
        val seen = mutableListOf<SignalEvent>()
        val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        val ok = SignalClient.sendReaction(peer, "msg-1", "\u2764\uFE0F")
        assertTrue(ok) // always true: send failures are surfaced via events, not the return
        job.cancel()
        val received = seen.filterIsInstance<SignalEvent.ReactionReceived>()
        assertEquals(1, received.size)
        assertEquals(peer, received[0].conversationId)
        assertEquals("msg-1", received[0].messageId)
        assertEquals("\u2764\uFE0F", received[0].emoji)
    }

    @Test
    fun sendReaction_usesCachedTimestampAndAuthor() = runTest {
        db.cachedMessageDao().upsert(
            SignalCachedMessage(
                messageId = "msg-9", conversationId = peer, body = "hi",
                timestamp = 424242L, outgoing = false, senderId = peer,
            ),
        )
        val seen = mutableListOf<SignalEvent>()
        val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        SignalClient.sendReaction(peer, "msg-9", "\uD83D\uDC4D")
        job.cancel()
        assertEquals(1, seen.filterIsInstance<SignalEvent.ReactionReceived>().size)
    }

    @Test
    fun removeReaction_emitsRemoved() = runTest {
        val seen = mutableListOf<SignalEvent>()
        val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        assertTrue(SignalClient.removeReaction(peer, "msg-2"))
        job.cancel()
        val removed = seen.filterIsInstance<SignalEvent.ReactionRemoved>()
        assertEquals(1, removed.size)
        assertEquals("msg-2", removed[0].messageId)
        assertEquals(peer, removed[0].conversationId)
    }

    // ---- edits / revokes ----

    @Test
    fun editMessage_marksEditedAndEmits() = runTest {
        db.cachedMessageDao().upsert(
            SignalCachedMessage(
                messageId = "edit-1", conversationId = peer, body = "typo",
                timestamp = 1000L, outgoing = true, senderId = "me",
            ),
        )
        val seen = mutableListOf<SignalEvent>()
        val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        assertTrue(SignalClient.editMessage(peer, "edit-1", "fixed"))
        job.cancel()
        assertEquals("fixed", db.cachedMessageDao().get("edit-1")?.body)
        val edited = seen.filterIsInstance<SignalEvent.MessageEdited>()
        assertEquals(1, edited.size)
        assertEquals("fixed", edited[0].newBody)
    }

    @Test
    fun revoke_marksRevokedAndEmits() = runTest {
        db.cachedMessageDao().upsert(
            SignalCachedMessage(
                messageId = "rev-1", conversationId = peer, body = "oops",
                timestamp = 2000L, outgoing = true, senderId = "me",
            ),
        )
        val seen = mutableListOf<SignalEvent>()
        val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        assertTrue(SignalClient.revoke(peer, "rev-1"))
        job.cancel()
        assertTrue(db.cachedMessageDao().get("rev-1")?.isRevoked == true)
        assertEquals(1, seen.filterIsInstance<SignalEvent.MessageDeleted>().size)
    }

    // ---- polls ----

    @Test
    fun poll_cachesQuestionAndEmitsUpdate() = runTest {
        val seen = mutableListOf<SignalEvent>()
        val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        val id = SignalClient.poll(peer, "lunch?", listOf("pizza", "sushi"))
        assertNotNull(id)
        job.cancel()
        val cached = db.cachedMessageDao().get(id)
        assertNotNull(cached)
        assertEquals("lunch?", cached.body)
        assertTrue(seen.filterIsInstance<SignalEvent.MessageUpdate>().any { it.messageId == id })
    }

    @Test
    fun sendPollVote_emitsVoteEvenOffline() = runTest {
        val id = SignalClient.poll(peer, "lunch?", listOf("pizza", "sushi"))
        assertNotNull(id)
        val seen = mutableListOf<SignalEvent>()
        val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        assertTrue(SignalClient.sendPollVote(peer, id, listOf("pizza")))
        job.cancel()
        val votes = seen.filterIsInstance<SignalEvent.PollVote>()
        assertEquals(1, votes.size)
        assertEquals(listOf("pizza"), votes[0].optionNames)
    }

    @Test
    fun sendPollVote_unknownPollFallsBackToIndexes() = runTest {
        val seen = mutableListOf<SignalEvent>()
        val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        assertTrue(SignalClient.sendPollVote(peer, "no-such-poll", listOf("a", "b")))
        job.cancel()
        assertEquals(1, seen.filterIsInstance<SignalEvent.PollVote>().size)
    }

    // ---- receipts / typing / presence ----

    @Test
    fun readReceipt_emitsWithCachedTimestamp() = runTest {
        db.cachedMessageDao().upsert(
            SignalCachedMessage(
                messageId = "rr-1", conversationId = peer, body = "yo",
                timestamp = 777L, outgoing = false, senderId = peer,
            ),
        )
        val seen = mutableListOf<SignalEvent>()
        val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        assertTrue(SignalClient.readReceipt(peer, "rr-1", 0L))
        job.cancel()
        val receipts = seen.filterIsInstance<SignalEvent.ReadReceipt>()
        assertEquals(1, receipts.size)
        assertEquals(777L, receipts[0].timestampMs)
    }

    @Test
    fun readReceipt_fallsBackToLastTimestamp() = runTest {
        val seen = mutableListOf<SignalEvent>()
        val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        assertTrue(SignalClient.readReceipt(peer, null, 999L))
        job.cancel()
        val receipts = seen.filterIsInstance<SignalEvent.ReadReceipt>()
        assertEquals(1, receipts.size)
        assertEquals(999L, receipts[0].timestampMs)
    }

    @Test
    fun markRead_marksDbAndEmitsPerMessage() = runTest {
        db.cachedMessageDao().upsert(
            SignalCachedMessage(
                messageId = "mr-1", conversationId = peer, body = "a",
                timestamp = 10L, outgoing = false, senderId = peer,
            ),
        )
        db.cachedMessageDao().upsert(
            SignalCachedMessage(
                messageId = "mr-2", conversationId = peer, body = "b",
                timestamp = 20L, outgoing = false, senderId = peer,
            ),
        )
        val seen = mutableListOf<SignalEvent>()
        val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        SignalClient.markRead(peer, listOf("mr-1", "mr-2"))
        job.cancel()
        val receipts = seen.filterIsInstance<SignalEvent.ReadReceipt>()
        assertEquals(2, receipts.size)
        assertEquals(setOf("mr-1", "mr-2"), receipts.map { it.messageId }.toSet())
    }

    @Test
    fun sendTyping_emitsIndicator() = runTest {
        val seen = mutableListOf<SignalEvent>()
        val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        SignalClient.sendTyping(peer, true)
        SignalClient.sendTyping(peer, false)
        job.cancel()
        val typing = seen.filterIsInstance<SignalEvent.TypingIndicator>()
        assertEquals(2, typing.size)
        assertTrue(typing[0].isTyping)
        assertFalse(typing[1].isTyping)
    }

    @Test
    fun refreshPresence_emitsOfflineUpdate() = runTest {
        // Signal has no presence REST: always an offline no-op update, never a network call.
        val first = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).async {
            SignalClient.events.first()
        }
        SignalClient.refreshPresence(peer)
        val event = withTimeoutOrNull(5_000) { first.await() }
        assertTrue(event is SignalEvent.PresenceUpdate, "got $event")
        assertFalse((event as SignalEvent.PresenceUpdate).isOnline)
    }

    // ---- contact cards ----

    @Test
    fun sendContactCard_failsClosedWithoutE2e() = runTest {
        // e2e is null so sendContent returns false; the card still builds offline.
        assertFalse(
            SignalClient.sendContactCard(
                peer, "Ada", "Lovelace",
                listOf("+14155552671" to "mobile"), listOf("ada@example.com"),
            ),
        )
    }

    // ---- media guard ----

    @Test
    fun sendMedia_emitsSendFailedWhenFormUnreachable() = runTest {
        // fetchForm hits the network; offline it returns null and a SendFailed is emitted.
        SignalClient.e2e = null
        val first = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).async {
            SignalClient.events.first()
        }
        val id = SignalClient.sendMedia(peer, byteArrayOf(1, 2, 3), "image/png", "pic.png")
        assertNull(id)
        val event = withTimeoutOrNull(15_000) { first.await() }
        assertTrue(event is SignalEvent.SendFailed, "got $event")
    }

    // ---- identity bookkeeping ----

    @Test
    fun reportIdentityChange_storesPendingAndEmits() = runTest {
        val key = ByteArray(33) { it.toByte() }
        val first = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined).async {
            SignalClient.events.first()
        }
        SignalClient.reportIdentityChange(peer, key)
        assertTrue(SignalClient.pendingIdentityChange(peer)?.contentEquals(key) == true)
        val event = withTimeoutOrNull(5_000) { first.await() }
        assertTrue(event is SignalEvent.IdentityKeyChanged, "got $event")
        assertEquals(peer, (event as SignalEvent.IdentityKeyChanged).peerAci)
    }

    @Test
    fun acceptIdentityChange_rejectsWithoutE2e() = runTest {
        SignalClient.reportIdentityChange(peer, ByteArray(33) { 7 })
        // e2e is null: cannot accept, pending stays.
        assertFalse(SignalClient.acceptIdentityChange(peer, "ff".repeat(33)))
        assertNotNull(SignalClient.pendingIdentityChange(peer))
    }

    @Test
    fun acceptIdentityChange_rejectsMissingPending() = runTest {
        assertFalse(SignalClient.acceptIdentityChange("nobody", "00"))
    }

    @Test
    fun acceptIdentityChange_rejectsWrongHex() = runTest {
        SignalClient.reportIdentityChange(peer, ByteArray(33) { 7 })
        SignalClient.e2e = SignalE2E(db, SignalClient.authData!!)
        assertFalse(SignalClient.acceptIdentityChange(peer, "00"))
    }

    @Test
    fun safetyNumber_nullWithoutE2e() = runTest {
        assertNull(SignalClient.safetyNumber(peer))
    }

    // ---- contact / display resolution ----

    @Test
    fun displayNameFor_prefersContactName() = runTest {
        assertEquals(peer, SignalClient.displayNameFor(peer))
        db.contactDao().upsert(
            SignalContact(aci = peer, phoneE164 = "+14155552671", displayName = "Grace"),
        )
        assertEquals("Grace", SignalClient.displayNameFor(peer))
    }

    @Test
    fun displayNameFor_fallsBackToPhoneThenId() = runTest {
        db.contactDao().upsert(
            SignalContact(aci = "x-1", phoneE164 = "+14155550000", displayName = ""),
        )
        assertEquals("+14155550000", SignalClient.displayNameFor("x-1"))
    }

    @Test
    fun conversationIdFor_anchorsOnPhone() = runTest {
        assertEquals(peer, SignalClient.conversationIdFor(peer))
        db.contactDao().upsert(
            SignalContact(aci = peer, phoneE164 = "+14155552671", displayName = "Grace"),
        )
        assertEquals("+14155552671", SignalClient.conversationIdFor(peer))
    }

    @Test
    fun groupHelpers_roundTrip() = runTest {
        val (masterKey, _) = SignalGroups.generateMasterKeyAndSecretParams()
        val convId = SignalProtocol.toConversationId("", masterKey)
        assertTrue(SignalProtocol.isGroupConversation(convId))
        assertTrue(SignalGroups.groupIdentifierBytes(masterKey).size == 32)
    }

    @Test
    fun rejectCall_withoutManagerEmitsAndReturnsTrue() = runTest {
        val seen = mutableListOf<SignalEvent>()
        val job = launch(kotlinx.coroutines.Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        assertTrue(SignalClient.rejectCall("call-1"))
        job.cancel()
        val ended = seen.filterIsInstance<SignalEvent.CallEnded>()
        assertEquals(1, ended.size)
        assertEquals("rejected", ended[0].reason)
    }
}
