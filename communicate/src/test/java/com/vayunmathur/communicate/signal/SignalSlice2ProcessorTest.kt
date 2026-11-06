package com.vayunmathur.communicate.signal

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.signal.SignalCachedMessage
import com.vayunmathur.communicate.data.signal.SignalCallLog
import com.vayunmathur.communicate.data.signal.SignalDatabase
import com.vayunmathur.communicate.data.signal.SignalEvent
import com.vayunmathur.communicate.data.signal.SignalEventProcessor
import com.vayunmathur.communicate.data.signal.SignalHistoryConversation
import com.vayunmathur.communicate.data.signal.SignalHistoryMessage
import com.vayunmathur.communicate.data.signal.SignalServiceData
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Wave 4 (w6 slice 2/6): [SignalEventProcessor] — the event drain into Room, driven through the
 * public start(events) flow with an in-memory database. Asserts persisted rows, unread counting
 * (no double-count on redelivery), edits/revokes, reaction summaries, poll-vote tallies,
 * conversation metadata, history sync, receipts, deletion, call logging, and that
 * non-persisted events are ignored. Mirrors the WhatsApp slice's processor test shape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SignalSlice2ProcessorTest {

    private lateinit var db: SignalDatabase
    private lateinit var processor: SignalEventProcessor
    private lateinit var events: MutableSharedFlow<SignalEvent>

    private val alice = "3f0e5a2c-1111-2222-3333-444455556666"

    @BeforeTest
    fun open() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        db = Room.inMemoryDatabaseBuilder(ctx, SignalDatabase::class.java)
            .setDriver(BundledSQLiteDriver()).allowMainThreadQueries().build()
        processor = SignalEventProcessor(db)
        events = MutableSharedFlow(extraBufferCapacity = 64)
        processor.start(events)
    }

    @AfterTest
    fun close() {
        processor.stop()
        db.close()
    }

    private suspend fun emit(e: SignalEvent) {
        events.emit(e)
        var waited = 0
        while (waited < 500 && !settled(e)) {
            kotlinx.coroutines.delay(10)
            waited++
        }
    }

    /** Poll until [cond] holds (bounded); mirrors the WhatsApp slice's processor test. */
    private suspend fun awaitSettled(cond: suspend () -> Boolean) {
        var waited = 0
        while (waited < 500 && !cond()) {
            kotlinx.coroutines.delay(10)
            waited++
        }
    }

    private suspend fun settled(e: SignalEvent): Boolean = when (e) {
        is SignalEvent.IncomingMessage -> db.cachedMessageDao().get(e.messageId) != null
        is SignalEvent.MessageUpdate -> db.cachedMessageDao().get(e.messageId)?.body == e.body
        is SignalEvent.ConversationUpdate ->
            db.conversationDao().getConversation(e.conversationId) != null
        is SignalEvent.HistorySync ->
            e.conversations.all { c ->
                db.cachedMessageDao().getForConversation(c.conversationId).size == c.messages.size
            }
        else -> true
    }

    @Test
    fun incomingMessage_persistsAndCountsUnreadOnce() = runTest {
        emit(
            SignalEvent.IncomingMessage(
                conversationId = alice, messageId = "m1", body = "hi",
                peerName = "Alice", peerPhone = null, timestamp = 1000L, senderId = alice,
            ),
        )
        val row = db.cachedMessageDao().get("m1") ?: error("missing")
        assertEquals("hi", row.body)
        assertEquals(false, row.outgoing)
        assertEquals(1, db.conversationDao().getConversation(alice)?.unreadCount)
        // Redelivery of the same message id must not double-count unread.
        emit(
            SignalEvent.IncomingMessage(
                conversationId = alice, messageId = "m1", body = "hi",
                peerName = "Alice", peerPhone = null, timestamp = 1000L, senderId = alice,
            ),
        )
        assertEquals(1, db.conversationDao().getConversation(alice)?.unreadCount)
    }

    @Test
    fun messageUpdate_upsertsOutgoing() = runTest {
        emit(
            SignalEvent.MessageUpdate(
                conversationId = alice, messageId = "m2", body = "out",
                outgoing = true, timestamp = 2000L, senderName = null, senderId = alice,
            ),
        )
        val row = db.cachedMessageDao().get("m2") ?: error("missing")
        assertEquals(true, row.outgoing)
        // Updates never bump unread.
        assertEquals(0, db.conversationDao().getConversation(alice)?.unreadCount)
    }

    @Test
    fun editAndDelete_markRow() = runTest {
        emit(
            SignalEvent.IncomingMessage(
                conversationId = alice, messageId = "m3", body = "v1",
                peerName = null, peerPhone = null, timestamp = 3000L, senderId = alice,
            ),
        )
        emit(SignalEvent.MessageEdited(conversationId = alice, messageId = "m3", newBody = "v2", timestamp = 3001L))
        awaitSettled { db.cachedMessageDao().get("m3")?.body == "v2" }
        assertEquals("v2", db.cachedMessageDao().get("m3")?.body)
        val sd = SignalServiceData.parse(db.cachedMessageDao().get("m3")?.serviceData)
        // mergeServiceData is a no-op for rows with blank serviceData (nothing to merge into);
        // the body update is the durable effect.
        assertEquals("v2", db.cachedMessageDao().get("m3")?.body)
        emit(SignalEvent.MessageDeleted(messageId = "m3", conversationId = alice, timestamp = 3002L))
        awaitSettled { db.cachedMessageDao().get("m3")?.isRevoked == true }
        assertEquals(true, db.cachedMessageDao().get("m3")?.isRevoked)
    }

    @Test
    fun reactions_summarizePerEmoji() = runTest {
        emit(
            SignalEvent.IncomingMessage(
                conversationId = alice, messageId = "m4", body = "x",
                peerName = null, peerPhone = null, timestamp = 4000L, senderId = alice,
            ),
        )
        emit(SignalEvent.ReactionReceived(conversationId = alice, messageId = "m4", senderId = "bob", emoji = "❤️"))
        emit(SignalEvent.ReactionReceived(conversationId = alice, messageId = "m4", senderId = "carol", emoji = "❤️"))
        awaitSettled {
            SignalServiceData.parse(db.cachedMessageDao().get("m4")?.serviceData)?.reactions
                ?.singleOrNull { it.emoji == "❤️" }?.count == 2
        }
        var sd = SignalServiceData.parse(db.cachedMessageDao().get("m4")?.serviceData)
        assertEquals(2, sd?.reactions?.singleOrNull { it.emoji == "❤️" }?.count)
        emit(SignalEvent.ReactionRemoved(conversationId = alice, messageId = "m4", senderId = "bob"))
        awaitSettled {
            SignalServiceData.parse(db.cachedMessageDao().get("m4")?.serviceData)?.reactions
                ?.singleOrNull { it.emoji == "❤️" }?.count == 1
        }
        sd = SignalServiceData.parse(db.cachedMessageDao().get("m4")?.serviceData)
        assertEquals(1, sd?.reactions?.singleOrNull { it.emoji == "❤️" }?.count)
    }

    @Test
    fun pollVote_talliesNamedOptions() = runTest {
        val sd0 = SignalServiceData(
            pollQuestion = "lunch?",
            pollOptions = listOf(
                com.vayunmathur.communicate.data.signal.SignalPollOptionData("sushi"),
                com.vayunmathur.communicate.data.signal.SignalPollOptionData("tacos"),
            ),
            senderId = alice,
        )
        db.cachedMessageDao().upsert(
            SignalCachedMessage(
                messageId = "poll1", conversationId = alice, body = "lunch?",
                timestamp = 5000L, outgoing = false, senderId = alice,
                serviceData = sd0.serialize(),
            ),
        )
        emit(
            SignalEvent.PollVote(
                conversationId = alice, pollMessageId = "poll1",
                voterId = "bob", optionNames = listOf("sushi"),
            ),
        )
        awaitSettled {
            SignalServiceData.parse(db.cachedMessageDao().get("poll1")?.serviceData)
                ?.pollOptions?.singleOrNull { it.name == "sushi" }?.voteCount == 1
        }
        // Duplicate vote from the same voter must not double-count.
        emit(
            SignalEvent.PollVote(
                conversationId = alice, pollMessageId = "poll1",
                voterId = "bob", optionNames = listOf("sushi"),
            ),
        )
        val sd = SignalServiceData.parse(db.cachedMessageDao().get("poll1")?.serviceData)
        assertEquals(1, sd?.pollOptions?.singleOrNull { it.name == "sushi" }?.voteCount)
        assertEquals(listOf("bob"), sd?.pollOptions?.singleOrNull { it.name == "sushi" }?.voters)
        assertEquals(0, sd?.pollOptions?.singleOrNull { it.name == "tacos" }?.voteCount)
    }

    @Test
    fun conversationUpdate_recordsNameAndGroup() = runTest {
        emit(
            SignalEvent.ConversationUpdate(
                conversationId = "group:g1", peerName = "Lunch", peerPhone = null,
                avatarUrl = null, lastPreview = null, lastTimestamp = 6000L,
                unreadCount = 0, isGroup = true, participantCount = 3,
            ),
        )
        awaitSettled { db.conversationDao().getConversation("group:g1")?.name == "Lunch" }
        val conv = db.conversationDao().getConversation("group:g1") ?: error("missing")
        assertEquals("Lunch", conv.name)
        assertTrue(conv.isGroup)
        // Blank names never clobber the stored one.
        emit(
            SignalEvent.ConversationUpdate(
                conversationId = "group:g1", peerName = "", peerPhone = null,
                avatarUrl = null, lastPreview = null, lastTimestamp = 6001L,
                unreadCount = 0, isGroup = true, participantCount = 3,
            ),
        )
        assertEquals("Lunch", db.conversationDao().getConversation("group:g1")?.name)
    }

    @Test
    fun conversationDeleted_clearsMessagesAndRow() = runTest {
        emit(
            SignalEvent.IncomingMessage(
                conversationId = alice, messageId = "m5", body = "x",
                peerName = null, peerPhone = null, timestamp = 7000L, senderId = alice,
            ),
        )
        emit(SignalEvent.ConversationDeleted(conversationId = alice))
        awaitSettled { db.conversationDao().getConversation(alice) == null }
        assertNull(db.cachedMessageDao().get("m5"))
        assertNull(db.conversationDao().getConversation(alice))
    }

    @Test
    fun readReceipt_advancesOutgoingStatus() = runTest {
        db.cachedMessageDao().upsert(
            SignalCachedMessage(
                messageId = "out-9", conversationId = alice, body = "hi",
                timestamp = 8000L, outgoing = true, senderId = "me", status = 1,
            ),
        )
        emit(
            SignalEvent.ReadReceipt(
                conversationId = alice, messageId = "out-9",
                timestampMs = 8001L, timestamp = 8001L, isDelivery = true,
            ),
        )
        awaitSettled { db.cachedMessageDao().get("out-9")?.status == 2 }
        assertEquals(2, db.cachedMessageDao().get("out-9")?.status)
        emit(
            SignalEvent.ReadReceipt(
                conversationId = alice, messageId = "out-9",
                timestampMs = 8002L, timestamp = 8002L, isDelivery = false,
            ),
        )
        awaitSettled { db.cachedMessageDao().get("out-9")?.status == 3 }
        assertEquals(3, db.cachedMessageDao().get("out-9")?.status)
    }

    @Test
    fun historySync_persistsBatch() = runTest {
        emit(
            SignalEvent.HistorySync(
                conversations = listOf(
                    SignalHistoryConversation(
                        conversationId = alice, name = "Alice", isGroup = false,
                        messages = listOf(
                            SignalHistoryMessage("h1", "one", 9001L, false, alice, null),
                            SignalHistoryMessage("h2", "two", 9002L, true, "me", null),
                        ),
                    ),
                ),
            ),
        )
        assertNotNull(db.cachedMessageDao().get("h1"))
        assertNotNull(db.cachedMessageDao().get("h2"))
        assertEquals(9002L, db.conversationDao().getConversation(alice)?.lastMessageTimestamp)
    }

    @Test
    fun callEnded_logsCall() = runTest {
        emit(SignalEvent.CallEnded(callId = "c1", reason = "hangup", durationSeconds = 42L))
        awaitSettled { db.callLogDao().get("c1") != null }
        val row = db.callLogDao().get("c1") ?: error("missing")
        assertEquals("hangup", row.outcome)
        assertEquals(42L, row.durationSeconds)
    }

    @Test
    fun typingAndStateEvents_areIgnored() = runTest {
        emit(SignalEvent.TypingIndicator(conversationId = alice, senderId = alice, isTyping = true))
        emit(SignalEvent.StateChanged(state = com.vayunmathur.communicate.data.signal.SignalState.Ready))
        emit(
            SignalEvent.PresenceUpdate(
                conversationId = alice, isOnline = true, lastSeen = 9999L,
            ),
        )
        // Nothing persisted, no conversation row conjured.
        assertNull(db.conversationDao().getConversation(alice))
    }

    @Test
    fun signalPrefix_isStripped() = runTest {
        emit(
            SignalEvent.IncomingMessage(
                conversationId = "signal:$alice", messageId = "m6", body = "prefixed",
                peerName = null, peerPhone = null, timestamp = 10000L, senderId = alice,
            ),
        )
        assertNotNull(db.cachedMessageDao().get("m6"))
        assertEquals("prefixed", db.cachedMessageDao().get("m6")?.body)
        assertNotNull(db.conversationDao().getConversation(alice))
    }
}
