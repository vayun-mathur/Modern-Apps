package com.vayunmathur.communicate.whatsapp

import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.communicate.data.whatsapp.MessageSource
import com.vayunmathur.communicate.data.whatsapp.WhatsAppCachedMessage
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDatabase
import com.vayunmathur.communicate.data.whatsapp.WhatsAppEvent
import com.vayunmathur.communicate.data.whatsapp.WhatsAppEventProcessor
import com.vayunmathur.communicate.data.whatsapp.WhatsAppHistoryConversation
import com.vayunmathur.communicate.data.whatsapp.WhatsAppHistoryMessage
import com.vayunmathur.communicate.data.whatsapp.WhatsAppServiceData
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 1 (w5): WhatsAppEventProcessor — event drain into Room, driven through the
 * public start(events) flow with an in-memory database. Asserts persisted rows,
 * unread counting (no double-count on redelivery), edits/revokes, reaction
 * summaries, poll-vote tallies, conversation metadata, history sync, receipts,
 * deletion, and that non-persisted events are ignored.
 */
@RunWith(AndroidJUnit4::class)
class W5Slice1EventProcessorTest {

    private lateinit var db: WhatsAppDatabase
    private lateinit var processor: WhatsAppEventProcessor
    private lateinit var events: MutableSharedFlow<WhatsAppEvent>

    @BeforeTest
    fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WhatsAppDatabase::class.java,
        ).allowMainThreadQueries().build()
        processor = WhatsAppEventProcessor(db)
        events = MutableSharedFlow(extraBufferCapacity = 64)
        processor.start(events)
    }

    @AfterTest
    fun close() {
        processor.stop()
        db.close()
    }

    private companion object {
        const val POLL_TIMEOUT_MS = 15_000L
    }

    private suspend fun emit(e: WhatsAppEvent) {
        events.emit(e)
        // start() collects on Dispatchers.IO (real time), while runTest's delay()
        // is virtual and would spin this poll out in milliseconds on a slow box.
        // Poll in real time instead, and fail with a clear message on timeout
        // rather than a downstream NPE on a row that never landed.
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline && !settled(e)) {
            Thread.sleep(10)
        }
        assertTrue(settled(e), "event $e was not persisted within ${POLL_TIMEOUT_MS}ms")
    }

    /** Poll until [cond] holds in real time (bounded); fails the test on timeout. */
    private suspend fun awaitSettled(cond: suspend () -> Boolean) {
        val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline && !cond()) {
            Thread.sleep(10)
        }
        assertTrue(cond(), "condition was not met within ${POLL_TIMEOUT_MS}ms")
    }

    /** True when the emitted event's effect is observable in the DB. */
    private suspend fun settled(e: WhatsAppEvent): Boolean = when (e) {
        is WhatsAppEvent.IncomingMessage -> db.cachedMessageDao().get(e.messageId) != null
        is WhatsAppEvent.MessageUpdate -> db.cachedMessageDao().get(e.messageId)?.body == e.body
        is WhatsAppEvent.ConversationUpdate ->
            db.conversationDao().getConversation(stripWa(e.conversationId)) != null
        is WhatsAppEvent.HistorySync ->
            e.conversations.all { c -> db.cachedMessageDao().getForConversation(stripWa(c.conversationId)).size == c.messages.size }
        else -> true
    }

    /** Mirrors the processor's chatJid: strips "wa:" and any ":device" suffix. */
    private fun stripWa(id: String): String {
        val s = id.removePrefix("wa:")
        val at = s.indexOf('@')
        if (at <= 0) return s
        return s.substring(0, at).substringBefore(':') + s.substring(at)
    }

    @Test
    fun incomingMessage_persistsAndBumpsUnreadOnce() = runTest {
        val msg = WhatsAppEvent.IncomingMessage(
            conversationId = "wa:1@s.whatsapp.net", messageId = "m1", body = "hello",
            peerName = "Al", peerPhone = "+1555", timestamp = 1000, senderName = "Al",
            senderId = "1@s.whatsapp.net",
        )
        emit(msg)
        val row = db.cachedMessageDao().get("m1")!!
        assertEquals("hello", row.body)
        assertEquals("1@s.whatsapp.net", row.conversationJid)
        assertFalse(row.outgoing)
        assertEquals(1, db.conversationDao().getConversation("1@s.whatsapp.net")!!.unreadCount)

        // redelivery of the same stanza must not double-count unread
        emit(msg.copy(body = "hello again"))
        assertEquals(1, db.conversationDao().getConversation("1@s.whatsapp.net")!!.unreadCount)
    }

    @Test
    fun incomingMessage_serviceDataFallback_serializesExtras() = runTest {
        emit(
            WhatsAppEvent.IncomingMessage(
                conversationId = "wa:1@s.whatsapp.net", messageId = "m9", body = "poll?",
                peerName = null, peerPhone = null, timestamp = 5,
                pollQuestion = "Q?", pollOptions = listOf("a", "b"),
            ),
        )
        val sd = WhatsAppServiceData.parse(db.cachedMessageDao().get("m9")!!.serviceData)!!
        assertEquals("Q?", sd.pollQuestion)
        assertEquals(2, sd.pollOptions.size)
    }

    @Test
    fun messageUpdate_upsertsOutgoing() = runTest {
        emit(
            WhatsAppEvent.MessageUpdate(
                conversationId = "wa:1@s.whatsapp.net", messageId = "u1", body = "sent!",
                outgoing = true, timestamp = 50, senderName = null,
            ),
        )
        val row = db.cachedMessageDao().get("u1")!!
        assertTrue(row.outgoing)
        // update path does not bump unread
        assertEquals(0, db.conversationDao().getConversation("1@s.whatsapp.net")!!.unreadCount)
    }

    @Test
    fun editAndDelete_flipFlags() = runTest {
        emit(
            WhatsAppEvent.IncomingMessage(
                conversationId = "wa:1@s.whatsapp.net", messageId = "e1", body = "v1",
                peerName = null, peerPhone = null, timestamp = 10,
            ),
        )
        emit(WhatsAppEvent.MessageEdited(conversationId = "wa:1@s.whatsapp.net", messageId = "e1", newBody = "v2"))
        awaitSettled {
            WhatsAppServiceData.parse(db.cachedMessageDao().get("e1")?.serviceData)?.isEdited == true
        }
        val edited = db.cachedMessageDao().get("e1")!!
        assertEquals("v2", edited.body)
        assertTrue(WhatsAppServiceData.parse(edited.serviceData)!!.isEdited)

        emit(WhatsAppEvent.MessageDeleted(messageId = "e1", conversationId = "wa:1@s.whatsapp.net"))
        awaitSettled {
            WhatsAppServiceData.parse(db.cachedMessageDao().get("e1")?.serviceData)?.isRevoked == true
        }
        val revoked = db.cachedMessageDao().get("e1")!!
        assertEquals("", revoked.body)
        assertTrue(WhatsAppServiceData.parse(revoked.serviceData)!!.isRevoked)
    }

    @Test
    fun reactions_buildSummaryAndRemove() = runTest {
        emit(
            WhatsAppEvent.IncomingMessage(
                conversationId = "wa:1@s.whatsapp.net", messageId = "r1", body = "hi",
                peerName = null, peerPhone = null, timestamp = 10,
            ),
        )
        emit(WhatsAppEvent.ReactionReceived(conversationId = "wa:1@s.whatsapp.net", messageId = "r1", senderId = "a", emoji = "👍"))
        emit(WhatsAppEvent.ReactionReceived(conversationId = "wa:1@s.whatsapp.net", messageId = "r1", senderId = "b", emoji = "👍"))
        awaitSettled {
            WhatsAppServiceData.parse(db.cachedMessageDao().get("r1")?.serviceData)?.reactions
                ?.firstOrNull()?.count == 2
        }
        val withBoth = WhatsAppServiceData.parse(db.cachedMessageDao().get("r1")!!.serviceData)!!
        assertEquals(2, withBoth.reactions.first { it.emoji == "👍" }.count)

        emit(WhatsAppEvent.ReactionRemoved(conversationId = "wa:1@s.whatsapp.net", messageId = "r1", senderId = "a"))
        awaitSettled {
            WhatsAppServiceData.parse(db.cachedMessageDao().get("r1")?.serviceData)?.reactions
                ?.firstOrNull()?.count == 1
        }
        assertEquals(1, WhatsAppServiceData.parse(db.cachedMessageDao().get("r1")!!.serviceData)!!.reactions.first().count)
    }

    @Test
    fun pollVote_talliesOncePerVoter() = runTest {
        emit(
            WhatsAppEvent.IncomingMessage(
                conversationId = "wa:1@g.us", messageId = "p1", body = "poll",
                peerName = null, peerPhone = null, timestamp = 10,
                pollQuestion = "Q?", pollOptions = listOf("x", "y"),
            ),
        )
        emit(WhatsAppEvent.PollVote(conversationId = "wa:1@g.us", pollMessageId = "p1", voterId = "v1", optionNames = listOf("x")))
        emit(WhatsAppEvent.PollVote(conversationId = "wa:1@g.us", pollMessageId = "p1", voterId = "v1", optionNames = listOf("x"))) // duplicate: no double count
        emit(WhatsAppEvent.PollVote(conversationId = "wa:1@g.us", pollMessageId = "p1", voterId = "v2", optionNames = listOf("y")))
        awaitSettled {
            WhatsAppServiceData.parse(db.cachedMessageDao().get("p1")?.serviceData)?.pollOptions
                ?.sumOf { it.voteCount } == 2
        }
        val opts = WhatsAppServiceData.parse(db.cachedMessageDao().get("p1")!!.serviceData)!!.pollOptions
        assertEquals(1, opts.first { it.name == "x" }.voteCount)
        assertEquals(listOf("v1"), opts.first { it.name == "x" }.voters)
        assertEquals(1, opts.first { it.name == "y" }.voteCount)
    }

    @Test
    fun conversationUpdate_setsGroupMetadata() = runTest {
        emit(
            WhatsAppEvent.ConversationUpdate(
                conversationId = "wa:9@g.us", peerName = "Squad", peerPhone = null,
                avatarUrl = null, lastPreview = null, lastTimestamp = 77, unreadCount = 0,
                isGroup = true,
            ),
        )
        val conv = db.conversationDao().getConversation("9@g.us")!!
        assertTrue(conv.isGroup)
        assertEquals("Squad", conv.name)
        // bare touch must not clobber the name
        emit(
            WhatsAppEvent.ConversationUpdate(
                conversationId = "wa:9@g.us", peerName = "", peerPhone = null,
                avatarUrl = null, lastPreview = null, lastTimestamp = 78, unreadCount = 0,
            ),
        )
        awaitSettled { db.conversationDao().getConversation("9@g.us")?.lastMessageTimestamp == 78L }
        assertEquals("Squad", db.conversationDao().getConversation("9@g.us")!!.name)
    }

    @Test
    fun readReceipt_advancesTicks() = runTest {
        db.cachedMessageDao().upsert(WhatsAppCachedMessage("t1", "c1", "b", 1, true))
        emit(WhatsAppEvent.ReadReceipt(conversationId = "wa:c1", messageId = "t1", isDelivery = true))
        awaitSettled { db.cachedMessageDao().get("t1")?.status == 2 }
        emit(WhatsAppEvent.ReadReceipt(conversationId = "wa:c1", messageId = "t1", isDelivery = false))
        awaitSettled { db.cachedMessageDao().get("t1")?.status == 3 }
        assertEquals(3, db.cachedMessageDao().get("t1")!!.status)
        // null message id is a no-op (must not crash)
        emit(WhatsAppEvent.ReadReceipt(conversationId = "wa:c1", messageId = null))
    }

    @Test
    fun historySync_batchesMessagesAndTouchesConversations() = runTest {
        emit(
            WhatsAppEvent.HistorySync(
                source = MessageSource.WHATSAPP,
                conversations = listOf(
                    WhatsAppHistoryConversation(
                        "wa:7@s.whatsapp.net", "Bob", false,
                        listOf(
                            WhatsAppHistoryMessage("h1", "one", 100, false, "7@s.whatsapp.net", "Bob"),
                            WhatsAppHistoryMessage("h2", "two", 200, true, null, null),
                        ),
                    ),
                ),
            ),
        )
        assertEquals(2, db.cachedMessageDao().getForConversation("7@s.whatsapp.net").size)
        assertEquals(200L, db.conversationDao().getConversation("7@s.whatsapp.net")!!.lastMessageTimestamp)
    }

    @Test
    fun conversationDeleted_clearsMessagesAndThread() = runTest {
        emit(
            WhatsAppEvent.IncomingMessage(
                conversationId = "wa:1@s.whatsapp.net", messageId = "d1", body = "bye",
                peerName = null, peerPhone = null, timestamp = 10,
            ),
        )
        emit(WhatsAppEvent.ConversationDeleted(source = MessageSource.WHATSAPP, conversationId = "wa:1@s.whatsapp.net"))
        awaitSettled { db.conversationDao().getConversation("1@s.whatsapp.net") == null }
        assertEquals(emptyList(), db.cachedMessageDao().getForConversation("1@s.whatsapp.net"))
    }

    @Test
    fun ignoredEvents_doNotCrash() = runTest {
        emit(WhatsAppEvent.StateChanged(source = MessageSource.WHATSAPP, state = com.vayunmathur.communicate.data.whatsapp.WhatsAppState.Ready))
        emit(WhatsAppEvent.TypingIndicator(source = MessageSource.WHATSAPP, conversationId = "wa:1@s.whatsapp.net", senderId = "a", isTyping = true))
        emit(WhatsAppEvent.CallOffer(source = MessageSource.WHATSAPP, callId = "c1", from = "a@s.whatsapp.net", callCreator = "a@s.whatsapp.net", isVideo = false))
        assertNull(db.cachedMessageDao().get("nope"))
    }
}
