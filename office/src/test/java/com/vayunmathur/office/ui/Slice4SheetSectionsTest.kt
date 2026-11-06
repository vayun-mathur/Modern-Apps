package com.vayunmathur.office.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.odf.OdfCell
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfRow
import com.vayunmathur.library.ui.odf.OdfSheet
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice 4/4 spreadsheet-section tests (Robolectric + createComposeRule):
 * [SpreadsheetEditBar] commit/advance, [SpreadsheetEditToolbar] row/col/sort/
 * freeze actions, and [SpreadsheetDialogs] rename/sort.
 */
@RunWith(AndroidJUnit4::class)
class Slice4SheetSectionsTest {

    @get:Rule val rule = createComposeRule()

    private fun doc() = OdfDocument.Spreadsheet(
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

    // ---- SpreadsheetEditBar ----

    @Test fun editBarShowsAddressAndCommits() {
        var committed: Triple<Int, Int, Int>? = null
        var committedText: String? = null
        rule.setContent {
            SpreadsheetEditBar(
                doc = doc(),
                selectedSheet = 0,
                editingCell = Triple(0, 0, 0),
                editText = TextFieldValue("a1"),
                onEditTextChange = {},
                onCommitCell = { s, r, c, t -> committed = Triple(s, r, c); committedText = t },
                onAdvanceCell = { _, _ -> },
                onCellSelected = { _, _, _ -> },
            )
        }
        rule.onNodeWithText("A1", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Done", substring = true).performClick()
        assertEquals(Triple(0, 0, 0), committed)
        assertEquals("a1", committedText)
    }

    @Test fun editBarDoneCommitsAndClears() {
        var committed: Triple<Int, Int, Int>? = null
        var committedText: String? = null
        var advanced: Triple<Int, Int, Int>? = Triple(-9, -9, -9)
        var selected: Triple<Int, Int, Int>? = null
        rule.setContent {
            SpreadsheetEditBar(
                doc = doc(),
                selectedSheet = 0,
                editingCell = Triple(0, 0, 0),
                editText = TextFieldValue("a1"),
                onEditTextChange = {},
                onCommitCell = { s, r, c, t -> committed = Triple(s, r, c); committedText = t },
                onAdvanceCell = { cell, _ -> advanced = cell },
                onCellSelected = { s, r, c -> selected = Triple(s, r, c) },
            )
        }
        // Address label A1; Done commits and clears the editor (advance=null).
        rule.onNodeWithText("A1").assertIsDisplayed()
        rule.onNodeWithText("Done").performClick()
        assertEquals(Triple(0, 0, 0), committed)
        assertEquals("a1", committedText)
        assertEquals(null, advanced)
        assertEquals(Triple(0, -1, -1), selected)
    }

    @Test fun editBarDoneOnLastRowClears() {
        var advanced: Triple<Int, Int, Int>? = Triple(-9, -9, -9)
        var selected: Triple<Int, Int, Int>? = null
        rule.setContent {
            SpreadsheetEditBar(
                doc = doc(),
                selectedSheet = 0,
                editingCell = Triple(0, 1, 0),
                editText = TextFieldValue("a2"),
                onEditTextChange = {},
                onCommitCell = { _, _, _, _ -> },
                onAdvanceCell = { cell, _ -> advanced = cell },
                onCellSelected = { s, r, c -> selected = Triple(s, r, c) },
            )
        }
        rule.onNodeWithText("Done").performClick()
        assertEquals(null, advanced)
        assertEquals(Triple(0, -1, -1), selected)
    }

    // ---- SpreadsheetEditToolbar ----

    @Test fun toolbarRowColSortFreeze() {
        var addedRow: Pair<Int, Int>? = null
        var addedCol: Int? = null
        var sortShown = false
        var freeze: Triple<Int, Int, Int>? = null
        rule.setContent {
            SpreadsheetEditToolbar(
                doc = doc(),
                selectedSheet = 0,
                editingCell = null,
                onAddRow = { s, r -> addedRow = s to r },
                onAddColumn = { addedCol = it },
                onDeleteRow = { _, _ -> },
                onDeleteColumn = { _, _ -> },
                onEditingCleared = {},
                onShowSort = { sortShown = true },
                onSetFreeze = { s, r, c -> freeze = Triple(s, r, c) },
                onDeleteSheetClick = {},
            )
        }
        rule.onNodeWithText("Row", substring = true).performClick()
        assertEquals(0 to 1, addedRow)
        rule.onNodeWithText("Col", substring = true).performClick()
        assertEquals(0, addedCol)
        rule.onNodeWithText("Sort").performClick()
        assertTrue(sortShown)
        rule.onNodeWithText("Freeze", substring = true).performClick()
        assertEquals(Triple(0, 1, 0), freeze)
    }

    @Test fun toolbarDeleteRowColWhenEditing() {
        var deletedRow: Pair<Int, Int>? = null
        var deletedCol: Pair<Int, Int>? = null
        var cleared = false
        rule.setContent {
            SpreadsheetEditToolbar(
                doc = doc(),
                selectedSheet = 0,
                editingCell = Triple(0, 0, 1),
                onAddRow = { _, _ -> },
                onAddColumn = {},
                onDeleteRow = { s, r -> deletedRow = s to r },
                onDeleteColumn = { s, c -> deletedCol = s to c },
                onEditingCleared = { cleared = true },
                onShowSort = {},
                onSetFreeze = { _, _, _ -> },
                onDeleteSheetClick = {},
            )
        }
        // Delete-row / delete-column appear only while editing.
        rule.onNodeWithText("Delete", substring = true).assertDoesNotExist()
        assertEquals(null, deletedRow)
        assertEquals(null, deletedCol)
        assertTrue(!cleared)
    }

    @Test fun toolbarUnfreezeWhenFrozen() {
        var freeze: Triple<Int, Int, Int>? = null
        val frozen = OdfDocument.Spreadsheet(
            "Book",
            listOf(
                OdfSheet(
                    "S",
                    listOf(OdfRow(listOf(OdfCell("a")))),
                    freezeRows = 1,
                    freezeCols = 0,
                )),
        )
        rule.setContent {
            SpreadsheetEditToolbar(
                doc = frozen,
                selectedSheet = 0,
                editingCell = null,
                onAddRow = { _, _ -> },
                onAddColumn = {},
                onDeleteRow = { _, _ -> },
                onDeleteColumn = { _, _ -> },
                onEditingCleared = {},
                onShowSort = {},
                onSetFreeze = { s, r, c -> freeze = Triple(s, r, c) },
                onDeleteSheetClick = {},
            )
        }
        rule.onNodeWithText("Unfreeze", substring = true).performClick()
        assertEquals(Triple(0, 0, 0), freeze)
    }

    // ---- SpreadsheetDialogs ----

    @Test fun renameDialogConfirms() {
        var renamed: String? = null
        var dismissed = false
        rule.setContent {
            SpreadsheetDialogs(
                doc = doc(),
                selectedSheet = 0,
                showRenameSheet = true,
                renameText = "Sheet1",
                onRenameTextChange = {},
                onRenameConfirm = { renamed = it },
                onRenameDismiss = { dismissed = true },
                showSortDialog = false,
                onSort = { _, _ -> },
                onSortDismiss = {},
            )
        }
        rule.onNodeWithText("Rename", substring = true).assertIsDisplayed()
        rule.onNodeWithText("OK").performClick()
        assertEquals("Sheet1", renamed)
        assertTrue(!dismissed)
    }

    @Test fun sortDialogFromSections() {
        var sorted: Pair<Int, Boolean>? = null
        var dismissed = false
        rule.setContent {
            SpreadsheetDialogs(
                doc = doc(),
                selectedSheet = 0,
                showRenameSheet = false,
                renameText = "",
                onRenameTextChange = {},
                onRenameConfirm = {},
                onRenameDismiss = {},
                showSortDialog = true,
                onSort = { c, a -> sorted = c to a },
                onSortDismiss = { dismissed = true },
            )
        }
        rule.onNodeWithText("Sort Rows").assertIsDisplayed()
        rule.onNode(hasSetTextAction()).performTextClearance()
        rule.onNode(hasSetTextAction()).performTextInput("1")
        rule.onNodeWithText("Sort").performClick()
        assertEquals(1 to true, sorted)
        assertTrue(dismissed)
    }
}
