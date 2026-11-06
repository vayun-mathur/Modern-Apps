package com.vayunmathur.office.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.odf.OdfCell
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfNamedRange
import com.vayunmathur.library.ui.odf.OdfNumberFormat
import com.vayunmathur.library.ui.odf.OdfNumberToken
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfRow
import com.vayunmathur.library.ui.odf.OdfSheet
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.office.DocumentScreenState
import com.vayunmathur.office.OfficeDocumentPane
import com.vayunmathur.office.util.DocumentTreeCrdt
import com.vayunmathur.office.util.OfficeNative
import com.vayunmathur.office.util.OfficeViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice 4/4 spreadsheet consolidation: EVERY test that touches the native
 * `office_engine` library lives in this one class.
 *
 * The JVM loads each native library exactly once per classloader, and every
 * Robolectric test class gets its own sandboxed classloader — so a second
 * class touching `OfficeNative` fails with "already loaded in another
 * classloader". That covers direct JNI calls AND production paths that reach
 * the engine ([SpreadsheetView]'s default values via [OfficeDocumentPane]'s
 * sheet branch). All of them are consolidated here.
 */
@RunWith(AndroidJUnit4::class)
class Slice4SheetsTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private fun vm(doc: OdfDocument): OfficeViewModel =
        OfficeViewModel(ApplicationProvider.getApplicationContext()).apply {
            stateMutable.value = OfficeViewModel.ViewState.Loaded(doc)
        }

    private fun sheetDoc(): OdfDocument.Spreadsheet = OdfDocument.Spreadsheet(
        "Book",
        listOf(
            OdfSheet(
                "Sheet1",
                listOf(
                    OdfRow(listOf(OdfCell("a1"), OdfCell("b1"))),
                    OdfRow(listOf(OdfCell("a2"), OdfCell("b2"))),
                )),
        ),
    )

    private fun literalValues(doc: OdfDocument.Spreadsheet) = object : SpreadsheetValues {
        override fun display(sheet: Int, row: Int, col: Int): String =
            doc.sheets[sheet].rows[row].cells[col].text

        override fun isNumeric(sheet: Int, row: Int, col: Int): Boolean =
            doc.sheets[sheet].rows[row].cells[col].text.toDoubleOrNull() != null
    }

    // ---- SpreadsheetView ----

    @Test fun sheetViewRendersCells() {
        val doc = sheetDoc()
        rule.setContent { SpreadsheetView(doc = doc, values = literalValues(doc)) }
        rule.onNodeWithText("a1").assertIsDisplayed()
        rule.onNodeWithText("b2").assertIsDisplayed()
        rule.onNodeWithText("Sheet1", substring = true).assertIsDisplayed()
    }

    @Test fun sheetViewSecondSheetTab() {
        val doc = OdfDocument.Spreadsheet(
            "Book",
            listOf(
                OdfSheet("One", listOf(OdfRow(listOf(OdfCell("alpha"))))),
                OdfSheet("Two", listOf(OdfRow(listOf(OdfCell("beta"))))),
            ),
        )
        rule.setContent { SpreadsheetView(doc = doc, values = literalValues(doc)) }
        rule.onNodeWithText("alpha").assertIsDisplayed()
        rule.onNodeWithText("Two", substring = true).performClick()
        rule.onNodeWithText("beta").assertIsDisplayed()
    }

    @Test fun sheetViewEmptyShowsPlaceholder() {
        val doc = OdfDocument.Spreadsheet("Empty", emptyList())
        rule.setContent { SpreadsheetView(doc = doc, values = literalValues(doc)) }
        rule.onNodeWithText("Empty spreadsheet").assertIsDisplayed()
    }

    @Test fun sheetViewCellSelectionCallback() {
        val doc = sheetDoc()
        var selected: Triple<Int, Int, Int>? = null
        rule.setContent {
            SpreadsheetView(
                doc = doc,
                // Taps select cells only in edit mode.
                isEditMode = true,
                values = literalValues(doc),
                onCellSelected = { s, r, c -> selected = Triple(s, r, c) },
            )
        }
        rule.onNodeWithText("b1").performClick()
        assertEquals(Triple(0, 0, 1), selected)
    }

    @Test fun sheetViewSearchHighlights() {
        val doc = sheetDoc()
        rule.setContent {
            SpreadsheetView(doc = doc, values = literalValues(doc), searchQuery = "a1")
        }
        rule.onNodeWithText("a1").assertIsDisplayed()
    }

    @Test fun sheetViewNativeValues() {
        // Default values source = the real native formula engine.
        val doc = sheetDoc()
        rule.setContent { SpreadsheetView(doc = doc) }
        rule.onNodeWithText("a1").assertIsDisplayed()
        rule.onNodeWithText("b2").assertIsDisplayed()
    }

    // ---- Sheet via the document pane (production native path) ----

    @Test fun paneSheetRendersCellsNative() {
        val document = sheetDoc()
        rule.setContent {
            OfficeDocumentPane(
                document = document,
                viewModel = vm(document),
                documentDarkMode = false,
                nightMode = false,
                searchQuery = "",
                fontSizeMultiplier = 1f,
                listState = rememberLazyListState(),
                isEditMode = false,
                activeSlideEl = -1,
                onRunSelectionChange = { _, _, _, _ -> },
                onCellFocus = { _, _, _ -> },
                onCellSelected = { _, _, _ -> },
                onSlideChange = {},
                onSlideElementSelected = { _, _ -> },
                onChartClick = {},
                onCropImage = {},
                onCropSheet = { _, _ -> },
                onCropSlide = { _, _ -> },
            )
        }
        rule.onNodeWithText("a1").assertIsDisplayed()
    }

    // ---- Workbook serialization (pure JVM; must agree with formula.rs serde schema) ----

    private fun namedSheet(name: String = "Sheet1") = OdfSheet(
        name,
        listOf(
            OdfRow(
                listOf(
                    OdfCell("1", numberValue = 1.0, valueType = "float"),
                    OdfCell("=SUM(A1:A3)", formula = "of:=SUM([.A1:.A3])"),
                    OdfCell("covered", isCovered = true),
                ))))

    @Test fun emptyWorkbookShape() {
        val json = OfficeNative.buildWorkbookJson(emptyList())
        assertTrue(json.contains("\"sheets\":[]"), json)
        assertTrue(json.contains("namedRanges"), json)
    }

    @Test fun cellsSerialized() {
        val json = OfficeNative.buildWorkbookJson(listOf(namedSheet()))
        assertTrue(json.contains("\"Sheet1\""), json)
        assertTrue(json.contains("\"of:=SUM([.A1:.A3])\""), json)
        assertTrue(json.contains("isCovered\":true"), json)
        assertTrue(json.contains("numberValue\":1.0"), json)
        assertTrue(json.contains("float"), json)
    }

    @Test fun numberFormatSerialized() {
        val fmt = OdfNumberFormat(
            decimals = 2,
            percent = true,
            currencySymbol = "$",
            grouping = true,
            isDate = false,
            isTime = true,
            isScientific = false,
            isFraction = true,
            fractionDenominatorDigits = 2,
            dateTimeTokens = listOf(OdfNumberToken("hours", style = "long")))
        val s = OdfSheet("S", listOf(OdfRow(listOf(OdfCell("x", numberFormat = fmt)))))
        val json = OfficeNative.buildWorkbookJson(listOf(s))
        assertTrue(json.contains("decimals\":2"), json)
        assertTrue(json.contains("percent\":true"), json)
        assertTrue(json.contains("currencySymbol\":\"\$\""), json)
        assertTrue(json.contains("grouping\":true"), json)
        assertTrue(json.contains("isTime\":true"), json)
        assertTrue(json.contains("fractionDenominatorDigits\":2"), json)
        assertTrue(json.contains("hours"), json)
        assertTrue(json.contains("dateTimeTokens"), json)
    }

    @Test fun namedRangesSerialized() {
        val json = OfficeNative.buildWorkbookJson(
            listOf(namedSheet()),
            namedRanges = listOf(OdfNamedRange("Total", "Sheet1.A1:Sheet1.A3", "Sheet1.A1")))
        assertTrue(json.contains("\"Total\""), json)
        assertTrue(json.contains("Sheet1.A1:Sheet1.A3"), json)
        assertEquals(1, Regex("Total").findAll(json).count())
    }

    @Test fun multipleSheets() {
        val json = OfficeNative.buildWorkbookJson(listOf(namedSheet("One"), namedSheet("Two")))
        assertTrue(json.contains("\"One\""), json)
        assertTrue(json.contains("\"Two\""), json)
    }

    // ---- Formula engine ----

    private fun wbSheet(vararg rows: List<OdfCell>, name: String = "S") =
        OdfSheet(name, rows.map { OdfRow(it) })

    @Test fun literalValuesDisplay() {
        val h = OfficeNative.createWorkbook(
            listOf(wbSheet(listOf(OdfCell("hi"), OdfCell("3", numberValue = 3.0)))), 0L)
        try {
            assertEquals("hi", OfficeNative.nativeDisplayValue(h, 0, 0, 0))
            assertEquals("3", OfficeNative.nativeDisplayValue(h, 0, 0, 1))
            assertTrue(OfficeNative.nativeIsNumeric(h, 0, 0, 1))
            assertTrue(!OfficeNative.nativeIsNumeric(h, 0, 0, 0))
        } finally {
            OfficeNative.nativeFree(h)
        }
    }

    @Test fun sumFormulaEvaluates() {
        val h = OfficeNative.createWorkbook(
            listOf(
                wbSheet(
                    listOf(OdfCell("1", numberValue = 1.0), OdfCell("2", numberValue = 2.0)),
                    listOf(
                        OdfCell("=SUM(A1:B1)", formula = "of:=SUM([.A1:.B1])"),
                        OdfCell("x")),
                )),
            0L)
        try {
            assertEquals("3", OfficeNative.nativeDisplayValue(h, 0, 1, 0))
            assertTrue(OfficeNative.nativeIsNumeric(h, 0, 1, 0))
        } finally {
            OfficeNative.nativeFree(h)
        }
    }

    @Test fun outOfRangeIsEmpty() {
        val h = OfficeNative.createWorkbook(listOf(wbSheet(listOf(OdfCell("a")))), 0L)
        try {
            assertEquals("", OfficeNative.nativeDisplayValue(h, 0, 9, 9))
            assertEquals("", OfficeNative.nativeDisplayValue(h, 5, 0, 0))
        } finally {
            OfficeNative.nativeFree(h)
        }
    }

    @Test fun formatValuePlainAndGrouped() {
        assertEquals("3.50", OfficeNative.formatValue(3.5, OdfNumberFormat(decimals = 2)))
        assertEquals("1", OfficeNative.formatValue(1.0, null))
        val grouped = OfficeNative.formatValue(1234.0, OdfNumberFormat(grouping = true))
        assertTrue(grouped.contains("1") && grouped.contains("234"), grouped)
    }

    @Test fun formatValuePercent() {
        val pct = OfficeNative.formatValue(0.5, OdfNumberFormat(percent = true))
        assertTrue(pct.contains("50"), pct)
    }

    // ---- Document-tree CRDT ----

    @Test fun crdtUpdateRenderRoundTrip() {
        val c = DocumentTreeCrdt("devA")
        try {
            val xml = "<office><p>hi</p></office>"
            val ops = c.updateJson(xml)
            assertTrue(ops.startsWith("["), ops)
            assertTrue(ops.length > 2, ops)
            assertEquals(xml, c.render())
        } finally {
            c.close()
        }
    }

    @Test fun crdtEditProducesNewRender() {
        val c = DocumentTreeCrdt("devA")
        try {
            c.updateJson("<p>ab</p>")
            c.updateJson("<p>axb</p>")
            assertEquals("<p>axb</p>", c.render())
        } finally {
            c.close()
        }
    }

    @Test fun crdtSerializeAndReload() {
        val a = DocumentTreeCrdt("devA")
        try {
            a.updateJson("<office><p>Hello</p></office>")
            val state = a.serialize()
            assertTrue(state.contains("devA"), state)
            val nodes = a.toStateNodesJson()
            assertTrue(nodes.startsWith("["), nodes)
            val b = DocumentTreeCrdt("devB")
            try {
                b.loadState(state)
                assertEquals(a.render(), b.render())
            } finally {
                b.close()
            }
        } finally {
            a.close()
        }
    }

    @Test fun crdtApplyMergesRemoteOps() {
        val src = DocumentTreeCrdt("devA")
        val dst = DocumentTreeCrdt("devB")
        try {
            val ops = src.updateJson("<office><p>Hello</p><p>World</p></office>")
            dst.applyJson(ops)
            assertEquals(src.render(), dst.render())
            // Idempotent: applying twice changes nothing.
            dst.applyJson(ops)
            assertEquals(src.render(), dst.render())
        } finally {
            src.close()
            dst.close()
        }
    }

    // ---- Full screen with a sheet (production native path) ----

    @Test fun sheetScreenRendersGridNative() {
        val doc = OdfDocument.Spreadsheet(
            "Book.ods",
            listOf(OdfSheet("S", listOf(OdfRow(listOf(OdfCell("grid1")))))),
        )
        rule.setContent {
            com.vayunmathur.office.OfficeLightTheme {
                com.vayunmathur.office.DocumentScreen(
                    document = doc,
                    viewModel = vm(doc),
                    activity = rule.activity,
                    onBack = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Book.ods").assertIsDisplayed()
        rule.onNodeWithText("grid1").assertIsDisplayed()
    }

    @Test fun textScreenUnaffected() {
        // Sanity: non-sheet content in the consolidated class renders too.
        val doc = OdfDocument.TextDocument(
            "T",
            listOf(
                OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("plain text"))))),
        )
        rule.setContent {
            com.vayunmathur.office.ui.TextDocumentView(doc = doc)
        }
        rule.onNodeWithText("plain text", substring = true).assertExists()
    }
}
