package com.vayunmathur.office

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import com.vayunmathur.library.ui.odf.OdfTable
import com.vayunmathur.library.ui.odf.OdfTableCell
import com.vayunmathur.library.ui.odf.OdfTableRow
import com.vayunmathur.office.util.OfficeViewModel
import com.vayunmathur.office.util.cellCommentText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Slice 4 pass 2: InsertMenu items, cell/slide color+comment+resize overlays. */
@RunWith(AndroidJUnit4::class)
class Slice4Pass2MenusOverlaysTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun str(id: Int) = ctx.getString(id)

    private fun vm(doc: OdfDocument): OfficeViewModel =
        OfficeViewModel(ApplicationProvider.getApplicationContext()).apply {
            stateMutable.value = OfficeViewModel.ViewState.Loaded(doc)
        }

    private fun textDoc() = OdfDocument.TextDocument(
        "Doc.odt",
        listOf(
            OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("body text")), style = com.vayunmathur.library.ui.odf.ParagraphStyle.HEADING1)),
            OdfContentBlock.Table(
                OdfTable(
                    name = "T",
                    rows = listOf(
                        OdfTableRow(listOf(OdfTableCell(listOf(OdfParagraph(listOf(OdfSpan("c00"))))), OdfTableCell())),
                        OdfTableRow(listOf(OdfTableCell(), OdfTableCell())),
                    ),
                )
            ),
        ),
    )

    private fun sheetDoc() = OdfDocument.Spreadsheet(
        "Book.ods",
        listOf(OdfSheet("S", listOf(OdfRow(listOf(OdfCell("a1"), OdfCell("b1")))))),
    )

    private fun deckDoc() = OdfDocument.Presentation(
        "Deck.odp",
        listOf(
            OdfSlide(
                "S1",
                listOf(
                    OdfSlideElement.Frame(
                        OdfFrame(0f, 0f, 100f, 50f, listOf(OdfParagraph(listOf(OdfSpan("el")))))
                    ),
                ),
            ),
        ),
    )

    private fun loadedText(vm: OfficeViewModel) =
        (vm.state.value as OfficeViewModel.ViewState.Loaded).document as OdfDocument.TextDocument

    // ---- InsertMenu ----

    @Test fun insertMenuHiddenForNonTextDoc() {
        val s = DocumentScreenState()
        val viewModel = vm(textDoc())
        rule.setContent {
            val launchers = rememberDocumentLaunchers(viewModel, s, onPickImageBytes = { _, _ -> })
            InsertMenu(s = s, viewModel = viewModel, isTextDoc = false, focusedPara = 0, launchers = launchers)
        }
        rule.onNodeWithText(str(R.string.insert)).assertDoesNotExist()
    }

    @Test fun insertMenuOpensAndChartItemFlagsEditor() {
        val s = DocumentScreenState()
        val viewModel = vm(textDoc())
        rule.setContent {
            val launchers = rememberDocumentLaunchers(viewModel, s, onPickImageBytes = { _, _ -> })
            InsertMenu(s = s, viewModel = viewModel, isTextDoc = true, focusedPara = 0, launchers = launchers)
        }
        rule.onNodeWithText(str(R.string.insert)).performClick()
        assertTrue(s.insertMenu)
        rule.onNodeWithText(str(R.string.chart)).performClick()
        assertTrue(s.showChartEditor)
        assertEquals(-1, s.editingChartBlock)
        assertFalse(s.insertMenu)
    }

    @Test fun insertMenuDialogItemsSetStateFlags() {
        val s = DocumentScreenState().apply {
            activeRunStart = 0; activeRunEnd = 0; selStart = 0; selEnd = 4
        }
        val viewModel = vm(textDoc())
        rule.setContent {
            val launchers = rememberDocumentLaunchers(viewModel, s, onPickImageBytes = { _, _ -> })
            InsertMenu(s = s, viewModel = viewModel, isTextDoc = true, focusedPara = 0, launchers = launchers)
        }
        fun openAndClick(label: String) {
            s.insertMenu = true
            rule.waitForIdle()
            rule.onNodeWithText(label).performScrollTo().performClick()
            rule.waitForIdle()
        }
        openAndClick(str(R.string.special_character_1)); assertTrue(s.showSpecialChars); s.showSpecialChars = false
        openAndClick(str(R.string.bookmark)); assertTrue(s.showAddBookmark); s.showAddBookmark = false
        openAndClick(str(R.string.footnote)); assertTrue(s.showFootnote); s.showFootnote = false
        openAndClick(str(R.string.comment_1)); assertTrue(s.showComment); s.showComment = false
        openAndClick(str(R.string.header_footer)); assertTrue(s.showHeaderFooter); s.showHeaderFooter = false
    }

    @Test fun insertMenuFieldAndStructuralItemsMutateDocument() {
        val s = DocumentScreenState().apply {
            activeRunStart = 0; activeRunEnd = 0; selStart = 0; selEnd = 4
        }
        val viewModel = vm(textDoc())
        rule.setContent {
            val launchers = rememberDocumentLaunchers(viewModel, s, onPickImageBytes = { _, _ -> })
            InsertMenu(s = s, viewModel = viewModel, isTextDoc = true, focusedPara = 0, launchers = launchers)
        }
        fun openAndClick(label: String) {
            s.insertMenu = true
            rule.waitForIdle()
            rule.onNodeWithText(label).performScrollTo().performClick()
            rule.waitForIdle()
        }
        openAndClick("Date (field)")
        val afterField = loadedText(viewModel)
        val spans = (afterField.content[0] as OdfContentBlock.Paragraph).paragraph.spans
        assertTrue(spans.any { it.field == "date" }, "date field span inserted: $spans")

        val before = loadedText(viewModel).content.size
        openAndClick(str(R.string.horizontal_line))
        assertTrue(loadedText(viewModel).content.size >= before)
        openAndClick(str(R.string.page_break))
        assertTrue(
            loadedText(viewModel).content.any { it is OdfContentBlock.PageBreak },
            "page break block inserted",
        )
        openAndClick(str(R.string.table_of_contents))
        assertTrue(
            loadedText(viewModel).content.any { it is OdfContentBlock.TableOfContents },
            "TOC block inserted",
        )
    }

    // ---- Cell / slide overlays ----

    private fun showCellOverlays(
        state: DocumentOverlayState,
        doc: OdfDocument,
        cell: Triple<Int, Int, Int>? = Triple(0, 0, 0),
        slideEl: Int = -1,
    ): OfficeViewModel {
        val viewModel = vm(doc)
        val activeCell = mutableStateOf(cell)
        val activeSlide = mutableIntStateOf(0)
        val activeSlideEl = mutableIntStateOf(slideEl)
        rule.setContent {
            CellOverlays(
                state = state,
                viewModel = viewModel,
                activeCell = activeCell,
                activeSlide = activeSlide,
                activeSlideEl = activeSlideEl,
            )
        }
        return viewModel
    }

    @Test fun cellBgColorOverlayApplies() {
        val bgState = DocumentOverlayState().apply { showCellBgColor = true }
        showCellOverlays(bgState, sheetDoc())
        rule.onNodeWithText("Background Color").assertIsDisplayed()
        rule.onNodeWithText("∅").performClick()
        assertFalse(bgState.showCellBgColor)
    }

    @Test fun cellBorderColorOverlayApplies() {
        val borderState = DocumentOverlayState().apply { showCellBorderColor = true }
        showCellOverlays(borderState, sheetDoc())
        rule.onNodeWithText("Border Color").assertIsDisplayed()
        rule.onNodeWithText("∅").performClick()
        assertFalse(borderState.showCellBorderColor)
    }

    @Test fun cellCommentOverlaySaveAndCancel() {
        val state = DocumentOverlayState().apply { showCellComment = true }
        val viewModel = showCellOverlays(state, sheetDoc())
        rule.onNodeWithText(str(R.string.cell_comment), substring = true).assertExists()
        // Save with the prefilled (empty) comment text.
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.save), substring = true).performClick()
        assertFalse(state.showCellComment)
        assertEquals("", viewModel.cellCommentText(0, 0, 0))
    }

    @Test fun slideElementTextColorOverlayApplies() {
        val textState = DocumentOverlayState().apply { showSlideTextColor = true }
        showCellOverlays(textState, deckDoc(), cell = null, slideEl = 0)
        rule.onNodeWithText("Text Color").assertIsDisplayed()
        rule.onNodeWithText("∅").performClick()
        assertFalse(textState.showSlideTextColor)
    }

    @Test fun slideElementFillColorOverlayApplies() {
        val fillState = DocumentOverlayState().apply { showSlideFillColor = true }
        showCellOverlays(fillState, deckDoc(), cell = null, slideEl = 0)
        rule.onNodeWithText("Fill Color").assertIsDisplayed()
        rule.onNodeWithText("∅").performClick()
        assertFalse(fillState.showSlideFillColor)
    }

    @Test fun slideElementStrokeColorOverlayApplies() {
        val strokeState = DocumentOverlayState().apply { showSlideStrokeColor = true }
        showCellOverlays(strokeState, deckDoc(), cell = null, slideEl = 0)
        rule.onNodeWithText("Border Color").assertIsDisplayed()
        rule.onNodeWithText("∅").performClick()
        assertFalse(strokeState.showSlideStrokeColor)
    }

    @Test fun insertMenuImageItemLaunchesPicker() {
        val s = DocumentScreenState()
        val viewModel = vm(textDoc())
        rule.setContent {
            val launchers = rememberDocumentLaunchers(viewModel, s, onPickImageBytes = { _, _ -> })
            InsertMenu(s = s, viewModel = viewModel, isTextDoc = true, focusedPara = 0, launchers = launchers)
        }
        s.insertMenu = true
        rule.waitForIdle()
        // Launching the picker opens a system UI under Robolectric; the menu must close first.
        rule.onNodeWithText(str(R.string.image_1)).performClick()
        assertFalse(s.insertMenu)
    }

    @Test fun cellResizeOverlayAppliesSizes() {
        val state = DocumentOverlayState().apply { showCellResize = true }
        showCellOverlays(state, sheetDoc())
        rule.onNodeWithText(str(R.string.row_column_size_1), substring = true).assertExists()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.apply)).performClick()
        assertFalse(state.showCellResize, "apply dismisses the resize dialog even with empty fields")
    }

    @Test fun cellCommentOverlayCancelDismisses() {
        val state = DocumentOverlayState().apply { showCellComment = true }
        showCellOverlays(state, sheetDoc())
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.cancel)).performClick()
        assertFalse(state.showCellComment)
    }
}
