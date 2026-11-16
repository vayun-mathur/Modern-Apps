package com.vayunmathur.office.util

import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.e2ee.E2ee
import com.vayunmathur.e2ee.E2eeKeyStore
import com.vayunmathur.e2ee.PqcIdentity
import com.vayunmathur.library.network.SimpleResponse
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.util.DataStoreUtils
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

/**
 * W4: end-to-end (fake-relay) share / open-online flows.
 *
 * The scripted transport serves registration, key lookup, appends and
 * per-channel pulls, so [OfficeViewModel.shareCurrentDocument],
 * [OfficeViewModel.openOnlineDocument], join-request approval, member
 * role changes and key rotation run their real success paths — including
 * CRDT op exchange — with no network.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class W4ShareOpenOnlineTest {

    private class MemStore : E2eeKeyStore {
        private val m = HashMap<String, ByteArray>()
        override suspend fun getBytes(name: String): ByteArray? = m[name]
        override suspend fun setBytes(name: String, value: ByteArray, onlyIfAbsent: Boolean) {
            if (onlyIfAbsent && m.containsKey(name)) return
            m[name] = value
        }
    }

    private val roboApp: android.app.Application
        get() = ApplicationProvider.getApplicationContext()

    private var previousTransport: OfficeSync.HttpTransport? = null
    private val rawChannels = mutableMapOf<String, List<String>>()
    private val appended = mutableListOf<Pair<String, List<String>>>()
    private var getKeyStatus = 200

    private fun channelOf(body: String): String =
        Regex(""""channel":"([^"]+)"""").find(body)?.groupValues?.get(1) ?: ""

    private fun resp(status: Int, body: String, url: String) =
        SimpleResponse(status, "", body, emptyMap(), url)

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        previousTransport = OfficeSync.httpTransport
        OfficeSync.httpTransport = OfficeSync.HttpTransport { url, body ->
            when {
                url.endsWith("/register") -> resp(200, "{}", url)
                url.endsWith("/getkey") ->
                    if (getKeyStatus == 200) resp(200, Base64.encode(OfficeSync.publicBundle), url)
                    else resp(getKeyStatus, "", url)
                url.endsWith("/append") -> {
                    val actions = Regex(""""actions":\[(.*?)\]""").find(body)?.groupValues?.get(1)
                        ?.let { Regex(""""([^"]*)"""").findAll(it).map { m -> m.groupValues[1] }.toList() }
                        ?: emptyList()
                    appended.add(channelOf(body) to actions)
                    resp(200, """{"seq":${appended.size}}""", url)
                }
                url.endsWith("/pull") -> {
                    val actions = rawChannels[channelOf(body)] ?: emptyList()
                    val jsonActions = actions.joinToString(",") { "\"$it\"" }
                    resp(200, """{"actions":[$jsonActions],"seq":${actions.size}}""", url)
                }
                else -> resp(404, "", url)
            }
        }
        runBlocking {
            if (runCatching { OfficeSync.identity }.isFailure) {
                OfficeSync.identity = PqcIdentity.loadOrCreate(MemStore(), "w4share")
            }
            OfficeSync.init(roboApp)
        }
    }

    @AfterTest
    fun tearDown() {
        OfficeSync.httpTransport = previousTransport
        OfficeSync.stopLive()
        Dispatchers.resetMain()
    }

    private fun loadedVm(text: String): OfficeViewModel {
        val v = OfficeViewModel(roboApp)
        v.stateMutable.value = OfficeViewModel.ViewState.Loaded(
            OdfDocument.TextDocument(
                title = "Doc",
                content = listOf(OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(text)))))))
        return v
    }

    private fun await(timeoutMs: Long = 15_000, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    private fun stopJobs(v: OfficeViewModel) {
        OfficeSync.stopLive()
        v.livePollJob?.cancel()
        v.presenceTickJob?.cancel()
        v.caretPresenceJob?.cancel()
        v.livePushJob?.cancel()
        v.currentTree?.close()
    }

    private fun aesBlob(key: ByteArray, plain: String): String =
        Base64.encode(E2ee.aesEncrypt(key, plain.encodeToByteArray()))

    // ---- shareCurrentDocument ----

    @Test
    fun `sharing a local document delivers a sealed invite`() {
        val v = loadedVm("shareable text")
        try {
            val latch = CountDownLatch(1)
            var error: String? = "unset"
            v.shareCurrentDocument("peer-device", docName = "My Shared Doc") { error = it; latch.countDown() }
            assertTrue(latch.await(15, TimeUnit.SECONDS), "share callback")
            assertNull(error)
            assertNotNull(v.currentDocId)
            assertEquals(OfficeRoles.OWNER, v.currentRole)
            // The invite went to the recipient's inbox channel, sealed to their bundle.
            val invite = appended.lastOrNull { it.first == "inbox:peer-device" }
            assertNotNull(invite)
            val opened = OfficeSync.identity.decrypt(Base64.decode(invite.second.single())).decodeToString()
            val decoded = OfficeSync.json.decodeFromString<OfficeSync.Invite>(opened)
            assertEquals(v.currentDocId, decoded.docId)
            assertEquals("My Shared Doc", decoded.title)
            // And the document is recorded in the online index.
            val index = runBlocking { v.loadIndex(DataStoreUtils.getInstance(roboApp)) }
            assertTrue(index.any { it.docId == v.currentDocId && it.owner })
        } finally {
            stopJobs(v)
        }
    }

    @Test
    fun `sharing with an unregistered recipient reports the reason`() {
        val v = loadedVm("orphan text")
        try {
            getKeyStatus = 404
            val latch = CountDownLatch(1)
            var error: String? = null
            v.shareCurrentDocument("ghost-device") { error = it; latch.countDown() }
            assertTrue(latch.await(15, TimeUnit.SECONDS), "share callback")
            assertNotNull(error)
            assertTrue(error!!.contains("registered", ignoreCase = true), error!!)
        } finally {
            getKeyStatus = 200
            stopJobs(v)
        }
    }

    // ---- openOnlineDocument ----

    private fun signedOpFor(v: OfficeViewModel, text: String, key: ByteArray): String {
        val author = loadedVm(text)
        val tree = DocumentTreeCrdt(OfficeSync.deviceId)
        try {
            val opsJson = tree.updateJson(author.exportFlat())
            val sig = Base64.encode(runBlocking { OfficeSync.sign(opsJson.encodeToByteArray()) })
            val signed = v.syncJson.encodeToString(SignedOp(OfficeSync.deviceId, sig, opsJson))
            return aesBlob(key, signed)
        } finally {
            tree.close()
        }
    }

    @Test
    fun `opening an online document adopts its content and title`() {
        val v = OfficeViewModel(roboApp)
        val key = ByteArray(32) { 12 }
        val docId = "online-doc-1"
        try {
            // Owner-signed rename waiting on the title channel.
            val titleSig = Base64.encode(runBlocking { OfficeSync.sign(v.titleSigningBytes(docId, "Shared Title")) })
            rawChannels["title:$docId"] = listOf(
                aesBlob(key, v.syncJson.encodeToString(SignedTitle("Shared Title", titleSig))))
            // The document's signed CRDT op on its own channel.
            rawChannels[docId] = listOf(signedOpFor(v, "shared hello", key))

            val meta = OfficeDocMeta(
                docId = docId, title = "Old Title", keyB64 = Base64.encode(key),
                owner = false, role = OfficeRoles.EDITOR,
                ownerKeyB64 = Base64.encode(OfficeSync.publicBundle))
            runBlocking { v.saveIndex(DataStoreUtils.getInstance(roboApp), listOf(meta)) }

            v.openOnlineDocument(meta)
            assertTrue(
                await { (v.state.value as? OfficeViewModel.ViewState.Loaded) != null },
                "state should load, was ${v.state.value}")
            val doc = (v.state.value as OfficeViewModel.ViewState.Loaded).document
            val text = (doc as OdfDocument.TextDocument).content
                .filterIsInstance<OdfContentBlock.Paragraph>()
                .joinToString("") { it.paragraph.spans.joinToString("") { s -> s.text } }
            assertTrue(text.contains("shared hello"), text)
            assertEquals(docId, v.currentDocId)
            assertTrue(v.isEditModeMutable.value, "editors open in edit mode")
            // The owner rename was folded into the local index.
            val index = runBlocking { v.loadIndex(DataStoreUtils.getInstance(roboApp)) }
            assertEquals("Shared Title", index.firstOrNull { it.docId == docId }?.title)
        } finally {
            stopJobs(v)
        }
    }

    @Test
    fun `opening an online document with no readable ops errors`() {
        val v = OfficeViewModel(roboApp)
        val key = ByteArray(32) { 13 }
        val docId = "online-doc-empty"
        try {
            rawChannels[docId] = listOf(aesBlob(key, "not a signed op"))
            val meta = OfficeDocMeta(
                docId = docId, title = "Empty", keyB64 = Base64.encode(key),
                owner = false, role = OfficeRoles.VIEWER,
                ownerKeyB64 = Base64.encode(OfficeSync.publicBundle))
            v.openOnlineDocument(meta)
            assertTrue(
                await { v.state.value is OfficeViewModel.ViewState.Error },
                "state should error, was ${v.state.value}")
            val message = (v.state.value as OfficeViewModel.ViewState.Error).message
            assertTrue(message.contains("no readable content", ignoreCase = true), message)
        } finally {
            stopJobs(v)
        }
    }

    // ---- approveJoinRequest / setMemberRole / securityCodeWith ----

    @Test
    fun `approving a join request invites the requester and clears the queue`() {
        val v = OfficeViewModel(roboApp)
        val key = ByteArray(32) { 14 }
        val docId = "owned-doc"
        try {
            val ds = DataStoreUtils.getInstance(roboApp)
            runBlocking {
                v.saveIndex(ds, listOf(OfficeDocMeta(
                    docId = docId, title = "Owned", keyB64 = Base64.encode(key), owner = true)))
                v.saveRequests(ds, listOf(OfficeSync.JoinRequest(docId = docId, requesterId = "req-9", name = "Nia")))
            }
            val latch = CountDownLatch(1)
            var ok: Boolean? = null
            v.approveJoinRequest(docId, "req-9", "Nia") { ok = it; latch.countDown() }
            assertTrue(latch.await(15, TimeUnit.SECONDS), "approve callback")
            assertEquals(true, ok)
            assertTrue(appended.any { it.first == "inbox:req-9" }, appended.toString())
            assertTrue(
                await { runBlocking { v.loadRequests(ds) }.isEmpty() },
                "pending request should be removed")
        } finally {
            stopJobs(v)
        }
    }

    @Test
    fun `set member role records the roster and revoking rotates the key`() = runTest {
        val v = loadedVm("roster text")
        val key = ByteArray(32) { 15 }
        v.currentDocId = "roster-doc"
        v.currentDocKey = key
        v.currentRole = OfficeRoles.OWNER
        v.currentOwnerKey = OfficeSync.publicBundle
        v.memberKeyCache["member-1"] = OfficeSync.publicBundle
        // Roster channel: one surviving member so rotation seals a wrap for them.
        val member = OfficeMember("member-1", "Mel", OfficeRoles.EDITOR)
        val sig = Base64.encode(OfficeSync.sign(v.memberSigningBytes("roster-doc", member)))
        rawChannels["members:roster-doc"] = listOf(
            aesBlob(key, v.syncJson.encodeToString(SignedMember(member, sig))))

        val latch = CountDownLatch(1)
        var ok: Boolean? = null
        v.setMemberRole("member-1", OfficeRoles.REVOKED) { ok = it; latch.countDown() }
        assertTrue(latch.await(15, TimeUnit.SECONDS), "role callback")
        assertEquals(true, ok)
        // Revocation rotated the content key to a new epoch.
        assertTrue(await { v.currentEpoch == 1 }, "epoch should advance, was ${v.currentEpoch}")
        assertTrue(!key.contentEquals(v.currentDocKey!!))
        stopJobs(v)
    }

    @Test
    fun `security code is derived for a registered peer`() {
        val v = OfficeViewModel(roboApp)
        try {
            val latch = CountDownLatch(1)
            var code: String? = null
            v.securityCodeWith("peer-7") { code = it; latch.countDown() }
            assertTrue(latch.await(15, TimeUnit.SECONDS), "code callback")
            assertNotNull(code)
            assertTrue(code!!.isNotBlank())
            val expected = runBlocking { OfficeSync.securityCode(OfficeSync.publicBundle) }
            assertEquals(expected, code)
        } finally {
            stopJobs(v)
        }
    }
}
