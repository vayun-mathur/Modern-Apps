package com.vayunmathur.communicate.signal

import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.google.protobuf.ByteString
import com.sun.net.httpserver.HttpServer
import com.vayunmathur.communicate.data.signal.SignalAuthData
import com.vayunmathur.communicate.data.signal.SignalDatabase
import com.vayunmathur.communicate.data.signal.SignalGroups
import com.vayunmathur.communicate.data.signal.e2e.SignalE2E
import com.vayunmathur.communicate.data.signal.e2e.ensureLocalPreKeys
import com.vayunmathur.communicate.data.signal.registration.SignalRegistrationKeys
import com.vayunmathur.communicate.data.signal.transport.SignalAttachmentUpload
import com.vayunmathur.communicate.data.signal.transport.SignalCallingApi
import com.vayunmathur.communicate.data.signal.transport.SignalCdsi
import com.vayunmathur.communicate.data.signal.transport.SignalGroupsApi
import com.vayunmathur.communicate.data.signal.transport.SignalKeysApi
import java.net.InetSocketAddress
import java.util.Collections
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.signal.libsignal.zkgroup.groups.ClientZkGroupCipher
import org.signal.libsignal.zkgroup.groups.GroupSecretParams
import org.signal.storageservice.storage.protos.groups.ExternalGroupCredential
import org.signal.storageservice.storage.protos.groups.Group
import org.signal.storageservice.storage.protos.groups.GroupResponse
import org.signal.storageservice.storage.protos.groups.Member

/**
 * Y3: transport APIs against a scripted localhost HttpServer via the lead's
 * `*BaseForTest` seams. Responses are crafted from each parser's expectations;
 * assertions check returned values AND the recorded wire requests.
 */
@OptIn(ExperimentalEncodingApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Y3SignalServerApiTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val selfAci = "aaaaaaaa-1111-2222-3333-444455556666"
    private val bobAci = "7c1d9b4e-aaaa-bbbb-cccc-ddddeeeeffff"
    private val servers = mutableListOf<ScriptServer>()
    private val dbs = mutableListOf<SignalDatabase>()

    @BeforeTest fun setUp() { resetSeams() }
    @AfterTest fun tearDown() {
        resetSeams(); servers.forEach { it.close() }; servers.clear(); dbs.forEach { it.close() }; dbs.clear()
    }
    private fun resetSeams() {
        SignalGroupsApi.storageBaseForTest = null; SignalGroupsApi.chatBaseForTest = null
        SignalKeysApi.chatBaseForTest = null; SignalCdsi.chatBaseForTest = null
        SignalAttachmentUpload.chatBaseForTest = null; SignalCallingApi.chatBaseForTest = null
    }
    private fun server(handler: (Req) -> Resp): ScriptServer = ScriptServer(handler).also { servers.add(it) }
    private fun db(): SignalDatabase = Room.inMemoryDatabaseBuilder(ctx, SignalDatabase::class.java)
        .setDriver(BundledSQLiteDriver()).allowMainThreadQueries().build().also { dbs.add(it) }

    data class Req(val method: String, val path: String, val query: String?, val headers: Map<String, String>, val body: ByteArray)
    data class Resp(val status: Int, val body: ByteArray, val type: String = "application/json") {
        constructor(status: Int, text: String) : this(status, text.encodeToByteArray())
    }
    class ScriptServer(private val handler: (Req) -> Resp) : AutoCloseable {
        val requests = Collections.synchronizedList(mutableListOf<Req>())
        private val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val base: String get() = "http://127.0.0.1:${http.address.port}"
        init {
            http.createContext("/") { ex ->
                val req = Req(ex.requestMethod, ex.requestURI.path, ex.requestURI.rawQuery,
                    ex.requestHeaders.mapValues { it.value.firstOrNull() ?: "" }, ex.requestBody.readBytes())
                requests.add(req)
                val resp = runCatching { handler(req) }.getOrElse { Resp(500, "handler: $it") }
                ex.responseHeaders.add("Content-Type", resp.type)
                ex.sendResponseHeaders(resp.status, resp.body.size.toLong())
                ex.responseBody.use { it.write(resp.body) }
            }; http.start()
        }
        override fun close() = http.stop(0)
    }

    private fun header(req: Req, name: String): String? =
        req.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
    private fun b64u(bytes: ByteArray): String = Base64.Default.encode(bytes).trimEnd('=')
    private fun aliceBob(): Triple<SignalAuthData, SignalAuthData, Pair<SignalE2E, SignalE2E>> {
        val authA = SignalRegistrationKeys.generate("+14155552671").authScaffold.copy(aci = selfAci)
        val authB = SignalRegistrationKeys.generate("+14155550000").authScaffold.copy(aci = bobAci)
        return Triple(authA, authB, SignalE2E(db(), authA) to SignalE2E(db(), authB))
    }
    /** Bob's real pre-key material as the JSON SignalKeysApi.parse expects. */
    private fun bobBundleJson(authB: SignalAuthData, bob: SignalE2E, deviceId: Int = 1): String {
        val up = bob.ensureLocalPreKeys()
        val signed = up.signedPreKey ?: error("signed")
        val kyber = up.lastResortKyber ?: error("kyber")
        val one = up.oneTimeEcPreKeys.firstOrNull()
        val identity = android.util.Base64.decode(authB.effectiveAciPublic(), android.util.Base64.NO_WRAP)
        val oneJson = one?.let { ""","preKey":{"keyId":${it.id},"publicKey":"${b64u(it.publicKey)}"}""" } ?: ""
        return """{"identityKey":"${b64u(identity)}","devices":[{"deviceId":$deviceId,""" +
            """"registrationId":${authB.effectiveAciRegId()},""" +
            """"signedPreKey":{"keyId":${signed.id},"publicKey":"${b64u(signed.publicKey)}","signature":"${b64u(signed.signature!!)}"},""" +
            """"pqPreKey":{"keyId":${kyber.id},"publicKey":"${b64u(kyber.publicKey)}","signature":"${b64u(kyber.signature!!)}"}$oneJson}]}"""
    }

    // ---------- SignalGroupsApi ----------

    @Test fun groups_fetchCredentials_successDayAlignedAnd4xxMalformed() = runTest {
        val cred = Base64.Default.encode(ByteArray(48) { 7 })
        val srv = server { req ->
            if (req.path == "/v1/certificate/auth/group") Resp(200, """{"credentials":[{"redemptionTime":1699963200,"credential":"$cred"},{"redemptionTime":1700049600,"credential":"$cred"}]}""")
            else Resp(404, "{}")
        }
        SignalGroupsApi.chatBaseForTest = srv.base
        val list = SignalGroupsApi.fetchCredentials("dGVzdA==", null, nowSeconds = 1_700_000_000L)
        assertEquals(listOf(1699963200L, 1700049600L), list.map { it.redemptionTimeSeconds })
        assertTrue(list.all { it.credential.contentEquals(ByteArray(48) { 7 }) })
        val req = srv.requests.single()
        assertEquals("GET", req.method)
        assertTrue(req.query!!.contains("redemptionStartSeconds=1699920000"), req.query)
        assertTrue(req.query.contains("redemptionEndSeconds=1700524800"), req.query)
        assertEquals("Basic dGVzdA==", req.headers["Authorization"])

        val bad = server { Resp(403, """{"error":"no"}""") }; SignalGroupsApi.chatBaseForTest = bad.base
        assertTrue(SignalGroupsApi.fetchCredentials("x", null, nowSeconds = 1_700_000_000L).isEmpty())
        val junk = server { Resp(200, "not json") }; SignalGroupsApi.chatBaseForTest = junk.base
        assertTrue(SignalGroupsApi.fetchCredentials("x", null, nowSeconds = 1_700_000_000L).isEmpty())
    }

    @Test fun groups_fetchGroup_wrappedDirectMalformedAnd4xx() = runTest {
        val group = Group.newBuilder().setVersion(9)
            .addMembers(Member.newBuilder().setUserId(ByteString.copyFrom(byteArrayOf(1, 2, 3))).setRole(Member.Role.DEFAULT).build())
            .build()
        var mode = "wrapped"
        val srv = server {
            when (mode) {
                "wrapped" -> Resp(200, GroupResponse.newBuilder().setGroup(group).build().toByteArray(), "application/x-protobuf")
                "direct" -> Resp(200, group.toByteArray(), "application/x-protobuf")
                "bad" -> Resp(200, byteArrayOf(-1, -1, -1, -1), "application/x-protobuf")
                else -> Resp(404, byteArrayOf(), "application/x-protobuf")
            }
        }
        SignalGroupsApi.storageBaseForTest = srv.base
        val fetched = SignalGroupsApi.fetchGroup("authz", null)
        assertNotNull(fetched); assertEquals(9, fetched.version); assertEquals(1, fetched.membersCount)
        assertEquals("/v2/groups/", srv.requests.last().path)
        assertEquals("Basic authz", srv.requests.last().headers["Authorization"])
        mode = "direct"; assertNotNull(SignalGroupsApi.fetchGroup("authz", null)) // wrapper-first parse: direct bytes parse as a GroupResponse
        mode = "bad"; assertNull(SignalGroupsApi.fetchGroup("authz", null))
        mode = "missing"; assertNull(SignalGroupsApi.fetchGroup("authz", null))
    }

    @Test fun groups_patchGroup_acceptedRecordsProtoAndRejectedReturnsFalse() = runTest {
        var status = 200
        val srv = server { Resp(status, byteArrayOf(), "application/x-protobuf") }
        SignalGroupsApi.storageBaseForTest = srv.base
        val (masterKey, _) = SignalGroups.generateMasterKeyAndSecretParams()
        val secretParams = GroupSecretParams(SignalGroups.secretParamsFor(masterKey))
        val actions = SignalGroupsApi.titleChange(secretParams, "Renamed", 4)!!
        assertTrue(SignalGroupsApi.patchGroup("authz", actions, null))
        val req = srv.requests.single()
        assertEquals("PATCH", req.method); assertEquals("/v2/groups/", req.path)
        assertEquals("application/x-protobuf", header(req, "Content-Type"))
        assertTrue(req.body.contentEquals(actions.toByteArray()))
        status = 409; assertFalse(SignalGroupsApi.patchGroup("authz", actions, null))
    }

    @Test fun groups_fetchMembershipProof_successMalformedAnd4xx() = runTest {
        var mode = "ok"
        val srv = server {
            when (mode) {
                "ok" -> Resp(200, ExternalGroupCredential.newBuilder().setToken("proof-abc-123").build().toByteArray(), "application/x-protobuf")
                "bad" -> Resp(200, byteArrayOf(-2, -2), "application/x-protobuf")
                else -> Resp(404, byteArrayOf(), "application/x-protobuf")
            }
        }
        SignalGroupsApi.storageBaseForTest = srv.base
        assertEquals("proof-abc-123", SignalGroupsApi.fetchMembershipProof("authz", null)?.decodeToString())
        assertEquals("/v2/groups/token", srv.requests.last().path)
        mode = "bad"; assertNull(SignalGroupsApi.fetchMembershipProof("authz", null))
        mode = "missing"; assertNull(SignalGroupsApi.fetchMembershipProof("authz", null))
    }

    @Test fun groups_decryptFetchedGroupState_fromScriptedStorageBytes() = runTest {
        // End-to-end at API level: storage bytes -> fetchGroup -> decryptGroup.
        val (masterKey, _) = SignalGroups.generateMasterKeyAndSecretParams()
        val secretParams = GroupSecretParams(SignalGroups.secretParamsFor(masterKey))
        val cipher = ClientZkGroupCipher(secretParams)
        val titleBlob = org.signal.storageservice.storage.protos.groups.GroupAttributeBlob.newBuilder().setTitle("Hiking").build().toByteArray()
        val group = Group.newBuilder()
            .setTitle(ByteString.copyFrom(cipher.encryptBlob(titleBlob))).setVersion(7)
            .addMembers(Member.newBuilder().setUserId(ByteString.copyFrom(
                cipher.encrypt(org.signal.libsignal.protocol.ServiceId.Aci.parseFromString(bobAci)).serialize())).build())
            .build()
        val srv = server { Resp(200, GroupResponse.newBuilder().setGroup(group).build().toByteArray(), "application/x-protobuf") }
        SignalGroupsApi.storageBaseForTest = srv.base
        val state = SignalGroupsApi.decryptGroup(secretParams, SignalGroupsApi.fetchGroup("a", null)!!)!!
        assertEquals("Hiking", state.title); assertEquals(7, state.revision)
        assertEquals(1, state.memberAcis.size); assertTrue(state.memberAcis[0].contains(bobAci), state.memberAcis[0])
    }

    // ---------- SignalKeysApi ----------

    @Test fun keys_fetchPreKeys_validBundleEstablishesRealSession() = runTest {
        val (authA, authB, pair) = aliceBob(); val (alice, bob) = pair
        val srv = server { Resp(200, bobBundleJson(authB, bob)) }
        SignalKeysApi.chatBaseForTest = srv.base
        val bundles = SignalKeysApi.fetchPreKeys(bobAci, 1, "authhdr", null)
        assertEquals(1, bundles.size); assertEquals(1, bundles[0].deviceId)
        assertTrue(bundles[0].bundle.identityKey.contentEquals(
            android.util.Base64.decode(authB.effectiveAciPublic(), android.util.Base64.NO_WRAP)))
        assertEquals("/v2/keys/$bobAci/*", srv.requests.single().path)
        assertEquals("Basic authhdr", srv.requests.single().headers["Authorization"])
        // Real E2E store: session established, ciphertext decrypts on Bob's side.
        alice.processPreKeyBundle(bobAci, bundles[0].deviceId, bundles[0].bundle)
        assertTrue(alice.hasSession(bobAci, 1))
        val enc = alice.encryptDM(bobAci, 1, "y3 keys hello".toByteArray())
        assertEquals("y3 keys hello", bob.decryptDM(selfAci, 1, isPreKey = true, ciphertext = enc.data).decodeToString())
    }

    @Test fun keys_fetchPreKeys_404Throws500AndMalformedReturnEmpty() = runTest {
        var status = 404
        val srv = server { Resp(status, """{"error":"x"}""") }
        SignalKeysApi.chatBaseForTest = srv.base
        assertFailsWith<SignalKeysApi.UnregisteredUserException> { SignalKeysApi.fetchPreKeys(bobAci, 1, "a", null) }
        status = 500; assertTrue(SignalKeysApi.fetchPreKeys(bobAci, 1, "a", null).isEmpty())
        val junk = server { Resp(200, "%%%") }; SignalKeysApi.chatBaseForTest = junk.base
        assertTrue(SignalKeysApi.fetchPreKeys(bobAci, 2, "a", null, allDevices = false).isEmpty())
        assertEquals("/v2/keys/$bobAci/2", junk.requests.single().path)
    }

    @Test fun keys_uploadPreKeys_putsUnpaddedJsonAndHandlesRejection() = runTest {
        val (_, authB, pair) = aliceBob(); val bob = pair.second
        val up = bob.ensureLocalPreKeys()
        var status = 200
        val srv = server { Resp(status, "{}") }; SignalKeysApi.chatBaseForTest = srv.base
        assertTrue(SignalKeysApi.uploadPreKeys(up.signedPreKey, up.lastResortKyber, up.oneTimeEcPreKeys, "hdr", null))
        val req = srv.requests.single()
        assertEquals("PUT", req.method); assertEquals("/v2/keys", req.path); assertTrue(req.query!!.contains("identity=aci"))
        val json = org.json.JSONObject(req.body.decodeToString())
        assertEquals(up.signedPreKey!!.id, json.getJSONObject("signedPreKey").getInt("keyId"))
        assertFalse(json.getJSONObject("signedPreKey").getString("publicKey").contains("="))
        assertEquals(up.lastResortKyber!!.id, json.getJSONObject("pqLastResortPreKey").getInt("keyId"))
        assertEquals(up.oneTimeEcPreKeys.size, json.getJSONArray("preKeys").length())
        assertEquals(authB.deviceId, 1) // scaffold sanity, not wire
        status = 403; assertFalse(SignalKeysApi.uploadPreKeys(up.signedPreKey, null, emptyList(), "hdr", null))
    }

    // ---------- SignalCdsi / Attachment / Calling ----------

    @Test fun cdsi_fetchCredentials_parseSuccessAndRefusals() = runTest {
        var mode = "ok"
        val srv = server {
            when (mode) {
                "ok" -> Resp(200, """{"username":"cdsi-user","password":"cdsi-pass"}""")
                "bad" -> Resp(200, """{"username":"only"}""")
                else -> Resp(401, "{}")
            }
        }
        SignalCdsi.chatBaseForTest = srv.base
        val creds = SignalCdsi.fetchCredentials("hdr", null)!!
        assertEquals("cdsi-user", creds.username); assertEquals("cdsi-pass", creds.password)
        assertEquals("/v2/directory/auth", srv.requests.last().path)
        mode = "bad"; assertNull(SignalCdsi.fetchCredentials("hdr", null))
        mode = "no"; assertNull(SignalCdsi.fetchCredentials("hdr", null))
    }

    @Test fun attachment_fetchFormAndUpload_successRefusalAndBadCdn() = runTest {
        val cdn = server { req -> if (req.method == "POST") Resp(201, byteArrayOf()) else Resp(404, byteArrayOf()) }
        var formStatus = 200
        val chat = server {
            if (formStatus != 200) Resp(formStatus, "{}")
            else Resp(200, """{"cdn":3,"key":"cdn-key-9","signedUploadLocation":"${cdn.base}/upload/xyz","headers":{"x-amz-meta":"m1","host":"drop-me"}}""")
        }
        SignalAttachmentUpload.chatBaseForTest = chat.base
        val form = SignalAttachmentUpload.fetchForm(1234, "hdr", null)!!
        assertEquals(3, form.cdn); assertEquals("cdn-key-9", form.key)
        assertTrue(chat.requests.single().path == "/v4/attachments/form/upload")
        assertTrue(chat.requests.single().query!!.contains("uploadLength=1234"))
        val blob = ByteArray(64) { it.toByte() }
        assertTrue(SignalAttachmentUpload.upload(form, blob, null))
        val post = cdn.requests.single()
        assertEquals("POST", post.method); assertTrue(post.body.contentEquals(blob))
        assertEquals("1.0.0", header(post, "Tus-Resumable")); assertEquals("64", header(post, "Upload-Length"))
        assertEquals("m1", header(post, "x-amz-meta"))
        // CDN2 form is fetched but upload refuses it without touching the CDN.
        val chat2 = server { Resp(200, """{"cdn":2,"key":"k","signedUploadLocation":"${cdn.base}/upload/xyz","headers":{}}""") }
        SignalAttachmentUpload.chatBaseForTest = chat2.base
        val form2 = SignalAttachmentUpload.fetchForm(10, "hdr", null)!!
        val before = cdn.requests.size
        assertFalse(SignalAttachmentUpload.upload(form2, blob, null)); assertEquals(before, cdn.requests.size)
        formStatus = 404; SignalAttachmentUpload.chatBaseForTest = chat.base
        assertNull(SignalAttachmentUpload.fetchForm(10, "hdr", null))
    }

    @Test fun calling_fetchIceServers_successAndRefusals() = runTest {
        var mode = "ok"
        val srv = server {
            when (mode) {
                "ok" -> Resp(200, """{"relays":[{"username":"u1","password":"p1","hostname":"turn.example","urls":["turn:turn.example:3478"],"urlsWithIps":["turn:1.2.3.4:3478"]},{"username":"u2","password":"p2","urls":["stun:stun.example:3478"]}]}""")
                "bad" -> Resp(200, "junk")
                else -> Resp(500, "{}")
            }
        }
        SignalCallingApi.chatBaseForTest = srv.base
        val servers = SignalCallingApi.fetchIceServers("hdr", null)
        assertEquals(2, servers.size); assertEquals("u1", servers[0].username); assertEquals("p1", servers[0].password)
        assertTrue(servers[0].urls.any { it.contains("1.2.3.4") })
        assertEquals("/v2/calling/relays", srv.requests.last().path)
        mode = "bad"; assertTrue(SignalCallingApi.fetchIceServers("hdr", null).isEmpty())
        mode = "no"; assertTrue(SignalCallingApi.fetchIceServers("hdr", null).isEmpty())
    }
}
