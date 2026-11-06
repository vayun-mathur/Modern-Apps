package com.vayunmathur.office.ui

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.EditorFormat
import com.vayunmathur.library.ui.odf.OdfCell
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfFrame
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfRow
import com.vayunmathur.library.ui.odf.OdfSheet
import com.vayunmathur.library.ui.odf.OdfSlide
import com.vayunmathur.library.ui.odf.OdfSlideElement
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.OdfShape
import com.vayunmathur.office.util.OfficeViewModel
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 4/4 formatter tests: [TextRunFormatter], [CellFormatter] and
 * [SlideFormatter] drive real [OfficeViewModel] edits (seeded Loaded state,
 * Robolectric Application). No composition needed — these are plain classes.
 */
@RunWith(AndroidJUnit4::class)
class Slice4FormattersTest {

    private fun vm(doc: OdfDocument): OfficeViewModel =
        OfficeViewModel(ApplicationProvider.getApplicationContext()).apply {
            stateMutable.value = OfficeViewModel.ViewState.Loaded(doc)
        }

    private fun textDoc() = OdfDocument.TextDocument(
        "Doc",
        listOf(
            OdfContentBlock.Paragraph(
                OdfParagraph(listOf(OdfSpan("plain"), OdfSpan("bold", bold = true)))),
        ),
    )

    private fun sheetDoc() = OdfDocument.Spreadsheet(
        "Book",
        listOf(OdfSheet("S", listOf(OdfRow(listOf(OdfCell("a")))))),
    )

    private fun deckDoc() = OdfDocument.Presentation(
        "Deck",
        listOf(
            OdfSlide(
                "S1",
                listOf(
                    OdfSlideElement.Frame(
                        OdfFrame(
                            0f, 0f, 100f, 50f,
                            listOf(OdfParagraph(listOf(OdfSpan("frame")))))),
                    OdfSlideElement.Shape(
                        OdfShape.Rect(
                            x = 0f, y = 0f, width = 50f, height = 50f,
                            text = listOf(OdfParagraph(listOf(OdfSpan("shape")))))),
                ),
            ),
        ),
    )

    private fun spansOf(vm: OfficeViewModel): List<OdfSpan> =
        ((vm.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.TextDocument)
            .content.filterIsInstance<OdfContentBlock.Paragraph>()
            .flatMap { it.paragraph.spans }

    // ---- TextRunFormatter ----

    @Test fun textFormatterSupported() {
        val f = TextRunFormatter(vm(textDoc()), 0, 0, 0, 0, enabled = true)
        assertTrue(f.supported.contains(EditorFormat.BOLD))
        assertTrue(f.supported.contains(EditorFormat.LINK))
    }

    @Test fun textFormatterToggleBold() {
        val viewModel = vm(textDoc())
        val f = TextRunFormatter(viewModel, 0, 0, 0, 5, enabled = true)
        assertFalse(f.isActive(EditorFormat.BOLD))
        f.toggle(EditorFormat.BOLD)
        assertTrue(spansOf(viewModel)[0].bold)
        assertTrue(f.isActive(EditorFormat.BOLD))
    }

    @Test fun textFormatterToggleItalicUnderlineStrike() {
        val viewModel = vm(textDoc())
        val f = TextRunFormatter(viewModel, 0, 0, 0, 5, enabled = true)
        f.toggle(EditorFormat.ITALIC)
        f.toggle(EditorFormat.UNDERLINE)
        f.toggle(EditorFormat.STRIKETHROUGH)
        val s = spansOf(viewModel)[0]
        assertTrue(s.italic && s.underline && s.strikethrough)
        assertTrue(f.isActive(EditorFormat.ITALIC))
    }

    @Test fun textFormatterDisabledIsNoop() {
        val viewModel = vm(textDoc())
        val f = TextRunFormatter(viewModel, 0, 0, 0, 5, enabled = false)
        f.toggle(EditorFormat.BOLD)
        assertFalse(spansOf(viewModel)[0].bold)
        assertFalse(f.isActive(EditorFormat.BOLD))
        assertNull(f.linkContext())
    }

    @Test fun textFormatterBoldActiveWhenAllBold() {
        val viewModel = vm(textDoc())
        // Select only the bold span (offsets 5..9 in "plainbold").
        val f = TextRunFormatter(viewModel, 0, 0, 5, 9, enabled = true)
        assertTrue(f.isActive(EditorFormat.BOLD))
        assertFalse(f.isActive(EditorFormat.ITALIC))
    }

    @Test fun textFormatterLinkContextSelection() {
        val viewModel = vm(textDoc())
        val f = TextRunFormatter(viewModel, 0, 0, 0, 5, enabled = true)
        val ctx = f.linkContext()
        // Non-empty selection, no link yet: creation context.
        assertTrue(ctx != null)
        assertEquals(false, ctx!!.editing)
        assertEquals("plain", ctx.text)
    }

    @Test fun textFormatterLinkContextCollapsedIsNull() {
        val viewModel = vm(textDoc())
        val f = TextRunFormatter(viewModel, 0, 0, 0, 0, enabled = true)
        assertNull(f.linkContext())
    }

    @Test fun textFormatterApplyAndRemoveLink() {
        val viewModel = vm(textDoc())
        val f = TextRunFormatter(viewModel, 0, 0, 0, 5, enabled = true)
        val ctx = f.linkContext()!!
        f.applyLink(ctx, "plain", "https://example.com")
        assertEquals("https://example.com", spansOf(viewModel)[0].href)
        val editCtx = f.linkContext()!!
        assertTrue(editCtx.editing)
        f.removeLink(editCtx)
        assertNull(spansOf(viewModel)[0].href)
    }

    @Test fun textFormatterApplyLinkBlankUrlIgnored() {
        val viewModel = vm(textDoc())
        val f = TextRunFormatter(viewModel, 0, 0, 0, 5, enabled = true)
        val ctx = f.linkContext()!!
        f.applyLink(ctx, "plain", "  ")
        assertNull(spansOf(viewModel)[0].href)
    }

    // ---- CellFormatter ----

    @Test fun cellFormatterSupportedAndToggle() {
        val viewModel = vm(sheetDoc())
        val f = CellFormatter(viewModel, 0, 0, 0, enabled = true)
        assertEquals(
            setOf(EditorFormat.BOLD, EditorFormat.ITALIC),
            f.supported)
        f.toggle(EditorFormat.BOLD)
        val cell = ((viewModel.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.Spreadsheet).sheets[0].rows[0].cells[0]
        assertTrue(cell.bold)
        f.toggle(EditorFormat.ITALIC)
        val cell2 = ((viewModel.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.Spreadsheet).sheets[0].rows[0].cells[0]
        assertTrue(cell2.italic)
    }

    @Test fun cellFormatterDisabledIsNoop() {
        val viewModel = vm(sheetDoc())
        val f = CellFormatter(viewModel, 0, 0, 0, enabled = false)
        f.toggle(EditorFormat.BOLD)
        val cell = ((viewModel.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.Spreadsheet).sheets[0].rows[0].cells[0]
        assertFalse(cell.bold)
    }

    // ---- SlideFormatter ----

    @Test fun slideFormatterToggleBoldUnderline() {
        val viewModel = vm(deckDoc())
        val f = SlideFormatter(viewModel, 0, 1, enabled = true)
        assertTrue(f.supported.contains(EditorFormat.UNDERLINE))
        f.toggle(EditorFormat.BOLD)
        f.toggle(EditorFormat.UNDERLINE)
        val shape = ((viewModel.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.Presentation).slides[0].elements[1]
            as OdfSlideElement.Shape
        val spans = (shape.shape as OdfShape.Rect).text.flatMap { it.spans }
        assertTrue(spans.all { it.bold })
        assertTrue(spans.all { it.underline })
    }

    @Test fun slideFormatterDisabledIsNoop() {
        val viewModel = vm(deckDoc())
        val f = SlideFormatter(viewModel, 0, 1, enabled = false)
        f.toggle(EditorFormat.BOLD)
        val shape = ((viewModel.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.Presentation).slides[0].elements[1]
            as OdfSlideElement.Shape
        val spans = (shape.shape as OdfShape.Rect).text.flatMap { it.spans }
        assertTrue(spans.none { it.bold })
    }

    @Test fun bottomBarActionsDefaults() {
        val a = BottomBarActions()
        // Defaults are all no-ops; invoking must not throw.
        a.onTextColor()
        a.onCellTextColor()
        a.onInsertTable()
        a.onSlideNotes()
        var called = false
        BottomBarActions(onFontSize = { called = true }).onFontSize()
        assertTrue(called)
    }

    @Test fun formatTargetModels() {
        val t = FormatTarget.TextRun(0, 1, 2, 3)
        assertEquals(0, t.runStart)
        val c = FormatTarget.Cell(1, 2, 3)
        assertEquals(2, c.row)
        val e = FormatTarget.Element(0, 4)
        assertEquals(4, e.element)
        assertTrue(FormatTarget.None is FormatTarget)
    }
}
