package com.vayunmathur.communicate.whatsapp

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.protobuf.ByteString
import com.vayunmathur.communicate.data.whatsapp.WhatsAppAuthData
import com.vayunmathur.communicate.data.whatsapp.WhatsAppClient
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDiag
import com.vayunmathur.communicate.data.whatsapp.WhatsAppEvent
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol
import com.vayunmathur.communicate.data.whatsapp.applyAppStateRecord
import com.vayunmathur.communicate.data.whatsapp.emitHistoryConversation
import com.vayunmathur.communicate.data.whatsapp.getAppStateKey
import com.vayunmathur.communicate.data.whatsapp.handleAppStateKeyShare
import com.vayunmathur.communicate.data.whatsapp.expandAppStateKeys
import com.vayunmathur.communicate.data.whatsapp.handleHistorySync
import com.vayunmathur.communicate.data.whatsapp.loadPollSecret
import com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppAppStateProto
import com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto
import com.vayunmathur.communicate.data.whatsapp.sendHistoryOnDemandRequest
import com.vayunmathur.communicate.data.whatsapp.sendHistorySyncReceipt
import com.vayunmathur.communicate.data.whatsapp.storeAppStateKey
import com.vayunmathur.communicate.data.whatsapp.transport.WhatsAppSocket
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Pass-2 slice-1 (whatsapp sync): app-state key share + record application, and
 * history-sync backfill, with the same deterministic no-DB/no-session harness as
 * the client-actions tests.
 */
@RunWith(AndroidJUnit4::class)
class W5Pass2SyncHistoryTest {

    private val auth = WhatsAppAuthData(
        phoneNumber = "+15550001", pushName = "Tester", wid = "15550001@s.whatsapp.net",
        noisePrivateKey = "", noisePublicKey = "", identityPrivateKey = "", identityPublicKey = "",
        registrationId = 14, signedPreKeyId = 1, signedPreKeyPublic = "", signedPreKeyPrivate = "",
        signedPreKeySignature = "", lid = "12345@lid",
    )

    private lateinit var eventScope: CoroutineScope
    private val receivedEvents = mutableListOf<WhatsAppEvent>()

    @BeforeTest
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        WhatsAppClient.appContext = app
        WhatsAppClient.authData = auth
        WhatsAppClient.db = null
        WhatsAppClient.webSocket = WhatsAppSocket(auth)
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Connected
        WhatsAppClient.nameCache.clear()
        WhatsAppClient.lidToPhoneMap.clear()
        WhatsAppClient.onDemandRequested.clear()
        WhatsAppClient.onDemandPageCount.clear()
        WhatsAppClient.appStateCollectionsFetching.clear()
        WhatsAppClient.appStateKeyRequested.clear()
        WhatsAppDiag.clear()
        receivedEvents.clear()
        eventScope = CoroutineScope(Dispatchers.Default + Job())
        eventScope.launch { WhatsAppClient.events.collect { receivedEvents.add(it) } }
    }

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    @Test
    fun `key share stores keys and kicks off collection fetches`() = runTest {
        val share = WhatsAppE2EProto.AppStateSyncKeyShare.newBuilder()
            .addKeys(
                WhatsAppE2EProto.AppStateSyncKey.newBuilder()
                    .setKeyId(WhatsAppE2EProto.AppStateSyncKeyId.newBuilder().setKeyId(ByteString.copyFrom(byteArrayOf(1, 1))))
                    .setKeyData(WhatsAppE2EProto.AppStateSyncKeyData.newBuilder().setKeyData(ByteString.copyFrom(ByteArray(32) { 3 }))),
            )
            .addKeys(
                WhatsAppE2EProto.AppStateSyncKey.newBuilder()
                    .setKeyId(WhatsAppE2EProto.AppStateSyncKeyId.newBuilder().setKeyId(ByteString.copyFrom(byteArrayOf())))
                    .setKeyData(WhatsAppE2EProto.AppStateSyncKeyData.newBuilder().setKeyData(ByteString.copyFrom(ByteArray(32)))),
            )
            .build()
        WhatsAppClient.handleAppStateKeyShare(share)
        // Only the non-empty key is stored.
        assertNotNull(WhatsAppClient.getAppStateKey(byteArrayOf(1, 1)))
        assertTrue(WhatsAppDiag.log.value.any { it.contains("stored 1 sync key(s)") })
        // Every collection fetch fails soft (no Iq answer) and clears its in-flight mark.
        assertTrue(WhatsAppClient.appStateCollectionsFetching.isEmpty())
        assertTrue(WhatsAppDiag.log.value.any { it.contains("synced to v0") })
    }

    @Test
    fun `app state contact record lands in the name cache`() = runTest {
        val keyId = byteArrayOf(42, 42)
        val keyData = ByteArray(32) { (it * 7 + 1).toByte() }
        WhatsAppClient.storeAppStateKey(keyId, keyData)
        val action = WhatsAppAppStateProto.SyncActionData.newBuilder()
            .setIndex(ByteString.copyFrom("""["contact","777@s.whatsapp.net"]""".toByteArray()))
            .setValue(
                WhatsAppAppStateProto.SyncActionValue.newBuilder()
                    .setContactAction(WhatsAppAppStateProto.ContactAction.newBuilder().setFullName("Seven Of Nine")),
            )
            .build()
        val record = encryptedRecord(keyId, keyData, action.toByteArray())
        WhatsAppClient.applyAppStateRecord(record, isSet = true)
        assertEquals("Seven Of Nine", WhatsAppClient.nameCache["777@s.whatsapp.net"])
        // A REMOVE record and an unknown-key record change nothing.
        WhatsAppClient.applyAppStateRecord(record, isSet = false)
        val unknown = encryptedRecord(byteArrayOf(1), keyData, action.toByteArray())
        WhatsAppClient.nameCache.clear()
        WhatsAppClient.applyAppStateRecord(unknown, isSet = true)
        assertTrue(WhatsAppClient.nameCache.isEmpty())
        // Garbage plaintext is dropped silently.
        val garbage = encryptedRecord(keyId, keyData, byteArrayOf(1, 2, 3, 4))
        WhatsAppClient.applyAppStateRecord(garbage, isSet = true)
        assertTrue(WhatsAppClient.nameCache.isEmpty())
    }

    private fun encryptedRecord(keyId: ByteArray, keyData: ByteArray, plain: ByteArray): WhatsAppAppStateProto.SyncdRecord {
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

    private fun historyMessage(id: String, body: String, fromMe: Boolean, ts: Long, pushName: String = "") =
        WhatsAppE2EProto.HistorySyncMsg.newBuilder()
            .setMessage(
                WhatsAppE2EProto.WebMessageInfo.newBuilder()
                    .setKey(WhatsAppE2EProto.HsMsgKey.newBuilder().setId(id).setFromMe(fromMe).setRemoteJid("777@s.whatsapp.net"))
                    .setMessage(WhatsAppE2EProto.Message.newBuilder().setConversation(body))
                    .setMessageTimestamp(ts)
                    .setPushName(pushName),
            )
            .build()

    @Test
    fun `history sync emits conversations and messages and maps LIDs`() = runTest {
        val history = WhatsAppE2EProto.HistorySync.newBuilder()
            .setSyncType(WhatsAppClient.SYNC_INITIAL_BOOTSTRAP)
            .setChunkOrder(1)
            .addPhoneNumberToLidMappings(
                WhatsAppE2EProto.PhoneNumberToLIDMapping.newBuilder()
                    .setPnJid("777@s.whatsapp.net").setLidJid("999@lid"),
            )
            .addConversations(
                WhatsAppE2EProto.HsConversation.newBuilder()
                    .setId("777@s.whatsapp.net")
                    .setName("Seven")
                    .setUnreadCount(2)
                    .addMessages(historyMessage("h1", "hello from history", fromMe = false, ts = 1_700_000_000L, pushName = "Seven"))
                    .addMessages(historyMessage("h2", "reply from me", fromMe = true, ts = 1_700_000_100L)),
            )
            .addConversations(
                WhatsAppE2EProto.HsConversation.newBuilder()
                    .setId("status@broadcast")
                    .addMessages(historyMessage("s1", "status junk", fromMe = false, ts = 1_700_000_000L)),
            )
            .build()
        val notif = WhatsAppE2EProto.HistorySyncNotification.newBuilder()
            .setInitialHistBootstrapInlinePayload(ByteString.copyFrom(deflate(history.toByteArray())))
            .build()
        WhatsAppClient.fullHistorySync = false // do not chase on-demand pages in this test
        WhatsAppClient.handleHistorySync(notif, "notif-1")
        assertEquals("777@s.whatsapp.net", WhatsAppClient.lidToPhoneMap["999@lid"])
        val updates = receivedEvents.filterIsInstance<WhatsAppEvent.MessageUpdate>()
        assertEquals(listOf("hello from history", "reply from me"), updates.map { it.body })
        assertEquals(listOf(false, true), updates.map { it.outgoing })
        val conv = receivedEvents.filterIsInstance<WhatsAppEvent.ConversationUpdate>().single()
        assertEquals("wa:777@s.whatsapp.net", conv.conversationId)
        assertEquals(2, conv.unreadCount)
        assertEquals("reply from me", conv.lastPreview)
        assertTrue(WhatsAppDiag.log.value.any { it.contains("emitted 2 message(s)") })
        assertTrue(WhatsAppDiag.log.value.any { it.contains("hist_sync receipt for notif-1") })
        WhatsAppClient.fullHistorySync = true
    }

    @Test
    fun `history sync failures are logged not thrown`() = runTest {
        val notif = WhatsAppE2EProto.HistorySyncNotification.newBuilder()
            .setInitialHistBootstrapInlinePayload(ByteString.copyFrom(byteArrayOf(1, 2, 3)))
            .build()
        WhatsAppClient.handleHistorySync(notif, "notif-bad")
        // No inline payload and no media host: still just a receipt.
        WhatsAppClient.handleHistorySync(WhatsAppE2EProto.HistorySyncNotification.getDefaultInstance(), "notif-empty")
        assertTrue(WhatsAppDiag.log.value.any { it.contains("hist_sync receipt for notif-empty") })
    }

    @Test
    fun `emit history conversation skips empty and counts bodies`() = runTest {
        val empty = WhatsAppE2EProto.HsConversation.newBuilder().setId("777@s.whatsapp.net").build()
        assertEquals(0, WhatsAppClient.emitHistoryConversation(empty, "777@s.whatsapp.net", requestMore = false))
        val conv = WhatsAppE2EProto.HsConversation.newBuilder()
            .setId("12345@g.us")
            .setName("Group")
            .addMessages(historyMessage("g1", "group hello", fromMe = false, ts = 1_700_000_000L))
            .build()
        assertEquals(1, WhatsAppClient.emitHistoryConversation(conv, "12345@g.us", requestMore = false))
        val update = receivedEvents.filterIsInstance<WhatsAppEvent.ConversationUpdate>().single()
        assertTrue(update.isGroup)
        assertEquals("wa:12345@g.us", update.conversationId)
    }

    @Test
    fun `history receipts and on-demand dedupe`() = runTest {
        WhatsAppClient.sendHistorySyncReceipt("")
        WhatsAppClient.sendHistorySyncReceipt("m-1")
        assertTrue(WhatsAppDiag.log.value.any { it.contains("hist_sync receipt for m-1") })
        WhatsAppClient.sendHistoryOnDemandRequest("777@s.whatsapp.net", "old-1", oldestFromMe = false, oldestTsSec = 100L)
        val diagAfterFirst = WhatsAppDiag.log.value.size
        // The same cursor is deduped; an empty id is ignored before that.
        WhatsAppClient.sendHistoryOnDemandRequest("777@s.whatsapp.net", "old-1", oldestFromMe = false, oldestTsSec = 100L)
        WhatsAppClient.sendHistoryOnDemandRequest("777@s.whatsapp.net", "", oldestFromMe = false, oldestTsSec = 0L)
        WhatsAppClient.sendHistoryOnDemandRequest("", "old-2", oldestFromMe = false, oldestTsSec = 0L)
        assertEquals(1, WhatsAppClient.onDemandPageCount["777@s.whatsapp.net"])
        assertTrue(WhatsAppDiag.log.value.size >= diagAfterFirst)
        assertNull(WhatsAppClient.loadPollSecret("never-stored"))
    }
}
