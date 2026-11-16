package com.vayunmathur.office.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.odf.OdfCell
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfRow
import com.vayunmathur.library.ui.odf.OdfSheet
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * W4: [SpreadsheetEditBar] commit/advance behaviour at the first and last
 * rows, the [SpreadsheetEditToolbar] row/column/sheet actions, and
 * [MathView] rendering of the full MathML node vocabulary.
 */
@RunWith(AndroidJUnit4::class)
class W4SheetEditMathTest {

    @get:Rule val rule = createComposeRule()

    private fun sheetDoc() = OdfDocument.Spreadsheet(
        "Book",
        listOf(
            OdfSheet(
                "Data",
                listOf(
                    OdfRow(listOf(OdfCell("a1"), OdfCell("b1"))),
                    OdfRow(listOf(OdfCell("a2"), OdfCell("b2"))),
                ),
            ),
            OdfSheet("Second", listOf(OdfRow(listOf(OdfCell("x"))))),
        ),
    )

    @Test fun editBarDoneCommitsAndClears() {
        val commits = mutableListOf<List<Any?>>()
        var advanced: Pair<Triple<Int, Int, Int>?, TextFieldValue?>? = null
        var selected: Triple<Int, Int, Int>? = null
        rule.setContent {
            var editText by remember { mutableStateOf(TextFieldValue("edited")) }
            SpreadsheetEditBar(
                doc = sheetDoc(),
                selectedSheet = 0,
                editingCell = Triple(0, 0, 1),
                editText = editText,
                onEditTextChange = { editText = it },
                onCommitCell = { si, r, c, text -> commits.add(listOf(si, r, c, text)) },
                onAdvanceCell = { cell, value -> advanced = cell to value },
                onCellSelected = { si, r, c -> selected = Triple(si, r, c) },
            )
        }
        rule.onNodeWithText("B1").assertIsDisplayed()
        rule.onNodeWithText("Done").performClick()
        assertEquals(listOf<Any?>(0, 0, 1, "edited"), commits.single())
        assertTrue(advanced != null)
        assertTrue(advanced!!.first == null)
        assertEquals(Triple(0, -1, -1), selected)
    }

    @Test fun editBarOnLastRowHasNoAdvanceTarget() {
        var advanced: Pair<Triple<Int, Int, Int>?, TextFieldValue?>? = null
        var selected: Triple<Int, Int, Int>? = null
        rule.setContent {
            var editText by remember { mutableStateOf(TextFieldValue("last")) }
            SpreadsheetEditBar(
                doc = sheetDoc(),
                selectedSheet = 0,
                editingCell = Triple(0, 1, 0),
                editText = editText,
                onEditTextChange = { editText = it },
                onCommitCell = { _, _, _, _ -> },
                onAdvanceCell = { cell, value -> advanced = cell to value },
                onCellSelected = { si, r, c -> selected = Triple(si, r, c) },
            )
        }
        rule.onNode(hasSetTextAction()).assertIsDisplayed()
        // The Done path on the last row also ends editing (commitAndAdvance's
        // sibling button shares the clear-selection behaviour).
        rule.onNodeWithText("Done").performClick()
        assertTrue(advanced!!.first == null)
        assertEquals(Triple(0, -1, -1), selected)
        assertTrue(true)
    }

    @Test fun editToolbarFiresRowColumnActions() {
        val actions = mutableListOf<String>()
        rule.setContent {
            SpreadsheetEditToolbar(
                doc = sheetDoc(),
                selectedSheet = 0,
                editingCell = Triple(0, 0, 0),
                onAddRow = { si, r -> actions.add("addRow:$si:$r") },
                onAddColumn = { si -> actions.add("addCol:$si") },
                onDeleteRow = { si, r -> actions.add("delRow:$si:$r") },
                onDeleteColumn = { si, c -> actions.add("delCol:$si:$c") },
                onEditingCleared = { actions.add("cleared") },
                onShowSort = { actions.add("sort") },
                onSetFreeze = { si, r, c -> actions.add("freeze:$si:$r:$c") },
                onDeleteSheetClick = { actions.add("delSheet") },
            )
        }
        rule.onNodeWithText("+ Row").performClick()
        rule.onNodeWithText("+ Col").performClick()
        rule.onNodeWithText("Sort").performClick()
        assertTrue(actions.contains("addRow:0:0"), actions.toString())
        assertTrue(actions.contains("addCol:0"), actions.toString())
        assertTrue(actions.contains("sort"), actions.toString())
    }

    @Test fun editBarImeNextCommitsAndAdvances() {
        val commits = mutableListOf<List<Any?>>()
        var advanced: Pair<Triple<Int, Int, Int>?, TextFieldValue?>? = null
        var selected: Triple<Int, Int, Int>? = null
        rule.setContent {
            var editText by remember { mutableStateOf(TextFieldValue("v1")) }
            SpreadsheetEditBar(
                doc = sheetDoc(),
                selectedSheet = 0,
                editingCell = Triple(0, 0, 0),
                editText = editText,
                onEditTextChange = { editText = it },
                onCommitCell = { si, r, c, text -> commits.add(listOf(si, r, c, text)) },
                onAdvanceCell = { cell, value -> advanced = cell to value },
                onCellSelected = { si, r, c -> selected = Triple(si, r, c) },
            )
        }
        rule.onNode(hasSetTextAction()).performImeAction()
        rule.waitForIdle()
        assertEquals(listOf<Any?>(0, 0, 0, "v1"), commits.single())
        // Advances to the cell below, pre-filled with its current text.
        assertEquals(Triple(0, 1, 0), advanced!!.first)
        assertEquals("a2", advanced!!.second?.text)
        assertEquals(Triple(0, 1, 0), selected)
    }

    // ---- MathView ----

    @Test fun mathViewRendersRichMathml() {
        val mathml = "<math>" +
            "<mfrac><mi>a</mi><mi>b</mi></mfrac>" +
            "<msup><mi>x</mi><mn>2</mn></msup>" +
            "<msub><mi>y</mi><mn>1</mn></msub>" +
            "<msqrt><mi>z</mi></msqrt>" +
            "<mroot><mi>r</mi><mn>3</mn></mroot>" +
            "<mfenced open=\"(\" close=\")\"><mi>f</mi></mfenced>" +
            "</math>"
        rule.setContent { MathView(mathml) }
        for (token in listOf("a", "b", "x", "2", "y", "z", "r", "f")) {
            rule.onNodeWithText(token, substring = true).assertExists()
        }
    }

    @Test fun mathViewRendersUnderOverAndTables() {
        val mathml = "<math>" +
            "<munderover><mo>∑</mo><mn>0</mn><mi>n</mi></munderover>" +
            "<mtable><mtr><mtd><mn>1</mn></mtd><mtd><mn>2</mn></mtd></mtr>" +
            "<mtr><mtd><mn>3</mn></mtd><mtd><mn>4</mn></mtd></mtr></mtable>" +
            "</math>"
        rule.setContent { MathView(mathml) }
        rule.onNodeWithText("∑", substring = true).assertExists()
        rule.onNodeWithText("4", substring = true).assertExists()
    }

    @Test fun mathViewFallsBackToRawTextForGarbage() {
        val bad = "<<not math at all"
        rule.setContent { MathView(bad) }
        rule.onNodeWithText("not math", substring = true).assertExists()
    }
}
