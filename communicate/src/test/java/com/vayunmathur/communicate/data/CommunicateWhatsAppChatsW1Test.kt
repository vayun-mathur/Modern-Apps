package com.vayunmathur.communicate.data

import android.app.Application
import android.util.Base64
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.ByteString
import com.vayunmathur.communicate.data.whatsapp.WhatsAppAuthData
import com.vayunmathur.communicate.data.whatsapp.WhatsAppClient
import com.vayunmathur.communicate.data.whatsapp.WhatsAppConversation
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDatabase
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol
import com.vayunmathur.communicate.data.whatsapp.expandAppStateKeys
import com.vayunmathur.communicate.data.whatsapp.applyAppStateRecord
import com.vayunmathur.communicate.data.whatsapp.deleteChat
import com.vayunmathur.communicate.data.whatsapp.deleteThread
import com.vayunmathur.communicate.data.whatsapp.markChatUnread
import com.vayunmathur.communicate.data.whatsapp.sendNewThread
import com.vayunmathur.communicate.data.whatsapp.setMute
import com.vayunmathur.communicate.data.whatsapp.storeAppStateKey
import com.vayunmathur.communicate.data.whatsapp.togglePin
import com.vayunmathur.communicate.data.whatsapp.transport.WhatsAppSocket
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppAppStateProto

/**
 * W1 wave 7: WhatsApp chat housekeeping (`WhatsAppClientChatsKt`) and
 * app-state action application (`WhatsAppClientSyncKt`), against an
 * in-memory database with a Connected-but-dead socket: the sync patches
 * are attempted, and the local store updates still land.
 */
@RunWith(AndroidJUnit4::class)
class CommunicateWhatsAppChatsW1Test {

    private fun b64(bytes: ByteArray): String = Base64.encodeToString(bytes, Base64.NO_WRAP)

    private val auth = WhatsAppAuthData(
        phoneNumber = "+15550001", pushName = "Tester", wid = "15550001@s.whatsapp.net",
        noisePrivateKey = b64(ByteArray(32) { 1 }), noisePublicKey = b64(ByteArray(32) { 2 }),
        identityPrivateKey = b64(ByteArray(32) { 3 }), identityPublicKey = b64(ByteArray(32) { 4 }),
        registrationId = 14, signedPreKeyId = 5,
        signedPreKeyPublic = b64(ByteArray(32) { 5 }), signedPreKeyPrivate = b64(ByteArray(32) { 6 }),
        signedPreKeySignature = b64(ByteArray(64) { 7 }), lid = "12345@lid",
    )

    private lateinit var db: WhatsAppDatabase
    private val jid = "15550002@s.whatsapp.net"

    @BeforeTest
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, WhatsAppDatabase::class.java)
            .setSingleConnectionPool().allowMainThreadQueries().build()
        WhatsAppClient.appContext = app
        WhatsAppClient.authData = auth
        WhatsAppClient.db = db
        WhatsAppClient.webSocket = WhatsAppSocket(auth)
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Connected
        WhatsAppClient.nameCache.clear()
    }

    @AfterTest
    fun tearDown() {
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Idle
        WhatsAppClient.webSocket = null
        WhatsAppClient.db = null
        WhatsAppClient.authData = null
    }

    private fun encryptedRecord(
        keyId: ByteArray, keyData: ByteArray, plain: ByteArray,
    ): WhatsAppAppStateProto.SyncdRecord {
        val expanded = WhatsAppProtocol.expandAppStateKeys(keyData)
        val iv = ByteArray(16) { it.toByte() }
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(expanded[1], "AES"), IvParameterSpec(iv))
        val blob = iv + cipher.doFinal(plain) + ByteArray(32)
        return WhatsAppAppStateProto.SyncdRecord.newBuilder()
            .setKeyId(WhatsAppAppStateProto.KeyId.newBuilder().setId(ByteString.copyFrom(keyId)))
            .setValue(WhatsAppAppStateProto.SyncdValue.newBuilder().setBlob(ByteString.copyFrom(blob)))
            .build()
    }

    private suspend fun applyAction(index: String, value: WhatsAppAppStateProto.SyncActionValue) {
        val keyId = byteArrayOf(42, 42)
        val keyData = ByteArray(32) { (it * 7 + 1).toByte() }
        WhatsAppClient.storeAppStateKey(keyId, keyData)
        val action = WhatsAppAppStateProto.SyncActionData.newBuilder()
            .setIndex(ByteString.copyFrom(index.toByteArray()))
            .setValue(value)
            .build()
        WhatsAppClient.applyAppStateRecord(encryptedRecord(keyId, keyData, action.toByteArray()), isSet = true)
    }

    @Test
    fun `mute and pin land in the local conversation row`() = runTest {
        db.conversationDao().upsert(WhatsAppConversation(chatJid = jid, lastMessageTimestamp = 99L))
        WhatsAppClient.setMute(jid, muteUntilMs = 777_000L)
        assertEquals(777_000L, db.conversationDao().getConversation(jid)!!.muteEndTime)
        WhatsAppClient.togglePin(jid, pinned = true)
        assertTrue(db.conversationDao().getConversation(jid)!!.pinned)
        WhatsAppClient.togglePin(jid, pinned = false)
        assertTrue(!db.conversationDao().getConversation(jid)!!.pinned)
        WhatsAppClient.markChatUnread(jid, unread = true) // patch attempted; no local flag
        // Unknown conversations are tolerated (nothing to update).
        WhatsAppClient.setMute("1999@s.whatsapp.net", 1L)
        WhatsAppClient.togglePin("1999@s.whatsapp.net", true)
        assertNull(db.conversationDao().getConversation("1999@s.whatsapp.net"))
    }

    @Test
    fun `deleteChat and deleteThread remove the local row`() = runTest {
        db.conversationDao().upsert(WhatsAppConversation(chatJid = jid, lastMessageTimestamp = 99L))
        assertTrue(WhatsAppClient.deleteChat(jid, leaveGroup = false))
        assertNull(db.conversationDao().getConversation(jid))
        db.conversationDao().upsert(WhatsAppConversation(chatJid = "12345@g.us", isGroup = true))
        assertTrue(WhatsAppClient.deleteChat("12345@g.us", leaveGroup = true))
        assertNull(db.conversationDao().getConversation("12345@g.us"))
        db.conversationDao().upsert(WhatsAppConversation(chatJid = jid))
        assertTrue(WhatsAppClient.deleteThread("wa:$jid"))
        assertNull(db.conversationDao().getConversation(jid))
        // The repository delete route uses the same client path.
        db.conversationDao().upsert(WhatsAppConversation(chatJid = jid))
        val thread = SmsThread(
            threadId = 1L, address = "+15550002", displayName = null, snippet = "",
            timestampMillis = 0L, unreadCount = 0, line = CommunicateLine.WhatsApp, remoteId = jid,
        )
        assertTrue(CommunicateRepository.deleteConversation(ApplicationProvider.getApplicationContext(), thread))
        assertNull(db.conversationDao().getConversation(jid))
    }

    @Test
    fun `sendNewThread cannot build without a session`() = runTest {
        assertNull(WhatsAppClient.sendNewThread(jid, "hello"))
        assertNull(WhatsAppClient.sendNewThread("15550003", "hello"))
    }

    @Test
    fun `app state actions update mute pin archive and read flags`() = runTest {
        db.conversationDao().upsert(WhatsAppConversation(chatJid = jid))
        applyAction(
            """["mute","$jid"]""",
            WhatsAppAppStateProto.SyncActionValue.newBuilder()
                .setMuteAction(WhatsAppAppStateProto.MuteAction.newBuilder().setMuteEndTimestamp(4242L))
                .build(),
        )
        assertEquals(4242L, db.conversationDao().getConversation(jid)!!.muteEndTime)
        applyAction(
            """["pin_v1","$jid"]""",
            WhatsAppAppStateProto.SyncActionValue.newBuilder()
                .setPinAction(WhatsAppAppStateProto.PinAction.newBuilder().setPinned(true))
                .build(),
        )
        assertTrue(db.conversationDao().getConversation(jid)!!.pinned)
        applyAction(
            """["archive","$jid"]""",
            WhatsAppAppStateProto.SyncActionValue.newBuilder()
                .setArchiveChatAction(WhatsAppAppStateProto.ArchiveChatAction.newBuilder().setArchived(true))
                .build(),
        )
        assertTrue(db.conversationDao().getConversation(jid)!!.archived)
        applyAction(
            """["markChatAsRead","$jid"]""",
            WhatsAppAppStateProto.SyncActionValue.newBuilder()
                .setMarkChatAsReadAction(WhatsAppAppStateProto.MarkChatAsReadAction.newBuilder().setRead(false))
                .build(),
        )
        assertTrue(db.conversationDao().getConversation(jid)!!.markedAsUnread)
        // An unknown action and an empty index change nothing.
        val before = db.conversationDao().getConversation(jid)
        applyAction(
            """["unknown_action","$jid"]""",
            WhatsAppAppStateProto.SyncActionValue.newBuilder().build(),
        )
        assertEquals(before, db.conversationDao().getConversation(jid))
        assertNotNull(before)
    }
}
