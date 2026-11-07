package com.vayunmathur.office.util

import android.net.Uri
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfPageSetup
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.OdfTable
import com.vayunmathur.library.ui.odf.OdfTableCell
import com.vayunmathur.library.ui.odf.OdfTableRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.mockito.Mockito.`when`
import org.robolectric.RobolectricTestRunner

/**
 * Slice 3/4: documents/recent/save-as, tracked changes, footnotes/comments,
 * headers/footers, links, fields and in-text tables on [OfficeViewModel].
 *
 * Robolectric (not plain JVM): several paths use `Uri.parse`/`Uri.fromFile` statics,
 * which are stubs on the plain JVM.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OfficeViewModelDocsSlice3Test : OfficeViewModelSlice3Harness() {

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

    @Test
    fun `createNewTextDocument and clearDocument`() = runTest {
        val vm = idleVm()
        vm.createNewTextDocument()
        assertTrue(vm.state.value is OfficeViewModel.ViewState.Loaded)
        assertTrue(vm.isEditMode.value)
        assertTrue(vm.hasUnsavedChanges.value)
        vm.clearDocument()
        assertTrue(vm.state.value is OfficeViewModel.ViewState.Empty)
        assertFalse(vm.isEditMode.value)
        assertFalse(vm.hasUnsavedChanges.value)
        assertFalse(vm.isOnline.value)
    }

    @Test
    fun `recent files add dedupe cap and clear`() = runTest {
        val vm = idleVm()
        val uri = Uri.parse("file:///tmp/a.odt")
        vm.addToRecent(app, uri, "a.odt")
        vm.addToRecent(app, uri, "a.odt")
        assertEquals(1, vm.getRecentFiles(app).size)
        assertEquals("a.odt", vm.getRecentFiles(app)[0].second)
        repeat(25) { i -> vm.addToRecent(app, Uri.parse("file:///tmp/$i.odt"), "$i.odt") }
        assertEquals(MAX_RECENT, vm.getRecentFiles(app).size)
        assertEquals("24.odt", vm.getRecentFiles(app)[0].second)
        vm.clearRecentFiles(app)
        assertTrue(vm.getRecentFiles(app).isEmpty())
    }

    @Test
    fun `needsSaveAs and isWritable for file uris`() = runTest {
        val vm = idleVm()
        assertTrue(vm.needsSaveAs())
        val missing = java.io.File(tmpDir, "nope.odt")
        vm.originalUri = Uri.fromFile(missing)
        // Non-existent file is not writable -> Save As needed.
        assertTrue(vm.needsSaveAs())
        val real = java.io.File(tmpDir, "doc.odt")
        real.writeText("x")
        vm.originalUri = Uri.fromFile(real)
        assertFalse(vm.needsSaveAs())
        assertTrue(vm.isWritable(Uri.fromFile(real)))
        assertFalse(vm.isWritable(Uri.fromFile(missing)))
    }

    @Test
    fun `needsSaveAs with content uri consults permission`() = runTest {
        val vm = idleVm()
        val uri = Uri.parse("content://com.example/docs/1")
        `when`(app.checkCallingOrSelfUriPermission(uri, android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
            .thenReturn(android.content.pm.PackageManager.PERMISSION_DENIED)
        vm.originalUri = uri
        assertTrue(vm.needsSaveAs())
    }

    @Test
    fun `save with no target is a no-op without loaded doc`() = runTest {
        val vm = idleVm()
        vm.save()
        assertFalse(vm.isSaving.value)
    }

    @Test
    fun `persistToAppStorage returns file uris inside docs dir`() = runTest {
        val vm = idleVm()
        val inside = java.io.File(java.io.File(tmpDir, "documents").apply { mkdirs() }, "x.odt")
        inside.writeText("x")
        val uri = Uri.fromFile(inside)
        assertEquals(uri, vm.persistToAppStorage(uri, "x.odt"))
    }

    @Test
    fun `accept reject change insertion and deletion`() = runTest {
        fun changeDoc() = textDoc("hello world").copy(
            changes = listOf(
                com.vayunmathur.library.ui.odf.OdfChange(id = "c1", type = "insertion"),
                com.vayunmathur.library.ui.odf.OdfChange(id = "c2", type = "deletion"),
            ),
            content = listOf(
                OdfContentBlock.Paragraph(OdfParagraph(listOf(
                    OdfSpan("hello "),
                    OdfSpan("brave ", changeId = "c1", changeKind = "insertion"),
                    OdfSpan("world", changeId = "c2", changeKind = "deletion"),
                ))),
            ),
        )
        val vm = newVm()
        vm.stateMutable.value = OfficeViewModel.ViewState.Loaded(changeDoc())
        // Accept insertion: span stays, marker cleared.
        vm.acceptChange("c1")
        var doc = textOf(vm)
        assertTrue(doc.changes.none { it.id == "c1" })
        assertTrue(doc.content.joinToString("") {
            ((it as OdfContentBlock.Paragraph).paragraph.spans.joinToString("") { s -> s.text })
        }.contains("brave"))
        // Reject deletion: text kept, marker cleared.
        vm.rejectChange("c2")
        doc = textOf(vm)
        assertTrue(doc.changes.none { it.id == "c2" })
        assertTrue(textOf(vm).content.joinToString("") {
            ((it as OdfContentBlock.Paragraph).paragraph.spans.joinToString("") { s -> s.text })
        }.contains("world"))
        // Unknown id is a no-op.
        vm.acceptChange("nope")
        // Non-text doc is a no-op.
        val sheet = newVm()
        sheet.stateMutable.value = OfficeViewModel.ViewState.Loaded(
            OdfDocument.Spreadsheet(title = "B", sheets = emptyList()))
        sheet.acceptChange("c1")
        sheet.rejectChange("c1")
    }

    @Test
    fun `acceptAll and rejectAll changes`() = runTest {
        fun changeDoc() = textDoc("ab").copy(
            changes = listOf(
                com.vayunmathur.library.ui.odf.OdfChange(id = "c1", type = "insertion"),
                com.vayunmathur.library.ui.odf.OdfChange(id = "c2", type = "deletion"),
            ),
            content = listOf(
                OdfContentBlock.Paragraph(OdfParagraph(listOf(
                    OdfSpan("a", changeId = "c1", changeKind = "insertion"),
                    OdfSpan("b", changeId = "c2", changeKind = "deletion"),
                ))),
            ),
        )
        val vm = newVm()
        vm.stateMutable.value = OfficeViewModel.ViewState.Loaded(changeDoc())
        vm.acceptAllChanges()
        var text = textOf(vm).content.joinToString("") {
            ((it as OdfContentBlock.Paragraph).paragraph.spans.joinToString("") { s -> s.text })
        }
        // Insertion kept, deletion removed.
        assertEquals("a", text)
        assertTrue(textOf(vm).changes.isEmpty())
        val vm2 = newVm()
        vm2.stateMutable.value = OfficeViewModel.ViewState.Loaded(changeDoc())
        vm2.rejectAllChanges()
        text = textOf(vm2).content.joinToString("") {
            ((it as OdfContentBlock.Paragraph).paragraph.spans.joinToString("") { s -> s.text })
        }
        // Insertion removed, deletion kept.
        assertEquals("b", text)
    }

    @Test
    fun `page setup header footer footnote comment`() = runTest {
        val vm = loaded("body text")
        vm.setPageSetup(OdfPageSetup(widthPx = 100f, heightPx = 50f))
        assertEquals(true, textOf(vm).pageSetup!!.isLandscape)
        vm.setHeaderText("head\ntwo")
        vm.setFooterText("foot")
        assertEquals(2, textOf(vm).headerParagraphs.size)
        assertEquals(1, textOf(vm).footerParagraphs.size)
        vm.setHeaderText("  ")
        assertTrue(textOf(vm).headerParagraphs.isEmpty())
        vm.insertFootnote(0, 0, 9, "note body")
        assertEquals(1, textOf(vm).footnotes.size)
        assertEquals("note body", textOf(vm).footnotes[0].body[0].spans[0].text)
        assertTrue(paraHas(vm, "[1]"))
        vm.insertComment(0, "Ann", "nice")
        assertTrue(textOf(vm).content.joinToString("") {
            ((it as? OdfContentBlock.Paragraph)?.paragraph?.spans?.any { s -> s.annotation != null } ?: false).toString()
        }.contains("true"))
        // Resolve the comment span (last span of block 0).
        val spans = (textOf(vm).content[0] as OdfContentBlock.Paragraph).paragraph.spans
        vm.resolveComment(0, spans.size - 1)
        assertFalse((textOf(vm).content[0] as OdfContentBlock.Paragraph)
            .paragraph.spans.any { it.annotation != null })
    }

    private fun paraHas(vm: OfficeViewModel, needle: String): Boolean =
        textOf(vm).content.any {
            ((it as? OdfContentBlock.Paragraph)?.paragraph?.spans?.joinToString("") { s -> s.text }
                ?: "").contains(needle)
        }

    @Test
    fun `links set inspect remove selected text`() = runTest {
        val vm = loaded("click here now")
        // runSelectedText reads the current run text.
        assertEquals("click", vm.runSelectedText(0, 0, 0, 5))
        vm.setLink(0, 0, 6, 10, "here", "https://example.com")
        val info = vm.linkAt(0, 0, 8)
        assertEquals("https://example.com", info!!.url)
        assertEquals("here", info.text)
        assertEquals(null, vm.linkAt(0, 0, 2))
        vm.removeLinkInRun(0, 0, 6, 10)
        assertEquals(null, vm.linkAt(0, 0, 8))
        assertTrue(paraHas(vm, "here"))
    }

    @Test
    fun `field insert and display values`() = runTest {
        val vm = loaded("today: ")
        vm.insertFieldInRun(0, 0, 7, "date", vm.fieldDisplayValue("date"))
        assertTrue(paraHas(vm, "-"))
        assertEquals("1", vm.fieldDisplayValue("page-number"))
        assertEquals("1", vm.fieldDisplayValue("page-count"))
        assertEquals("", vm.fieldDisplayValue("bogus-kind"))
        // Metadata-backed fields.
        vm.updateMetadata { it.copy(author = "Ann", title = "T", subject = "S", description = "D") }
        assertEquals("Ann", vm.fieldDisplayValue("author-name"))
        assertEquals("T", vm.fieldDisplayValue("title"))
        assertEquals("S", vm.fieldDisplayValue("subject"))
        assertEquals("D", vm.fieldDisplayValue("description"))
        assertTrue(vm.fieldDisplayValue("time").contains(":"))
    }

    @Test
    fun `in-text tables crud merge format`() = runTest {
        val vm = loaded("top")
        vm.insertTableForTest()
        var table = textOf(vm).tableAt(1)
        assertEquals(2, table.rows.size)
        assertEquals(2, table.rows[0].cells.size)
        vm.updateTextTableCell(1, 0, 0, "A\nB")
        assertEquals(2, textOf(vm).tableAt(1).rows[0].cells[0].paragraphs.size)
        vm.textTableAddRow(1, 0)
        vm.textTableAddColumn(1, 0)
        assertEquals(3, textOf(vm).tableAt(1).rows.size)
        assertEquals(3, textOf(vm).tableAt(1).rows[0].cells.size)
        vm.mergeTextTableCells(1, 0, 0, 1, 1)
        assertEquals(2, textOf(vm).tableAt(1).rows[0].cells[0].colSpan)
        assertTrue(textOf(vm).tableAt(1).rows[1].cells[1].isCovered)
        vm.unmergeTextTableCells(1, 0, 0)
        assertEquals(1, textOf(vm).tableAt(1).rows[0].cells[0].colSpan)
        vm.setTextTableCellSpanFormat(1, 0, 0) { it.copy(bold = true) }
        assertTrue(textOf(vm).tableAt(1).rows[0].cells[0].paragraphs.all { p ->
            p.spans.all { it.bold }
        })
        vm.setTextTableCellAlignment(1, 0, 0, androidx.compose.ui.text.style.TextAlign.Center)
        vm.setTextTableCellBackground(1, 0, 0, 0xFFFF0000L)
        assertEquals(0xFFFF0000L, textOf(vm).tableAt(1).rows[0].cells[0].backgroundColor)
        vm.textTableDeleteRow(1, 0)
        vm.textTableDeleteColumn(1, 0)
        assertEquals(2, textOf(vm).tableAt(1).rows.size)
        // Out-of-range row/col edits are contained no-ops.
        vm.updateTextTableCell(1, 9, 9, "z")
        vm.textTableDeleteRow(1, 9)
    }

    private fun OfficeViewModel.insertTableForTest() {
        insertTable(0, 2, 2)
    }

    private fun OdfDocument.TextDocument.tableAt(i: Int): OdfTable =
        (content[i] as OdfContentBlock.Table).table

    @Test
    fun `currentDocRole online id canEdit myDisplayName`() = runTest {
        val vm = idleVm()
        assertEquals(OfficeRoles.OWNER, vm.currentDocRole())
        assertEquals(null, vm.currentOnlineDocId())
        assertTrue(vm.canEditCurrent())
        // Offline docs are always editable; the role gate applies once online.
        vm.currentRole = OfficeRoles.VIEWER
        assertTrue(vm.canEditCurrent())
        vm.currentDocId = "d1"
        assertFalse(vm.canEditCurrent())
        assertEquals("d1", vm.currentOnlineDocId())
        assertTrue(vm.myDisplayName().startsWith("User "))
    }

    @Test
    fun `signing bytes builders are canonical`() = runTest {
        val vm = idleVm()
        val m = OfficeMember("id1", "Ann", OfficeRoles.EDITOR)
        assertEquals("doc|id1|editor",
            vm.memberSigningBytes("doc", m).decodeToString())
        assertEquals("doc|title|Hello",
            vm.titleSigningBytes("doc", "Hello").decodeToString())
        assertEquals("doc|owner|n1|keyB64",
            vm.ownerSigningBytes("doc", "n1", "keyB64").decodeToString())
        val epoch = vm.epochSigningBytes("doc", 2, mapOf("b" to "2", "a" to "1"))
        assertEquals("doc|2|a=1|b=2", epoch.decodeToString())
        assertEquals("https://maproj.org/of/share#id=d9&owner=${vm.syncDeviceId}",
            vm.shareLinkFor("d9"))
    }

    @Test
    fun `withTitle setLoadedTitle resolveShareTitle`() = runTest {
        val vm = loaded("x")
        val doc = textOf(vm)
        assertEquals("N", (vm.withTitle(doc, "N") as OdfDocument.TextDocument).title)
        vm.setLoadedTitle("Renamed")
        assertEquals("Renamed", textOf(vm).title)
        // First share: blank docName falls back to doc title.
        vm.currentDocId = null
        assertEquals("Doc", vm.resolveShareTitle("  ", "Doc"))
        // Later share keeps the stored online title.
        vm.currentDocId = "d1"
        vm.onlineDocsMutable.value = listOf(
            OfficeDocMeta(docId = "d1", title = "Stored", keyB64 = "", owner = true))
        assertEquals("Stored", vm.resolveShareTitle("New", "Doc"))
    }
}
