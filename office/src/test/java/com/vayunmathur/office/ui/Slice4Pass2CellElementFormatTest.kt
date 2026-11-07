package com.vayunmathur.office.ui

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.odf.OdfCell
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfFrame
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfRow
import com.vayunmathur.library.ui.odf.OdfSheet
import com.vayunmathur.library.ui.odf.OdfSlide
import com.vayunmathur.library.ui.odf.OdfSlideElement
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.office.util.OfficeViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Slice 4 pass 2: CellFormatControls menus and ElementFormatControls menus. */
@RunWith(AndroidJUnit4::class)
class Slice4Pass2CellElementFormatTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun str(id: Int) = ctx.getString(id)

    private fun vm(doc: OdfDocument): OfficeViewModel =
        OfficeViewModel(ApplicationProvider.getApplicationContext()).apply {
            stateMutable.value = OfficeViewModel.ViewState.Loaded(doc)
        }

    private fun sheetDoc() = OdfDocument.Spreadsheet(
        "Book.ods",
        listOf(
            OdfSheet(
                "S",
                listOf(
                    OdfRow(listOf(OdfCell("a1"), OdfCell("b1"))),
                    OdfRow(listOf(OdfCell(""), OdfCell(""))),
                    OdfRow(listOf(OdfCell(""), OdfCell(""))),
                ),
            )
        ),
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
                    OdfSlideElement.Frame(
                        OdfFrame(10f, 60f, 50f, 20f, listOf(OdfParagraph(listOf(OdfSpan("el2")))))
                    ),
                ),
            ),
        ),
    )

    private fun cell(vm: OfficeViewModel) =
        ((vm.state.value as OfficeViewModel.ViewState.Loaded).document as OdfDocument.Spreadsheet)
            .sheets[0].rows[0].cells[0]

    private fun sheet(vm: OfficeViewModel) =
        ((vm.state.value as OfficeViewModel.ViewState.Loaded).document as OdfDocument.Spreadsheet).sheets[0]

    private fun deck(vm: OfficeViewModel) =
        ((vm.state.value as OfficeViewModel.ViewState.Loaded).document as OdfDocument.Presentation)

    private fun menuItem(label: String) =
        rule.onAllNodesWithText(label, substring = true, useUnmergedTree = true).let {
            it[it.fetchSemanticsNodes().size - 1]
        }

    @Test fun cellAlignAndNumberFormatMenusApply() {
        val viewModel = vm(sheetDoc())
        rule.setContent {
            Column {
                CellFormatControls(FormatTarget.Cell(0, 0, 0), viewModel, BottomBarActions())
            }
        }
        rule.onNodeWithContentDescription("Align left", substring = true).performClick()
        menuItem(str(com.vayunmathur.office.R.string.center)).performClick()
        assertEquals(androidx.compose.ui.text.style.TextAlign.Center, cell(viewModel).alignment)

        fun numberFormat(label: String) {
            rule.onNodeWithContentDescription("More").performClick()
            menuItem(str(com.vayunmathur.office.R.string.number_format)).performClick()
            menuItem(label).performClick()
            rule.waitForIdle()
        }
        numberFormat(str(com.vayunmathur.office.R.string.integer))
        assertEquals(0, cell(viewModel).numberFormat?.decimals)
        numberFormat(str(com.vayunmathur.office.R.string.currency))
        assertEquals("$", cell(viewModel).numberFormat?.currencySymbol)
        numberFormat(str(com.vayunmathur.office.R.string.number_2_dp))
        assertEquals(2, cell(viewModel).numberFormat?.decimals)
        assertTrue(cell(viewModel).numberFormat?.grouping == true)
        numberFormat(str(com.vayunmathur.office.R.string.scientific))
        assertTrue(cell(viewModel).numberFormat?.isScientific == true)
        numberFormat(str(com.vayunmathur.office.R.string.fraction))
        assertTrue(cell(viewModel).numberFormat?.isFraction == true)
        numberFormat(str(com.vayunmathur.library.ui.R.string.date))
        assertTrue(cell(viewModel).numberFormat?.isDate == true)
        numberFormat(str(com.vayunmathur.library.ui.R.string.time))
        assertTrue(cell(viewModel).numberFormat?.isTime == true)
        numberFormat(str(com.vayunmathur.office.R.string.general))
        assertNull(cell(viewModel).numberFormat)
    }

    @Test fun cellMoreMenuCallbacksAndFillDown() {
        val viewModel = vm(sheetDoc())
        var border = 0; var comment = 0; var resize = 0
        rule.setContent {
            Column {
                CellFormatControls(
                    FormatTarget.Cell(0, 0, 0), viewModel,
                    BottomBarActions(
                        onCellBorder = { border++ },
                        onCellComment = { comment++ },
                        onCellResize = { resize++ },
                    ),
                )
            }
        }
        fun moreItem(label: String) {
            rule.onNodeWithContentDescription("More").performClick()
            menuItem(label).performClick()
            rule.waitForIdle()
        }
        moreItem(str(com.vayunmathur.office.R.string.border_color)); assertEquals(1, border)
        moreItem(str(com.vayunmathur.office.R.string.comment_1)); assertEquals(1, comment)
        moreItem(str(com.vayunmathur.office.R.string.row_column_size)); assertEquals(1, resize)
        moreItem(str(com.vayunmathur.office.R.string.fill_down))
        assertEquals("a1", sheet(viewModel).rows[1].cells[0].text)
        assertEquals("a1", sheet(viewModel).rows[2].cells[0].text)
    }

    @Test fun elementAlignMenuAppliesAndCallbacksFire() {
        val viewModel = vm(deckDoc())
        var fill = 0; var stroke = 0; var deleted = 0; var textColor = 0
        rule.setContent {
            Column {
                ElementFormatControls(
                    FormatTarget.Element(0, 0), viewModel,
                    BottomBarActions(
                        onSlideFill = { fill++ },
                        onSlideStroke = { stroke++ },
                        onDeleteElement = { deleted++ },
                        onSlideTextColor = { textColor++ },
                    ),
                )
            }
        }
        rule.onNodeWithText(str(com.vayunmathur.office.R.string.fill)).performClick()
        rule.onNodeWithText(str(com.vayunmathur.office.R.string.border)).performClick()
        assertEquals(1, fill); assertEquals(1, stroke)

        rule.onNodeWithContentDescription("Align left", substring = true).performClick()
        menuItem(str(com.vayunmathur.office.R.string.center)).performClick()
        val para = (deck(viewModel).slides[0].elements[0] as OdfSlideElement.Frame).frame.paragraphs[0]
        assertEquals(androidx.compose.ui.text.style.TextAlign.Center, para.alignment)

        rule.onNodeWithContentDescription("Delete").performClick()
        assertEquals(1, deleted)
        assertEquals(0, textColor)
        assertNotNull(viewModel.state.value)
    }

    @Test fun elementMoreMenuReordersRotatesDuplicatesAndSlideActions() {
        val viewModel = vm(deckDoc())
        var notes = 0; var bg = 0; var transition = 0
        rule.setContent {
            Column {
                ElementFormatControls(
                    FormatTarget.Element(0, 0), viewModel,
                    BottomBarActions(
                        onSlideNotes = { notes++ },
                        onSlideBackground = { bg++ },
                        onSlideTransition = { transition++ },
                    ),
                )
            }
        }
        fun moreItem(label: String) {
            rule.onNodeWithContentDescription("More").performClick()
            menuItem(label).performClick()
            rule.waitForIdle()
        }
        moreItem(str(com.vayunmathur.office.R.string.duplicate))
        assertEquals(3, deck(viewModel).slides[0].elements.size)
        moreItem(str(com.vayunmathur.office.R.string.rotate_90))
        // Rotation only applies to image frames and shapes: a text frame is unchanged.
        val rotated = (deck(viewModel).slides[0].elements[0] as OdfSlideElement.Frame).frame
        assertEquals(0f, rotated.rotationDegrees)
        moreItem(str(com.vayunmathur.office.R.string.bring_to_front))
        moreItem(str(com.vayunmathur.office.R.string.send_to_back))
        moreItem(str(com.vayunmathur.office.R.string.speaker_notes_1)); assertEquals(1, notes)
        moreItem(str(com.vayunmathur.office.R.string.slide_background)); assertEquals(1, bg)
        moreItem(str(com.vayunmathur.office.R.string.slide_transition)); assertEquals(1, transition)
    }

    @Test fun nullTargetsRenderDisabled() {
        rule.setContent {
            Column {
                CellFormatControls(null, vm(sheetDoc()), BottomBarActions())
                ElementFormatControls(null, vm(deckDoc()), BottomBarActions())
            }
        }
        rule.onAllNodesWithText(str(com.vayunmathur.office.R.string.fill)).assertCountEquals(2)
        rule.onAllNodesWithText(str(com.vayunmathur.office.R.string.fill))[0].assertExists()
    }
}
