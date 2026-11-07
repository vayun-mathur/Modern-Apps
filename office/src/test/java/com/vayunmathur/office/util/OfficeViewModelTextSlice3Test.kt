package com.vayunmathur.office.util

import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.ParagraphStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Slice 3/4: text-document editing ops on [OfficeViewModel] — paragraph CRUD, span
 * formatting, list handling, find/replace, links, fields, comments and tables.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OfficeViewModelTextSlice3Test : OfficeViewModelSlice3Harness() {

    private fun textDoc(vararg texts: String) = OdfDocument.TextDocument(
        title = "Doc",
        content = texts.map { OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(it)))) },
    )

    private fun loaded(vararg texts: String): OfficeViewModel {
        val vm = newVm()
        vm.stateMutable.value = OfficeViewModel.ViewState.Loaded(textDoc(*texts))
        return vm
    }

    private fun paraText(vm: OfficeViewModel, i: Int = 0): String {
        val doc = (vm.state.value as OfficeViewModel.ViewState.Loaded).document as OdfDocument.TextDocument
        return ((doc.content[i] as OdfContentBlock.Paragraph).paragraph.spans.joinToString("") { it.text })
    }

    @Test
    fun `updateParagraphText replaces text and marks unsaved`() = runTest {
        val vm = loaded("hello")
        vm.updateParagraphText(0, "hello world")
        assertEquals("hello world", paraText(vm))
        assertTrue(vm.hasUnsavedChanges.value)
        assertTrue(vm.canUndo.value)
        // Same text is a no-op (library returns null) — no new undo entry.
        val undos = vm.canUndo.value
        vm.updateParagraphText(0, "hello world")
        assertEquals(undos, vm.canUndo.value)
        assertEquals("hello world", paraText(vm))
    }

    @Test
    fun `updateParagraphText on missing block is a no-op`() = runTest {
        val vm = loaded("a")
        vm.updateParagraphText(9, "x")
        assertEquals("a", paraText(vm))
        assertFalse(vm.canUndo.value)
    }

    @Test
    fun `bold italic underline strikethrough toggles`() = runTest {
        val vm = loaded("styled")
        vm.toggleBold(0)
        assertTrue((vm.state.value as OfficeViewModel.ViewState.Loaded).let {
            (((it.document as OdfDocument.TextDocument).content[0] as OdfContentBlock.Paragraph).paragraph.spans[0].bold)
        })
        vm.toggleBold(0)
        vm.toggleItalic(0)
        vm.toggleUnderline(0)
        vm.toggleStrikethrough(0)
        val spans = ((vm.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.TextDocument)
            .let { ((it.content[0] as OdfContentBlock.Paragraph).paragraph.spans) }
        assertFalse(spans[0].bold)
        assertTrue(spans[0].italic)
        assertTrue(spans[0].underline)
        assertTrue(spans[0].strikethrough)
    }

    @Test
    fun `setSpanColor and setSpanFontSize`() = runTest {
        val vm = loaded("colored")
        vm.setSpanColor(0, 0xFFFF0000L)
        vm.setSpanFontSize(0, 24f)
        val span = ((vm.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.TextDocument)
            .let { ((it.content[0] as OdfContentBlock.Paragraph).paragraph.spans[0]) }
        assertEquals(0xFFFF0000L, span.color)
        assertEquals(24f, span.fontSize)
        vm.setSpanColor(0, null)
        val cleared = ((vm.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.TextDocument)
            .let { ((it.content[0] as OdfContentBlock.Paragraph).paragraph.spans[0]) }
        assertEquals(null, cleared.color)
    }

    @Test
    fun `paragraph style and alignment`() = runTest {
        val vm = loaded("head")
        vm.setParagraphStyle(0, ParagraphStyle.HEADING1)
        vm.setParagraphAlignment(0, androidx.compose.ui.text.style.TextAlign.Center)
        val para = ((vm.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.TextDocument)
            .let { ((it.content[0] as OdfContentBlock.Paragraph).paragraph) }
        assertEquals(ParagraphStyle.HEADING1, para.style)
        assertEquals(androidx.compose.ui.text.style.TextAlign.Center, para.alignment)
    }

    @Test
    fun `add duplicate move delete paragraph`() = runTest {
        val vm = loaded("one", "two")
        vm.addParagraphAfter(0)
        assertEquals(3, (vm.state.value as OfficeViewModel.ViewState.Loaded)
            .let { ((it.document as OdfDocument.TextDocument).content.size) })
        vm.duplicateParagraph(0)
        assertEquals("one", paraText(vm, 0))
        assertEquals("one", paraText(vm, 1))
        vm.moveParagraphDown(0)
        assertEquals("one", paraText(vm, 1))
        vm.moveParagraphUp(1)
        assertEquals("one", paraText(vm, 0))
        val sizeBefore = ((vm.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.TextDocument).content.size
        vm.deleteParagraph(0)
        assertEquals(sizeBefore - 1, ((vm.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.TextDocument).content.size)
    }

    @Test
    fun `list toggles indent outdent checkbox`() = runTest {
        val vm = loaded("item")
        vm.toggleListItem(0)
        vm.toggleNumberedList(0)
        vm.indentParagraph(0)
        vm.outdentParagraph(0)
        vm.toggleCheckbox(0)
        vm.setCheckboxChecked(0, true)
        // No crash and still a single paragraph with the same text.
        assertEquals("item", paraText(vm))
        assertTrue(vm.hasUnsavedChanges.value)
    }

    @Test
    fun `replaceInDocument replaces first or all`() = runTest {
        val vm = loaded("foo bar foo")
        val one = vm.replaceInDocument("foo", "baz", replaceAll = false)
        assertEquals(1, one)
        assertEquals("baz bar foo", paraText(vm))
        val rest = vm.replaceInDocument("foo", "baz", replaceAll = true)
        assertEquals(1, rest)
        assertEquals("baz bar baz", paraText(vm))
        assertEquals(0, vm.replaceInDocument("", "x", replaceAll = true))
        assertEquals(0, vm.replaceInDocument("zzz", "x", replaceAll = true))
    }

    @Test
    fun `replaceInDocument honors case and whole-word`() = runTest {
        val vm = loaded("Foo foobar FOO")
        // Case-sensitive "foo" still matches inside "foobar".
        assertEquals(1, vm.replaceInDocument("foo", "x", replaceAll = true, matchCase = true))
        assertEquals("Foo xbar FOO", paraText(vm))
        val vm2 = loaded("Foo foobar FOO")
        assertEquals(2, vm2.replaceInDocument("foo", "x", replaceAll = true, wholeWord = true))
        assertEquals("x foobar x", paraText(vm2))
    }

    @Test
    fun `findMatchBlocks finds matching block indices`() = runTest {
        val vm = loaded("alpha", "beta", "alphabet")
        assertEquals(listOf(0, 2), vm.findMatchBlocks("alpha"))
        assertEquals(listOf(1), vm.findMatchBlocks("BETA"))
        assertEquals(listOf(1), vm.findMatchBlocks("beta", matchCase = true))
        assertEquals(emptyList(), vm.findMatchBlocks(""))
        assertEquals(emptyList(), vm.findMatchBlocks("zzz"))
    }

    @Test
    fun `search pattern and span replacement helpers`() = runTest {
        val pattern = buildSearchPattern("a", matchCase = false, wholeWord = false)!!
        val (span, delta, changed) = applyReplacementToSpan(
            OdfSpan("aaa"), pattern, "b", replaceAll = false, count = 1)
        assertEquals("aaa", span.text)
        assertEquals(0, delta)
        assertFalse(changed)
        val (span2, delta2, changed2) = applyReplacementToSpan(
            OdfSpan("aaa"), pattern, "b", replaceAll = true, count = 0)
        assertEquals("bbb", span2.text)
        assertEquals(3, delta2)
        assertTrue(changed2)
    }

    @Test
    fun `insertTableOfContents needs headings`() = runTest {
        val plain = loaded("just body")
        plain.insertTableOfContents(0)
        // No headings -> no-op.
        assertEquals(1, ((plain.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.TextDocument).content.size)
        val headed = loaded("Title", "Body")
        headed.setParagraphStyle(0, ParagraphStyle.HEADING1)
        headed.insertTableOfContents(1)
        val content = ((headed.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.TextDocument).content
        assertTrue(content.any { it is OdfContentBlock.TableOfContents })
        assertTrue(content.any { it is OdfContentBlock.PageBreak })
    }

    @Test
    fun `insertTable insertPageBreak insertHyperlink bookmarks metadata`() = runTest {
        val vm = loaded("p0")
        vm.insertTable(0, 2, 3)
        vm.insertPageBreak(0)
        vm.insertHyperlink(0, "click", "https://example.com")
        vm.addBookmark("b1", 0)
        vm.removeBookmark("b1")
        vm.updateMetadata { it.copy(title = "New Title") }
        val doc = (vm.state.value as OfficeViewModel.ViewState.Loaded).document as OdfDocument.TextDocument
        assertEquals("New Title", doc.metadata.title)
        assertTrue(doc.bookmarks.isEmpty())
        assertTrue(doc.content.any { it is OdfContentBlock.Table })
        assertTrue(doc.content.any { it is OdfContentBlock.PageBreak })
    }

    @Test
    fun `insertChart and updateChart round trip`() = runTest {
        val vm = loaded("p")
        val chart = com.vayunmathur.library.ui.odf.OdfChart(
            type = com.vayunmathur.library.ui.odf.ChartType.BAR,
            categories = listOf("a"),
            series = listOf(com.vayunmathur.library.ui.odf.OdfChartSeries("s", listOf(1f))),
        )
        vm.insertChart(0, chart)
        val withChart = (vm.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.TextDocument
        assertTrue(withChart.content[1] is OdfContentBlock.Chart)
        val chart2 = chart.copy(title = "T")
        vm.updateChart(1, chart2)
        val updated = ((vm.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.TextDocument).content[1] as OdfContentBlock.Chart
        assertEquals("T", updated.chart.title)
        // Wrong index is a no-op.
        vm.updateChart(0, chart)
        assertTrue(((vm.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.TextDocument).content[0] is OdfContentBlock.Paragraph)
    }

    @Test
    fun `run helpers locate and measure paragraphs`() = runTest {
        val vm = loaded("ab", "cde")
        val paras = vm.runParas(0, 1)!!
        assertEquals(listOf(2, 3), vm.paraLens(paras))
        assertEquals(0 to 1, vm.runLocate(listOf(2, 3), 1))
        assertEquals(1 to 0, vm.runLocate(listOf(2, 3), 3))
        assertEquals(3, vm.paragraphStartGlobal(listOf(2, 3), 1))
        assertEquals(null, vm.runParas(-1, 1))
        assertEquals(null, vm.runParas(0, 5))
        assertEquals(1, vm.runParagraphIndexAt(0, 1, 4))
    }

    @Test
    fun `updateParagraphRun edits across paragraphs`() = runTest {
        val vm = loaded("hello", "world")
        vm.updateParagraphRun(0, 1, "hello brave\nworld")
        assertEquals("hello brave", paraText(vm, 0))
        assertEquals("world", paraText(vm, 1))
    }

    @Test
    fun `insertTextInRun and clearRunFormatting`() = runTest {
        val vm = loaded("helloworld")
        vm.applyRunSpanStyle(0, 0, 0, 5) { it.copy(bold = true) }
        assertTrue(vm.runRangeHasFormat(0, 0, 0, 5) { it.bold })
        vm.clearRunFormatting(0, 0, 0, 5)
        assertFalse(vm.runRangeHasFormat(0, 0, 0, 5) { it.bold })
        vm.insertTextInRun(0, 0, 5, " ")
        assertEquals("hello world", paraText(vm))
    }

    @Test
    fun `applySpanStyleToRange and rangeHasFormat`() = runTest {
        val vm = loaded("hello")
        vm.applySpanStyleToRange(0, 0, 5) { it.copy(italic = true) }
        assertTrue(vm.rangeHasFormat(0, 0, 5) { it.italic })
        assertFalse(vm.rangeHasFormat(9, 0, 5) { it.italic })
    }

    @Test
    fun `list enter backspace and level ops`() = runTest {
        val vm = loaded("item")
        vm.toggleListItem(0)
        // Enter at end of a list item splits it; far-out caret clamps to the end and still splits.
        val caret = vm.handleListEnter(0, 0, 4)
        assertEquals(5, caret)
        assertEquals(2, ((vm.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.TextDocument).content.size)
        assertEquals(null, vm.handleListBackspace(0, 0, 99))
        vm.changeListLevel(0, 1)
        vm.restartNumbering(0)
        assertTrue(vm.hasUnsavedChanges.value)
    }

    @Test
    fun `insertHorizontalLine and deleteBlockBefore`() = runTest {
        val vm = loaded("p")
        vm.insertHorizontalLine(0)
        assertEquals(2, ((vm.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.TextDocument).content.size)
        // Block before run start is a paragraph -> false.
        assertFalse(vm.deleteBlockBefore(2))
        // Insert a table then delete it via deleteBlockBefore.
        vm.insertTable(1, 1, 1)
        val size = ((vm.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.TextDocument).content.size
        // Table is the last block; run starting right after it deletes it.
        assertTrue(vm.deleteBlockBefore(size))
    }

    @Test
    fun `image insert unique path crop replace`() = runTest {
        val vm = loaded("p")
        vm.insertImage(0, "pic.png", byteArrayOf(1, 2, 3))
        var doc = (vm.state.value as OfficeViewModel.ViewState.Loaded).document as OdfDocument.TextDocument
        assertTrue(doc.content[1] is OdfContentBlock.Image)
        assertEquals("Pictures/pic.png", (doc.content[1] as OdfContentBlock.Image).image.path)
        // Second insert with the same name gets a unique path.
        vm.insertImage(1, "pic.png", byteArrayOf(4))
        doc = (vm.state.value as OfficeViewModel.ViewState.Loaded).document as OdfDocument.TextDocument
        assertEquals("Pictures/pic_1.png", (doc.content[2] as OdfContentBlock.Image).image.path)
        assertEquals("Pictures/pic_1.png", vm.uniqueImagePath(setOf("Pictures/pic.png"), "pic.png"))
        vm.setImageCrop(2, 0.1f, 0.1f, 0.1f, 0.1f)
        doc = (vm.state.value as OfficeViewModel.ViewState.Loaded).document as OdfDocument.TextDocument
        assertEquals(0.1f, (doc.content[2] as OdfContentBlock.Image).image.cropLeftPct)
        vm.rotateTextImage(2, 90f)
        doc = (vm.state.value as OfficeViewModel.ViewState.Loaded).document as OdfDocument.TextDocument
        assertEquals(90f, (doc.content[2] as OdfContentBlock.Image).image.rotationDegrees)
        vm.replaceTextImage(2, "new.png", byteArrayOf(9))
        doc = (vm.state.value as OfficeViewModel.ViewState.Loaded).document as OdfDocument.TextDocument
        val replaced = (doc.content[2] as OdfContentBlock.Image).image
        assertEquals(0f, replaced.rotationDegrees)
        assertEquals(0f, replaced.cropLeftPct)
        assertEquals("Pictures/new.png", replaced.path)
        assertTrue(doc.images.containsKey("Pictures/new.png"))
    }

    @Test
    fun `mutateRunParagraphs applies transform`() = runTest {
        val vm = loaded("a", "b")
        vm.mutateRunParagraphs(0, 1, 0, 3) { it.copy(style = ParagraphStyle.HEADING2) }
        val doc = (vm.state.value as OfficeViewModel.ViewState.Loaded).document as OdfDocument.TextDocument
        assertTrue(doc.content.all {
            (it as OdfContentBlock.Paragraph).paragraph.style == ParagraphStyle.HEADING2
        })
    }
}
