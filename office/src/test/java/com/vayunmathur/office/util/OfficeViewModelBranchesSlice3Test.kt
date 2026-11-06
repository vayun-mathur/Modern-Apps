package com.vayunmathur.office.util

import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfFrame
import com.vayunmathur.library.ui.odf.OdfImage
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfShape
import com.vayunmathur.library.ui.odf.OdfSlide
import com.vayunmathur.library.ui.odf.OdfSlideElement
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.ParagraphStyle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Slice 3/4, round 3: branch coverage — every shape kind through the slide-element
 * paths, all document variants through updateMetadata, field fallbacks, TOC levels,
 * list-backspace outdent, and image-block deletion.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OfficeViewModelBranchesSlice3Test : OfficeViewModelSlice3Harness() {

    private fun textDoc(vararg texts: String) = OdfDocument.TextDocument(
        title = "Doc",
        content = texts.map { OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(it)))) },
    )

    private fun loadedText(vararg texts: String): OfficeViewModel {
        val vm = newVm()
        vm.stateMutable.value = OfficeViewModel.ViewState.Loaded(textDoc(*texts))
        return vm
    }

    private fun textOf(vm: OfficeViewModel): OdfDocument.TextDocument =
        (vm.state.value as OfficeViewModel.ViewState.Loaded).document as OdfDocument.TextDocument

    private fun shapeDoc(vararg shapes: OdfShape): OfficeViewModel {
        val vm = newVm()
        vm.stateMutable.value = OfficeViewModel.ViewState.Loaded(
            OdfDocument.Presentation(title = "D", slides = listOf(
                OdfSlide(name = "S", elements = shapes.map { OdfSlideElement.Shape(it) }))))
        return vm
    }

    private fun shapeText(kind: String): List<OdfParagraph> =
        listOf(OdfParagraph(listOf(OdfSpan("t-$kind"))))

    private fun allShapes(): Array<OdfShape> = arrayOf(
        OdfShape.Rect(0f, 0f, 10f, 10f, text = shapeText("rect")),
        OdfShape.Ellipse(0f, 0f, 10f, 10f, text = shapeText("ellipse")),
        OdfShape.Line(0f, 0f, 10f, 10f, x2 = 10f, y2 = 10f),
        OdfShape.CustomShape(0f, 0f, 10f, 10f, text = shapeText("custom")),
        OdfShape.Polyline(0f, 0f, 10f, 10f, text = shapeText("poly")),
    )

    private fun presOf(vm: OfficeViewModel): OdfDocument.Presentation =
        (vm.state.value as OfficeViewModel.ViewState.Loaded).document as OdfDocument.Presentation

    @Test
    fun `all shape kinds through element text paths`() = runTest {
        val kinds = allShapes()
        val vm = shapeDoc(*kinds)
        for (i in kinds.indices) {
            vm.updateSlideElementText(0, i, "new-$i")
            val shape = (presOf(vm).slides[0].elements[i] as OdfSlideElement.Shape).shape
            val text = shape.text.joinToString("") { p -> p.spans.joinToString("") { it.text } }
            if (shape is OdfShape.Line) {
                // Line shapes carry no text; the write is still applied without crashing.
                assertTrue(text.isEmpty() || text == "new-$i")
            } else {
                assertEquals("new-$i", text)
            }
        }
        // Multi-line write rebuilds every paragraph from the first span's style.
        vm.updateSlideElementText(0, 0, "a\nb")
        assertEquals(2, (presOf(vm).slides[0].elements[0] as OdfSlideElement.Shape)
            .shape.text.size)
    }

    @Test
    fun `all shape kinds through rotation and recolor`() = runTest {
        val kinds = allShapes()
        val vm = shapeDoc(*kinds)
        for (i in kinds.indices) {
            vm.setSlideElementRotation(0, i, 10f)
            assertEquals(10f, (presOf(vm).slides[0].elements[i] as OdfSlideElement.Shape)
                .shape.rotationDegrees)
            vm.setSlideElementFill(0, i, 0xFFFF0000L)
            vm.setSlideElementStroke(0, i, 0xFF00FF00L)
            val s = (presOf(vm).slides[0].elements[i] as OdfSlideElement.Shape).shape
            assertEquals(0xFFFF0000L, s.fillColor)
            assertEquals(0xFF00FF00L, s.strokeColor)
        }
        // Clearing with nulls and set-flags off keeps prior colors on frames.
        val vm2 = loadedText("p")
        vm2.insertImageIntoSlide2()
        vm2.setSlideElementFill(0, 0, null)
        assertEquals(null, ((presOf(vm2).slides[0].elements[0] as OdfSlideElement.Frame)
            .frame.fillColor))
    }

    @Test
    fun `all shape kinds through span style and alignment`() = runTest {
        val kinds = allShapes()
        val vm = shapeDoc(*kinds)
        for (i in kinds.indices) {
            vm.toggleSlideElementBold(0, i)
            vm.toggleSlideElementItalic(0, i)
            vm.toggleSlideElementUnderline(0, i)
            vm.setSlideElementColor(0, i, 0xFF123456L)
            vm.setSlideElementAlignment(0, i, androidx.compose.ui.text.style.TextAlign.Justify)
            val el = presOf(vm).slides[0].elements[i] as OdfSlideElement.Shape
            val first = el.shape.text.firstOrNull()?.spans?.firstOrNull()
            // Line shapes have no text spans; the others take the style.
            if (el.shape is OdfShape.Line) assertEquals(null, first)
            else {
                assertEquals(true, first!!.bold)
                assertEquals(androidx.compose.ui.text.style.TextAlign.Justify,
                    el.shape.text[0].alignment)
            }
        }
    }

    @Test
    fun `updateMetadata covers every document variant`() = runTest {
        val vm = newVm()
        vm.stateMutable.value = OfficeViewModel.ViewState.Loaded(
            OdfDocument.Spreadsheet(title = "B", sheets = emptyList()))
        vm.updateMetadata { it.copy(title = "S2") }
        assertEquals("S2", ((vm.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.Spreadsheet).metadata.title)
        vm.stateMutable.value = OfficeViewModel.ViewState.Loaded(
            OdfDocument.Presentation(title = "P", slides = emptyList()))
        vm.updateMetadata { it.copy(title = "P2") }
        assertEquals("P2", ((vm.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.Presentation).metadata.title)
        vm.stateMutable.value = OfficeViewModel.ViewState.Loaded(
            OdfDocument.Drawing(title = "G", pages = emptyList()))
        vm.updateMetadata { it.copy(title = "G2") }
        assertEquals("G2", ((vm.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.Drawing).metadata.title)
        // Empty state is a no-op.
        idleVm().updateMetadata { it.copy(title = "N") }
    }

    @Test
    fun `field display fallbacks`() = runTest {
        val vm = loadedText("x")
        // No metadata anywhere: author falls back to creator then "", title to doc title.
        assertEquals("", vm.fieldDisplayValue("author-name"))
        assertEquals("Doc", vm.fieldDisplayValue("title"))
        // file-name falls back to the doc title ("Untitled" only with no document).
        assertEquals("B", idleVm().let {
            it.stateMutable.value = OfficeViewModel.ViewState.Loaded(
                OdfDocument.Spreadsheet(title = "B", sheets = emptyList()))
            it.fieldDisplayValue("file-name")
        })
        assertEquals("Untitled", idleVm().fieldDisplayValue("file-name"))
        assertEquals("", vm.fieldDisplayValue("subject"))
        assertEquals("", vm.fieldDisplayValue("description"))
        vm.updateMetadata { it.copy(creator = "C") }
        assertEquals("C", vm.fieldDisplayValue("author-name"))
        // file-name uses the doc title when metadata is absent.
        assertEquals("Doc", vm.fieldDisplayValue("file-name"))
    }

    @Test
    fun `toc collects all heading levels and skips blanks`() = runTest {
        val vm = loadedText("H1", "H2", "H3", "H4", "   ", "Body")
        vm.setParagraphStyle(0, ParagraphStyle.HEADING1)
        vm.setParagraphStyle(1, ParagraphStyle.HEADING2)
        vm.setParagraphStyle(2, ParagraphStyle.HEADING3)
        vm.setParagraphStyle(3, ParagraphStyle.HEADING4)
        vm.setParagraphStyle(4, ParagraphStyle.HEADING1) // blank text -> skipped
        vm.insertTableOfContents(5)
        val toc = textOf(vm).content.filterIsInstance<OdfContentBlock.TableOfContents>()
        assertEquals(1, toc.size)
        assertEquals(4, toc[0].entries.size)
        // Indent grows with level.
        val indents = toc[0].entries.map { it.marginLeft }
        assertTrue(indents[0] < indents[1] && indents[1] < indents[2] && indents[2] < indents[3])
    }

    @Test
    fun `list backspace outdents nested and exits top level`() = runTest {
        val vm = loadedText("item")
        vm.toggleListItem(0)
        vm.changeListLevel(0, 2)
        // Caret at run start (gPos 0) on nested item: outdent one level at a time.
        assertEquals(0, vm.handleListBackspace(0, 0, 0))
        assertEquals(ParagraphStyle.LIST_ITEM,
            (textOf(vm).content[0] as OdfContentBlock.Paragraph).paragraph.style)
        assertEquals(0, vm.handleListBackspace(0, 0, 0))
        // Now top-level: backspace exits the list to a body paragraph.
        assertEquals(0, vm.handleListBackspace(0, 0, 0))
        assertEquals(ParagraphStyle.BODY,
            (textOf(vm).content[0] as OdfContentBlock.Paragraph).paragraph.style)
        // Mid-line caret is a no-op (default delete).
        assertEquals(null, vm.handleListBackspace(0, 0, 2))
    }

    @Test
    fun `deleteBlockBefore drops image binary too`() = runTest {
        val vm = loadedText("p")
        vm.insertImage(0, "gone.png", byteArrayOf(1, 2))
        assertTrue(textOf(vm).images.containsKey("Pictures/gone.png"))
        // Image is the last block; a run starting right after it deletes it + its bytes.
        assertTrue(vm.deleteBlockBefore(2))
        assertTrue(textOf(vm).content.none { it is OdfContentBlock.Image })
        assertTrue(!textOf(vm).images.containsKey("Pictures/gone.png"))
        // Missing index and empty doc guards.
        assertTrue(!vm.deleteBlockBefore(0))
        assertTrue(!idleVm().deleteBlockBefore(1))
    }

    @Test
    fun `runLocate and charsToSpans edges`() = runTest {
        val vm = loadedText("ab", "c")
        // Separator consumption: gPos 3 (the newline) lands at the start of para 1.
        assertEquals(1 to 0, vm.runLocate(listOf(2, 1), 3))
        // Beyond everything clamps to the last paragraph end.
        assertEquals(1 to 1, vm.runLocate(listOf(2, 1), 99))
        // Empty lens guards.
        assertEquals(0 to 0, vm.runLocate(emptyList(), 5))
        // charsToSpans merges adjacent same-style chars and keeps distinct ones apart.
        val merged = vm.charsToSpans(listOf(OdfSpan("a", bold = true), OdfSpan("b", bold = true)))
        assertEquals(listOf("ab"), merged.map { it.text })
        assertEquals(true, merged[0].bold)
        assertEquals(0, vm.charsToSpans(emptyList()).size - 1) // single empty span
    }

    @Test
    fun `sheet floating mutate guards and text on shapes`() = runTest {
        val vm = newVm()
        val cell = com.vayunmathur.library.ui.odf.OdfCell("x")
        val row = com.vayunmathur.library.ui.odf.OdfRow(listOf(cell))
        val sheet = com.vayunmathur.library.ui.odf.OdfSheet("S", listOf(row))
        val oneCell = OdfDocument.Spreadsheet(title = "B", sheets = listOf(sheet))
        vm.stateMutable.value = OfficeViewModel.ViewState.Loaded(oneCell)
        // Bad sheet index: no-op.
        vm.addShapeToSheet(9, "rect")
        vm.setSheetElementBounds(9, 0, 0f, 0f, 1f, 1f)
        vm.deleteSheetElement(9, 0)
        vm.updateSheetElementText(9, 0, "t")
        assertTrue(((vm.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.Spreadsheet).sheets[0].floating.isEmpty())
        // Shape element text on a sheet + out-of-range element index.
        vm.addShapeToSheet(0, "rect")
        vm.updateSheetElementText(0, 0, "cell note")
        vm.updateSheetElementText(0, 9, "nope")
        val el = ((vm.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.Spreadsheet).sheets[0].floating[0]
        assertTrue(el is OdfSlideElement.Shape)
    }

    private fun OfficeViewModel.insertImageIntoSlide2() {
        val imgPara = OdfParagraph(listOf(OdfSpan("f")))
        val frame = OdfSlideElement.Frame(
            OdfFrame(x = 0f, y = 0f, width = 10f, height = 10f,
                paragraphs = listOf(imgPara),
                image = OdfImage(path = "p", imageData = byteArrayOf())))
        val deck = OdfDocument.Presentation(title = "D", slides = listOf(
            OdfSlide(name = "S", elements = listOf(frame))))
        stateMutable.value = OfficeViewModel.ViewState.Loaded(deck)
    }
}
