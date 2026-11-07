package com.vayunmathur.office.util

import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.OdfTable
import com.vayunmathur.library.ui.odf.OdfTableCell
import com.vayunmathur.library.ui.odf.OdfTableRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock

/**
 * Slice 3/4, round 2: tracked-change branches in tables/TOC, footnote/comment edge
 * paths, page-setup/header guards, plus the network-free sync helpers
 * (role gates, signing inputs, DataStore index/requests, live presence decode).
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OfficeViewModelTracked2Slice3Test : OfficeViewModelSlice3Harness() {

    private fun textDoc(vararg texts: String) = OdfDocument.TextDocument(
        title = "Doc",
        content = texts.map { OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(it)))) },
    )

    private fun loaded(vararg texts: String): OfficeViewModel {
        val vm = newVm()
        vm.stateMutable.value = OfficeViewModel.ViewState.Loaded(textDoc(*texts))
        return vm
    }

    private fun textOf(vm: OfficeViewModel): OdfDocument.TextDocument =
        (vm.state.value as OfficeViewModel.ViewState.Loaded).document as OdfDocument.TextDocument

    private fun paraTexts(doc: OdfDocument.TextDocument): List<String> =
        doc.content.map {
            ((it as? OdfContentBlock.Paragraph)?.paragraph?.spans?.joinToString("") { s -> s.text })
                ?: "<${it.javaClass.simpleName}>"
        }

    @Test
    fun `transformChangeSpans covers tables and toc`() = runTest {
        val cell = OdfTableCell(listOf(OdfParagraph(listOf(
            OdfSpan("keep "),
            OdfSpan("gone", changeId = "d1", changeKind = "deletion"),
        ))))
        val doc = textDoc("p").copy(
            changes = listOf(com.vayunmathur.library.ui.odf.OdfChange(id = "d1", type = "deletion")),
            content = listOf(
                OdfContentBlock.Table(OdfTable("T", rows = listOf(OdfTableRow(listOf(cell))))),
                OdfContentBlock.TableOfContents("TOC", listOf(
                    OdfParagraph(listOf(
                        OdfSpan("entry "),
                        OdfSpan("old", changeId = "d1", changeKind = "deletion"),
                    )))),
                OdfContentBlock.PageBreak,
            ),
        )
        val vm = newVm()
        vm.stateMutable.value = OfficeViewModel.ViewState.Loaded(doc)
        // Accept deletion: marked spans removed from table cell and TOC entry.
        vm.acceptChange("d1")
        val after = textOf(vm)
        val cellText = after.let {
            (((it.content[0] as OdfContentBlock.Table).table.rows[0].cells[0]
                .paragraphs[0].spans.joinToString("") { s -> s.text }))
        }
        assertEquals("keep ", cellText)
        val tocText = ((after.content[1] as OdfContentBlock.TableOfContents)
            .entries[0].spans.joinToString("") { it.text })
        assertEquals("entry ", tocText)
        assertTrue(after.changes.isEmpty())
    }

    @Test
    fun `reject insertion clears markers but keeps other spans`() = runTest {
        val doc = textDoc("p").copy(
            changes = listOf(com.vayunmathur.library.ui.odf.OdfChange(id = "i1", type = "insertion")),
            content = listOf(
                OdfContentBlock.Paragraph(OdfParagraph(listOf(
                    OdfSpan("a", changeId = "i1", changeKind = "insertion"),
                    OdfSpan("b"),
                ))),
            ),
        )
        val vm = newVm()
        vm.stateMutable.value = OfficeViewModel.ViewState.Loaded(doc)
        vm.rejectChange("i1")
        assertEquals(listOf("b"), paraTexts(textOf(vm)))
    }

    @Test
    fun `footnote without paragraph run still records footnote`() = runTest {
        val vm = newVm()
        vm.stateMutable.value = OfficeViewModel.ViewState.Loaded(
            OdfDocument.TextDocument(title = "D", content = listOf(OdfContentBlock.PageBreak)))
        // No paragraph run (block 0 is a PageBreak) -> marker insert skipped, footnote kept.
        vm.insertFootnote(0, 0, 0, "lonely")
        assertEquals(1, textOf(vm).footnotes.size)
        assertEquals("1", textOf(vm).footnotes[0].citation)
    }

    @Test
    fun `endnote flag and citation increments`() = runTest {
        val vm = loaded("t")
        vm.insertFootnote(0, 0, 1, "first")
        vm.insertFootnote(0, 0, 1, "second", isEndnote = true)
        assertEquals(listOf("1", "2"), textOf(vm).footnotes.map { it.citation })
        assertTrue(textOf(vm).footnotes[1].isEndnote)
    }

    @Test
    fun `comment guards non-paragraph and plain spans`() = runTest {
        val vm = newVm()
        vm.stateMutable.value = OfficeViewModel.ViewState.Loaded(
            OdfDocument.TextDocument(title = "D", content = listOf(OdfContentBlock.PageBreak)))
        // Non-paragraph block -> no-op.
        vm.insertComment(0, "Ann", "x")
        assertTrue(textOf(vm).content.all { it is OdfContentBlock.PageBreak })
        val vm2 = loaded("plain")
        // Span without annotation -> resolve is a no-op (text kept).
        vm2.resolveComment(0, 0)
        assertEquals(listOf("plain"), paraTexts(textOf(vm2)))
        // Out-of-range indices -> no-op.
        vm2.resolveComment(0, 9)
        vm2.resolveComment(9, 0)
        assertEquals(listOf("plain"), paraTexts(textOf(vm2)))
    }

    @Test
    fun `page setup header footer ignore non-text docs`() = runTest {
        val vm = newVm()
        vm.stateMutable.value = OfficeViewModel.ViewState.Loaded(
            OdfDocument.Spreadsheet(title = "B", sheets = emptyList()))
        vm.setPageSetup(com.vayunmathur.library.ui.odf.OdfPageSetup())
        vm.setHeaderText("h")
        vm.setFooterText("f")
        // Still a spreadsheet, untouched.
        assertTrue((vm.state.value as OfficeViewModel.ViewState.Loaded)
            .document is OdfDocument.Spreadsheet)
        assertFalse(vm.canUndo.value)
    }

    @Test
    fun `memberKey serves cache without network`() = runTest {
        val vm = idleVm()
        vm.memberKeyCache["peer1"] = byteArrayOf(1, 2, 3)
        assertEquals(listOf<Byte>(1, 2, 3), vm.memberKey("peer1")!!.toList())
        // NOTE: uncached ids hit OfficeSync.getKey (network) — not exercised here.
    }

    @Test
    fun `fetch null-owner paths return empty without network`() = runTest {
        val vm = idleVm()
        val key = byteArrayOf(9, 9)
        assertEquals(0 to key, vm.fetchCurrentKey("d", key, null).let { it.first to it.second })
        assertTrue(vm.fetchMembers("d", key, null).isEmpty())
        assertNull(vm.fetchTitle("d", key, null))
        // Owner-key fetch with blank base returns null before any network.
        assertNull(vm.fetchOwnerKey("d", ""))
        assertNull(vm.fetchOwnerKey("d", "!!!not-base64!!!"))
    }

    @Test
    fun `pollTitle mismatch and setLoadedTitle guards`() = runTest {
        val vm = loaded("x")
        // Different doc id -> early return, no network.
        vm.pollTitle("other", byteArrayOf(1))
        assertEquals("x", paraTexts(textOf(vm))[0])
        // No loaded doc -> setLoadedTitle is a no-op.
        val empty = idleVm()
        empty.setLoadedTitle("N")
        assertTrue(empty.state.value is OfficeViewModel.ViewState.Empty)
        // Same title -> no state churn.
        val before = vm.state.value
        vm.setLoadedTitle("Doc")
        assertEquals(before, vm.state.value)
    }

    @Test
    fun `currentOnlineTitle withTitle all variants`() = runTest {
        val vm = idleVm()
        assertNull(vm.currentOnlineTitle())
        vm.currentDocId = "d1"
        assertNull(vm.currentOnlineTitle())
        vm.onlineDocsMutable.value = listOf(
            OfficeDocMeta(docId = "d1", title = "T", keyB64 = "", owner = false))
        assertEquals("T", vm.currentOnlineTitle())
        val sheet = OdfDocument.Spreadsheet(title = "S", sheets = emptyList())
        assertEquals("N", (vm.withTitle(sheet, "N") as OdfDocument.Spreadsheet).title)
        val pres = OdfDocument.Presentation(title = "P", slides = emptyList())
        assertEquals("N", (vm.withTitle(pres, "N") as OdfDocument.Presentation).title)
        val draw = OdfDocument.Drawing(title = "G", pages = emptyList())
        assertEquals("N", (vm.withTitle(draw, "N") as OdfDocument.Drawing).title)
    }

    @Test
    fun `index and requests round-trip through datastore`() = runTest {
        // Map-backed DataStoreUtils mock: getInstance() is a process-wide singleton
        // bound to whichever test's (deleted) tmpDir came first, so drive the
        // suspend index/request helpers directly against a fake instead.
        val backing = mutableMapOf<String, String?>()
        val ds = mock(com.vayunmathur.library.util.DataStoreUtils::class.java)
        `when`(ds.getString(anyString()))
            .thenAnswer { backing[it.arguments[0] as String] }
        `when`(ds.setString(anyString(), anyString(), anyBoolean()))
            .thenAnswer {
                backing[it.arguments[0] as String] = it.arguments[1] as String?
                null
            }
        val vm = idleVm()
        val metas = listOf(
            OfficeDocMeta(docId = "d1", title = "One", keyB64 = "a2V5", owner = true))
        vm.saveIndex(ds, metas)
        assertEquals(metas, vm.loadIndex(ds))
        assertEquals(metas, vm.onlineDocsMutable.value)
        val reqs = listOf(OfficeSync.JoinRequest(docId = "d1", requesterId = "r1"))
        vm.saveRequests(ds, reqs)
        assertEquals(reqs, vm.loadRequests(ds))
        assertEquals(reqs, vm.pendingRequestsMutable.value)
        // Corrupt blobs fall back to empty.
        backing["officeDocsIndex"] = "not-json{{"
        assertTrue(vm.loadIndex(ds).isEmpty())
        backing["officePendingRequests"] = "not-json{{"
        assertTrue(vm.loadRequests(ds).isEmpty())
    }

    @Test
    fun `owner guards refuse without owner role`() = runTest {
        val vm = idleVm()
        vm.currentRole = OfficeRoles.VIEWER
        vm.currentDocKey = byteArrayOf(1)
        // recordMembers + rotateKey early-return for non-owners (no network, no crash).
        vm.recordMembers("d", byteArrayOf(1), listOf(OfficeMember("x")))
        vm.rotateKey("d")
        assertEquals(0, vm.currentEpoch)
    }

    @Test
    fun `applyLivePresence decodes and tracks peers`() = runTest {
        val selfId = OfficeSync.deviceId
        val vm = idleVm()
        val key = ByteArray(32) { it.toByte() }
        fun blob(p: OfficePresence): String {
            val raw = com.vayunmathur.e2ee.E2ee.aesEncrypt(
                key, vm.syncJson.encodeToString(OfficePresence.serializer(), p).encodeToByteArray())
            return kotlin.io.encoding.Base64.encode(raw)
        }
        // Self presence is ignored.
        vm.applyLivePresence(OfficeSync.LiveMsg(t = "presence", data = blob(
            OfficePresence(selfId, "Me", typing = false, ts = 1))), key)
        assertTrue(vm.remotePresenceMutable.value.isEmpty())
        // Peer presence is recorded.
        vm.applyLivePresence(OfficeSync.LiveMsg(t = "presence", data = blob(
            OfficePresence("peer1", "Ann", typing = true, ts = 1, caret = 3))), key)
        val peers = vm.remotePresenceMutable.value
        assertEquals(1, peers.size)
        assertEquals("peer1", peers[0].id)
        assertTrue(peers[0].typing)
        assertTrue(peers[0].typingTs > 0)
        // Garbage blob is ignored.
        vm.applyLivePresence(OfficeSync.LiveMsg(t = "presence", data = "!!!"), key)
        assertEquals(1, vm.remotePresenceMutable.value.size)
        // Typing=false keeps the previous typing timestamp.
        val tsBefore = vm.remotePresenceMutable.value[0].typingTs
        vm.applyLivePresence(OfficeSync.LiveMsg(t = "presence", data = blob(
            OfficePresence("peer1", "Ann", typing = false, ts = 1))), key)
        assertEquals(tsBefore, vm.remotePresenceMutable.value[0].typingTs)
    }

    @Test
    fun `myName derives suffix and enableOnlineSharing short-circuits`() = runTest {
        val vm = idleVm()
        // "User " + last 4 of the device id (whatever it is in this environment).
        assertEquals("User " + OfficeSync.deviceId.takeLast(4), vm.myName())
        assertEquals(vm.myName(), vm.myDisplayName())
        vm.onlineEnabledMutable.value = true
        // Already enabled -> returns before initSync/network.
        vm.enableOnlineSharing()
        assertTrue(vm.onlineEnabled.value)
    }
}
