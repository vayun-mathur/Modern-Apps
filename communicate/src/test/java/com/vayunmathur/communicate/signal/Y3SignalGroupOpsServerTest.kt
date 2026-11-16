package com.vayunmathur.communicate.signal

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.google.protobuf.ByteString
import com.sun.net.httpserver.HttpServer
import com.vayunmathur.communicate.data.signal.SignalAuthData
import com.vayunmathur.communicate.data.signal.SignalClient
import com.vayunmathur.communicate.data.signal.SignalConversation
import com.vayunmathur.communicate.data.signal.SignalDatabase
import com.vayunmathur.communicate.data.signal.SignalEvent
import com.vayunmathur.communicate.data.signal.SignalGroups
import com.vayunmathur.communicate.data.signal.SignalProtocol
import com.vayunmathur.communicate.data.signal.refreshGroup
import com.vayunmathur.communicate.data.signal.registration.SignalRegistrationKeys
import com.vayunmathur.communicate.data.signal.setGroupName
import com.vayunmathur.communicate.data.signal.transport.SignalGroupsApi
import com.vayunmathur.communicate.data.signal.updateGroupParticipants
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.time.Instant
import java.util.Collections
import java.util.UUID
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.libsignal.protocol.ServiceId
import org.signal.libsignal.zkgroup.ServerSecretParams
import org.signal.libsignal.zkgroup.auth.ServerZkAuthOperations
import org.signal.libsignal.zkgroup.groups.ClientZkGroupCipher
import org.signal.libsignal.zkgroup.groups.GroupSecretParams
import org.signal.libsignal.zkgroup.groups.UuidCiphertext
import org.signal.storageservice.storage.protos.groups.Group
import org.signal.storageservice.storage.protos.groups.GroupAttributeBlob
import org.signal.storageservice.storage.protos.groups.GroupChange
import org.signal.storageservice.storage.protos.groups.GroupResponse
import org.signal.storageservice.storage.protos.groups.Member

/**
 * Y3 group ops, server-accepted: a locally minted zkgroup server
 * ([ServerSecretParams.generate] + [ServerZkAuthOperations]) issues the
 * auth credential, `serverPublicParamsForTest` points the client's
 * verification at that server's public params, and chat (credentials) +
 * storage (group GET/PATCH) are scripted on localhost. The private
 * `groupChange`/`fetchGroupState` are covered through their public callers
 * [setGroupName], [updateGroupParticipants] and [refreshGroup], with real
 * asserts on DB rows, events and recorded requests.
 */
@OptIn(ExperimentalEncodingApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Y3SignalGroupOpsServerTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val selfAci = "123e4567-e89b-42d3-a456-426614174000"
    private val pniUuid = "123e4567-e89b-42d3-a456-426614174001"
    private val bobAci = "7c1d9b4e-aaaa-bbbb-cccc-ddddeeeeffff"
    private val carolAci = "123e4567-e89b-42d3-a456-426614174002"

    private lateinit var db: SignalDatabase
    private lateinit var authA: SignalAuthData
    private lateinit var serverSecret: ServerSecretParams
    private val servers = mutableListOf<HttpScript>()

    @BeforeTest fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ctx, SignalDatabase::class.java)
            .setDriver(BundledSQLiteDriver()).allowMainThreadQueries().build()
        authA = SignalRegistrationKeys.generate("+14155552671").authScaffold
            .copy(aci = selfAci, pni = "PNI:$pniUuid")
        SignalClient.db = db; SignalClient.authData = authA; SignalClient.e2e = null
        SignalClient.socket = null; SignalClient.unauthSocket = null; SignalClient.appContext = null
        // Local zkgroup server: client verification trusts THIS server's params.
        serverSecret = ServerSecretParams.generate()
        SignalGroupsApi.serverPublicParamsForTest = serverPublicParamsBytes(serverSecret)
    }
    @AfterTest fun tearDown() {
        SignalGroupsApi.storageBaseForTest = null
        SignalGroupsApi.chatBaseForTest = null
        SignalGroupsApi.serverPublicParamsForTest = null
        servers.forEach { it.close() }; servers.clear()
        SignalClient.db = null; SignalClient.authData = null; SignalClient.e2e = null
        db.close()
    }

    // ---------- scripted HTTP ----------
    data class HReq(val method: String, val path: String, val query: String?, val headers: Map<String, String>, val body: ByteArray)
    data class HResp(val status: Int, val body: ByteArray, val type: String = "application/json") {
        constructor(status: Int, text: String) : this(status, text.encodeToByteArray())
    }
    class HttpScript(private val handler: (HReq) -> HResp) : AutoCloseable {
        val requests = Collections.synchronizedList(mutableListOf<HReq>())
        private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base: String get() = "http://127.0.0.1:${http.address.port}"
        init {
            http.createContext("/") { ex ->
                val req = HReq(ex.requestMethod, ex.requestURI.path, ex.requestURI.rawQuery,
                    ex.requestHeaders.mapValues { it.value.firstOrNull() ?: "" }, ex.requestBody.readBytes())
                requests.add(req)
                val resp = runCatching { handler(req) }.getOrElse { HResp(500, "handler: $it") }
                ex.responseHeaders.add("Content-Type", resp.type)
                ex.sendResponseHeaders(resp.status, resp.body.size.toLong())
                ex.responseBody.use { it.write(resp.body) }
            }; http.start()
        }
        fun header(req: HReq, name: String): String? =
            req.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        override fun close() = http.stop(0)
    }
    private fun http(handler: (HReq) -> HResp) = HttpScript(handler).also { servers.add(it) }

    private fun todayAligned(): Long { val now = System.currentTimeMillis() / 1000; return now - (now % 86_400) }

    /** Serialized ServerPublicParams for [secret], via whichever accessor this libsignal exposes. */
    private fun serverPublicParamsBytes(secret: ServerSecretParams): ByteArray {
        val method = secret.javaClass.methods.first {
            it.parameterCount == 0 && it.returnType.simpleName == "ServerPublicParams"
        }
        val params = method.invoke(secret)
        return params.javaClass.getMethod("serialize").invoke(params) as ByteArray
    }

    /** Credential for our own ACI/PNI at today's redemption boundary, from the local zk server. */
    private fun mintedCredential(): ByteArray =
        ServerZkAuthOperations(serverSecret).issueAuthCredentialWithPniZkc(
            SecureRandom(),
            ServiceId.Aci.parseFromString(selfAci),
            ServiceId.Pni(UUID.fromString(pniUuid)),
            Instant.ofEpochSecond(todayAligned()),
        ).serialize()

    private class World(val convId: String, val masterKey: ByteArray, val secretParams: GroupSecretParams)

    private fun world(name: String = "Before", revision: Int = 0, participants: String = bobAci): World {
        val (masterKey, _) = SignalGroups.generateMasterKeyAndSecretParams()
        val convId = SignalProtocol.toConversationId("", masterKey)
        kotlinx.coroutines.runBlocking {
            db.conversationDao().upsert(SignalConversation(chatId = convId, isGroup = true, name = name,
                participants = participants, groupMasterKey = masterKey, groupRevision = revision))
        }
        return World(convId, masterKey, GroupSecretParams(SignalGroups.secretParamsFor(masterKey)))
    }

    /** Chat serves the locally minted credential; storage is per-test. */
    private fun wire(storage: (HReq) -> HResp): Pair<HttpScript, HttpScript> {
        val credB64 = Base64.Default.encode(mintedCredential())
        val chat = http { req ->
            if (req.path == "/v1/certificate/auth/group")
                HResp(200, """{"credentials":[{"redemptionTime":${todayAligned()},"credential":"$credB64"}]}""")
            else HResp(404, "{}")
        }
        val store = http(storage)
        SignalGroupsApi.chatBaseForTest = chat.base
        SignalGroupsApi.storageBaseForTest = store.base
        return chat to store
    }

    // ---------- groupChange via setGroupName / updateGroupParticipants ----------

    @Test fun setGroupName_serverAccepted_updatesRowEmitsEventAndPatchesProto() = runTest {
        val w = world()
        var patch: HReq? = null
        val (chat, store) = wire { req ->
            if (req.method == "PATCH") { patch = req; HResp(200, byteArrayOf(), "application/x-protobuf") }
            else HResp(404, "{}")
        }
        val seen = mutableListOf<SignalEvent>()
        val job = launch(Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        assertTrue(SignalClient.setGroupName(w.convId, "After"))
        job.cancel()

        val row = db.conversationDao().getConversation(w.convId)!!
        assertEquals("After", row.name); assertEquals(1, row.groupRevision)
        assertTrue(seen.any { it is SignalEvent.ConversationNameChanged && it.newName == "After" }, "$seen")
        // Credential dance really happened against both scripted servers.
        assertEquals(1, chat.requests.size)
        assertEquals("/v1/certificate/auth/group", chat.requests[0].path)
        assertTrue(chat.requests[0].query!!.contains("redemptionStartSeconds="))
        val p = assertNotNull(patch)
        assertEquals("/v2/groups/", p.path)
        assertTrue(store.header(p, "Authorization")!!.startsWith("Basic "))
        assertEquals("application/x-protobuf", store.header(p, "Content-Type"))
        // PATCH body is the encrypted title change at the new revision.
        val actions = GroupChange.Actions.parseFrom(p.body)
        assertEquals(1, actions.version); assertTrue(actions.hasModifyTitle())
        val blob = GroupAttributeBlob.parseFrom(
            ClientZkGroupCipher(w.secretParams).decryptBlob(actions.modifyTitle.title.toByteArray()))
        assertEquals("After", blob.title)
    }

    @Test fun setGroupName_serverRejectedPatch_keepsRowButServerWasAsked() = runTest {
        val w = world()
        val (_, store) = wire { HResp(403, byteArrayOf(), "application/x-protobuf") }
        assertFalse(SignalClient.setGroupName(w.convId, "After"))
        val row = db.conversationDao().getConversation(w.convId)!!
        assertEquals("Before", row.name); assertEquals(0, row.groupRevision)
        // Rejection is now a real server round-trip, not an offline failure.
        assertTrue(store.requests.any { it.method == "PATCH" && it.path == "/v2/groups/" })
    }

    @Test fun updateGroupParticipants_addAccepted_emitsEventAndPatchesMember() = runTest {
        val w = world()
        var patch: HReq? = null
        wire { req ->
            if (req.method == "PATCH") { patch = req; HResp(200, byteArrayOf(), "application/x-protobuf") }
            else HResp(404, "{}")
        }
        val seen = mutableListOf<SignalEvent>()
        val job = launch(Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        assertTrue(SignalClient.updateGroupParticipants(w.convId, listOf(carolAci), "add"))
        job.cancel()
        assertTrue(seen.any { it is SignalEvent.ParticipantAdded && it.participantId == carolAci }, "$seen")
        val actions = GroupChange.Actions.parseFrom(assertNotNull(patch).body)
        assertEquals(1, actions.version); assertEquals(1, actions.addMembersCount)
        val member = actions.getAddMembers(0).added
        assertEquals(Member.Role.DEFAULT, member.role)
        val decrypted = ClientZkGroupCipher(w.secretParams)
            .decrypt(UuidCiphertext(member.userId.toByteArray()))
        assertTrue(decrypted.toString().contains(carolAci), "$decrypted")
    }

    @Test fun updateGroupParticipants_addRejected_emitsNothingAndKeepsServerVerdict() = runTest {
        val w = world()
        val (_, store) = wire { HResp(409, byteArrayOf(), "application/x-protobuf") }
        val seen = mutableListOf<SignalEvent>()
        val job = launch(Dispatchers.Unconfined) { SignalClient.events.collect { seen.add(it) } }
        assertFalse(SignalClient.updateGroupParticipants(w.convId, listOf(carolAci), "add"))
        job.cancel()
        assertTrue(seen.none { it is SignalEvent.ParticipantAdded }, "$seen")
        assertTrue(store.requests.any { it.method == "PATCH" })
    }

    // ---------- fetchGroupState via refreshGroup ----------

    @Test fun refreshGroup_serverAccepted_fetchesStateAndUpdatesRow() = runTest {
        val w = world(name = "Old", revision = 0)
        val cipher = ClientZkGroupCipher(w.secretParams)
        val titleBlob = GroupAttributeBlob.newBuilder().setTitle("Fresh title").build().toByteArray()
        val group = Group.newBuilder()
            .setTitle(ByteString.copyFrom(cipher.encryptBlob(titleBlob))).setVersion(12)
            .addMembers(Member.newBuilder().setUserId(ByteString.copyFrom(
                cipher.encrypt(ServiceId.Aci.parseFromString(bobAci)).serialize())).build())
            .addMembers(Member.newBuilder().setUserId(ByteString.copyFrom(
                cipher.encrypt(ServiceId.Aci.parseFromString(carolAci)).serialize())).build())
            .build()
        val (chat, store) = wire { req ->
            if (req.method == "GET" && req.path == "/v2/groups/")
                HResp(200, GroupResponse.newBuilder().setGroup(group).build().toByteArray(), "application/x-protobuf")
            else HResp(404, "{}")
        }
        assertTrue(SignalClient.refreshGroup(w.convId))
        val row = db.conversationDao().getConversation(w.convId)!!
        assertEquals("Fresh title", row.name); assertEquals(12, row.groupRevision)
        assertTrue(row.participants.contains(bobAci), row.participants)
        assertTrue(row.participants.contains(carolAci), row.participants)
        assertTrue(chat.requests.any { it.path == "/v1/certificate/auth/group" })
        val get = store.requests.single { it.method == "GET" }
        assertEquals("/v2/groups/", get.path)
        assertTrue(store.header(get, "Authorization")!!.startsWith("Basic "))
    }

    @Test fun refreshGroup_storageNotFound_keepsRowAfterRealFetch() = runTest {
        val w = world(name = "Old", revision = 3)
        val (_, store) = wire { HResp(404, byteArrayOf(), "application/x-protobuf") }
        assertFalse(SignalClient.refreshGroup(w.convId))
        val row = db.conversationDao().getConversation(w.convId)!!
        assertEquals("Old", row.name); assertEquals(3, row.groupRevision)
        assertEquals(bobAci, row.participants)
        assertTrue(store.requests.any { it.method == "GET" && it.path == "/v2/groups/" })
    }

    @Test fun refreshGroup_malformedGroupBytes_keepsRow() = runTest {
        val w = world(name = "Old", revision = 1)
        wire { HResp(200, byteArrayOf(-1, -1, -1), "application/x-protobuf") }
        assertFalse(SignalClient.refreshGroup(w.convId))
        val row = db.conversationDao().getConversation(w.convId)!!
        assertEquals("Old", row.name); assertEquals(1, row.groupRevision)
    }
}
