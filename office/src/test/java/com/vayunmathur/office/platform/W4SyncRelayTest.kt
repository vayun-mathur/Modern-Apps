package com.vayunmathur.office.platform

import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.e2ee.E2ee
import com.vayunmathur.e2ee.E2eeKeyStore
import com.vayunmathur.e2ee.Pqc
import com.vayunmathur.e2ee.PqcIdentity
import com.vayunmathur.library.network.SimpleResponse
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.util.DataStoreUtils
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
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

import com.vayunmathur.office.sync.OfficeSync
import com.vayunmathur.office.sync.seal
import com.vayunmathur.office.sync.sign
/**
 * W4: positive-path sync relay tests.
 *
 * A scripted [OfficeSync.HttpTransport] replaces the relay: `/pull` serves
 * per-channel actions, `/append` acknowledges, `/getkey` serves this
 * device's own public bundle. Member/title/key/owner records are signed
 * with the real test identity, so the ViewModel's owner-signature
 * verification, key-epoch resolution and ownership-chain following run
 * exactly as in production — forged and malformed records must be dropped.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class W4SyncRelayTest {

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

    /** Raw (unencrypted) actions served per channel by the fake relay. */
    private val rawChannels = mutableMapOf<String, List<String>>()
    private val appended = mutableListOf<Pair<String, List<String>>>()
    private var getKeyStatus = 200

    private fun resp(status: Int, body: String, url: String) =
        SimpleResponse(status, "", body, emptyMap(), url)

    private fun channelOf(body: String): String =
        Regex(""""channel":"([^"]+)"""").find(body)?.groupValues?.get(1) ?: ""

    private fun installTransport() {
        OfficeSync.httpTransport = OfficeSync.HttpTransport { url, body ->
            when {
                url.endsWith("/register") -> resp(200, "{}", url)
                url.endsWith("/getkey") ->
                    if (getKeyStatus == 200) {
                        resp(200, Base64.encode(OfficeSync.publicBundle), url)
                    } else {
                        resp(getKeyStatus, "", url)
                    }
                url.endsWith("/append") -> {
                    val channel = channelOf(body)
                    val actions = Regex(""""actions":\[(.*?)\]""").find(body)?.groupValues?.get(1)
                        ?.let { Regex(""""([^"]*)"""").findAll(it).map { m -> m.groupValues[1] }.toList() }
                        ?: emptyList()
                    appended.add(channel to actions)
                    resp(200, """{"seq":${appended.size}}""", url)
                }
                url.endsWith("/pull") -> {
                    val channel = channelOf(body)
                    val actions = rawChannels[channel] ?: emptyList()
                    val jsonActions = actions.joinToString(",") { "\"$it\"" }
                    resp(200, """{"actions":[$jsonActions],"seq":${actions.size}}""", url)
                }
                else -> resp(404, "", url)
            }
        }
    }

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        previousTransport = OfficeSync.httpTransport
        runBlocking {
            if (runCatching { OfficeSync.identity }.isFailure) {
                OfficeSync.identity = PqcIdentity.loadOrCreate(MemStore(), "w4sync")
            }
            installTransport()
            // Registration succeeds against the fake relay, so init mints a device id.
            OfficeSync.init(roboApp)
        }
    }

    @AfterTest
    fun tearDown() {
        OfficeSync.httpTransport = previousTransport
        OfficeSync.stopLive()
        Dispatchers.resetMain()
    }

    private fun vm(): OfficeViewModel = OfficeViewModel(roboApp)

    private fun loadedVm(text: String = "relay line"): OfficeViewModel {
        val v = vm()
        v.stateMutable.value = OfficeViewModel.ViewState.Loaded(
            OdfDocument.TextDocument(
                title = "Doc",
                content = listOf(OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(text)))))))
        return v
    }

    private fun mockDs(
        strings: MutableMap<String, String?> = mutableMapOf(),
        longs: MutableMap<String, Long?> = mutableMapOf(),
    ): DataStoreUtils {
        val ds = mock(DataStoreUtils::class.java)
        `when`(ds.getString(anyString())).thenAnswer { strings[it.arguments[0] as String] }
        `when`(ds.getLong(anyString())).thenAnswer { longs[it.arguments[0] as String] }
        runBlocking {
            `when`(ds.setString(anyString(), anyString(), anyBoolean())).thenAnswer {
                strings[it.arguments[0] as String] = it.arguments[1] as String
                null
            }
            `when`(ds.setLong(anyString(), anyLong(), anyBoolean())).thenAnswer {
                longs[it.arguments[0] as String] = it.arguments[1] as Long
                null
            }
        }
        return ds
    }

    private fun aesBlob(key: ByteArray, plain: String): String =
        Base64.encode(E2ee.aesEncrypt(key, plain.encodeToByteArray()))

    private fun signedMemberBlob(v: OfficeViewModel, docId: String, key: ByteArray, m: OfficeMember): String {
        val sig = Base64.encode(runBlocking { OfficeSync.sign(v.memberSigningBytes(docId, m)) })
        return aesBlob(key, v.syncJson.encodeToString(SignedMember(m, sig)))
    }

    // ---- fetchMembers / fetchTitle ----

    @Test
    fun `fetchMembers keeps only owner-signed records and last write wins`() = runTest {
        val v = vm()
        val key = ByteArray(32) { 3 }
        val ann = OfficeMember("ann", "Ann", OfficeRoles.EDITOR)
        val bobOld = OfficeMember("bob", "Bob", OfficeRoles.VIEWER)
        val bobNew = OfficeMember("bob", "Bob", OfficeRoles.EDITOR)
        val forged = OfficeMember("mallory", "Mallory", OfficeRoles.OWNER)
        val forgedSig = Base64.encode(runBlocking { OfficeSync.sign("d1|mallory|viewer".encodeToByteArray()) })
        rawChannels["members:d1"] = listOf(
            signedMemberBlob(v, "d1", key, ann),
            "not-json-at-all",
            aesBlob(key, v.syncJson.encodeToString(SignedMember(forged, forgedSig))),
            signedMemberBlob(v, "d1", key, bobOld),
            signedMemberBlob(v, "d1", key, bobNew),
        )
        val members = v.fetchMembers("d1", key, OfficeSync.publicBundle)
        assertEquals(listOf(ann, bobNew), members)
        assertEquals(OfficeRoles.EDITOR, v.currentMembers["ann"])
        assertEquals(OfficeRoles.EDITOR, v.currentMembers["bob"])
        assertNull(v.currentMembers["mallory"])
    }

    @Test
    fun `fetchTitle returns latest owner-signed title only`() = runTest {
        val v = vm()
        val key = ByteArray(32) { 4 }
        fun titleBlob(title: String, signedTitle: String = title): String {
            val sig = Base64.encode(runBlocking { OfficeSync.sign(v.titleSigningBytes("d2", signedTitle)) })
            return aesBlob(key, v.syncJson.encodeToString(SignedTitle(title, sig)))
        }
        rawChannels["title:d2"] = listOf(
            titleBlob("Forged", signedTitle = "Other"),
            "garbage",
            titleBlob("First"),
            titleBlob("Second"),
        )
        assertEquals("Second", v.fetchTitle("d2", key, OfficeSync.publicBundle))
    }

    // ---- fetchCurrentKey ----

    @Test
    fun `fetchCurrentKey picks highest verified epoch unsealable by this device`() = runTest {
        val v = vm()
        val invite = ByteArray(32) { 9 }
        val epoch1Key = ByteArray(32) { 21 }
        val epoch2Key = ByteArray(32) { 22 }
        val device = OfficeSync.deviceId
        assertTrue(device.isNotBlank())

        fun epochBlob(epoch: Int, wraps: Map<String, String>, signEpoch: Int = epoch): String {
            val sig = Base64.encode(runBlocking { OfficeSync.sign(v.epochSigningBytes("d3", signEpoch, wraps)) })
            val record = KeyEpoch(epoch, wraps, sig)
            return Base64.encode(v.syncJson.encodeToString(record).encodeToByteArray())
        }
        val wrap1 = mapOf(device to Base64.encode(runBlocking { OfficeSync.seal(OfficeSync.publicBundle, epoch1Key) }))
        val wrap2 = mapOf(device to Base64.encode(runBlocking { OfficeSync.seal(OfficeSync.publicBundle, epoch2Key) }))
        val wrapOther = mapOf("someone-else" to Base64.encode(runBlocking { OfficeSync.seal(OfficeSync.publicBundle, epoch1Key) }))
        rawChannels["keys:d3"] = listOf(
            epochBlob(2, wrap2),
            epochBlob(1, wrap1), // older epoch must not downgrade the result
            epochBlob(9, wrap1, signEpoch = 8), // signature does not match contents
            epochBlob(7, wrapOther), // verified but not sealed to this device
            "!!!not-a-blob!!!",
            epochBlob(5, mapOf(device to "!!!not-base64!!!")), // unsealable wrap
        )
        val (epoch, resolved) = v.fetchCurrentKey("d3", invite, OfficeSync.publicBundle)
        assertEquals(2, epoch)
        assertTrue(epoch2Key.contentEquals(resolved))
    }

    // ---- fetchOwnerKey ----

    @Test
    fun `fetchOwnerKey follows signed transfers and ignores forged ones`() = runTest {
        val v = vm()
        val base = OfficeSync.publicBundle
        fun transferBlob(newOwnerId: String, newOwnerKeyB64: String, signAs: String = newOwnerKeyB64): String {
            val sig = Base64.encode(runBlocking {
                OfficeSync.sign(v.ownerSigningBytes("d4", newOwnerId, signAs)) })
            val record = OwnerTransfer(newOwnerId, newOwnerKeyB64, sig)
            return Base64.encode(v.syncJson.encodeToString(record).encodeToByteArray())
        }
        val sameKeyB64 = Base64.encode(base)
        rawChannels["owner:d4"] = listOf(
            transferBlob("mallory", sameKeyB64, signAs = "AAAA"), // forged signature
            "not-a-blob",
            transferBlob("new-owner", "!!!bad-key!!!"), // signed, but key undecodable
            transferBlob("new-owner", sameKeyB64), // valid hop (same bundle re-registered)
        )
        val resolved = v.fetchOwnerKey("d4", Base64.encode(base))
        assertNotNull(resolved)
        assertTrue(base.contentEquals(resolved))
    }

    // ---- pollTitle / refreshTitles ----

    @Test
    fun `pollTitle applies owner rename to editor and index`() = runTest {
        val v = loadedVm()
        val key = ByteArray(32) { 6 }
        v.currentDocId = "d5"
        v.currentOwnerKey = OfficeSync.publicBundle
        val sig = Base64.encode(runBlocking { OfficeSync.sign(v.titleSigningBytes("d5", "Renamed")) })
        rawChannels["title:d5"] = listOf(
            aesBlob(key, v.syncJson.encodeToString(SignedTitle("Renamed", sig))))

        val ds = DataStoreUtils.getInstance(roboApp)
        v.saveIndex(ds, listOf(OfficeDocMeta(docId = "d5", title = "Doc", keyB64 = Base64.encode(key), owner = true)))

        v.pollTitle("d5", key)
        val doc = (v.state.value as OfficeViewModel.ViewState.Loaded).document
        assertEquals("Renamed", doc.title)
        assertEquals("Renamed", v.loadIndex(ds).single().title)

        // Re-polling with the same title is a no-op (title already current).
        v.pollTitle("d5", key)
        val again = (v.state.value as OfficeViewModel.ViewState.Loaded).document
        assertEquals("Renamed", again.title)
    }

    @Test
    fun `refreshTitles rewrites only renamed docs`() = runTest {
        val v = vm()
        val strings = mutableMapOf<String, String?>()
        val ds = mockDs(strings)
        val key = ByteArray(32) { 7 }
        fun titleBlob(docId: String, title: String): String {
            val sig = Base64.encode(runBlocking { OfficeSync.sign(v.titleSigningBytes(docId, title)) })
            return aesBlob(key, v.syncJson.encodeToString(SignedTitle(title, sig)))
        }
        rawChannels["title:renamed"] = listOf(titleBlob("renamed", "Fresh"))
        rawChannels["title:stale"] = listOf(titleBlob("stale", "Stale"))
        val ownerB64 = Base64.encode(OfficeSync.publicBundle)
        val metas = listOf(
            OfficeDocMeta(docId = "renamed", title = "Old", keyB64 = Base64.encode(key), owner = false, ownerKeyB64 = ownerB64),
            OfficeDocMeta(docId = "stale", title = "Stale", keyB64 = Base64.encode(key), owner = false, ownerKeyB64 = ownerB64),
            OfficeDocMeta(docId = "nokey", title = "NoKey", keyB64 = Base64.encode(key), owner = false, ownerKeyB64 = ""),
        )
        v.saveIndex(ds, metas)
        v.refreshTitles(ds)
        val byId = v.loadIndex(ds).associateBy { it.docId }
        assertEquals("Fresh", byId["renamed"]?.title)
        assertEquals("Stale", byId["stale"]?.title)
        assertEquals("NoKey", byId["nokey"]?.title)
    }

    // ---- inbox: invites + join requests ----

    @Test
    fun `pullInboxInvites merges new invites into index`() = runTest {
        val v = vm()
        val strings = mutableMapOf<String, String?>()
        val ds = mockDs(strings)
        val docKey = ByteArray(32) { 8 }
        val invite = OfficeSync.Invite(
            docId = "invited-doc", key = Base64.encode(docKey), title = "Invited",
            role = OfficeRoles.VIEWER, ownerKey = Base64.encode(OfficeSync.publicBundle))
        val sealedInvite = Base64.encode(
            Pqc.encryptTo(OfficeSync.publicBundle, OfficeSync.json.encodeToString(invite).encodeToByteArray()))
        val sealedGarbage = Base64.encode(
            Pqc.encryptTo(OfficeSync.publicBundle, "hello not json".encodeToByteArray()))
        val request = OfficeSync.JoinRequest(docId = "invited-doc", requesterId = "req-1", name = "Rob")
        val sealedRequest = Base64.encode(
            Pqc.encryptTo(OfficeSync.publicBundle, OfficeSync.json.encodeToString(request).encodeToByteArray()))
        rawChannels["inbox:${OfficeSync.deviceId}"] = listOf(sealedGarbage, sealedInvite, sealedRequest)

        val res = v.pullInboxInvites(ds)
        assertNotNull(res)
        assertEquals(listOf(invite), res.invites)
        assertEquals(listOf(request), res.requests)
        val index = v.loadIndex(ds)
        assertEquals(1, index.size)
        assertEquals("Invited", index[0].title)
        assertEquals(false, index[0].owner)

        // Second pull with the same invite must not duplicate the index entry.
        val again = v.pullInboxInvites(ds)
        assertNotNull(again)
        assertEquals(1, v.loadIndex(ds).size)
    }

    // ---- OfficeSync invite / join-request sending ----

    @Test
    fun `sendInvite and sendJoinRequest seal to registered peers`() = runTest {
        val key = ByteArray(32) { 10 }
        assertTrue(OfficeSync.sendInvite(
            "peer-1", "doc-x", key, "Title", charMode = false,
            role = OfficeRoles.EDITOR, ownerKeyB64 = "owner", charKind = ""))
        val inviteAppend = appended.last { it.first == "inbox:peer-1" }
        assertEquals(1, inviteAppend.second.size)
        // Sealed to this device's bundle, so the test identity can open it.
        val opened = OfficeSync.identity.decrypt(Base64.decode(inviteAppend.second[0])).decodeToString()
        val decoded = OfficeSync.json.decodeFromString<OfficeSync.Invite>(opened)
        assertEquals("doc-x", decoded.docId)
        assertEquals("Title", decoded.title)

        assertTrue(OfficeSync.sendJoinRequest("owner-1", "doc-y", OfficeSync.deviceId, "Me"))
        assertTrue(appended.any { it.first == "inbox:owner-1" })

        getKeyStatus = 404
        assertTrue(!OfficeSync.sendInvite(
            "ghost", "doc-x", key, "Title", charMode = false,
            role = OfficeRoles.EDITOR, ownerKeyB64 = "owner", charKind = ""))
        assertTrue(!OfficeSync.sendJoinRequest("ghost", "doc-y", OfficeSync.deviceId, "Me"))
        getKeyStatus = 200
    }

    @Test
    fun `pullInvites separates invites from requests`() = runTest {
        val invite = OfficeSync.Invite(docId = "d-inv", key = "aw==", title = "In")
        val sealedInvite = Base64.encode(
            Pqc.encryptTo(OfficeSync.publicBundle, OfficeSync.json.encodeToString(invite).encodeToByteArray()))
        rawChannels["inbox:${OfficeSync.deviceId}"] = listOf(sealedInvite)
        val res = OfficeSync.pullInvites(0)
        assertEquals(listOf(invite), res.invites)
        assertEquals(1, res.seq)
    }
}
