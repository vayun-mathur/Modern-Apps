package com.vayunmathur.office.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.odf.OdfAnnotation
import com.vayunmathur.library.ui.odf.OdfBorders
import com.vayunmathur.library.ui.odf.OdfCell
import com.vayunmathur.library.ui.odf.OdfCondFormat
import com.vayunmathur.library.ui.odf.OdfDataValidation
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfFrame
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfRow
import com.vayunmathur.library.ui.odf.OdfShape
import com.vayunmathur.library.ui.odf.OdfSheet
import com.vayunmathur.library.ui.odf.OdfSlideElement
import com.vayunmathur.library.ui.odf.OdfSpan
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Slice 4 pass 2: SpreadsheetView rich cells, edit mode, validation dropdown, floating layer. */
@RunWith(AndroidJUnit4::class)
class Slice4Pass2SheetViewsTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun literalValues(doc: OdfDocument.Spreadsheet) = object : SpreadsheetValues {
        override fun display(sheet: Int, row: Int, col: Int): String =
            doc.sheets[sheet].rows.getOrNull(row)?.cells?.getOrNull(col)?.text ?: ""

        override fun isNumeric(sheet: Int, row: Int, col: Int): Boolean =
            display(sheet, row, col).toDoubleOrNull() != null
    }

    private fun richDoc(withFloating: Boolean = true): OdfDocument.Spreadsheet {
        val sheet = OdfSheet(
            name = "Data",
            rows = listOf(
                OdfRow(
                    listOf(
                        OdfCell(
                            "10", bold = true, numberValue = 10.0,
                            condFormats = listOf(OdfCondFormat("value() >= 10", backgroundColor = 0x00FF00L)),
                            borders = OdfBorders(top = "1pt solid #000000", left = "1pt solid #000000"),
                            annotation = OdfAnnotation(author = "Ann"),
                        ),
                        OdfCell("pick", validationName = "tiers"),
                        OdfCell("wide", spannedColumns = 2, italic = true, wrap = true),
                        OdfCell("covered", isCovered = true),
                    )
                ),
                OdfRow(
                    listOf(
                        OdfCell("vcover", isCovered = true),
                        OdfCell("3.5", formula = "of:=SUM([.A1])"),
                        OdfCell("plain"),
                    )
                ),
            ),
            columnWidths = listOf(120f, null, 500f),
            floating = if (withFloating) listOf(
                OdfSlideElement.Frame(
                    OdfFrame(10f, 10f, 120f, 40f, listOf(OdfParagraph(listOf(OdfSpan("float text")))))
                ),
                OdfSlideElement.Shape(OdfShape.Rect(200f, 60f, 80f, 40f, fillColor = 0xFF0000L)),
            ) else emptyList(),
        )
        return OdfDocument.Spreadsheet(
            "Book.ods",
            listOf(sheet, OdfSheet("Second", listOf(OdfRow(listOf(OdfCell("other")))))),
            validations = listOf(
                OdfDataValidation("tiers", "cell-content-is-in-list(\"Gold\";\"Silver\")"),
            ),
        )
    }

    @Test fun richSheetRendersDecoratedCellsAndDraws() {
        val doc = richDoc()
        rule.setContent { SpreadsheetView(doc = doc, values = literalValues(doc)) }
        rule.onNodeWithText("10").assertExists()
        rule.onNodeWithText("wide").assertExists()
        rule.onNodeWithText("float text", substring = true).assertExists()
        rule.onNodeWithText("A", substring = true).assertExists()
        rule.onRoot().captureToImage()
    }

    @Test fun editModeSelectionValidationDropdownAndRename() {
        val doc = richDoc(withFloating = false)
        var selected: Triple<Int, Int, Int>? = null
        var changed: List<Any>? = null
        var renamed: Pair<Int, String>? = null
        var addedSheets = 0
        rule.setContent {
            SpreadsheetView(
                doc = doc,
                values = literalValues(doc),
                isEditMode = true,
                onCellSelected = { s, r, c -> selected = Triple(s, r, c) },
                onCellTextChange = { s, r, c, v -> changed = listOf(s, r, c, v) },
                onRenameSheet = { i, n -> renamed = i to n },
                onAddSheet = { addedSheets++ },
            )
        }
        // Validation dropdown on the "pick" cell.
        rule.onNodeWithText("▾").performClick()
        rule.onNodeWithText("Gold").performClick()
        assertEquals(listOf(0, 0, 1, "Gold"), changed)

        // Selecting a numeric cell reports the selection and opens the edit bar.
        rule.onNodeWithText("10").performClick()
        assertEquals(Triple(0, 0, 0), selected)

        // Rename via the tab label in edit mode.
        rule.onNodeWithText("Data").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("OK").performClick()
        assertEquals(0, renamed?.first)
        assertEquals("Data", renamed?.second)

        rule.onNodeWithText("+").performClick()
        assertEquals(1, addedSheets)
        assertTrue(selected != null)
    }

    @Test fun sheetTabsSwitchBetweenSheets() {
        val doc = richDoc(withFloating = false)
        rule.setContent {
            SpreadsheetView(doc = doc, values = literalValues(doc))
        }
        rule.onNodeWithText("Second").performClick()
        rule.onNodeWithText("other").assertExists()
        rule.onNodeWithText("Data").performClick()
        rule.onNodeWithText("10").assertExists()
    }
}
