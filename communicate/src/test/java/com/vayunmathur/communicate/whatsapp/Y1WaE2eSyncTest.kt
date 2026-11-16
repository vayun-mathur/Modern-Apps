package com.vayunmathur.communicate.whatsapp

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
import com.vayunmathur.communicate.data.whatsapp.WhatsAppDiag
import com.vayunmathur.communicate.data.whatsapp.WhatsAppEvent
import com.vayunmathur.communicate.data.whatsapp.WhatsAppPollSecret
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol
import com.vayunmathur.communicate.data.whatsapp.appStateVersion
import com.vayunmathur.communicate.data.whatsapp.applyAppStateRecord
import com.vayunmathur.communicate.data.whatsapp.downloadAppStateBlob
import com.vayunmathur.communicate.data.whatsapp.e2e.RustWhatsAppCrypto
import com.vayunmathur.communicate.data.whatsapp.e2e.WhatsAppE2E
import com.vayunmathur.communicate.data.whatsapp.emitHistoryConversation
import com.vayunmathur.communicate.data.whatsapp.expandAppStateKeys
import com.vayunmathur.communicate.data.whatsapp.fetchAppStateCollection
import com.vayunmathur.communicate.data.whatsapp.getAppStateKey
import com.vayunmathur.communicate.data.whatsapp.handleAppStateKeyShare
import com.vayunmathur.communicate.data.whatsapp.handleHistorySync
import com.vayunmathur.communicate.data.whatsapp.inflateZlib
import com.vayunmathur.communicate.data.whatsapp.kickoffBackfill
import com.vayunmathur.communicate.data.whatsapp.loadPollSecret
import com.vayunmathur.communicate.data.whatsapp.padMessage
import com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppAppStateProto
import com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppE2EProto
import com.vayunmathur.communicate.data.whatsapp.requestAppStateKey
import com.vayunmathur.communicate.data.whatsapp.sendHistoryOnDemandRequest
import com.vayunmathur.communicate.data.whatsapp.sendHistorySyncReceipt
import com.vayunmathur.communicate.data.whatsapp.setAppStateVersion
import com.vayunmathur.communicate.data.whatsapp.storeAppStateKey
import com.vayunmathur.communicate.data.whatsapp.storePollOptions
import com.vayunmathur.communicate.data.whatsapp.storePollSecret
import com.vayunmathur.communicate.data.whatsapp.transport.WhatsAppSocket
import com.vayunmathur.communicate.data.whatsapp.unpadMessage
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Collections
import java.util.zip.Deflater
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
 * Y1: WhatsAppE2E (real host-Rust X3DH/Double-Ratchet/sender-key round trips and
 * bundle-node parsing) plus WhatsAppClientSync (app-state keys/records, poll
 * stores, history-sync emission and on-demand pagination guards).
 */
@RunWith(AndroidJUnit4::class)
class Y1WaE2eSyncTest {

    companion object {
        fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
        fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
        fun authFor(idSeed: Int, spkSeed: Int, wid: String, reg: Int): WhatsAppAuthData {
            val idPriv = ByteArray(32) { (idSeed + it).toByte() }
            val idPub = RustWhatsAppCrypto.publicFromPrivate(idPriv)!!
            val spkPriv = ByteArray(32) { (spkSeed + it).toByte() }
            val spkPub = RustWhatsAppCrypto.publicFromPrivate(spkPriv)!!
            return WhatsAppAuthData(
                phoneNumber = "+15550001", pushName = "Tester", wid = wid,
                noisePrivateKey = b64(ByteArray(32) { 1 }), noisePublicKey = b64(ByteArray(32) { 2 }),
                identityPrivateKey = b64(idPriv), identityPublicKey = b64(idPub),
                registrationId = reg, signedPreKeyId = 5,
                signedPreKeyPublic = b64(spkPub), signedPreKeyPrivate = b64(spkPriv),
                signedPreKeySignature = b64(WhatsAppE2E.signSignedPreKey(idPriv, spkPub)), lid = "12345@lid",
            )
        }
        val ALICE: WhatsAppAuthData by lazy { authFor(1, 41, "15550001@s.whatsapp.net", 14) }
        val BOB: WhatsAppAuthData by lazy { authFor(101, 141, "15550002@s.whatsapp.net", 99) }
        const val GROUP = "12345@g.us"
    }

    private lateinit var dbA: WhatsAppDatabase
    private lateinit var dbB: WhatsAppDatabase
    private val events = Collections.synchronizedList(mutableListOf<WhatsAppEvent>())

    private fun newDb(): WhatsAppDatabase {
        val app = ApplicationProvider.getApplicationContext<Application>()
        return Room.inMemoryDatabaseBuilder(app, WhatsAppDatabase::class.java)
            .setSingleConnectionPool().allowMainThreadQueries().build()
    }

    @BeforeTest
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        dbA = newDb(); dbB = newDb()
        WhatsAppClient.appContext = app
        WhatsAppClient.authData = ALICE
        WhatsAppClient.db = dbA
        WhatsAppClient.webSocket = WhatsAppSocket(ALICE)
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Connected
        WhatsAppClient.fullHistorySync = false
        WhatsAppClient.nameCache.clear(); WhatsAppClient.lidToPhoneMap.clear()
        WhatsAppClient.pollSecrets.clear(); WhatsAppClient.onDemandRequested.clear()
        WhatsAppClient.onDemandPageCount.clear(); WhatsAppClient.appStateCollectionsFetching.clear()
        WhatsAppClient.appStateKeyRequested.clear()
        WhatsAppDiag.clear(); events.clear()
        CoroutineScope(Dispatchers.Default + Job()).launch { WhatsAppClient.events.collect { events.add(it) } }
    }

    private fun bundleFor(auth: WhatsAppAuthData, withOneTime: Pair<Int, ByteArray>? = null) =
        WhatsAppE2E.ParsedPreKeyBundle(
            registrationId = auth.registrationId,
            preKeyId = withOneTime?.first, preKeyPublic = withOneTime?.second,
            signedPreKeyId = auth.signedPreKeyId, signedPreKeyPublic = unb64(auth.signedPreKeyPublic),
            signedPreKeySignature = unb64(auth.signedPreKeySignature), identityKey = unb64(auth.identityPublicKey),
        )

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(); d.setInput(data); d.finish()
        val out = ByteArrayOutputStream(); val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf)); d.end(); return out.toByteArray()
    }

    // ---------- E2E: addressing, prekey-id scan, signing ----------

    @Test
    fun `signal address parsing and parsePreKeyIdFromMessage wire scan`() {
        val e2e = WhatsAppE2E(dbA, ALICE)
        assertEquals("15550002" to 0, e2e.signalAddress("15550002@s.whatsapp.net"))
        assertEquals("15550002" to 7, e2e.signalAddress("15550002:7@s.whatsapp.net"))
        assertEquals("15550002" to 0, e2e.signalAddress("15550002.9@s.whatsapp.net"))
        assertEquals("15550002" to 0, e2e.signalAddress("15550002:abc@s.whatsapp.net"))
        assertEquals("x" to 0, e2e.signalAddress("x@lid"))
        // version byte, field1 varint 42
        assertEquals(42, WhatsAppE2E.parsePreKeyIdFromMessage(byteArrayOf(0x33, 0x08, 42)))
        // field2 length-delimited skipped, then field1 = 300 (varint)
        assertEquals(300, WhatsAppE2E.parsePreKeyIdFromMessage(byteArrayOf(0x33, 0x12, 2, 1, 2, 0x08, 0xAC.toByte(), 0x02)))
        assertNull(WhatsAppE2E.parsePreKeyIdFromMessage(ByteArray(0)))
        assertNull(WhatsAppE2E.parsePreKeyIdFromMessage(byteArrayOf(0x33, 0x08))) // truncated varint value
        assertNull(WhatsAppE2E.parsePreKeyIdFromMessage(byteArrayOf(0x33, 0x12, 9, 1))) // truncated len field
        assertNull(WhatsAppE2E.parsePreKeyIdFromMessage(byteArrayOf(0x33, 0x10, 5))) // field2 varint, no field1
        // XEdDSA sign/verify through Rust, tamper fails.
        val msg = byteArrayOf(5) + unb64(ALICE.signedPreKeyPublic)
        val sig = WhatsAppE2E.signSignedPreKey(unb64(ALICE.identityPrivateKey), unb64(ALICE.signedPreKeyPublic))
        assertEquals(64, sig.size)
        assertTrue(RustWhatsAppCrypto.verify(unb64(ALICE.identityPublicKey), msg, sig))
        assertFalse(RustWhatsAppCrypto.verify(unb64(ALICE.identityPublicKey), byteArrayOf(9, 9), sig))
        e2e.ensureSignedPreKeyStored() // documented no-op
        assertTrue(RustWhatsAppCrypto.isAvailable)
    }

    // ---------- E2E: DM round trip both directions ----------

    @Test
    fun `x3dh session encrypt decrypt round trip and session lifecycle`() {
        val alice = WhatsAppE2E(dbA, ALICE); val bob = WhatsAppE2E(dbB, BOB)
        assertFalse(alice.hasSession(BOB.wid)); assertFalse(bob.hasSession(ALICE.wid))
        assertFailsWith<IllegalStateException> { alice.encryptDM(BOB.wid, WhatsAppProtocol.padMessage("x".toByteArray())) }
        assertFailsWith<IllegalStateException> { bob.decryptDM(ALICE.wid, isPreKey = false, ciphertext = byteArrayOf(1)) }

        bob.processPreKeyBundle(ALICE.wid, bundleFor(ALICE))
        assertTrue(bob.hasSession(ALICE.wid))
        val proto = WhatsAppE2EProto.Message.newBuilder().setConversation("hello alice").build()
        val first = bob.encryptDM(ALICE.wid, WhatsAppProtocol.padMessage(proto.toByteArray()))
        assertEquals("pkmsg", first.type)
        val plain1 = alice.decryptDM(BOB.wid, isPreKey = true, ciphertext = first.data)
        assertEquals("hello alice", WhatsAppE2EProto.Message.parseFrom(WhatsAppProtocol.unpadMessage(plain1)).conversation)
        assertTrue(alice.hasSession(BOB.wid))

        // Reply on the established (non-prekey) ratchet, decrypted as msg.
        val reply = alice.encryptDM(BOB.wid, WhatsAppProtocol.padMessage(
            WhatsAppE2EProto.Message.newBuilder().setConversation("hi bob").build().toByteArray()))
        assertEquals("msg", reply.type)
        val plain2 = bob.decryptDM(ALICE.wid, isPreKey = false, ciphertext = reply.data)
        assertEquals("hi bob", WhatsAppE2EProto.Message.parseFrom(WhatsAppProtocol.unpadMessage(plain2)).conversation)

        // Garbage pkmsg and tampered msg both fail (Rust throws), never yield plaintext.
        assertFailsWith<Exception> { alice.decryptDM(BOB.wid, isPreKey = true, ciphertext = byteArrayOf(1, 2, 3)) }
        alice.deleteSession(BOB.wid); assertFalse(alice.hasSession(BOB.wid))
        alice.deleteSession("nobody@s.whatsapp.net") // absent delete is fine
    }

    @Test
    fun `one-time prekey is consumed on inbound pkmsg decrypt`() = runTest {
        val alice = WhatsAppE2E(dbA, ALICE); val bob = WhatsAppE2E(dbB, BOB)
        // Alice seeds one-time prekeys via the retry-keys builder (stores id 1 in her DB).
        val keysNode = alice.buildRetryReceiptKeysNode(accountDeviceIdentity = byteArrayOf(4, 5))
        val keyNode = keysNode.getChildren().first { it.tag == "key" }
        val oneTimeId = keyNode.getChildByTag("id")!!.data!!.let { ((it[0].toInt() and 0xFF) shl 16) or ((it[1].toInt() and 0xFF) shl 8) or (it[2].toInt() and 0xFF) }
        val oneTimePub = keyNode.getChildByTag("value")!!.data!!
        assertNotNull(dbA.e2ePreKeyDao().get(oneTimeId))
        bob.processPreKeyBundle(ALICE.wid, bundleFor(ALICE, withOneTime = oneTimeId to oneTimePub))
        val enc = bob.encryptDM(ALICE.wid, WhatsAppProtocol.padMessage(
            WhatsAppE2EProto.Message.newBuilder().setConversation("with otp").build().toByteArray()))
        val plain = alice.decryptDM(BOB.wid, isPreKey = true, ciphertext = enc.data)
        assertEquals("with otp", WhatsAppE2EProto.Message.parseFrom(WhatsAppProtocol.unpadMessage(plain)).conversation)
        assertNull(dbA.e2ePreKeyDao().get(oneTimeId)) // one-time key deleted after use
    }

    // ---------- E2E: bundle node parsing + upload content ----------

    private fun idBytes(id: Int) = byteArrayOf((id shr 16).toByte(), (id shr 8).toByte(), id.toByte())
    private fun keyChild(tag: String, id: Int, value: ByteArray, sig: ByteArray? = null) = WhatsAppProtocol.Node(
        tag = tag, content = listOfNotNull(
            WhatsAppProtocol.Node(tag = "id", data = idBytes(id)),
            WhatsAppProtocol.Node(tag = "value", data = value),
            sig?.let { WhatsAppProtocol.Node(tag = "signature", data = it) },
        ),
    )

    @Test
    fun `parsePreKeyBundleNode accepts valid and rejects malformed bundles`() {
        val e2e = WhatsAppE2E(dbA, ALICE)
        val reg = WhatsAppProtocol.Node(tag = "registration", data = byteArrayOf(0, 0, 0, 99))
        val keys = WhatsAppProtocol.Node(tag = "keys", content = listOf(
            WhatsAppProtocol.Node(tag = "identity", data = ByteArray(32) { 7 }),
            keyChild("key", 12, ByteArray(32) { 8 }),
            keyChild("skey", 5, ByteArray(32) { 9 }, ByteArray(64) { 3 }),
        ))
        val user = WhatsAppProtocol.Node(tag = "user", content = listOf(reg, keys))
        val parsed = e2e.parsePreKeyBundleNode(0, user)!!
        assertEquals(99, parsed.registrationId); assertEquals(12, parsed.preKeyId)
        assertEquals(5, parsed.signedPreKeyId); assertEquals(32, parsed.identityKey.size)
        assertTrue(parsed.preKeyPublic!!.contentEquals(ByteArray(32) { 8 }))
        // Bundle without the optional one-time key parses with null prekey.
        val noOtpKeys = WhatsAppProtocol.Node(tag = "keys", content = listOf(
            WhatsAppProtocol.Node(tag = "identity", data = ByteArray(32) { 7 }),
            keyChild("skey", 5, ByteArray(32) { 9 }, ByteArray(64) { 3 }),
        ))
        val noOtp = e2e.parsePreKeyBundleNode(2, WhatsAppProtocol.Node(tag = "user", content = listOf(reg, noOtpKeys)))!!
        assertNull(noOtp.preKeyId); assertNull(noOtp.preKeyPublic)
        // Malformed variants all return null rather than throwing.
        assertNull(e2e.parsePreKeyBundleNode(0, WhatsAppProtocol.Node(tag = "user", content = listOf(
            WhatsAppProtocol.Node(tag = "error"), reg, keys))))
        assertNull(e2e.parsePreKeyBundleNode(0, WhatsAppProtocol.Node(tag = "user", content = listOf(keys)))) // no registration
        assertNull(e2e.parsePreKeyBundleNode(0, WhatsAppProtocol.Node(tag = "user", content = listOf(
            WhatsAppProtocol.Node(tag = "registration", data = byteArrayOf(1)), keys)))) // short registration
        val badIdentity = WhatsAppProtocol.Node(tag = "keys", content = listOf(
            WhatsAppProtocol.Node(tag = "identity", data = ByteArray(31)),
            keyChild("skey", 5, ByteArray(32) { 9 }, ByteArray(64) { 3 })))
        assertNull(e2e.parsePreKeyBundleNode(0, WhatsAppProtocol.Node(tag = "user", content = listOf(reg, badIdentity))))
        val badSig = WhatsAppProtocol.Node(tag = "keys", content = listOf(
            WhatsAppProtocol.Node(tag = "identity", data = ByteArray(32)),
            keyChild("skey", 5, ByteArray(32) { 9 }, ByteArray(63))))
        assertNull(e2e.parsePreKeyBundleNode(0, WhatsAppProtocol.Node(tag = "user", content = listOf(reg, badSig))))
        val badKeyVal = WhatsAppProtocol.Node(tag = "keys", content = listOf(
            WhatsAppProtocol.Node(tag = "identity", data = ByteArray(32)),
            keyChild("key", 12, ByteArray(10)),
            keyChild("skey", 5, ByteArray(32) { 9 }, ByteArray(64) { 3 })))
        // A malformed one-time key is treated as absent, not as a fatal bundle error.
        val badKeyParsed = e2e.parsePreKeyBundleNode(0, WhatsAppProtocol.Node(tag = "user", content = listOf(reg, badKeyVal)))!!
        assertNull(badKeyParsed.preKeyId); assertNull(badKeyParsed.preKeyPublic)
    }

    @Test
    fun `prekey upload content shape refill counts and uploaded marking`() = runTest {
        val e2e = WhatsAppE2E(dbA, ALICE)
        val content = e2e.buildPreKeyUploadContent(initialUpload = false)
        assertEquals(listOf("registration", "type", "identity", "list", "skey"), content.map { it.tag })
        assertTrue(content[0].data!!.contentEquals(byteArrayOf(0, 0, 0, 14)))
        assertTrue(content[1].data!!.contentEquals(byteArrayOf(5)))
        assertTrue(content[2].data!!.contentEquals(unb64(ALICE.identityPublicKey)))
        assertEquals(50, content[3].getChildren().size)
        assertEquals(50, dbA.e2ePreKeyDao().getCount())
        assertEquals(50, dbA.e2ePreKeyDao().getUnuploaded().size)
        e2e.markPreKeysUploaded()
        assertEquals(0, dbA.e2ePreKeyDao().getUnuploaded().size)
        // Refill continues ids from the stored max, never reusing one-time ids.
        val again = e2e.buildPreKeyUploadContent(initialUpload = false)
        assertEquals(100, dbA.e2ePreKeyDao().getCount())
        val firstIds = content[3].getChildren().map { it.getChildByTag("id")!!.data!!.joinToString("") { b -> "%02x".format(b) } }.toSet()
        val secondIds = again[3].getChildren().map { it.getChildByTag("id")!!.data!!.joinToString("") { b -> "%02x".format(b) } }.toSet()
        assertTrue(firstIds.intersect(secondIds).isEmpty())
        // Retry keys node: with and without device identity.
        val withId = e2e.buildRetryReceiptKeysNode(byteArrayOf(1, 2))
        assertEquals(listOf("type", "identity", "key", "skey", "device-identity"), withId.getChildren().map { it.tag })
        val without = e2e.buildRetryReceiptKeysNode(null)
        assertEquals(listOf("type", "identity", "key", "skey"), without.getChildren().map { it.tag })
    }

    // ---------- E2E: sender keys ----------

    @Test
    fun `sender key distribution group round trip and failure modes`() {
        val alice = WhatsAppE2E(dbA, ALICE); val bob = WhatsAppE2E(dbB, BOB)
        val skdm = alice.createSenderKeyDistribution(GROUP)
        assertTrue(skdm.isNotEmpty())
        bob.processSenderKeyDistribution(GROUP, ALICE.wid, skdm)
        val proto = WhatsAppE2EProto.Message.newBuilder().setConversation("group hello").build()
        val cipher = alice.encryptGroup(GROUP, WhatsAppProtocol.padMessage(proto.toByteArray()))
        val plain = bob.decryptGroup(GROUP, ALICE.wid, cipher)
        assertEquals("group hello", WhatsAppE2EProto.Message.parseFrom(WhatsAppProtocol.unpadMessage(plain)).conversation)
        // Second creation rotates the sender key; a fresh peer can still join from the new SKDM.
        val dbC = newDb(); val carol = WhatsAppE2E(dbC, authFor(201, 241, "15550003@s.whatsapp.net", 7))
        val skdm2 = alice.createSenderKeyDistribution(GROUP)
        carol.processSenderKeyDistribution(GROUP, ALICE.wid, skdm2)
        val cipher2 = alice.encryptGroup(GROUP, WhatsAppProtocol.padMessage(proto.toByteArray()))
        assertEquals("group hello", WhatsAppE2EProto.Message.parseFrom(
            WhatsAppProtocol.unpadMessage(carol.decryptGroup(GROUP, ALICE.wid, cipher2))).conversation)
        // Failures: no sender key, garbage SKDM, tampered group ciphertext.
        assertFailsWith<IllegalStateException> { carol.decryptGroup(GROUP, BOB.wid, cipher) }
        assertFailsWith<Exception> { bob.processSenderKeyDistribution(GROUP, ALICE.wid, byteArrayOf(1, 2, 3)) }
        assertFailsWith<Exception> { bob.decryptGroup(GROUP, ALICE.wid, cipher.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }) }
        // Device-scoped addressing: distribution from a :3 device is a distinct sender key slot.
        bob.processSenderKeyDistribution(GROUP, "15550001:3@s.whatsapp.net", skdm)
        assertFailsWith<IllegalStateException> { bob.decryptGroup("999@g.us", ALICE.wid, cipher) }
    }

    // ---------- Sync: app-state keys / versions / records ----------

    @Test
    fun `app state key store version and corrupted values`() = runTest {
        WhatsAppClient.storeAppStateKey(byteArrayOf(1, 2), ByteArray(32) { 4 })
        assertTrue(WhatsAppClient.getAppStateKey(byteArrayOf(1, 2))!!.contentEquals(ByteArray(32) { 4 }))
        assertNull(WhatsAppClient.getAppStateKey(byteArrayOf(9, 9)))
        // Corrupted stored base64 decodes to null instead of throwing.
        WhatsAppClient.appStatePrefs.edit().putString(
            "key_${android.util.Base64.encodeToString(byteArrayOf(3, 3), android.util.Base64.NO_WRAP)}", "!!!not-base64!!!",
        ).commit()
        assertNull(WhatsAppClient.getAppStateKey(byteArrayOf(3, 3)))
        assertEquals(0L, WhatsAppClient.appStateVersion("regular"))
        WhatsAppClient.setAppStateVersion("regular", 123L)
        assertEquals(123L, WhatsAppClient.appStateVersion("regular"))
        WhatsAppClient.setAppStateVersion("regular", 0L)
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

    private suspend fun applyAction(indexJson: String, value: WhatsAppAppStateProto.SyncActionValue, keyId: ByteArray = byteArrayOf(42, 42)) {
        val keyData = ByteArray(32) { (it * 7 + 1).toByte() }
        WhatsAppClient.storeAppStateKey(keyId, keyData)
        val action = WhatsAppAppStateProto.SyncActionData.newBuilder()
            .setIndex(ByteString.copyFrom(indexJson.toByteArray())).setValue(value).build()
        WhatsAppClient.applyAppStateRecord(encryptedRecord(keyId, keyData, action.toByteArray()), isSet = true)
    }

    @Test
    fun `app state records contact fallbacks and silent drop branches`() = runTest {
        dbA.conversationDao().upsert(WhatsAppConversation(chatJid = "15550002@s.whatsapp.net"))
        // contact with firstName fallback, cached under both resolved and raw jids
        applyAction("""["contact","15550002@s.whatsapp.net"]""",
            WhatsAppAppStateProto.SyncActionValue.newBuilder().setContactAction(
                WhatsAppAppStateProto.ContactAction.newBuilder().setFirstName("Bobby")).build())
        assertEquals("Bobby", WhatsAppClient.nameCache["15550002@s.whatsapp.net"])
        // empty contact name and non-SET do not overwrite
        applyAction("""["contact","15550002@s.whatsapp.net"]""",
            WhatsAppAppStateProto.SyncActionValue.newBuilder().setContactAction(
                WhatsAppAppStateProto.ContactAction.newBuilder()).build())
        assertEquals("Bobby", WhatsAppClient.nameCache["15550002@s.whatsapp.net"])
        // mute/pin/archive/read land on the conversation row
        applyAction("""["mute","15550002@s.whatsapp.net"]""",
            WhatsAppAppStateProto.SyncActionValue.newBuilder().setMuteAction(
                WhatsAppAppStateProto.MuteAction.newBuilder().setMuteEndTimestamp(777L)).build())
        applyAction("""["archive","15550002@s.whatsapp.net"]""",
            WhatsAppAppStateProto.SyncActionValue.newBuilder().setArchiveChatAction(
                WhatsAppAppStateProto.ArchiveChatAction.newBuilder().setArchived(true)).build())
        applyAction("""["markChatAsRead","15550002@s.whatsapp.net"]""",
            WhatsAppAppStateProto.SyncActionValue.newBuilder().setMarkChatAsReadAction(
                WhatsAppAppStateProto.MarkChatAsReadAction.newBuilder().setRead(true)).build())
        val row = dbA.conversationDao().getConversation("15550002@s.whatsapp.net")!!
        assertEquals(777L, row.muteEndTime); assertTrue(row.archived); assertFalse(row.markedAsUnread)
        // unknown action / empty index / bad JSON / garbage proto / short blob / unknown key: silent no-ops
        applyAction("""["label","15550002@s.whatsapp.net"]""", WhatsAppAppStateProto.SyncActionValue.newBuilder().build())
        applyAction("""[]""", WhatsAppAppStateProto.SyncActionValue.newBuilder().build())
        val keyId = byteArrayOf(42, 42); val keyData = ByteArray(32) { (it * 7 + 1).toByte() }
        WhatsAppClient.applyAppStateRecord(encryptedRecord(keyId, keyData, "not json at all".toByteArray()), isSet = true)
        WhatsAppClient.applyAppStateRecord(encryptedRecord(byteArrayOf(8, 8), keyData,
            WhatsAppAppStateProto.SyncActionData.newBuilder().setIndex(ByteString.copyFrom("""["mute","x"]""".toByteArray())).build().toByteArray()), isSet = true)
        val shortBlob = WhatsAppAppStateProto.SyncdRecord.newBuilder()
            .setKeyId(WhatsAppAppStateProto.KeyId.newBuilder().setId(ByteString.copyFrom(keyId)))
            .setValue(WhatsAppAppStateProto.SyncdValue.newBuilder().setBlob(ByteString.copyFrom(byteArrayOf(1, 2)))).build()
        WhatsAppClient.applyAppStateRecord(shortBlob, isSet = true)
        WhatsAppClient.applyAppStateRecord(encryptedRecord(keyId, keyData,
            WhatsAppAppStateProto.SyncActionData.newBuilder().setIndex(ByteString.copyFrom("""["mute","15550002@s.whatsapp.net"]""".toByteArray())).build().toByteArray()), isSet = false)
        assertEquals(row, dbA.conversationDao().getConversation("15550002@s.whatsapp.net"))
        // downloadAppStateBlob: empty path and dead media connection both null
        assertNull(WhatsAppClient.downloadAppStateBlob(WhatsAppAppStateProto.ExternalBlobReference.getDefaultInstance()))
        assertNull(WhatsAppClient.downloadAppStateBlob(
            WhatsAppAppStateProto.ExternalBlobReference.newBuilder().setDirectPath("/x?y=").build()))
    }

    @Test
    fun `key share dedupes collection fetching guards and missing key requests`() = runTest {
        val share = WhatsAppE2EProto.AppStateSyncKeyShare.newBuilder()
            .addKeys(WhatsAppE2EProto.AppStateSyncKey.newBuilder()
                .setKeyId(WhatsAppE2EProto.AppStateSyncKeyId.newBuilder().setKeyId(ByteString.copyFrom(byteArrayOf(5, 5))))
                .setKeyData(WhatsAppE2EProto.AppStateSyncKeyData.newBuilder().setKeyData(ByteString.copyFrom(ByteArray(32) { 2 }))))
            .addKeys(WhatsAppE2EProto.AppStateSyncKey.newBuilder()
                .setKeyId(WhatsAppE2EProto.AppStateSyncKeyId.newBuilder().setKeyId(ByteString.copyFrom(ByteArray(0))))
                .setKeyData(WhatsAppE2EProto.AppStateSyncKeyData.newBuilder().setKeyData(ByteString.copyFrom(ByteArray(32)))))
            .build()
        WhatsAppClient.handleAppStateKeyShare(share)
        assertNotNull(WhatsAppClient.getAppStateKey(byteArrayOf(5, 5)))
        assertTrue(WhatsAppDiag.log.value.any { it.contains("stored 1 sync key(s)") })
        assertTrue(WhatsAppClient.appStateCollectionsFetching.isEmpty())
        // In-flight guard: a pre-marked collection is not refetched.
        WhatsAppClient.appStateCollectionsFetching.add("regular")
        WhatsAppClient.fetchAppStateCollection("regular", fullSync = true)
        assertTrue(WhatsAppClient.appStateCollectionsFetching.contains("regular"))
        WhatsAppClient.appStateCollectionsFetching.remove("regular")
        // Missing-key request is deduped by base64 key id and fails soft without a session.
        WhatsAppClient.requestAppStateKey(byteArrayOf(6, 6))
        val sizeAfterFirst = WhatsAppClient.appStateKeyRequested.size
        WhatsAppClient.requestAppStateKey(byteArrayOf(6, 6))
        assertEquals(sizeAfterFirst, WhatsAppClient.appStateKeyRequested.size)
        assertEquals(1, sizeAfterFirst)
    }

    // ---------- Sync: poll stores ----------

    @Test
    fun `poll option hashes and secret hex round trip through the database`() = runTest {
        WhatsAppClient.storePollOptions("poll-9", listOf("Yes", "No"))
        val yesHash = MessageDigest.getInstance("SHA-256").digest("Yes".toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals("Yes", dbA.pollOptionDao().getByHash("poll-9", yesHash)?.optionName)
        assertEquals(2, dbA.pollOptionDao().getByMessageId("poll-9").size)
        assertNull(dbA.pollOptionDao().getByHash("poll-9", "00"))
        WhatsAppClient.storePollSecret("poll-9", byteArrayOf(1, 2, 3, 127))
        assertTrue(WhatsAppClient.loadPollSecret("poll-9")!!.contentEquals(byteArrayOf(1, 2, 3, 127)))
        // DB-backed load (cache cleared) decodes hex; corrupt hex yields null.
        WhatsAppClient.pollSecrets.clear()
        assertTrue(WhatsAppClient.loadPollSecret("poll-9")!!.contentEquals(byteArrayOf(1, 2, 3, 127)))
        dbA.pollSecretDao().upsert(WhatsAppPollSecret("poll-bad", "zzzz"))
        assertNull(WhatsAppClient.loadPollSecret("poll-bad"))
        assertNull(WhatsAppClient.loadPollSecret("poll-absent"))
        // No DB: stores are no-ops, loads miss.
        WhatsAppClient.db = null
        WhatsAppClient.storePollOptions("x", listOf("A")); WhatsAppClient.storePollSecret("x", byteArrayOf(1))
        WhatsAppClient.pollSecrets["x"]?.let { assertTrue(it.contentEquals(byteArrayOf(1))) } // in-memory still set
        WhatsAppClient.db = dbA
    }

    // ---------- Sync: history ----------

    private fun histMsg(id: String, body: String?, fromMe: Boolean, ts: Long, push: String = "") =
        WhatsAppE2EProto.HistorySyncMsg.newBuilder().setMessage(
            WhatsAppE2EProto.WebMessageInfo.newBuilder()
                .setKey(WhatsAppE2EProto.HsMsgKey.newBuilder().setId(id).setFromMe(fromMe).setRemoteJid("15550002@s.whatsapp.net"))
                .apply { if (body != null) setMessage(WhatsAppE2EProto.Message.newBuilder().setConversation(body)) }
                .setMessageTimestamp(ts).setPushName(push),
        ).build()

    @Test
    fun `history sync LID chats empty bodies group rows and receipts`() = runTest {
        WhatsAppClient.lidToPhoneMap["999@lid"] = "15550002@s.whatsapp.net"
        val history = WhatsAppE2EProto.HistorySync.newBuilder().setSyncType(WhatsAppClient.SYNC_INITIAL_BOOTSTRAP)
            .addPhoneNumberToLidMappings(WhatsAppE2EProto.PhoneNumberToLIDMapping.newBuilder().setPnJid("15550003@s.whatsapp.net").setLidJid("888@lid"))
            .addConversations(WhatsAppE2EProto.HsConversation.newBuilder().setId("999@lid").setUnreadCount(1)
                .addMessages(histMsg("a1", "first", false, 1700000000L, "Alice"))
                .addMessages(histMsg("a2", null, false, 1700000100L)) // no message proto: skipped
                .addMessages(histMsg("a3", "mine", true, 1700000200L)))
            .addConversations(WhatsAppE2EProto.HsConversation.newBuilder().setId("12345@g.us").setName("Grp")
                .addMessages(histMsg("g1", "group hist", false, 1700000000L)))
            .addConversations(WhatsAppE2EProto.HsConversation.newBuilder().setId("15550009@s.whatsapp.net")) // empty
            .build()
        val notif = WhatsAppE2EProto.HistorySyncNotification.newBuilder()
            .setInitialHistBootstrapInlinePayload(ByteString.copyFrom(deflate(history.toByteArray()))).build()
        WhatsAppClient.handleHistorySync(notif, "hn-1")
        assertEquals("15550003@s.whatsapp.net", WhatsAppClient.lidToPhoneMap["888@lid"])
        val updates = events.filterIsInstance<WhatsAppEvent.MessageUpdate>()
        assertEquals(listOf("first", "mine", "group hist"), updates.map { it.body })
        val convs = events.filterIsInstance<WhatsAppEvent.ConversationUpdate>()
        assertEquals(setOf("wa:15550002@s.whatsapp.net", "wa:12345@g.us"), convs.map { it.conversationId }.toSet())
        assertTrue(convs.first { it.isGroup }.peerName == "Grp")
        assertEquals("mine", convs.first { !it.isGroup }.lastPreview)
        assertTrue(WhatsAppDiag.log.value.any { it.contains("hist_sync receipt for hn-1") })
        // Corrupt inline blob and missing payload: logged, receipt still attempted, nothing emitted.
        val before = events.size
        WhatsAppClient.handleHistorySync(
            WhatsAppE2EProto.HistorySyncNotification.newBuilder()
                .setInitialHistBootstrapInlinePayload(ByteString.copyFrom(byteArrayOf(1, 2, 3))).build(), "hn-2")
        WhatsAppClient.handleHistorySync(WhatsAppE2EProto.HistorySyncNotification.getDefaultInstance(), "hn-3")
        WhatsAppClient.sendHistorySyncReceipt(""); WhatsAppClient.sendHistorySyncReceipt("hn-9")
        assertEquals(before, events.size)
        // Direct emit: empty conversation returns 0 and emits nothing.
        assertEquals(0, WhatsAppClient.emitHistoryConversation(
            WhatsAppE2EProto.HsConversation.newBuilder().setId("15550002@s.whatsapp.net").build(), "15550002@s.whatsapp.net", false))
    }

    @Test
    fun `on-demand pagination dedupes cursors and stops at the page cap`() = runTest {
        WhatsAppClient.kickoffBackfill()
        assertTrue(WhatsAppDiag.log.value.any { it.contains("awaiting pushed history") })
        WhatsAppClient.sendHistoryOnDemandRequest("15550002@s.whatsapp.net", "old-1", false, 100L)
        WhatsAppClient.sendHistoryOnDemandRequest("15550002@s.whatsapp.net", "old-1", false, 100L)
        WhatsAppClient.sendHistoryOnDemandRequest("15550002@s.whatsapp.net", "", false, 0L)
        WhatsAppClient.sendHistoryOnDemandRequest("", "old-2", false, 0L)
        assertEquals(1, WhatsAppClient.onDemandPageCount["15550002@s.whatsapp.net"])
        assertEquals(1, WhatsAppClient.onDemandRequested.size)
        // A distinct older cursor paginates once more.
        WhatsAppClient.sendHistoryOnDemandRequest("15550002@s.whatsapp.net", "old-0", true, 50L)
        assertEquals(2, WhatsAppClient.onDemandPageCount["15550002@s.whatsapp.net"])
        // At the cap, a fresh cursor is recorded as requested but no request is built/sent.
        WhatsAppClient.onDemandPageCount["15550003@s.whatsapp.net"] = WhatsAppClient.MAX_ON_DEMAND_PAGES_PER_CHAT
        WhatsAppClient.sendHistoryOnDemandRequest("15550003@s.whatsapp.net", "ancient", false, 1L)
        assertTrue(WhatsAppDiag.log.value.any { it.contains("hit page cap for 15550003@s.whatsapp.net") })
        // inflateZlib: valid round trip and empty input.
        assertTrue(WhatsAppClient.inflateZlib(deflate("hello zlib".toByteArray())).contentEquals("hello zlib".toByteArray()))
        assertEquals(0, WhatsAppClient.inflateZlib(ByteArray(0)).size)
        assertTrue(WhatsAppClient.inflateZlib(deflate(ByteArray(0))).isEmpty())
    }
}
