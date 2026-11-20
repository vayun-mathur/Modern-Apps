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
import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol
import com.vayunmathur.communicate.data.whatsapp.appStateVersion
import com.vayunmathur.communicate.data.whatsapp.downloadAppStateBlob
import com.vayunmathur.communicate.data.whatsapp.e2e.RustWhatsAppCrypto
import com.vayunmathur.communicate.data.whatsapp.e2e.WhatsAppE2E
import com.vayunmathur.communicate.data.whatsapp.expandAppStateKeys
import com.vayunmathur.communicate.data.whatsapp.fetchAppStateCollection
import com.vayunmathur.communicate.data.whatsapp.decodeNode
import com.vayunmathur.communicate.data.whatsapp.encodeNode
import com.vayunmathur.communicate.data.whatsapp.handleIncomingMessage
import com.vayunmathur.communicate.data.whatsapp.proto.WhatsAppAppStateProto
import com.vayunmathur.communicate.data.whatsapp.setAppStateVersion
import com.vayunmathur.communicate.data.whatsapp.storeAppStateKey
import com.vayunmathur.communicate.data.whatsapp.transport.WhatsAppSocket
import java.io.ByteArrayOutputStream
import java.util.Collections
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

/**
 * Y3: app-state page application (`applyAppStatePage` / `applyAppStatePatches`,
 * driven through the internal `fetchAppStateCollection` because the page
 * functions are file-private) plus the `WhatsAppClient` lifecycle surface
 * (concat / stop / init / forceResync / network monitor / mexCall /
 * mediaConn / refreshPresence) over the connected fake socket from
 * [Y2WaSocketSendTest]'s seam. IQ waits are completed by feeding crafted
 * IQ-result stanzas through `handleIncomingMessage`.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
class Y3AppStatePageTest {

    companion object {
        fun b64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)
        fun unb64(s: String): ByteArray = Base64.decode(s, Base64.NO_WRAP)
        fun authFor(idSeed: Int, spkSeed: Int, wid: String, lid: String): WhatsAppAuthData {
            val idPriv = ByteArray(32) { (idSeed + it).toByte() }
            val idPub = RustWhatsAppCrypto.publicFromPrivate(idPriv)!!
            val spkPriv = ByteArray(32) { (spkSeed + it).toByte() }
            val spkPub = RustWhatsAppCrypto.publicFromPrivate(spkPriv)!!
            return WhatsAppAuthData(
                phoneNumber = "+15550001", pushName = "Tester", wid = wid,
                noisePrivateKey = b64(ByteArray(32) { 1 }), noisePublicKey = b64(ByteArray(32) { 2 }),
                identityPrivateKey = b64(idPriv), identityPublicKey = b64(idPub),
                registrationId = 14, signedPreKeyId = 5,
                signedPreKeyPublic = b64(spkPub), signedPreKeyPrivate = b64(spkPriv),
                signedPreKeySignature = b64(WhatsAppE2E.signSignedPreKey(idPriv, spkPub)), lid = lid,
            )
        }
        val AUTH: WhatsAppAuthData by lazy { authFor(1, 41, "15550001@s.whatsapp.net", "12345@lid") }
        const val PEER_JID = "15550002@s.whatsapp.net"
    }

    /** A WhatsAppSocket armed via reflection; captured frames decrypt to nodes. */
    private class FakeSocket(auth: WhatsAppAuthData) {
        val socket = WhatsAppSocket(auth)
        private val out = ByteArrayOutputStream()
        private val decryptor: Any
        init {
            val key = SecretKeySpec(ByteArray(32) { 42 }, "AES")
            val noiseClass = Class.forName(
                "com.vayunmathur.communicate.data.whatsapp.transport.WhatsAppSocket\$NoiseSocket")
            val ctor = noiseClass.getDeclaredConstructor(
                SecretKeySpec::class.java, SecretKeySpec::class.java).apply { isAccessible = true }
            val noise = ctor.newInstance(key, key)
            decryptor = ctor.newInstance(key, key)
            setField(socket, "noiseSocket", noise)
            setField(socket, "output", out)
            setField(socket, "isConnected", true)
            setField(socket, "isHandshakeComplete", true)
        }
        fun drainNodes(): List<WhatsAppProtocol.Node> {
            val bytes = synchronized(out) { val b = out.toByteArray(); out.reset(); b }
            val plain = mutableListOf<ByteArray>()
            var off = 0
            val decrypt = decryptor.javaClass.getDeclaredMethod("decrypt", ByteArray::class.java)
                .apply { isAccessible = true }
            while (off + 3 <= bytes.size) {
                val len = ((bytes[off].toInt() and 0xFF) shl 16) or
                    ((bytes[off + 1].toInt() and 0xFF) shl 8) or (bytes[off + 2].toInt() and 0xFF)
                val frame = bytes.copyOfRange(off + 3, off + 3 + len)
                plain.add(decrypt.invoke(decryptor, frame) as ByteArray)
                off += 3 + len
            }
            return plain.map { WhatsAppProtocol.decodeNode(it) }
        }
        companion object {
            fun setField(target: Any, name: String, value: Any?) {
                val f = target.javaClass.getDeclaredField(name).apply { isAccessible = true }
                f.set(target, value)
            }
        }
    }

    private lateinit var db: WhatsAppDatabase
    private lateinit var fake: FakeSocket
    private val events = Collections.synchronizedList(mutableListOf<WhatsAppEvent>())
    private val sentNodes = Collections.synchronizedList(mutableListOf<WhatsAppProtocol.Node>())

    private fun setClientField(name: String, value: Any?) {
        val f = WhatsAppClient.javaClass.getDeclaredField(name).apply { isAccessible = true }
        f.set(WhatsAppClient, value)
    }

    @BeforeTest
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, WhatsAppDatabase::class.java)
            .setSingleConnectionPool().allowMainThreadQueries().build()
        WhatsAppClient.appContext = app
        WhatsAppClient.authData = AUTH
        WhatsAppClient.db = db
        setClientField("e2e", null)
        setClientField("mediaConnCache", null)
        fake = FakeSocket(AUTH)
        WhatsAppClient.webSocket = fake.socket
        WhatsAppClient.stateMutable.value = WhatsAppClient.State.Connected
        WhatsAppClient.suppressReconnect = false
        WhatsAppClient.nameCache.clear(); WhatsAppClient.lidToPhoneMap.clear()
        WhatsAppClient.pendingIqs.clear(); WhatsAppClient.appStateCollectionsFetching.clear()
        WhatsAppClient.appStateKeyRequested.clear()
        WhatsAppDiag.clear(); events.clear(); sentNodes.clear()
        CoroutineScope(Dispatchers.Default + SupervisorJob()).async {
            WhatsAppClient.events.collect { events.add(it) }
        }
        runBlocking { db.conversationDao().upsert(WhatsAppConversation(chatJid = PEER_JID)) }
    }

    @AfterTest
    fun tearDown() {
        WhatsAppClient.setAppStateVersion("regular", 0L)
        runCatching { db.close() }
    }

    // ---------- app-state record crafting (mirrors Y1WaE2eSyncTest) ----------

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

    private fun actionRecord(
        keyId: ByteArray, keyData: ByteArray, indexJson: String,
        value: WhatsAppAppStateProto.SyncActionValue,
    ): WhatsAppAppStateProto.SyncdRecord {
        val action = WhatsAppAppStateProto.SyncActionData.newBuilder()
            .setIndex(ByteString.copyFrom(indexJson.toByteArray())).setValue(value).build()
        return encryptedRecord(keyId, keyData, action.toByteArray())
    }

    private fun mutation(
        record: WhatsAppAppStateProto.SyncdRecord, remove: Boolean = false,
    ): WhatsAppAppStateProto.SyncdMutation =
        WhatsAppAppStateProto.SyncdMutation.newBuilder()
            .setOperation(
                if (remove) WhatsAppAppStateProto.SyncdMutation.SyncdOperation.REMOVE
                else WhatsAppAppStateProto.SyncdMutation.SyncdOperation.SET)
            .setRecord(record).build()

    private fun patchBytes(
        version: Long, mutations: List<WhatsAppAppStateProto.SyncdMutation>,
        external: WhatsAppAppStateProto.ExternalBlobReference? = null,
    ): ByteArray {
        val b = WhatsAppAppStateProto.SyncdPatch.newBuilder()
            .setVersion(WhatsAppAppStateProto.SyncdVersion.newBuilder().setVersion(version))
        mutations.forEach { b.addMutations(it) }
        if (external != null) b.setExternalMutations(external)
        return b.build().toByteArray()
    }

    // ---------- IQ plumbing ----------

    private fun iqResult(
        req: WhatsAppProtocol.Node, content: List<WhatsAppProtocol.Node> = emptyList(), type: String = "result",
    ) = WhatsAppProtocol.Node(
        tag = "iq",
        attrs = mapOf("id" to (req.attrs["id"] ?: ""), "type" to type, "from" to "s.whatsapp.net"),
        content = content,
    )

    private fun collectionPage(
        name: String, hasMore: Boolean,
        patchDatas: List<ByteArray> = emptyList(),
        snapshotData: ByteArray? = null,
    ): List<WhatsAppProtocol.Node> {
        val coll = WhatsAppProtocol.Node(
            tag = "collection",
            attrs = mapOf("name" to name, "has_more_patches" to hasMore.toString()),
            content = listOfNotNull(
                snapshotData?.let { WhatsAppProtocol.Node(tag = "snapshot", data = it) },
                if (patchDatas.isEmpty()) null else WhatsAppProtocol.Node(
                    tag = "patches",
                    content = patchDatas.map { WhatsAppProtocol.Node(tag = "patch", data = it) },
                ),
            ),
        )
        return listOf(WhatsAppProtocol.Node(tag = "sync", content = listOf(coll)))
    }

    /** Run [block] while answering every stanza it sends from [responder]. */
    private fun <T> drive(responder: (WhatsAppProtocol.Node) -> WhatsAppProtocol.Node?, block: suspend () -> T): T {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val deferred = scope.async { block() }
        val deadline = System.currentTimeMillis() + 30_000
        while (!deferred.isCompleted) {
            val nodes = fake.drainNodes()
            if (nodes.isNotEmpty()) {
                sentNodes.addAll(nodes)
                nodes.forEach { n -> responder(n)?.let { WhatsAppClient.handleIncomingMessage(WhatsAppProtocol.encodeNode(it)) } }
            }
            if (System.currentTimeMillis() > deadline) {
                deferred.cancel()
                error("drive timeout; sent=${sentNodes.map { it.tag to it.attrs }} diag=${WhatsAppDiag.log.value.takeLast(6)}")
            }
            Thread.sleep(15)
        }
        sentNodes.addAll(fake.drainNodes())
        return runBlocking { deferred.await() }
    }

    private fun syncRequests(): List<WhatsAppProtocol.Node> = synchronized(sentNodes) {
        sentNodes.filter { it.tag == "iq" && it.attrs["xmlns"] == "w:sync:app:state" }
    }

    // ---------- applyAppStatePage / applyAppStatePatches via fetchAppStateCollection ----------

    @Test
    fun `app state pages paginate inline patches and persist the final version`() {
        val keyId = byteArrayOf(7, 7)
        val keyData = ByteArray(32) { (it * 3 + 5).toByte() }
        WhatsAppClient.storeAppStateKey(keyId, keyData)
        val mute = actionRecord(keyId, keyData, """["mute","$PEER_JID"]""",
            WhatsAppAppStateProto.SyncActionValue.newBuilder().setMuteAction(
                WhatsAppAppStateProto.MuteAction.newBuilder().setMuteEndTimestamp(111L)).build())
        val pin = actionRecord(keyId, keyData, """["pin_v1","$PEER_JID"]""",
            WhatsAppAppStateProto.SyncActionValue.newBuilder().setPinAction(
                WhatsAppAppStateProto.PinAction.newBuilder().setPinned(true)).build())
        val contact = actionRecord(keyId, keyData, """["contact","$PEER_JID"]""",
            WhatsAppAppStateProto.SyncActionValue.newBuilder().setContactAction(
                WhatsAppAppStateProto.ContactAction.newBuilder().setFullName("Should Not Apply")).build())

        var page = 0
        drive({ req ->
            val resp: WhatsAppProtocol.Node = when {
                req.attrs["xmlns"] == "w:m" -> iqResult(req, listOf(WhatsAppProtocol.Node(
                    tag = "media_conn", attrs = mapOf("auth" to "tok", "ttl" to "300"),
                    content = listOf(WhatsAppProtocol.Node(tag = "host", attrs = mapOf("hostname" to "127.0.0.1:9"))))))
                req.attrs["xmlns"] != "w:sync:app:state" -> iqResult(req)
                else -> {
                    page++
                    if (page == 1) {
                        iqResult(req, collectionPage("regular", hasMore = true,
                            patchDatas = listOf(patchBytes(5L, listOf(mutation(mute))))))
                    } else {
                        iqResult(req, collectionPage("regular", hasMore = false,
                            patchDatas = listOf(patchBytes(9L, listOf(mutation(pin), mutation(contact, remove = true))))))
                    }
                }
            }
            resp
        }) { WhatsAppClient.fetchAppStateCollection("regular", fullSync = true) }

        // Two pages were requested: snapshot first, then a versioned follow-up.
        val reqs = syncRequests()
        assertEquals(2, reqs.size)
        val first = reqs[0].getChildByTag("sync")!!.getChildByTag("collection")!!
        assertEquals("true", first.attrs["return_snapshot"])
        assertNull(first.attrs["version"])
        val second = reqs[1].getChildByTag("sync")!!.getChildByTag("collection")!!
        assertEquals("false", second.attrs["return_snapshot"])
        assertEquals("5", second.attrs["version"])

        // SET mutations landed; the REMOVE mutation was dropped by applyAppStateRecord.
        val row = runBlocking { db.conversationDao().getConversation(PEER_JID) }!!
        assertEquals(111L, row.muteEndTime)
        assertTrue(row.pinned)
        assertNull(WhatsAppClient.nameCache[PEER_JID])
        assertEquals(9L, WhatsAppClient.appStateVersion("regular"))
        assertTrue(WhatsAppClient.appStateCollectionsFetching.isEmpty())
        assertTrue(WhatsAppDiag.log.value.any { it.contains("regular synced to v9") }, "${WhatsAppDiag.log.value}")
    }

    @Test
    fun `snapshot download failure and external mutations failure still advance the patch version`() {
        val keyId = byteArrayOf(8, 8)
        val keyData = ByteArray(32) { (it * 5 + 2).toByte() }
        WhatsAppClient.storeAppStateKey(keyId, keyData)
        val mute = actionRecord(keyId, keyData, """["mute","$PEER_JID"]""",
            WhatsAppAppStateProto.SyncActionValue.newBuilder().setMuteAction(
                WhatsAppAppStateProto.MuteAction.newBuilder().setMuteEndTimestamp(222L)).build())
        val emptyRef = WhatsAppAppStateProto.ExternalBlobReference.newBuilder()
            .setDirectPath("").build()

        drive({ req ->
            if (req.attrs["xmlns"] == "w:sync:app:state") {
                iqResult(req, collectionPage(
                    "regular", hasMore = false,
                    patchDatas = listOf(patchBytes(7L, listOf(mutation(mute)), external = emptyRef)),
                    snapshotData = emptyRef.toByteArray(),
                ))
            } else iqResult(req)
        }) { WhatsAppClient.fetchAppStateCollection("regular", fullSync = true) }

        // The patch carried externalMutations, so its inline mutation list was
        // replaced by the (failed) blob download: nothing applied, but the
        // patch version still became the collection version.
        val row = runBlocking { db.conversationDao().getConversation(PEER_JID) }!!
        assertEquals(0L, row.muteEndTime)
        assertEquals(7L, WhatsAppClient.appStateVersion("regular"))
        assertTrue(
            WhatsAppDiag.log.value.any { it.contains("snapshot blob download FAILED") },
            "${WhatsAppDiag.log.value}",
        )
    }

    @Test
    fun `resumed sync sends the stored version and the in-flight guard suppresses duplicates`() {
        WhatsAppClient.setAppStateVersion("regular", 41L)
        drive({ req ->
            if (req.attrs["xmlns"] == "w:sync:app:state") {
                iqResult(req, collectionPage("regular", hasMore = false))
            } else iqResult(req)
        }) { WhatsAppClient.fetchAppStateCollection("regular", fullSync = false) }

        val reqs = syncRequests()
        assertEquals(1, reqs.size)
        val coll = reqs[0].getChildByTag("sync")!!.getChildByTag("collection")!!
        assertEquals("false", coll.attrs["return_snapshot"])
        assertEquals("41", coll.attrs["version"])
        assertEquals(41L, WhatsAppClient.appStateVersion("regular"))

        // A collection already being fetched is not refetched (no new IQ).
        WhatsAppClient.appStateCollectionsFetching.add("regular_high")
        val before = syncRequests().size
        runBlocking { WhatsAppClient.fetchAppStateCollection("regular_high", fullSync = true) }
        assertEquals(before, syncRequests().size)
        assertTrue(WhatsAppClient.appStateCollectionsFetching.contains("regular_high"))
        WhatsAppClient.appStateCollectionsFetching.remove("regular_high")
    }

    @Test
    fun `app state blob download needs a media host and an empty path never reaches the network`() {
        // Empty direct path: null without any stanza at all.
        assertNull(runBlocking {
            WhatsAppClient.downloadAppStateBlob(WhatsAppAppStateProto.ExternalBlobReference.getDefaultInstance())
        })
        assertTrue(synchronized(sentNodes) { sentNodes.isEmpty() })

        // With a media host cached via the w:m IQ, a blob pointing at a dead
        // https endpoint still fails soft to null (decrypt/download layer).
        drive({ req ->
            if (req.attrs["xmlns"] == "w:m") {
                iqResult(req, listOf(WhatsAppProtocol.Node(
                    tag = "media_conn", attrs = mapOf("auth" to "tok123", "ttl" to "300"),
                    content = listOf(WhatsAppProtocol.Node(tag = "host", attrs = mapOf("hostname" to "127.0.0.1:9"))))))
            } else iqResult(req)
        }) { WhatsAppClient.mediaConn() }
        val ext = WhatsAppAppStateProto.ExternalBlobReference.newBuilder()
            .setDirectPath("/mm/app-state?t=1")
            .setMediaKey(ByteString.copyFrom(ByteArray(32) { 9 }))
            .setFileEncSha256(ByteString.copyFrom(ByteArray(32) { 3 }))
            .build()
        assertNull(runBlocking { WhatsAppClient.downloadAppStateBlob(ext) })
    }

    // ---------- WhatsAppClient lifecycle: concat / init / stop / forceResync / mex / presence ----------

    @Test
    fun `concat joins parts in order including empties`() {
        val m = WhatsAppClient.javaClass.getDeclaredMethod("concat", Array<ByteArray>::class.java)
            .apply { isAccessible = true }
        val joined = m.invoke(WhatsAppClient, arrayOf(byteArrayOf(1, 2), ByteArray(0), byteArrayOf(3))) as ByteArray
        assertTrue(joined.contentEquals(byteArrayOf(1, 2, 3)))
        val empty = m.invoke(WhatsAppClient, arrayOf<ByteArray>()) as ByteArray
        assertEquals(0, empty.size)
    }

    @OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)
    private fun setInitialized(v: Boolean) {
        val current = WhatsAppClient.initialized.load()
        if (current != v) WhatsAppClient.initialized.compareAndSet(current, v)
    }

    @Test
    fun `stop before init is a no-op and forceResync without a session is ignored`() {
        val wasInitialized = WhatsAppClient.initialized.load()
        try {
            setInitialized(false)
            WhatsAppClient.stateMutable.value = WhatsAppClient.State.Idle
            WhatsAppClient.stop() // never touches appContext/DAOs before init
            assertEquals(WhatsAppClient.State.Idle, WhatsAppClient.stateMutable.value)

            // With init done but no saved session, forceResync logs and stops.
            setInitialized(true)
            WhatsAppClient.authData = null
            WhatsAppClient.forceResync()
            assertTrue(WhatsAppDiag.log.value.any { it.contains("forceResync: no session") },
                "${WhatsAppDiag.log.value}")
        } finally {
            setInitialized(wasInitialized)
            WhatsAppClient.authData = AUTH
            WhatsAppClient.stateMutable.value = WhatsAppClient.State.Connected
        }
    }

    @Test
    fun `init with a saved session connects and stop tears the monitor down`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        runBlocking { WhatsAppAuthData.save(app, AUTH) }
        // Route the client's database through the in-memory override so the
        // SQLCipher/KeyStore path (unavailable on this host) is not touched.
        WhatsAppDatabase.overrideForTest = db
        try {
            setInitialized(false)
            WhatsAppClient.init(app)
            // Saved auth loads asynchronously off the calling thread; await it.
            runBlocking { WhatsAppClient.awaitInitAuth() }
            // Saved auth: the client arms the network monitor and starts connecting.
            assertEquals(WhatsAppClient.State.Connecting, WhatsAppClient.stateMutable.value)
            assertEquals(AUTH.wid, WhatsAppClient.authData?.wid)
            // init is one-shot: a second call must not reset the state machine.
            WhatsAppClient.stateMutable.value = WhatsAppClient.State.Connected
            WhatsAppClient.init(app)
            assertEquals(WhatsAppClient.State.Connected, WhatsAppClient.stateMutable.value)

            WhatsAppClient.stop()
            assertEquals(WhatsAppClient.State.NeedsSetup, WhatsAppClient.stateMutable.value)
            assertNull(WhatsAppClient.webSocket)
        } finally {
            runBlocking { WhatsAppAuthData.clear(app) }
            WhatsAppDatabase.overrideForTest = null
            setInitialized(true)
            WhatsAppClient.authData = AUTH
            WhatsAppClient.webSocket = fake.socket
            WhatsAppClient.stateMutable.value = WhatsAppClient.State.Connected
        }
    }

    @Test
    fun `mexCall reports missing persisted ids and IQ errors as transport failures`() {
        val missing = runBlocking { WhatsAppClient.mexCall("NoSuchOperationY3", "{}") }
        assertFalse(missing.isSuccess)
        assertEquals("no_persisted_id:NoSuchOperationY3", missing.transportError)

        // QueryGroupInfo has a bundled doc_id, so the call reaches the socket;
        // an IQ error surfaces as iq_error:<code>, and no <result> as no_result.
        val errored = drive({ req ->
            if (req.attrs["xmlns"] == "w:mex") {
                iqResult(req, listOf(WhatsAppProtocol.Node(
                    tag = "error", attrs = mapOf("code" to "401"))), type = "error")
            } else iqResult(req)
        }) { WhatsAppClient.mexCall("QueryGroupInfo", "{\"group_id\":\"1@g.us\"}") }
        assertFalse(errored.isSuccess)
        assertEquals("iq_error:401", errored.transportError)

        val noResult = drive({ req ->
            if (req.attrs["xmlns"] == "w:mex") iqResult(req) else iqResult(req)
        }) { WhatsAppClient.mexCall("QueryGroupInfo", "{\"group_id\":\"1@g.us\"}") }
        assertEquals("no_result", noResult.transportError)

        // A well-formed result envelope parses into data.
        val ok = drive({ req ->
            if (req.attrs["xmlns"] == "w:mex") {
                iqResult(req, listOf(WhatsAppProtocol.Node(
                    tag = "result",
                    data = """{"data":{"xwa2_group_query_by_id":{"id":"1@g.us"}}}""".toByteArray())))
            } else iqResult(req)
        }) { WhatsAppClient.mexCall("QueryGroupInfo", "{\"group_id\":\"1@g.us\"}") }
        assertTrue(ok.isSuccess, "errors=${ok.errors} transport=${ok.transportError}")
        assertEquals("1@g.us", ok.data?.optJSONObject("xwa2_group_query_by_id")?.optString("id"))
    }

    @Test
    fun `mediaConn caches the host and refreshPresence drops unroutable ids silently`() {
        val conn = drive({ req ->
            if (req.attrs["xmlns"] == "w:m") {
                iqResult(req, listOf(WhatsAppProtocol.Node(
                    tag = "media_conn", attrs = mapOf("auth" to "tok123", "ttl" to "300"),
                    content = listOf(WhatsAppProtocol.Node(tag = "host", attrs = mapOf("hostname" to "mmg.example.net"))))))
            } else iqResult(req)
        }) { WhatsAppClient.mediaConn() }
        assertEquals("mmg.example.net" to "tok123", conn)

        // Second call is served from the cache: no further stanza is sent.
        val sentBefore = synchronized(sentNodes) { sentNodes.size }
        assertEquals(conn, runBlocking { WhatsAppClient.mediaConn() })
        assertEquals(sentBefore, synchronized(sentNodes) { sentNodes.size })

        // Presence: an id with no JID inside returns before any MEX traffic,
        // and a routable JID whose MEX op has no persisted id emits nothing.
        runBlocking { WhatsAppClient.refreshPresence("wa:") }
        runBlocking { WhatsAppClient.refreshPresence("wa:$PEER_JID") }
        assertTrue(events.filterIsInstance<WhatsAppEvent.PresenceUpdate>().isEmpty(), "$events")
    }
}
