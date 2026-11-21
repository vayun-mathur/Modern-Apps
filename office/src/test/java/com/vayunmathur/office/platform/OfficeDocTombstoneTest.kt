package com.vayunmathur.office.platform

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
import kotlin.test.assertFalse
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
import com.vayunmathur.office.domain.DocumentTreeCrdt
import com.vayunmathur.office.sync.OfficeSync
import com.vayunmathur.office.sync.seal
import com.vayunmathur.office.sync.sign

/**
 * Doc-delete tombstones: pure list helpers, plus ViewModel pull paths
 * against a fake relay. The resurrection scenario under test is a stale
 * peer/pull replaying what it still holds (an invite, or the deleted
 * doc's whole op log) after the delete — the tombstone must win.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class OfficeDocTombstoneTest {

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

    private fun channelOf(body: String): String =
        Regex(""""channel":"([^"]+)"""").find(body)?.groupValues?.get(1) ?: ""

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        previousTransport = OfficeSync.httpTransport
        OfficeSync.httpTransport = OfficeSync.HttpTransport { url, body ->
            when {
                url.endsWith("/register") -> SimpleResponse(200, "", "{}", emptyMap(), url)
                url.endsWith("/getkey") ->
                    SimpleResponse(200, "", Base64.encode(OfficeSync.publicBundle), emptyMap(), url)
                url.endsWith("/append") -> SimpleResponse(200, "", """{"seq":1}""", emptyMap(), url)
                url.endsWith("/pull") -> {
                    val actions = rawChannels[channelOf(body)] ?: emptyList()
                    val jsonActions = actions.joinToString(",") { "\"$it\"" }
                    SimpleResponse(200, "", """{"actions":[$jsonActions],"seq":7}""", emptyMap(), url)
                }
                else -> SimpleResponse(404, "", "", emptyMap(), url)
            }
        }
        runBlocking {
            if (runCatching { OfficeSync.identity }.isFailure) {
                OfficeSync.identity = PqcIdentity.loadOrCreate(MemStore(), "tombstone")
            }
            OfficeSync.init(roboApp)
        }
    }

    @AfterTest
    fun tearDown() {
        OfficeSync.httpTransport = previousTransport
        OfficeSync.stopLive()
        Dispatchers.resetMain()
        // Leave the shared real store as other test classes expect to find
        // it (empty index/requests, no tombstones): these tests seed it.
        runBlocking {
            val ds = realDs()
            ds.setString("officeDocsIndex", "[]")
            ds.setString("officePendingRequests", "[]")
            ds.setString(OfficeDocTombstones.KEY, "[]")
        }
    }

    private fun vm(): OfficeViewModel = OfficeViewModel(roboApp)

    private fun realDs(): DataStoreUtils = DataStoreUtils.getInstance(roboApp)

    private fun loadedVm(text: String): OfficeViewModel {
        val v = vm()
        v.stateMutable.value = OfficeViewModel.ViewState.Loaded(
            OdfDocument.TextDocument(
                title = "Doc",
                content = listOf(
                    OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(text)))))))
        return v
    }

    private fun await(timeoutMs: Long = 10_000, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(20)
        }
        return cond()
    }

    /** A signed CRDT op batch for [text], encrypted under [key] like a relay doc action. */
    private fun signedOpBlob(v: OfficeViewModel, text: String, key: ByteArray): String {
        val tree = DocumentTreeCrdt(OfficeSync.deviceId)
        try {
            val opsJson = tree.updateJson(loadedVm(text).exportFlat())
            val sig = Base64.encode(runBlocking { OfficeSync.sign(opsJson.encodeToByteArray()) })
            val signed = v.syncJson.encodeToString(SignedOp(OfficeSync.deviceId, sig, opsJson))
            return Base64.encode(E2ee.aesEncrypt(key, signed.encodeToByteArray()))
        } finally {
            tree.close()
        }
    }

    /** An owner-signed delete record for [docId], encrypted under [key]. */
    private fun signedDeleteBlob(docId: String, key: ByteArray, deletedAt: Long): String {
        val tombstone = OfficeDocTombstone(docId, deletedAt, OfficeSync.deviceId)
        val sig = Base64.encode(
            runBlocking {
                OfficeSync.sign(OfficeDocTombstones.signingBytes(docId, deletedAt))
            },
        )
        val signed = OfficeSync.json.encodeToString(
            SignedDocDelete.serializer(),
            SignedDocDelete(tombstone, sig),
        )
        return Base64.encode(E2ee.aesEncrypt(key, signed.encodeToByteArray()))
    }

    // ---- Pure helpers ----

    @Test
    fun `tombstone helpers round-trip and filter`() {
        val tombstones = listOf(
            OfficeDocTombstone("d1", 100, "dev-a"),
            OfficeDocTombstone("d2", 200, "dev-b"),
        )
        assertEquals(tombstones, OfficeDocTombstones.decode(OfficeDocTombstones.encode(tombstones)))
        assertEquals(emptyList(), OfficeDocTombstones.decode(null))
        assertEquals(emptyList(), OfficeDocTombstones.decode("not json"))
        assertTrue(OfficeDocTombstones.isDeleted(tombstones, "d1"))
        assertFalse(OfficeDocTombstones.isDeleted(tombstones, "d3"))
        // Upsert keeps the newest stamp per doc and never duplicates.
        val older = OfficeDocTombstones.upsert(tombstones, OfficeDocTombstone("d1", 50))
        assertEquals(tombstones, older)
        val newer = OfficeDocTombstones.upsert(tombstones, OfficeDocTombstone("d1", 300))
        assertEquals(2, newer.size)
        assertEquals(300, newer.first { it.docId == "d1" }.deletedAt)
        val metas = listOf(
            OfficeDocMeta(docId = "d1", title = "A", keyB64 = "", owner = true),
            OfficeDocMeta(docId = "d3", title = "C", keyB64 = "", owner = false),
        )
        assertEquals(listOf("d3"), OfficeDocTombstones.filterLive(metas, tombstones).map { it.docId })
        assertEquals(
            "d1|deleted|123",
            OfficeDocTombstones.signingBytes("d1", 123).decodeToString(),
        )
    }

    // ---- Pull paths ----

    @Test
    fun `stale invite pull does not re-add a tombstoned doc`() = runTest {
        val v = vm()
        val ds = realDs()
        v.saveIndex(ds, emptyList())
        v.saveTombstones(ds, listOf(OfficeDocTombstone("doc-tomb-invite", 1_000, "owner-dev")))
        // The fake relay's inbox holds two sealed invites: one for the
        // deleted doc (stale replay), one for a live doc (control).
        val inviteJson = { docId: String ->
            OfficeSync.json.encodeToString(
                OfficeSync.Invite.serializer(),
                OfficeSync.Invite(
                    docId = docId,
                    key = Base64.encode(ByteArray(32) { 4 }),
                    title = "T-$docId",
                ),
            )
        }
        val sealed = listOf("doc-tomb-invite", "doc-tomb-live").map { docId ->
            Base64.encode(runBlocking { OfficeSync.seal(OfficeSync.publicBundle, inviteJson(docId).encodeToByteArray()) })
        }
        rawChannels["inbox:${OfficeSync.deviceId}"] = sealed

        val res = v.pullInboxInvites(ds)
        assertNotNull(res)
        assertEquals(2, res.invites.size)
        // Only the live doc was added; the tombstoned one stays deleted.
        assertEquals(listOf("doc-tomb-live"), v.loadIndex(ds).map { it.docId })
    }

    @Test
    fun `owner tombstone on pull blocks open and stale ops do not resurrect`() = runTest {
        val v = vm()
        val ds = realDs()
        val docId = "doc-tomb-open"
        val key = ByteArray(32) { 31 }
        val meta = OfficeDocMeta(
            docId = docId,
            title = "Doomed",
            keyB64 = Base64.encode(key),
            owner = false,
            role = OfficeRoles.EDITOR,
            ownerKeyB64 = Base64.encode(OfficeSync.publicBundle),
        )
        v.saveIndex(ds, listOf(meta))
        // Relay state: the owner has deleted the doc, but the stale op log
        // (which would rebuild it) is still fully present.
        rawChannels["deleted:$docId"] = listOf(
            signedDeleteBlob(docId, key, System.currentTimeMillis()),
        )
        rawChannels[docId] = listOf(signedOpBlob(v, "zombie text", key))

        v.openOnlineDocument(meta)

        assertTrue(
            await(12_000) { v.state.value is OfficeViewModel.ViewState.Error },
            "state was ${v.state.value}",
        )
        val message = (v.state.value as OfficeViewModel.ViewState.Error).message
        assertTrue(message.contains("deleted"), message)
        // The tombstone was recorded, the index entry dropped, and the doc
        // was never loaded from its stale ops.
        assertTrue(v.loadIndex(ds).none { it.docId == docId })
        assertTrue(v.isDocTombstoned(ds, docId))
        assertFalse(v.state.value is OfficeViewModel.ViewState.Loaded)
    }

    @Test
    fun `syncDoc is a no-op for a tombstoned doc`() = runTest {
        val v = loadedVm("should not sync")
        val ds = realDs()
        val docId = "doc-tomb-sync"
        v.saveTombstones(ds, listOf(OfficeDocTombstone(docId, 5_000, "dev")))
        v.currentRole = OfficeRoles.EDITOR
        v.currentTree = null
        v.syncDoc(docId, ByteArray(32) { 9 })
        // No tree was adopted/created, nothing was queued or persisted.
        assertNull(v.currentTree)
        assertTrue(v.loadPendingOps(ds, docId).isEmpty())
        assertNull(ds.getString("crdt:$docId"))
    }

    @Test
    fun `deleteOnlineDocument records tombstone and drops local state`() = runTest {
        val v = vm()
        val ds = realDs()
        val docId = "doc-tomb-delete"
        val key = ByteArray(32) { 17 }
        ds.setString("crdt:$docId", "seeded-tree")
        ds.setLong("crdtCursor:$docId", 3)
        v.saveTombstones(ds, emptyList())
        v.saveIndex(
            ds,
            listOf(
                OfficeDocMeta(
                    docId = docId,
                    title = "Mine",
                    keyB64 = Base64.encode(key),
                    owner = true,
                    ownerKeyB64 = Base64.encode(OfficeSync.publicBundle),
                ),
            ),
        )
        val latch = CountDownLatch(1)
        var ok: Boolean? = null
        v.deleteOnlineDocument(docId) { ok = it; latch.countDown() }
        assertTrue(latch.await(10, TimeUnit.SECONDS), "delete callback")
        assertEquals(true, ok)
        assertTrue(await { v.isDocTombstoned(ds, docId) })
        assertTrue(v.loadIndex(ds).none { it.docId == docId })
        assertNull(ds.getString("crdt:$docId"))
        assertNull(ds.getLong("crdtCursor:$docId"))
    }

    @Test
    fun `forged delete record is not honoured`() = runTest {
        val v = vm()
        val ds = realDs()
        val docId = "doc-tomb-forged"
        val key = ByteArray(32) { 23 }
        // Signed over different bytes than it carries: verification fails,
        // so fetch reports "unknown" instead of a tombstone.
        val tombstone = OfficeDocTombstone(docId, 9_999, "attacker")
        val badSig = Base64.encode(
            runBlocking { OfficeSync.sign("something else".encodeToByteArray()) },
        )
        val signed = OfficeSync.json.encodeToString(
            SignedDocDelete.serializer(),
            SignedDocDelete(tombstone, badSig),
        )
        rawChannels["deleted:$docId"] = listOf(
            Base64.encode(E2ee.aesEncrypt(key, signed.encodeToByteArray())),
        )
        assertNull(v.fetchDocDelete(docId, key, OfficeSync.publicBundle))
        assertFalse(v.isDocTombstoned(ds, docId))
    }
}
