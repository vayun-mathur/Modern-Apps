package com.vayunmathur.office.util

import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.e2ee.E2eeKeyStore
import com.vayunmathur.e2ee.PqcIdentity
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
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * Slice 3/4: SyncA/B/C ViewModel helpers with no real network.
 *
 * A test PQC identity is installed on [OfficeSync] so signing/sealing runs
 * for real; [OfficeSync.httpTransport] is replaced with a failing double so no
 * test touches the real relay, and the production code's offline/null-result
 * branches are the behaviour under test. DataStore-backed helpers are driven
 * against either the real Robolectric store or a map-backed mock.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@OptIn(ExperimentalCoroutinesApi::class)
class OfficeViewModelSyncSlice3Test {

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

    @BeforeTest
    fun setUp() {
        // Never touch the real relay: every POST fails as if offline, so the
        // production offline branches are exercised deterministically in CI too.
        previousTransport = OfficeSync.httpTransport
        OfficeSync.httpTransport = OfficeSync.HttpTransport { _, _ -> null }
        Dispatchers.setMain(UnconfinedTestDispatcher())
        runBlocking {
            if (runCatching { OfficeSync.identity }.isFailure) {
                OfficeSync.identity = PqcIdentity.loadOrCreate(MemStore(), "slice3sync")
            }
        }
    }

    @AfterTest
    fun tearDown() {
        OfficeSync.httpTransport = previousTransport
        OfficeSync.stopLive()
        Dispatchers.resetMain()
    }

    private fun vm(): OfficeViewModel = OfficeViewModel(roboApp)

    private fun loadedVm(text: String = "sync line"): OfficeViewModel {
        val v = vm()
        v.stateMutable.value = OfficeViewModel.ViewState.Loaded(
            OdfDocument.TextDocument(
                title = "Doc",
                content = listOf(
                    OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(text)))))))
        return v
    }

    private fun realDs(): DataStoreUtils = DataStoreUtils.getInstance(roboApp)

    /** Map-backed DataStoreUtils mock for the ds-taking helpers. */
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

    private fun await(timeoutMs: Long = 10_000, cond: () -> Boolean): Boolean {
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
    }

    // ---- SyncB: index / requests against a mock store ----

    @Test
    fun `pullInboxInvites returns empty inbox result offline`() = runTest {
        val v = vm()
        val ds = mockDs()
        val res = v.pullInboxInvites(ds)
        assertNotNull(res)
        assertTrue(res.invites.isEmpty())
        assertEquals(0, res.seq)
    }

    @Test
    fun `mergeRequests keeps only owned deduped requests`() = runTest {
        val v = vm()
        val strings = mutableMapOf<String, String?>()
        val ds = mockDs(strings)
        v.saveIndex(ds, listOf(
            OfficeDocMeta(docId = "owned", title = "O", keyB64 = "aw==", owner = true),
            OfficeDocMeta(docId = "foreign", title = "F", keyB64 = "aw==", owner = false)))
        val req = OfficeSync.JoinRequest(docId = "owned", requesterId = "r1", name = "Ann")
        val res = OfficeSync.InboxResult(
            invites = emptyList(),
            requests = listOf(
                req, req,
                OfficeSync.JoinRequest(docId = "foreign", requesterId = "r2"),
                OfficeSync.JoinRequest(docId = "owned", requesterId = "r1")),
            seq = 4)
        v.mergeRequests(ds, res)
        assertEquals(listOf(req), v.loadRequests(ds))
        assertEquals(listOf(req), v.pendingRequestsMutable.value)
        // Empty request list is a no-op.
        v.mergeRequests(ds, OfficeSync.InboxResult(emptyList(), emptyList(), 5))
        assertEquals(listOf(req), v.loadRequests(ds))
    }

    @Test
    fun `refreshTitles tolerates bad keys and offline fetches`() = runTest {
        val v = vm()
        val ds = mockDs()
        val metas = listOf(
            OfficeDocMeta(docId = "bad", title = "B", keyB64 = "!!!", owner = false),
            OfficeDocMeta(
                docId = "good", title = "G",
                keyB64 = Base64.encode(ByteArray(32)), owner = false,
                ownerKeyB64 = Base64.encode(OfficeSync.publicBundle)))
        v.saveIndex(ds, metas)
        v.refreshTitles(ds)
        // No title could be fetched offline, so the index is unchanged.
        assertEquals(metas, v.loadIndex(ds))
    }

    @Test
    fun `loadTree and saveTree round-trip through store`() = runTest {
        val v = loadedVm("tree text")
        val strings = mutableMapOf<String, String?>()
        val ds = mockDs(strings)
        assertNull(v.loadTree(ds, "missing"))
        val tree = DocumentTreeCrdt("dev-tree")
        try {
            tree.updateJson(v.exportFlat())
            v.saveTree(ds, "d-tree", tree)
            assertTrue(strings.containsKey("crdt:d-tree"))
            val back = v.loadTree(ds, "d-tree")
            assertNotNull(back)
            try {
                assertEquals(tree.render(), back.render())
            } finally {
                back.close()
            }
        } finally {
            tree.close()
        }
    }

    @Test
    fun `parseFlat parses exported doc and rejects garbage`() = runTest {
        val v = loadedVm("flat text")
        val parsed = v.parseFlat(v.exportFlat())
        assertNotNull(parsed)
        // DocumentImporter titles the import after the temp file name.
        assertEquals("merge.fodt", parsed.title)
        val text = (parsed as OdfDocument.TextDocument).content
            .filterIsInstance<OdfContentBlock.Paragraph>()
            .joinToString("") { it.paragraph.spans.joinToString("") { s -> s.text } }
        assertTrue(text.contains("flat text"), text)
    }

    // ---- SyncB: syncDoc / share helpers ----

    @Test
    fun `syncDoc pushes local ops and persists tree offline`() = runTest {
        val v = loadedVm("synced text")
        val key = ByteArray(32) { it.toByte() }
        v.currentRole = OfficeRoles.EDITOR
        val tree = DocumentTreeCrdt("dev-sync")
        v.currentTree = tree
        v.syncDoc("doc-sync-1", key)
        // Tree was persisted under the doc id and renders the local text.
        val stored = realDs().getString("crdt:doc-sync-1")
        assertNotNull(stored)
        assertTrue(tree.render().contains("synced text"), tree.render())
        assertEquals(0L, realDs().getLong("crdtCursor:doc-sync-1") ?: -1L)
    }

    @Test
    fun `syncDoc viewer does not push and adopts stored tree`() = runTest {
        val v = loadedVm("viewer text")
        val key = ByteArray(32) { 7 }
        v.currentRole = OfficeRoles.VIEWER
        v.currentTree = null
        v.syncDoc("doc-sync-viewer", key)
        assertNotNull(v.currentTree)
        assertTrue(v.currentTree!!.render().contains("viewer text"))
    }

    @Test
    fun `share title and registration helpers`() = runTest {
        val v = loadedVm()
        // Offline registration fails (relay unreachable) with a human error.
        val err = v.ensureShareRegistration()
        assertNotNull(err)
        assertTrue(err.contains("register", ignoreCase = true), err)
        // First share: explicit name wins, blank falls back to doc title.
        v.currentDocId = null
        assertEquals("Named", v.resolveShareTitle("  Named  ", "DocTitle"))
        assertEquals("DocTitle", v.resolveShareTitle(" ", "DocTitle"))
        assertEquals("Document", v.resolveShareTitle("", null))
        // Later shares keep the stored online title.
        v.currentDocId = "d9"
        v.onlineDocsMutable.value = listOf(
            OfficeDocMeta(docId = "d9", title = "Stored", keyB64 = "", owner = true))
        assertEquals("Stored", v.resolveShareTitle("Other", "DocTitle"))
    }

    @Test
    fun `initShareOwnership claims owner role and retitles`() = runTest {
        val v = loadedVm()
        val key = ByteArray(32)
        v.initShareOwnership("Online Name", key, hasDoc = true)
        assertEquals(OfficeRoles.OWNER, v.currentRole)
        assertEquals(0, v.currentEpoch)
        assertTrue(key.contentEquals(v.currentBaseKey!!))
        val doc = (v.state.value as OfficeViewModel.ViewState.Loaded).document
        assertEquals("Online Name", doc.title)
        assertEquals(
            OfficeRoles.OWNER, v.currentMembers[OfficeSync.deviceId])
    }

    @Test
    fun `prepareShareTarget records index and starts live`() = runTest {
        val v = loadedVm("share me")
        v.currentRole = OfficeRoles.OWNER
        val strings = mutableMapOf<String, String?>()
        val ds = mockDs(strings)
        val key = ByteArray(32) { 3 }
        try {
            val ownerKeyB64 = v.prepareShareTarget(ds, "doc-share-1", key, "Shared Doc")
            assertEquals(Base64.encode(OfficeSync.publicBundle), ownerKeyB64)
            assertEquals("doc-share-1", v.currentDocId)
            assertTrue(v.isOnlineMutable.value)
            val index = v.loadIndex(ds)
            assertEquals(1, index.size)
            assertEquals("Shared Doc", index[0].title)
            assertTrue(index[0].owner)
        } finally {
            stopJobs(v)
        }
    }

    @Test
    fun `pushShareInvite reports undeliverable invite offline`() = runTest {
        val v = loadedVm()
        v.currentRole = OfficeRoles.OWNER
        val key = ByteArray(32) { 5 }
        val err = v.pushShareInvite("doc-share-2", key, "T", "ghost-device", OfficeRoles.EDITOR, "ownerB64")
        assertNotNull(err)
        assertTrue(err.contains("deliver", ignoreCase = true), err)
    }

    // ---- SyncC: members / keys / titles ----

    @Test
    fun `fetchMembers and fetchTitle handle missing owner key and offline`() = runTest {
        val v = vm()
        val key = ByteArray(32)
        assertTrue(v.fetchMembers("d1", key, null).isEmpty())
        v.currentMembers["stale"] = OfficeRoles.EDITOR
        assertTrue(v.fetchMembers("d1", key, OfficeSync.publicBundle).isEmpty())
        assertTrue(v.currentMembers.isEmpty())
        assertNull(v.fetchTitle("d1", key, null))
        assertNull(v.fetchTitle("d1", key, OfficeSync.publicBundle))
    }

    @Test
    fun `fetchCurrentKey falls back to invite key offline`() = runTest {
        val v = vm()
        val invite = ByteArray(32) { 9 }
        val (epochNull, keyNull) = v.fetchCurrentKey("d1", invite, null)
        assertEquals(0, epochNull)
        assertTrue(invite.contentEquals(keyNull))
        val (epoch, key) = v.fetchCurrentKey("d1", invite, OfficeSync.publicBundle)
        assertEquals(0, epoch)
        assertTrue(invite.contentEquals(key))
    }

    @Test
    fun `fetchOwnerKey validates base key offline`() = runTest {
        val v = vm()
        assertNull(v.fetchOwnerKey("d1", ""))
        assertNull(v.fetchOwnerKey("d1", "!!!not-base64!!!"))
        val base = OfficeSync.publicBundle
        val resolved = v.fetchOwnerKey("d1", Base64.encode(base))
        assertNotNull(resolved)
        assertTrue(base.contentEquals(resolved))
    }

    @Test
    fun `signing bytes are deterministic and scoped`() = runTest {
        val v = vm()
        val m = OfficeMember("peer", "Ann", OfficeRoles.EDITOR)
        assertEquals("d1|peer|editor", v.memberSigningBytes("d1", m).decodeToString())
        assertEquals("d1|title|New", v.titleSigningBytes("d1", "New").decodeToString())
        assertEquals(
            "d1|owner|peer|a2V5", v.ownerSigningBytes("d1", "peer", "a2V5").decodeToString())
        val wraps = mapOf("b" to "2", "a" to "1")
        assertEquals(
            "d1|3|a=1|b=2", v.epochSigningBytes("d1", 3, wraps).decodeToString())
    }

    @Test
    fun `writeMembers signs and memberKey uses cache`() = runTest {
        val v = vm()
        v.currentRole = OfficeRoles.OWNER
        // No server: append fails silently inside runCatching, call completes.
        v.writeMembers("d1", ByteArray(32), listOf(OfficeMember("p1", "", OfficeRoles.VIEWER)))
        v.recordMembers("d1", ByteArray(32), listOf(OfficeMember("p2")))
        val bundle = OfficeSync.publicBundle
        v.memberKeyCache["cached-peer"] = bundle
        assertTrue(bundle.contentEquals(v.memberKey("cached-peer")!!))
        // Cache miss goes to the (unreachable) relay and yields null.
        assertNull(v.memberKey("no-such-device-slice3"))
    }

    @Test
    fun `rotateKey mints epoch and rebaselines tree`() = runTest {
        val v = loadedVm("rotate text")
        v.currentRole = OfficeRoles.OWNER
        v.currentEpoch = 0
        val oldKey = ByteArray(32) { 1 }
        v.currentDocKey = oldKey
        v.currentOwnerKey = OfficeSync.publicBundle
        val tree = DocumentTreeCrdt("dev-rotate")
        tree.updateJson(v.exportFlat())
        v.currentTree = tree
        try {
            v.rotateKey("doc-rotate")
            assertEquals(1, v.currentEpoch)
            assertNotNull(v.currentDocKey)
            assertFalse(oldKey.contentEquals(v.currentDocKey!!))
        } finally {
            stopJobs(v)
        }
    }

    @Test
    fun `pollTitle ignores foreign docs and offline titles`() = runTest {
        val v = loadedVm()
        v.currentDocId = "mine"
        v.currentOwnerKey = OfficeSync.publicBundle
        // Foreign doc id: immediate return.
        v.pollTitle("other", ByteArray(32))
        // Own doc but no signed title on the (unreachable) relay: no change.
        v.pollTitle("mine", ByteArray(32))
        val doc = (v.state.value as OfficeViewModel.ViewState.Loaded).document
        assertEquals("Doc", doc.title)
    }

    // ---- SyncA: presence / live / local edits ----

    @Test
    fun `broadcastPresence and onLocalEdit schedule jobs for online doc`() = runTest {
        val v = loadedVm()
        v.currentDocId = "doc-live-1"
        v.currentDocKey = ByteArray(32) { 2 }
        v.currentRole = OfficeRoles.EDITOR
        v.setLocalCaret(4)
        assertNotNull(v.caretPresenceJob)
        v.onLocalEdit()
        assertNotNull(v.livePushJob)
        // While applying a remote merge, onLocalEdit is suppressed.
        v.applyingRemote = true
        val pushBefore = v.livePushJob
        v.onLocalEdit()
        assertEquals(pushBefore, v.livePushJob)
        v.applyingRemote = false
        stopJobs(v)
    }

    @Test
    fun `pushLiveOps and applyLiveActions merge signed peer ops`() = runTest {
        val v = loadedVm("base text")
        val key = ByteArray(32) { 11 }
        v.currentDocId = "doc-live-2"
        v.currentDocKey = key
        v.currentRole = OfficeRoles.EDITOR
        val tree = DocumentTreeCrdt("dev-live")
        v.currentTree = tree
        try {
            // Direct push: live socket is down, HTTP append fails offline, no throw.
            v.pushLiveOps("doc-live-2", key, "[]")
            // Actions path with no incoming blobs still folds local edits in.
            v.applyLiveActions(OfficeSync.LiveMsg(t = "actions", seq = 3), "doc-live-2", key)
            assertTrue(tree.render().contains("base text"), tree.render())
            assertEquals(3L, realDs().getLong("crdtCursor:doc-live-2") ?: -1L)
        } finally {
            stopJobs(v)
        }
    }

    @Test
    fun `mergeLiveTree renders changed tree into editor`() = runTest {
        val v = loadedVm("before merge")
        val tree = DocumentTreeCrdt("dev-merge")
        v.currentTree = tree
        try {
            val other = loadedVm("after merge")
            tree.updateJson(other.exportFlat())
            v.mergeLiveTree("doc-merge-1", 7, tree, "stale-local-xml", v.editVersion)
            val doc = (v.state.value as OfficeViewModel.ViewState.Loaded)
                .document as OdfDocument.TextDocument
            val text = doc.content.filterIsInstance<OdfContentBlock.Paragraph>()
                .joinToString("") { it.paragraph.spans.joinToString("") { s -> s.text } }
            assertTrue(text.contains("after merge"), text)
            assertEquals(7L, realDs().getLong("crdtCursor:doc-merge-1") ?: -1L)
            // Identical render is a no-op for the editor.
            val stateBefore = v.state.value
            v.mergeLiveTree("doc-merge-1", 8, tree, tree.render(), v.editVersion)
            assertEquals(stateBefore, v.state.value)
        } finally {
            stopJobs(v)
        }
    }

    @Test
    fun `startLive presence tick clears stale typing`() = runTest {
        val v = loadedVm("tick text")
        v.currentTree = DocumentTreeCrdt("dev-tick")
        val now = System.currentTimeMillis()
        v.remotePresenceMutable.value = listOf(
            OfficePresence("peer-tick", "Ann", typing = true, ts = now, typingTs = 0L))
        try {
            v.startLive("doc-tick", ByteArray(32) { 4 })
            assertTrue(
                await(12_000) { v.remotePresenceMutable.value.firstOrNull()?.typing == false },
                "presence tick should clear stale typing, was ${v.remotePresenceMutable.value}")
        } finally {
            stopJobs(v)
            v.currentTree?.close()
        }
    }

    // ---- Public launchers (IO coroutines, offline relay) ----

    @Test
    fun `requestToJoin and securityCode report failure offline`() {
        val v = vm()
        val joinLatch = CountDownLatch(1)
        var joinOk: Boolean? = null
        v.requestToJoin("doc-j", "owner-x") { joinOk = it; joinLatch.countDown() }
        assertTrue(joinLatch.await(10, TimeUnit.SECONDS), "join callback")
        assertEquals(false, joinOk)

        val codeLatch = CountDownLatch(1)
        var code: String? = "unset"
        v.securityCodeWith("no-such-peer") { code = it; codeLatch.countDown() }
        assertTrue(codeLatch.await(10, TimeUnit.SECONDS), "code callback")
        assertNull(code)
    }

    @Test
    fun `approve and deny join requests update pending list`() = runTest {
        val v = vm()
        val ds = realDs()
        v.saveIndex(ds, listOf(
            OfficeDocMeta(
                docId = "doc-appr", title = "Owned", keyB64 = Base64.encode(ByteArray(32)),
                owner = true)))
        v.saveRequests(ds, listOf(
            OfficeSync.JoinRequest(docId = "doc-appr", requesterId = "req-1"),
            OfficeSync.JoinRequest(docId = "doc-appr", requesterId = "req-2")))
        // Approve req-1: invite cannot be delivered offline, so result is
        // false and the request stays pending.
        val latch = CountDownLatch(1)
        var ok: Boolean? = null
        v.approveJoinRequest("doc-appr", "req-1") { ok = it; latch.countDown() }
        assertTrue(latch.await(10, TimeUnit.SECONDS), "approve callback")
        assertEquals(false, ok)
        // Deny req-2: removed locally without any grant.
        v.denyRequest("doc-appr", "req-2")
        assertTrue(await { v.pendingRequestsMutable.value.none { it.requesterId == "req-2" } })
        assertTrue(v.loadRequests(ds).any { it.requesterId == "req-1" })
        // Approving an unknown doc also reports false.
        val latch2 = CountDownLatch(1)
        var ok2: Boolean? = null
        v.approveJoinRequest("no-doc", "req-9") { ok2 = it; latch2.countDown() }
        assertTrue(latch2.await(10, TimeUnit.SECONDS))
        assertEquals(false, ok2)
    }

    @Test
    fun `documentMembers setMemberRole and transfer guards and owner paths`() {
        val v = loadedVm()
        // Guards: nothing open online.
        var guardMembers: List<OfficeMember>? = null
        v.documentMembers { guardMembers = it }
        assertEquals(emptyList(), guardMembers)
        var guardRole: Boolean? = null
        v.setMemberRole("p1", OfficeRoles.VIEWER) { guardRole = it }
        assertEquals(false, guardRole)
        var guardTransfer: Boolean? = null
        v.transferOwnership("p1") { guardTransfer = it }
        assertEquals(false, guardTransfer)

        // Owner paths against the offline relay.
        v.currentDocId = "doc-members"
        v.currentDocKey = ByteArray(32) { 6 }
        v.currentRole = OfficeRoles.OWNER
        v.currentOwnerKey = OfficeSync.publicBundle
        val memLatch = CountDownLatch(1)
        var members: List<OfficeMember>? = null
        v.documentMembers { members = it; memLatch.countDown() }
        assertTrue(memLatch.await(10, TimeUnit.SECONDS))
        assertEquals(emptyList(), members)

        var roleOk: Boolean? = null
        val roleLatch = CountDownLatch(1)
        v.setMemberRole("p1", OfficeRoles.VIEWER) { roleOk = it; roleLatch.countDown() }
        assertTrue(roleLatch.await(10, TimeUnit.SECONDS))
        assertEquals(true, roleOk)

        // Transfer to a cached member succeeds (records signed roster offline).
        v.memberKeyCache["new-owner"] = OfficeSync.publicBundle
        var transferOk: Boolean? = null
        val transferLatch = CountDownLatch(1)
        v.transferOwnership("new-owner") { transferOk = it; transferLatch.countDown() }
        assertTrue(transferLatch.await(10, TimeUnit.SECONDS))
        assertEquals(true, transferOk)
        assertEquals(OfficeRoles.EDITOR, v.currentRole)
        stopJobs(v)
    }

    @Test
    fun `renameDocument owner path updates title and index`() = runTest {
        val v = loadedVm()
        // Guards first.
        var guard: Boolean? = null
        v.renameDocument("X") { guard = it }
        assertEquals(false, guard)
        v.currentDocId = "doc-rename"
        v.currentDocKey = ByteArray(32) { 8 }
        v.currentRole = OfficeRoles.VIEWER
        var viewerGuard: Boolean? = null
        v.renameDocument("X") { viewerGuard = it }
        assertEquals(false, viewerGuard)

        v.currentRole = OfficeRoles.OWNER
        val ds = realDs()
        v.saveIndex(ds, listOf(
            OfficeDocMeta(docId = "doc-rename", title = "Old", keyB64 = "", owner = true)))
        val latch = CountDownLatch(1)
        var ok: Boolean? = null
        v.renameDocument("  Fresh Name  ") { ok = it; latch.countDown() }
        assertTrue(latch.await(10, TimeUnit.SECONDS), "rename callback")
        assertEquals(true, ok)
        assertTrue(await { v.onlineDocsMutable.value.firstOrNull()?.title == "Fresh Name" })
        val doc = (v.state.value as OfficeViewModel.ViewState.Loaded).document
        assertEquals("Fresh Name", doc.title)
        // Blank name is refused.
        var blank: Boolean? = null
        v.renameDocument("   ") { blank = it }
        assertEquals(false, blank)
    }

    @Test
    fun `shareCurrentDocument reports registration failure offline`() {
        val v = loadedVm()
        val latch = CountDownLatch(1)
        var err: String? = null
        var called = false
        v.shareCurrentDocument("recipient-x") { err = it; called = true; latch.countDown() }
        assertTrue(latch.await(10, TimeUnit.SECONDS), "share callback")
        assertTrue(called)
        assertNotNull(err)
        assertTrue(err!!.contains("register", ignoreCase = true), err!!)
    }

    @Test
    fun `openOnlineDocument with no readable content errors`() {
        val v = vm()
        val meta = OfficeDocMeta(
            docId = "doc-empty-online", title = "Empty",
            keyB64 = Base64.encode(ByteArray(32) { 12 }),
            owner = false, role = OfficeRoles.VIEWER, ownerKeyB64 = "")
        v.openOnlineDocument(meta)
        assertTrue(
            await(12_000) { v.state.value is OfficeViewModel.ViewState.Error },
            "state was ${v.state.value}")
        val msg = (v.state.value as OfficeViewModel.ViewState.Error).message
        assertTrue(msg.contains("no readable content"), msg)
        stopJobs(v)
    }

    @Test
    fun `syncCurrentDocument and refreshOnline run offline`() = runTest {
        val v = loadedVm("current sync")
        v.currentDocId = "doc-current"
        v.currentDocKey = ByteArray(32) { 13 }
        v.currentTree = DocumentTreeCrdt("dev-current")
        v.onlineEnabledMutable.value = true
        v.syncCurrentDocument()
        // syncDoc persists the tree + cursor as its offline completion signal.
        assertTrue(await(12_000) { realDs().getString("crdt:doc-current") != null })
        v.refreshOnline()
        assertTrue(await(12_000) { realDs().getLong("officeInboxCursor") != null })
        stopJobs(v)
    }

    @Test
    fun `presence and live-push jobs complete offline`() = runTest {
        val v = loadedVm("job text")
        v.currentDocId = "doc-jobs"
        v.currentDocKey = ByteArray(32) { 21 }
        v.currentRole = OfficeRoles.EDITOR
        v.currentTree = DocumentTreeCrdt("dev-jobs")
        v.setLocalCaret(2)
        // Joining (not sleeping) waits for the debounced presence send, which
        // is a no-op without a live socket.
        v.caretPresenceJob?.join()
        v.onLocalEdit()
        // The live-push job syncs via the unreachable relay and completes.
        v.livePushJob?.join()
        // saveTree commits through a suspending DataStore edit while
        // getString reads the asynchronously-updated mirror; on a loaded
        // box the mirror can lag the completed job, so wait for the
        // persisted tree to become visible instead of reading it once.
        assertTrue(
            await(12_000) { realDs().getString("crdt:doc-jobs") != null },
            "live-push job completed but no tree persisted",
        )
        stopJobs(v)
    }

    @Test
    fun `openOnlineDocument with seeded tree loads document`() = runTest {
        val seed = loadedVm("seeded online text")
        val tree = DocumentTreeCrdt("dev-seed")
        tree.updateJson(seed.exportFlat())
        val ds = realDs()
        ds.setString("crdt:doc-seeded", tree.serialize())
        tree.close()
        assertTrue(await { ds.getString("crdt:doc-seeded") != null })
        val v = vm()
        val meta = OfficeDocMeta(
            docId = "doc-seeded", title = "Seeded",
            keyB64 = Base64.encode(ByteArray(32) { 22 }),
            owner = false, role = OfficeRoles.EDITOR, ownerKeyB64 = "")
        v.openOnlineDocument(meta)
        assertTrue(
            await(15_000) { v.state.value is OfficeViewModel.ViewState.Loaded },
            "state was ${v.state.value}")
        val doc = (v.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.TextDocument
        val text = doc.content.filterIsInstance<OdfContentBlock.Paragraph>()
            .joinToString("") { it.paragraph.spans.joinToString("") { s -> s.text } }
        assertTrue(text.contains("seeded online text"), text)
        assertEquals("doc-seeded", v.currentDocId)
        assertNotNull(v.currentTree)
        stopJobs(v)
    }

    @Test
    fun `initSync loads local index when identity exists`() = runTest {
        val v = vm()
        val ds = realDs()
        // Provision identity keys in the real store so hasOnlineIdentity passes.
        ds.setByteArray("officeKemPub", byteArrayOf(1))
        ds.setByteArray("officeDsaPub", byteArrayOf(2))
        ds.setString("officeDeviceId", "slice3-device")
        val metas = listOf(
            OfficeDocMeta(docId = "doc-init", title = "Indexed", keyB64 = "", owner = false))
        v.saveIndex(ds, metas)
        // The store's sync getters read the eager snapshot; wait for hydration.
        assertTrue(await { v.hasOnlineIdentity() }, "identity should hydrate")
        v.initSync()
        assertTrue(await(12_000) { v.onlineDocsMutable.value == metas },
            "onlineDocs=${v.onlineDocsMutable.value}")
        assertTrue(v.onlineEnabledMutable.value)
        stopJobs(v)
    }
}
