package com.vayunmathur.office.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.odf.ChartType
import com.vayunmathur.library.ui.odf.OdfChart
import com.vayunmathur.library.ui.odf.OdfChartSeries
import com.vayunmathur.office.EnableOnlineDialog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 4/4 dialog tests (Robolectric + createComposeRule): small self-contained
 * dialogs owned by this slice. Each test renders the dialog, interacts, and
 * asserts the callback contract. Text fields are located via [hasSetTextAction]
 * (labels are separate nodes from the editable field).
 */
@RunWith(AndroidJUnit4::class)
class Slice4DialogsTest {

    @get:Rule val rule = createComposeRule()

    // ---- ColorPickerDialog ----

    @Test fun colorPickerSelectsClearAndDismisses() {
        var picked: Long? = 123L
        var dismissed = false
        rule.setContent {
            ColorPickerDialog(
                title = "Text Color",
                onColorSelected = { picked = it },
                onDismiss = { dismissed = true })
        }
        rule.onNodeWithText("Text Color").assertIsDisplayed()
        // The null/"clear" swatch shows ∅.
        rule.onNodeWithText("∅").assertIsDisplayed()
        rule.onNodeWithText("∅").performClick()
        assertNull(picked)
        assertTrue(dismissed)
    }

    @Test fun colorPickerCancelDismissesWithoutPick() {
        var picked: Long? = null
        var dismissed = false
        rule.setContent {
            ColorPickerDialog(
                title = "Fill",
                onColorSelected = { picked = it },
                onDismiss = { dismissed = true })
        }
        rule.onNodeWithText("Cancel", substring = true).performClick()
        assertNull(picked)
        assertTrue(dismissed)
    }

    // ---- FontSizePickerDialog ----

    @Test fun fontSizePickerSelectsSize() {
        var picked: Float? = null
        var dismissed = false
        rule.setContent {
            FontSizePickerDialog(
                onSizeSelected = { picked = it },
                onDismiss = { dismissed = true })
        }
        rule.onNodeWithText("Font Size").assertIsDisplayed()
        rule.onNodeWithText("16").assertIsDisplayed()
        rule.onNodeWithText("24").performClick()
        assertEquals(24f, picked)
        assertTrue(dismissed)
    }

    // ---- SpecialCharsDialog ----

    @Test fun specialCharsPicksChar() {
        var picked: String? = null
        var dismissed = false
        rule.setContent {
            SpecialCharsDialog(
                onPick = { picked = it },
                onDismiss = { dismissed = true })
        }
        rule.onNodeWithText("Special Character").assertIsDisplayed()
        rule.onNodeWithText("©").assertIsDisplayed()
        rule.onNodeWithText("→").performClick()
        assertEquals("→", picked)
        assertTrue(dismissed)
    }

    // ---- FootnoteDialog ----

    @Test fun footnoteBlankInsertDoesNothing() {
        var added: String? = null
        var dismissed = false
        rule.setContent {
            FootnoteDialog(onAdd = { added = it }, onDismiss = { dismissed = true })
        }
        rule.onNodeWithText("Insert Footnote").assertIsDisplayed()
        rule.onNodeWithText("Insert").performClick()
        assertNull(added)
        assertTrue(!dismissed)
    }

    @Test fun footnoteTypingThenInsert() {
        var added: String? = null
        var dismissed = false
        rule.setContent {
            FootnoteDialog(onAdd = { added = it }, onDismiss = { dismissed = true })
        }
        rule.onNode(hasSetTextAction()).performTextInput("see page 2")
        rule.onNodeWithText("Insert").performClick()
        assertEquals("see page 2", added)
        assertTrue(dismissed)
    }

    @Test fun footnoteCancelDismisses() {
        var dismissed = false
        rule.setContent {
            FootnoteDialog(onAdd = {}, onDismiss = { dismissed = true })
        }
        rule.onNodeWithText("Cancel", substring = true).performClick()
        assertTrue(dismissed)
    }

    // ---- AddBookmarkDialog ----

    @Test fun bookmarkBlankAddDoesNothing() {
        var added: String? = null
        var dismissed = false
        rule.setContent {
            AddBookmarkDialog(onAdd = { added = it }, onDismiss = { dismissed = true })
        }
        rule.onNodeWithText("Add Bookmark").assertIsDisplayed()
        rule.onNodeWithText("Add").performClick()
        assertNull(added)
        assertTrue(!dismissed)
    }

    @Test fun bookmarkTypingThenAdd() {
        var added: String? = null
        var dismissed = false
        rule.setContent {
            AddBookmarkDialog(onAdd = { added = it }, onDismiss = { dismissed = true })
        }
        rule.onNode(hasSetTextAction()).performTextInput("ch1")
        rule.onNodeWithText("Add").performClick()
        assertEquals("ch1", added)
        assertTrue(dismissed)
    }

    // ---- GoToSlideDialog ----

    @Test fun goToSlideOutOfRangeIgnored() {
        var went: Int? = null
        var dismissed = false
        rule.setContent {
            GoToSlideDialog(total = 10, onGo = { went = it }, onDismiss = { dismissed = true })
        }
        rule.onNodeWithText("Go to Slide").assertIsDisplayed()
        rule.onNode(hasSetTextAction()).performTextInput("99")
        rule.onNodeWithText("Go").performClick()
        assertNull(went)
        assertTrue(!dismissed)
    }

    @Test fun goToSlideTypingThenGo() {
        var went: Int? = null
        var dismissed = false
        rule.setContent {
            GoToSlideDialog(total = 10, onGo = { went = it }, onDismiss = { dismissed = true })
        }
        rule.onNode(hasSetTextAction()).performTextInput("3")
        rule.onNodeWithText("Go").performClick()
        assertEquals(2, went)
        assertTrue(dismissed)
    }

    @Test fun goToSlideNonNumericIgnored() {
        var went: Int? = null
        rule.setContent {
            GoToSlideDialog(total = 10, onGo = { went = it }, onDismiss = {})
        }
        rule.onNode(hasSetTextAction()).performTextInput("abc")
        rule.onNodeWithText("Go").performClick()
        assertNull(went)
    }

    // ---- InsertTableDialog ----

    @Test fun insertTableDefaults3x3() {
        var rows = -1
        var cols = -1
        var dismissed = false
        rule.setContent {
            InsertTableDialog(
                onInsert = { r, c -> rows = r; cols = c },
                onDismiss = { dismissed = true })
        }
        rule.onNodeWithText("Insert Table").assertIsDisplayed()
        rule.onNodeWithText("Insert").performClick()
        assertEquals(3, rows)
        assertEquals(3, cols)
        assertTrue(dismissed)
    }

    @Test fun insertTableCustomSize() {
        var rows = -1
        var cols = -1
        var dismissed = false
        rule.setContent {
            InsertTableDialog(
                onInsert = { r, c -> rows = r; cols = c },
                onDismiss = { dismissed = true })
        }
        // Clear defaults and type 5 rows (cols keep default 3).
        val rowFields = rule.onAllNodes(hasSetTextAction())
        rowFields[0].performTextClearance()
        rowFields[0].performTextInput("5")
        rule.onNodeWithText("Insert").performClick()
        assertEquals(5, rows)
        assertEquals(3, cols)
        assertTrue(dismissed)
    }

    @Test fun insertTableClampsOutOfRange() {
        var rows = -1
        var cols = -1
        rule.setContent {
            InsertTableDialog(
                onInsert = { r, c -> rows = r; cols = c },
                onDismiss = {})
        }
        val fields = rule.onAllNodes(hasSetTextAction())
        fields[0].performTextClearance()
        fields[0].performTextInput("999")
        fields[1].performTextClearance()
        fields[1].performTextInput("0")
        rule.onNodeWithText("Insert").performClick()
        assertEquals(50, rows)
        assertEquals(1, cols)
    }

    // ---- InsertHyperlinkDialog ----

    @Test fun hyperlinkBlankTextDoesNotInsert() {
        var inserted: Pair<String, String>? = null
        var dismissed = false
        rule.setContent {
            InsertHyperlinkDialog(
                onInsert = { t, u -> inserted = t to u },
                onDismiss = { dismissed = true })
        }
        rule.onNodeWithText("Insert Hyperlink").assertIsDisplayed()
        rule.onNodeWithText("Insert").performClick()
        assertNull(inserted)
        assertTrue(!dismissed)
    }

    @Test fun hyperlinkInsert() {
        var inserted: Pair<String, String>? = null
        var dismissed = false
        rule.setContent {
            InsertHyperlinkDialog(
                onInsert = { t, u -> inserted = t to u },
                onDismiss = { dismissed = true })
        }
        val fields = rule.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("Example")
        rule.onNodeWithText("Insert").performClick()
        assertEquals("Example" to "https://", inserted)
        assertTrue(dismissed)
    }

    // ---- CommentDialog ----

    @Test fun commentBlankDoesNotAdd() {
        var added: Pair<String, String>? = null
        rule.setContent {
            CommentDialog(
                onAdd = { a, t -> added = a to t },
                onDismiss = {})
        }
        rule.onNodeWithText("Insert Comment").assertIsDisplayed()
        rule.onNodeWithText("Insert").performClick()
        assertNull(added)
    }

    @Test fun commentInsert() {
        var added: Pair<String, String>? = null
        var dismissed = false
        rule.setContent {
            CommentDialog(
                onAdd = { a, t -> added = a to t },
                onDismiss = { dismissed = true })
        }
        val fields = rule.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("me")
        fields[1].performTextInput("nice doc")
        rule.onNodeWithText("Insert").performClick()
        assertEquals("me" to "nice doc", added)
        assertTrue(dismissed)
    }

    // ---- HeaderFooterDialog ----

    @Test fun headerFooterShowsInitialValues() {
        rule.setContent {
            HeaderFooterDialog(
                initialHeader = "H1",
                initialFooter = "F1",
                onSave = { _, _ -> },
                onDismiss = {})
        }
        rule.onNodeWithText("H1").assertIsDisplayed()
        rule.onNodeWithText("F1").assertIsDisplayed()
    }

    @Test fun headerFooterSave() {
        var saved: Pair<String, String>? = null
        var dismissed = false
        rule.setContent {
            HeaderFooterDialog(
                initialHeader = "",
                initialFooter = "keep",
                onSave = { h, f -> saved = h to f },
                onDismiss = { dismissed = true })
        }
        val fields = rule.onAllNodes(hasSetTextAction())
        fields[0].performTextInput("My header")
        rule.onNodeWithText("Save", substring = true).performClick()
        assertEquals("My header" to "keep", saved)
        assertTrue(dismissed)
    }

    // ---- SortDialog ----

    @Test fun sortConfirmDefault() {
        var sorted: Pair<Int, Boolean>? = null
        var dismissed = false
        rule.setContent {
            SortDialog(
                maxCols = 5,
                onSort = { c, a -> sorted = c to a },
                onDismiss = { dismissed = true })
        }
        rule.onNodeWithText("Sort Rows").assertIsDisplayed()
        rule.onNodeWithText("Sort").performClick()
        assertEquals(0 to true, sorted)
        assertTrue(dismissed)
    }

    @Test fun sortCustomColumn() {
        var sorted: Pair<Int, Boolean>? = null
        rule.setContent {
            SortDialog(maxCols = 5, onSort = { c, a -> sorted = c to a }, onDismiss = {})
        }
        // Clear the default "0" first: typing appends at the cursor.
        rule.onNode(hasSetTextAction()).performTextClearance()
        rule.onNode(hasSetTextAction()).performTextInput("3")
        rule.onNodeWithText("Sort").performClick()
        assertEquals(3 to true, sorted)
    }

    @Test fun sortToggleOrder() {
        var sorted: Pair<Int, Boolean>? = null
        rule.setContent {
            SortDialog(maxCols = 5, onSort = { c, a -> sorted = c to a }, onDismiss = {})
        }
        rule.onNodeWithText("A→Z", substring = true).performClick()
        rule.onNodeWithText("Sort").performClick()
        assertEquals(0 to false, sorted)
    }

    @Test fun sortClampsColumn() {
        var sorted: Pair<Int, Boolean>? = null
        rule.setContent {
            SortDialog(maxCols = 5, onSort = { c, a -> sorted = c to a }, onDismiss = {})
        }
        rule.onNode(hasSetTextAction()).performTextClearance()
        rule.onNode(hasSetTextAction()).performTextInput("99")
        rule.onNodeWithText("Sort").performClick()
        assertEquals(4 to true, sorted)
    }

    // ---- SettingsDialog ----

    @Test fun settingsShowsAndSaves() {
        var saved: Array<Any?>? = null
        var dismissed = false
        rule.setContent {
            SettingsDialog(
                autoSave = true,
                autoSaveInterval = 60,
                defaultFontSize = 16f,
                onSave = { a, i, f, m -> saved = arrayOf(a, i, f, m) },
                onDismiss = { dismissed = true })
        }
        rule.onNodeWithText("Settings", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Save", substring = true).performClick()
        val s = assertNotNull(saved)
        assertEquals(true, s[0])
        assertEquals(60, s[1])
        assertEquals(16f, s[2])
        assertTrue(dismissed)
    }

    @Test fun settingsToggleAutoSaveOff() {
        var saved: Array<Any?>? = null
        rule.setContent {
            SettingsDialog(
                autoSave = true,
                autoSaveInterval = 60,
                defaultFontSize = 16f,
                onSave = { a, i, f, m -> saved = arrayOf(a, i, f, m) },
                onDismiss = {})
        }
        rule.onNodeWithText("ON", substring = true).performClick()
        rule.onNodeWithText("Save", substring = true).performClick()
        assertEquals(false, assertNotNull(saved)[0])
    }

    // ---- EnableOnlineDialog ----

    @Test fun enableOnlineConfirmAndDismiss() {
        var enabled = false
        var dismissed = false
        rule.setContent {
            EnableOnlineDialog(
                onEnable = { enabled = true },
                onDismiss = { dismissed = true })
        }
        // Exact match: the title ("Enable online sharing?") also contains "Enable".
        rule.onNodeWithText("Enable").performClick()
        assertTrue(enabled)
        assertTrue(!dismissed)
    }

    // ---- ChartEditorDialog ----

    @Test fun chartEditorShowsDefaults() {
        rule.setContent {
            ChartEditorDialog(initial = null, onConfirm = {}, onDismiss = {})
        }
        rule.onNodeWithText("Insert Chart", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Category 1, Category 2, Category 3").assertIsDisplayed()
    }

    @Test fun chartEditorShowsInitial() {
        val initial = OdfChart(
            ChartType.LINE,
            listOf("A", "B"),
            listOf(OdfChartSeries("S", listOf(1f, 2f))))
        rule.setContent {
            ChartEditorDialog(initial = initial, onConfirm = {}, onDismiss = {})
        }
        rule.onNodeWithText("Edit Chart", substring = true).assertIsDisplayed()
        rule.onNodeWithText("A, B").assertIsDisplayed()
    }

    @Test fun chartConfirmDefaults() {
        var confirmed: OdfChart? = null
        var dismissed = false
        rule.setContent {
            ChartEditorDialog(
                initial = null,
                onConfirm = { confirmed = it },
                onDismiss = { dismissed = true })
        }
        rule.onNodeWithText("OK").performClick()
        val chart = assertNotNull(confirmed)
        assertEquals(ChartType.BAR, chart.type)
        assertEquals(3, chart.categories.size)
        assertEquals(1, chart.series.size)
        assertEquals("Series 1", chart.series[0].name)
        assertTrue(dismissed)
    }

    @Test fun chartConfirmSkipsBlankLines() {
        var confirmed: OdfChart? = null
        rule.setContent {
            ChartEditorDialog(
                initial = null,
                onConfirm = { confirmed = it },
                onDismiss = {})
        }
        // Blank out categories+series -> confirm must not fire.
        val fields = rule.onAllNodes(hasSetTextAction())
        fields[0].performTextClearance()
        fields[1].performTextClearance()
        rule.onNodeWithText("OK").performClick()
        assertNull(confirmed)
    }

    @Test fun chartSwitchType() {
        var confirmed: OdfChart? = null
        rule.setContent {
            ChartEditorDialog(
                initial = null,
                onConfirm = { confirmed = it },
                onDismiss = {})
        }
        rule.onNodeWithText("Line").performClick()
        rule.onNodeWithText("OK").performClick()
        assertEquals(ChartType.LINE, assertNotNull(confirmed).type)
    }
}
