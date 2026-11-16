package com.vayunmathur.communicate.signal

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.google.protobuf.ByteString
import com.vayunmathur.communicate.data.signal.SignalAuthData
import com.vayunmathur.communicate.data.signal.SignalCachedMessage
import com.vayunmathur.communicate.data.signal.SignalClient
import com.vayunmathur.communicate.data.signal.SignalConversation
import com.vayunmathur.communicate.data.signal.SignalDatabase
import com.vayunmathur.communicate.data.signal.SignalEvent
import com.vayunmathur.communicate.data.signal.SignalGroups
import com.vayunmathur.communicate.data.signal.SignalPollOptionData
import com.vayunmathur.communicate.data.signal.SignalProtocol
import com.vayunmathur.communicate.data.signal.SignalServiceData
import com.vayunmathur.communicate.data.signal.conversationForGroupId
import com.vayunmathur.communicate.data.signal.createGroup
import com.vayunmathur.communicate.data.signal.downloadMedia
import com.vayunmathur.communicate.data.signal.editMessage
import com.vayunmathur.communicate.data.signal.e2e.SignalE2E
import com.vayunmathur.communicate.data.signal.e2e.ensureLocalPreKeys
import com.vayunmathur.communicate.data.signal.groupAuthorization
import com.vayunmathur.communicate.data.signal.handleInboundFrame
import com.vayunmathur.communicate.data.signal.markRead
import com.vayunmathur.communicate.data.signal.poll
import com.vayunmathur.communicate.data.signal.readReceipt
import com.vayunmathur.communicate.data.signal.refreshGroup
import com.vayunmathur.communicate.data.signal.refreshPresence
import com.vayunmathur.communicate.data.signal.registration.SignalRegistrationKeys
import com.vayunmathur.communicate.data.signal.rememberInboundGroup
import com.vayunmathur.communicate.data.signal.removeReaction
import com.vayunmathur.communicate.data.signal.revoke
import com.vayunmathur.communicate.data.signal.sendContactCard
import com.vayunmathur.communicate.data.signal.sendMedia
import com.vayunmathur.communicate.data.signal.sendPollVote
import com.vayunmathur.communicate.data.signal.sendReaction
import com.vayunmathur.communicate.data.signal.sendTyping
import com.vayunmathur.communicate.data.signal.setGroupName
import com.vayunmathur.communicate.data.signal.transport.SignalPayload
import com.vayunmathur.communicate.data.signal.updateGroupParticipants
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.whispersystems.signalservice.internal.push.SignalServiceProtos
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketMessage
import signal.proto.chat_websocket.SignalChatWebsocket.WebSocketRequestMessage

/**
 * Y1: SignalMessaging (reactions / edit / revoke / polls / receipts / typing /
 * media / contact cards) and SignalGroupOps (create / rename / membership /
 * refresh / inbound-group learning), plus the inbound dispatch branches
 * those features mirror. Offline sends fail closed; local DB + event
 * effects are the contract. Inbound cases run a real Alice<->Bob E2E
 * session through [handleInboundFrame].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Y1SignalMessagingGroupTest {

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

    // ---------- reactions ----------

    @Test
    fun reactionOnGroupMessage_emitsWithSelfAsSender() = runTest {
        val (masterKey, _) = SignalGroups.generateMasterKeyAndSecretParams()
        val groupId = SignalProtocol.toConversationId("", masterKey)
        db.conversationDao().upsert(
            SignalConversation(chatId = groupId, isGroup = true, groupMasterKey = masterKey, participants = peer),
        )
        db.cachedMessageDao().upsert(
            SignalCachedMessage(messageId = "g-msg", conversationId = groupId, body = "hi", timestamp = 5000L, outgoing = false, senderId = peer),
        )
        val seen = mutableListOf<SignalEvent>()
        val job = launch(Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        assertTrue(SignalClient.sendReaction(groupId, "g-msg", "🔥"))
        job.cancel()
        val r = seen.filterIsInstance<SignalEvent.ReactionReceived>().single()
        assertEquals(groupId, r.conversationId)
        assertEquals("g-msg", r.messageId)
        assertEquals(selfAci, r.senderId)
        assertEquals("🔥", r.emoji)
    }

    @Test
    fun reactionOnUnknownMessageAndRemoveBothEmit() = runTest {
        val seen = mutableListOf<SignalEvent>()
        val job = launch(Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        assertTrue(SignalClient.sendReaction(peer, "ghost", "👍"))
        assertTrue(SignalClient.removeReaction(peer, "ghost"))
        // Empty emoji is exactly the remove path, even called directly.
        assertTrue(SignalClient.sendReaction(peer, "ghost", ""))
        job.cancel()
        assertEquals(1, seen.filterIsInstance<SignalEvent.ReactionReceived>().size)
        assertEquals(2, seen.filterIsInstance<SignalEvent.ReactionRemoved>().size)
        assertTrue(seen.filterIsInstance<SignalEvent.ReactionRemoved>().all { it.senderId == selfAci })
    }

    @Test
    fun reactionUsesCachedAuthorEvenWhenOutgoing() = runTest {
        db.cachedMessageDao().upsert(
            SignalCachedMessage(messageId = "mine", conversationId = peer, body = "x", timestamp = 1234L, outgoing = true, senderId = selfAci),
        )
        val seen = mutableListOf<SignalEvent>()
        val job = launch(Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        SignalClient.sendReaction(peer, "mine", "😀")
        job.cancel()
        assertEquals(selfAci, seen.filterIsInstance<SignalEvent.ReactionReceived>().single().senderId)
    }

    // ---------- edit / revoke ----------

    @Test
    fun editUnknownMessage_emitsButCreatesNoRow() = runTest {
        val seen = mutableListOf<SignalEvent>()
        val job = launch(Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        assertTrue(SignalClient.editMessage(peer, "no-such", "replacement"))
        job.cancel()
        assertNull(db.cachedMessageDao().get("no-such"))
        val e = seen.filterIsInstance<SignalEvent.MessageEdited>().single()
        assertEquals("replacement", e.newBody)
        assertEquals(peer, e.conversationId)
    }

    @Test
    fun editCachedMessage_updatesBodyAndEditedFlagOnly() = runTest {
        db.cachedMessageDao().upsert(
            SignalCachedMessage(messageId = "e1", conversationId = peer, body = "orig", timestamp = 10L, outgoing = true, senderId = selfAci, status = 1),
        )
        SignalClient.editMessage(peer, "e1", "corrected")
        val row = db.cachedMessageDao().get("e1")!!
        assertEquals("corrected", row.body)
        assertTrue(row.isEdited)
        assertFalse(row.isRevoked)
        assertEquals(10L, row.timestamp)
    }

    @Test
    fun revokeCachedMessage_clearsBodyAndSetsRevoked() = runTest {
        db.cachedMessageDao().upsert(
            SignalCachedMessage(messageId = "r1", conversationId = peer, body = "secret", timestamp = 20L, outgoing = true, senderId = selfAci),
        )
        val seen = mutableListOf<SignalEvent>()
        val job = launch(Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        assertTrue(SignalClient.revoke(peer, "r1"))
        job.cancel()
        val row = db.cachedMessageDao().get("r1")!!
        assertTrue(row.isRevoked)
        assertEquals("", row.body)
        val d = seen.filterIsInstance<SignalEvent.MessageDeleted>().single()
        assertEquals("r1", d.messageId)
        assertEquals(peer, d.conversationId)
    }

    @Test
    fun revokeUnknownMessage_stillEmitsDelete() = runTest {
        val seen = mutableListOf<SignalEvent>()
        val job = launch(Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        assertTrue(SignalClient.revoke(peer, "ghost-del"))
        job.cancel()
        assertEquals("ghost-del", seen.filterIsInstance<SignalEvent.MessageDeleted>().single().messageId)
        assertNull(db.cachedMessageDao().get("ghost-del"))
    }

    // ---------- polls ----------

    @Test
    fun poll_persistsServiceDataAndOutgoingRow() = runTest {
        val id = SignalClient.poll(peer, "Best colour?", listOf("red", "blue", "green"))
        assertNotNull(id)
        val row = db.cachedMessageDao().get(id)!!
        assertEquals("Best colour?", row.body)
        assertTrue(row.outgoing)
        assertEquals(selfAci, row.senderId)
        val sd = SignalServiceData.parse(row.serviceData)!!
        assertEquals("Best colour?", sd.pollQuestion)
        assertEquals(listOf("red", "blue", "green"), sd.pollOptions.map { it.name })
        assertEquals(selfAci, sd.senderId)
    }

    @Test
    fun poll_emptyOptionsStillCreatesPoll() = runTest {
        val id = SignalClient.poll(peer, "Empty?", emptyList())
        assertNotNull(id)
        val sd = SignalServiceData.parse(db.cachedMessageDao().get(id)!!.serviceData)!!
        assertTrue(sd.pollOptions.isEmpty())
    }

    @Test
    fun pollVote_multipleKnownOptionsEmitInGivenOrder() = runTest {
        val id = SignalClient.poll(peer, "Pick", listOf("a", "b", "c"))!!
        val seen = mutableListOf<SignalEvent>()
        val job = launch(Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        assertTrue(SignalClient.sendPollVote(peer, id, listOf("c", "a")))
        job.cancel()
        val v = seen.filterIsInstance<SignalEvent.PollVote>().single()
        assertEquals(listOf("c", "a"), v.optionNames)
        assertEquals(id, v.pollMessageId)
        assertEquals(selfAci, v.voterId)
    }

    @Test
    fun pollVote_unknownOptionNameFallsBackButEmitsGivenNames() = runTest {
        val id = SignalClient.poll(peer, "Pick", listOf("a", "b"))!!
        val seen = mutableListOf<SignalEvent>()
        val job = launch(Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        assertTrue(SignalClient.sendPollVote(peer, id, listOf("not-an-option")))
        job.cancel()
        assertEquals(listOf("not-an-option"), seen.filterIsInstance<SignalEvent.PollVote>().single().optionNames)
    }

    @Test
    fun pollVote_pollWithNonUuidSenderStillEmits() = runTest {
        db.cachedMessageDao().upsert(
            SignalCachedMessage(
                messageId = "p-bad-author", conversationId = peer, body = "Q", timestamp = 88L,
                outgoing = false, senderId = "not-a-uuid",
                serviceData = SignalServiceData(pollQuestion = "Q", pollOptions = listOf(SignalPollOptionData("x"))).serialize(),
            ),
        )
        val seen = mutableListOf<SignalEvent>()
        val job = launch(Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        assertTrue(SignalClient.sendPollVote(peer, "p-bad-author", listOf("x")))
        job.cancel()
        assertEquals(1, seen.filterIsInstance<SignalEvent.PollVote>().size)
    }

    // ---------- receipts / markRead / typing / presence ----------

    @Test
    fun readReceipt_unknownIdUsesSuppliedTimestampAndId() = runTest {
        val seen = mutableListOf<SignalEvent>()
        val job = launch(Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        assertTrue(SignalClient.readReceipt(peer, "missing-id", 31337L))
        job.cancel()
        val r = seen.filterIsInstance<SignalEvent.ReadReceipt>().single()
        assertEquals("missing-id", r.messageId)
        assertEquals(31337L, r.timestampMs)
        assertFalse(r.isDelivery)
    }

    @Test
    fun markRead_advancesStatusAndEmitsPerIdIncludingUnknown() = runTest {
        db.cachedMessageDao().upsert(
            SignalCachedMessage(messageId = "m1", conversationId = peer, body = "a", timestamp = 1L, outgoing = false, senderId = peer, status = 1),
        )
        val seen = mutableListOf<SignalEvent>()
        val job = launch(Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        SignalClient.markRead(peer, listOf("m1", "ghost"))
        job.cancel()
        assertEquals(3, db.cachedMessageDao().get("m1")?.status)
        assertEquals(setOf("m1", "ghost"), seen.filterIsInstance<SignalEvent.ReadReceipt>().map { it.messageId }.toSet())
    }

    @Test
    fun markRead_emptyListEmitsNothing() = runTest {
        val seen = mutableListOf<SignalEvent>()
        val job = launch(Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        SignalClient.markRead(peer, emptyList())
        job.cancel()
        assertTrue(seen.filterIsInstance<SignalEvent.ReadReceipt>().isEmpty())
    }

    @Test
    fun typingInGroupConversation_emitsWithSelfSender() = runTest {
        val (masterKey, _) = SignalGroups.generateMasterKeyAndSecretParams()
        val groupId = SignalProtocol.toConversationId("", masterKey)
        db.conversationDao().upsert(SignalConversation(chatId = groupId, isGroup = true, groupMasterKey = masterKey))
        val seen = mutableListOf<SignalEvent>()
        val job = launch(Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        SignalClient.sendTyping(groupId, true)
        job.cancel()
        val t = seen.filterIsInstance<SignalEvent.TypingIndicator>().single()
        assertEquals(groupId, t.conversationId)
        assertEquals(selfAci, t.senderId)
        assertTrue(t.isTyping)
    }

    @Test
    fun presence_isAlwaysOfflineNoOp() = runTest {
        val first = CoroutineScope(Dispatchers.Unconfined).async { SignalClient.events.first() }
        SignalClient.refreshPresence(peer)
        val e = withTimeoutOrNull(5_000) { first.await() } as? SignalEvent.PresenceUpdate
        assertNotNull(e)
        assertFalse(e.isOnline)
        assertEquals(peer, e.conversationId)
        assertTrue(e.lastSeen > 0L)
    }

    // ---------- media / contact card ----------

    @Test
    fun sendMedia_emptyBytesFailsAtUploadForm() = runTest {
        val first = CoroutineScope(Dispatchers.Unconfined).async { SignalClient.events.first() }
        assertNull(SignalClient.sendMedia(peer, ByteArray(0), "image/png"))
        val e = withTimeoutOrNull(15_000) { first.await() }
        assertTrue(e is SignalEvent.SendFailed && e.conversationId == peer, "got $e")
        assertTrue(db.cachedMessageDao().getForConversation(peer).isEmpty())
    }

    @Test
    fun downloadMedia_unreachableAndMalformedUrlsReturnNull() = runTest {
        assertNull(SignalClient.downloadMedia("not a url", ByteArray(64) { 1 }, "image/png"))
        assertNull(SignalClient.downloadMedia("https://cdn.signal.org/attachments/missing-y1", ByteArray(64) { 1 }, "image/png"))
    }

    @Test
    fun contactCard_nameOnlyBuildsButFailsOffline_nameAndNumbersEmptyRefused() = runTest {
        assertFalse(SignalClient.sendContactCard(peer, "", null, emptyList(), emptyList()))
        assertFalse(SignalClient.sendContactCard(peer, "Ada", null, emptyList(), emptyList()))
        assertFalse(
            SignalClient.sendContactCard(peer, "", null, listOf("+14155550001" to null), emptyList()),
        )
    }

    // ---------- group ops ----------

    @Test
    fun createGroup_persistsRowKeyRevisionAndEmitsConversationUpdate() = runTest {
        val seen = mutableListOf<SignalEvent>()
        val job = launch(Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        val id = SignalClient.createGroup("Y1 Group", listOf(peer, bobAci))
        job.cancel()
        assertNotNull(id)
        assertTrue(id.startsWith("group:"))
        val row = db.conversationDao().getConversation(id)!!
        assertEquals("Y1 Group", row.name)
        assertTrue(row.isGroup)
        assertEquals("$peer,$bobAci", row.participants)
        assertEquals(0, row.groupRevision)
        assertEquals(32, row.groupMasterKey!!.size)
        // The id is derived from that very master key, not from the key itself.
        assertEquals(id, SignalProtocol.toConversationId("", row.groupMasterKey))
        val upd = seen.filterIsInstance<SignalEvent.ConversationUpdate>().single()
        assertEquals(id, upd.conversationId)
        assertEquals("Y1 Group", upd.peerName)
        assertEquals(2, upd.participantCount)
        assertTrue(upd.isGroup)
    }

    @Test
    fun createGroup_emptyContactsAndNoAuthCases() = runTest {
        val id = SignalClient.createGroup("Solo", emptyList())
        assertNotNull(id)
        assertEquals("", db.conversationDao().getConversation(id)?.participants)
        // Without auth the server call cannot be made, but local persistence
        // is skipped too because createGroup returns before upsert? No: auth
        // check is before the emit/upsert only for putNewGroup; with no auth
        // it returns null and creates nothing new.
        val before = db.conversationDao().getRecent(50).size
        SignalClient.authData = null
        assertNull(SignalClient.createGroup("NoAuth", listOf(peer)))
        assertEquals(before, db.conversationDao().getRecent(50).size)
    }

    @Test
    fun createGroup_secondGroupGetsFreshKeyAndId() = runTest {
        val a = SignalClient.createGroup("A", listOf(peer))!!
        val b = SignalClient.createGroup("B", listOf(peer))!!
        assertTrue(a != b)
        val ka = db.conversationDao().getConversation(a)!!.groupMasterKey!!
        val kb = db.conversationDao().getConversation(b)!!.groupMasterKey!!
        assertFalse(ka.contentEquals(kb))
    }

    @Test
    fun setGroupName_keylessAndUnknownGroupsFailWithoutTouchingRow() = runTest {
        db.conversationDao().upsert(SignalConversation(chatId = "group:keyless-y1", isGroup = true, name = "Old"))
        assertFalse(SignalClient.setGroupName("group:keyless-y1", "New"))
        assertEquals("Old", db.conversationDao().getConversation("group:keyless-y1")?.name)
        assertFalse(SignalClient.setGroupName("group:absent-y1", "New"))
    }

    @Test
    fun setGroupName_withKeyButServerUnreachableKeepsOldNameAndRevision() = runTest {
        val (masterKey, _) = SignalGroups.generateMasterKeyAndSecretParams()
        val convId = SignalProtocol.toConversationId("", masterKey)
        db.conversationDao().upsert(
            SignalConversation(chatId = convId, isGroup = true, name = "Before", groupMasterKey = masterKey, groupRevision = 4),
        )
        val seen = mutableListOf<SignalEvent>()
        val job = launch(Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        assertFalse(SignalClient.setGroupName(convId, "After"))
        job.cancel()
        val row = db.conversationDao().getConversation(convId)!!
        assertEquals("Before", row.name)
        assertEquals(4, row.groupRevision)
        assertTrue(seen.filterIsInstance<SignalEvent.ConversationNameChanged>().isEmpty())
    }

    @Test
    fun updateGroupParticipants_removeAndBogusActionsRefusedLocally() = runTest {
        val (masterKey, _) = SignalGroups.generateMasterKeyAndSecretParams()
        val convId = SignalProtocol.toConversationId("", masterKey)
        db.conversationDao().upsert(
            SignalConversation(chatId = convId, isGroup = true, participants = peer, groupMasterKey = masterKey, groupRevision = 1),
        )
        val seen = mutableListOf<SignalEvent>()
        val job = launch(Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        assertFalse(SignalClient.updateGroupParticipants(convId, listOf(bobAci), "remove"))
        assertFalse(SignalClient.updateGroupParticipants(convId, listOf(bobAci), "bogus"))
        assertFalse(SignalClient.updateGroupParticipants("group:absent", listOf(bobAci), "add"))
        job.cancel()
        assertTrue(seen.filterIsInstance<SignalEvent.ParticipantAdded>().isEmpty())
        assertTrue(seen.filterIsInstance<SignalEvent.ParticipantRemoved>().isEmpty())
        assertEquals(peer, db.conversationDao().getConversation(convId)?.participants)
    }

    @Test
    fun updateGroupParticipants_addWithKeyButServerUnreachableEmitsNothing() = runTest {
        val (masterKey, _) = SignalGroups.generateMasterKeyAndSecretParams()
        val convId = SignalProtocol.toConversationId("", masterKey)
        db.conversationDao().upsert(
            SignalConversation(chatId = convId, isGroup = true, participants = peer, groupMasterKey = masterKey, groupRevision = 2),
        )
        val seen = mutableListOf<SignalEvent>()
        val job = launch(Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        assertFalse(SignalClient.updateGroupParticipants(convId, listOf(bobAci), "add"))
        job.cancel()
        assertTrue(seen.filterIsInstance<SignalEvent.ParticipantAdded>().isEmpty())
        // Local membership is not optimistically rewritten on a rejected change.
        assertEquals(peer, db.conversationDao().getConversation(convId)?.participants)
        assertEquals(2, db.conversationDao().getConversation(convId)?.groupRevision)
    }

    @Test
    fun refreshGroup_guardsAndUnreachableServerLeaveRowUntouched() = runTest {
        assertFalse(SignalClient.refreshGroup("group:absent"))
        db.conversationDao().upsert(SignalConversation(chatId = "group:keyless-r", isGroup = true, name = "K"))
        assertFalse(SignalClient.refreshGroup("group:keyless-r"))
        val (masterKey, _) = SignalGroups.generateMasterKeyAndSecretParams()
        val convId = SignalProtocol.toConversationId("", masterKey)
        db.conversationDao().upsert(
            SignalConversation(chatId = convId, isGroup = true, name = "Keep", participants = peer, groupMasterKey = masterKey, groupRevision = 7),
        )
        assertFalse(SignalClient.refreshGroup(convId))
        val row = db.conversationDao().getConversation(convId)!!
        assertEquals("Keep", row.name)
        assertEquals(peer, row.participants)
        assertEquals(7, row.groupRevision)
        SignalClient.authData = null
        assertFalse(SignalClient.refreshGroup(convId))
    }

    @Test
    fun rememberInboundGroup_createsThenMergesMembership() = runTest {
        val (masterKey, _) = SignalGroups.generateMasterKeyAndSecretParams()
        val convId = SignalProtocol.toConversationId("", masterKey)
        SignalClient.rememberInboundGroup(convId, masterKey, bobAci)
        var row = db.conversationDao().getConversation(convId)!!
        assertTrue(row.isGroup)
        assertTrue(row.groupMasterKey!!.contentEquals(masterKey))
        assertEquals(setOf(bobAci, selfAci), row.participants.split(",").toSet())
        // A second sender is merged; duplicates and our own id are not repeated.
        SignalClient.rememberInboundGroup(convId, masterKey, peer)
        row = db.conversationDao().getConversation(convId)!!
        assertEquals(setOf(bobAci, selfAci, peer), row.participants.split(",").toSet())
        SignalClient.rememberInboundGroup(convId, masterKey, peer)
        row = db.conversationDao().getConversation(convId)!!
        assertEquals(3, row.participants.split(",").size)
    }

    @Test
    fun rememberInboundGroup_preservesExistingNameAndReplacesKey() = runTest {
        val (k1, _) = SignalGroups.generateMasterKeyAndSecretParams()
        val (k2, _) = SignalGroups.generateMasterKeyAndSecretParams()
        val convId = SignalProtocol.toConversationId("", k1)
        db.conversationDao().upsert(
            SignalConversation(chatId = convId, isGroup = true, name = "Named", participants = peer, groupMasterKey = k1, groupRevision = 5),
        )
        SignalClient.rememberInboundGroup(convId, k2, bobAci)
        val row = db.conversationDao().getConversation(convId)!!
        assertEquals("Named", row.name)
        assertTrue(row.groupMasterKey!!.contentEquals(k2))
        assertEquals(setOf(peer, bobAci, selfAci), row.participants.split(",").toSet())
    }

    @Test
    fun conversationForGroupId_andGroupAuthorizationOffline() = runTest {
        val (masterKey, _) = SignalGroups.generateMasterKeyAndSecretParams()
        val convId = SignalProtocol.toConversationId("", masterKey)
        db.conversationDao().upsert(
            SignalConversation(chatId = convId, isGroup = true, groupMasterKey = masterKey),
        )
        val groupIdBytes = SignalGroups.groupIdentifierBytes(masterKey)
        assertEquals(convId, SignalClient.conversationForGroupId(groupIdBytes)?.chatId)
        assertNull(SignalClient.conversationForGroupId(ByteArray(32) { 9 }))
        // Credentials cannot be fetched offline, so no authorization is minted.
        assertNull(SignalClient.groupAuthorization(groupIdBytes))
    }

    // ---------- inbound dispatch (real E2E Bob -> Alice) ----------

    private lateinit var alice: SignalE2E
    private lateinit var bob: SignalE2E
    private lateinit var authA: SignalAuthData
    private lateinit var authB: SignalAuthData

    private fun wireE2E() {
        authA = SignalRegistrationKeys.generate("+14155552671").authScaffold.copy(aci = selfAci)
        authB = SignalRegistrationKeys.generate("+14155550000").authScaffold.copy(aci = bobAci)
        alice = SignalE2E(db, authA)
        bob = SignalE2E(dbBob, authB)
        SignalClient.authData = authA
        SignalClient.e2e = alice
    }

    private fun bobBundle(): SignalE2E.ParsedPreKeyBundle {
        val upload = bob.ensureLocalPreKeys()
        val signed = upload.signedPreKey ?: error("no signed")
        val kyber = upload.lastResortKyber ?: error("no kyber")
        return SignalE2E.ParsedPreKeyBundle(
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
        )
    }

    private suspend fun feedFromBob(content: SignalServiceProtos.Content, ts: Long): SignalEvent? {
        if (!alice.hasSession(bobAci, 1)) alice.processPreKeyBundle(bobAci, 1, bobBundle())
        val padded = SignalProtocol.padMessageBody(content.toByteArray())
        val enc = alice.encryptDM(bobAci, 1, padded)
        bob.decryptDM(authA.aci, 1, isPreKey = true, ciphertext = enc.data)
        val back = bob.encryptDM(authA.aci, 1, padded)
        val envelope = SignalServiceProtos.Envelope.newBuilder()
            .setType(SignalServiceProtos.Envelope.Type.DOUBLE_RATCHET)
            .setSourceServiceId(bobAci).setSourceDeviceId(1)
            .setClientTimestamp(ts).setContent(ByteString.copyFrom(back.data)).build()
        val frame = WebSocketMessage.newBuilder().setType(WebSocketMessage.Type.REQUEST)
            .setRequest(
                WebSocketRequestMessage.newBuilder().setVerb("PUT").setPath("/api/v1/message")
                    .setId(77L).setBody(ByteString.copyFrom(envelope.toByteArray())).build(),
            ).build().toByteArray()
        val deferred = CoroutineScope(Dispatchers.Unconfined).async { SignalClient.events.first() }
        SignalClient.handleInboundFrame(frame)
        return withTimeoutOrNull(5_000) { deferred.await() }
    }

    private fun dmContent(dm: SignalServiceProtos.DataMessage): SignalServiceProtos.Content =
        SignalServiceProtos.Content.newBuilder().setDataMessage(dm).build()

    @Test
    fun inboundReactionRemove_resolvesTargetIdByTimestamp() = runTest {
        wireE2E()
        db.cachedMessageDao().upsert(
            SignalCachedMessage(messageId = "target-1", conversationId = bobAci, body = "hi", timestamp = 1_700_000_000_000L, outgoing = true, senderId = selfAci),
        )
        val dm = SignalPayload.buildDataMessage(
            body = "", timestamp = 1_700_000_001_000L,
            reaction = SignalPayload.buildReaction(emoji = "", remove = true, targetSentTimestamp = 1_700_000_000_000L),
        )
        val e = feedFromBob(dmContent(dm), 1_700_000_001_000L)
        assertTrue(e is SignalEvent.ReactionRemoved, "got $e")
        assertEquals("target-1", e.messageId)
        assertEquals(bobAci, e.senderId)
    }

    @Test
    fun inboundDelete_resolvesTargetId() = runTest {
        wireE2E()
        db.cachedMessageDao().upsert(
            SignalCachedMessage(messageId = "victim", conversationId = bobAci, body = "bye", timestamp = 1_700_000_010_000L, outgoing = false, senderId = bobAci),
        )
        val delDm = SignalPayload.buildDataMessage(
            body = "", timestamp = 1_700_000_011_000L,
            delete = SignalPayload.buildDelete(targetSentTimestamp = 1_700_000_010_000L),
        )
        val del = feedFromBob(dmContent(delDm), 1_700_000_011_000L)
        assertTrue(del is SignalEvent.MessageDeleted && del.messageId == "victim", "got $del")
    }

    @Test
    fun inboundEdit_resolvesTargetId() = runTest {
        wireE2E()
        db.cachedMessageDao().upsert(
            SignalCachedMessage(messageId = "victim", conversationId = bobAci, body = "bye", timestamp = 1_700_000_010_000L, outgoing = false, senderId = bobAci),
        )
        val editContent = SignalPayload.buildContentForEdit(
            targetSentTimestamp = 1_700_000_010_000L,
            newDataMessage = SignalPayload.buildDataMessage(body = "edited!", timestamp = 1_700_000_012_000L),
        )
        val edit = feedFromBob(editContent, 1_700_000_012_000L)
        assertTrue(edit is SignalEvent.MessageEdited, "got $edit")
        assertEquals("victim", edit.messageId)
        assertEquals("edited!", edit.newBody)
    }

    @Test
    fun inboundReadReceiptForUnknownOutgoing_usesTimestampAsId() = runTest {
        wireE2E()
        val content = SignalServiceProtos.Content.newBuilder()
            .setReceiptMessage(
                SignalServiceProtos.ReceiptMessage.newBuilder()
                    .setType(SignalServiceProtos.ReceiptMessage.Type.READ)
                    .addTimestamp(1_700_000_020_000L),
            ).build()
        val e = feedFromBob(content, 1_700_000_021_000L)
        assertTrue(e is SignalEvent.ReadReceipt, "got $e")
        assertFalse(e.isDelivery)
        assertEquals("1700000020000", e.messageId)
    }

    @Test
    fun inboundTypingStopped_emitsNotTyping() = runTest {
        wireE2E()
        val content = SignalServiceProtos.Content.newBuilder()
            .setTypingMessage(
                SignalServiceProtos.TypingMessage.newBuilder()
                    .setTimestamp(1_700_000_030_000L)
                    .setAction(SignalServiceProtos.TypingMessage.Action.STOPPED),
            ).build()
        val e = feedFromBob(content, 1_700_000_030_000L)
        assertTrue(e is SignalEvent.TypingIndicator && !e.isTyping, "got $e")
    }

    @Test
    fun inboundSyncViewed_emitsReadReceiptResolvedByTimestamp() = runTest {
        wireE2E()
        db.cachedMessageDao().upsert(
            SignalCachedMessage(messageId = "seen-1", conversationId = bobAci, body = "hi", timestamp = 1_700_000_040_000L, outgoing = false, senderId = bobAci),
        )
        val content = SignalServiceProtos.Content.newBuilder()
            .setSyncMessage(
                SignalServiceProtos.SyncMessage.newBuilder()
                    .addViewed(
                        SignalServiceProtos.SyncMessage.Viewed.newBuilder()
                            .setTimestamp(1_700_000_040_000L).build(),
                    ),
            ).build()
        val e = feedFromBob(content, 1_700_000_041_000L)
        assertTrue(e is SignalEvent.ReadReceipt, "got $e")
        assertEquals("seen-1", e.messageId)
        assertFalse(e.isDelivery)
    }

    @Test
    fun inboundAttachments_videoAndFileTypesAndEmptyKeyDropped() = runTest {
        wireE2E()
        fun pointer(key: String, mime: String) = SignalServiceProtos.AttachmentPointer.newBuilder()
            .setCdnKey(key).setCdnNumber(3).setContentType(mime).setSize(5)
            .setKey(ByteString.copyFrom(ByteArray(64) { 2 })).build()
        val dm = SignalServiceProtos.DataMessage.newBuilder()
            .setBody("media").setTimestamp(1_700_000_050_000L)
            .addAttachments(pointer("vidkey", "video/mp4"))
            .addAttachments(pointer("", "image/png"))
            .addAttachments(pointer("docKey", "application/pdf"))
            .build()
        val e = feedFromBob(dmContent(dm), 1_700_000_050_000L)
        assertTrue(e is SignalEvent.IncomingMessage, "got $e")
        val atts = e.attachments
        assertEquals(2, atts.size)
        assertEquals("video", atts[0].attachmentType)
        assertEquals("https://cdn3.signal.org/attachments/vidkey", atts[0].url)
        assertEquals("file", atts[1].attachmentType)
    }

    @Test
    fun inboundGroupText_createsGroupConversationAndFilesMessageThere() = runTest {
        wireE2E()
        val (masterKey, _) = SignalGroups.generateMasterKeyAndSecretParams()
        val groupConv = SignalProtocol.toConversationId("", masterKey)
        val dm = SignalPayload.buildDataMessage(
            body = "hello group", timestamp = 1_700_000_060_000L,
            groupV2MasterKey = masterKey, groupV2Revision = 2,
        )
        val e = feedFromBob(dmContent(dm), 1_700_000_060_000L)
        assertTrue(e is SignalEvent.IncomingMessage, "got $e")
        assertEquals(groupConv, e.conversationId)
        assertEquals("hello group", e.body)
        val row = db.conversationDao().getConversation(groupConv)
        assertNotNull(row)
        assertTrue(row.isGroup)
        assertTrue(row.participants.split(",").contains(bobAci))
    }
}
