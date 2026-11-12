package com.vayunmathur.notes.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vayunmathur.notes.data.Note
import com.vayunmathur.notes.data.NoteBlock
import com.vayunmathur.notes.domain.normalizeTags
import com.vayunmathur.notes.domain.withoutTag
import com.vayunmathur.notes.platform.NoteActions
import com.vayunmathur.notes.platform.NoteUiState
import com.vayunmathur.notes.platform.NotesListActions
import com.vayunmathur.notes.platform.NotesListUiState
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tag UI (#913): adding/removing in the editor, filtering and the rename/delete menu in the list. */
@RunWith(RobolectricTestRunner::class)
class NoteTagsComposeTest {
    @get:Rule val rule = createComposeRule()

    private class RecordingListActions : NotesListActions {
        val renamed = mutableListOf<Pair<String, String>>()
        val deleted = mutableListOf<String>()
        override fun renameTag(from: String, to: String) { renamed += from to to }
        override fun deleteTag(tag: String) { deleted += tag }
    }

    private fun notes() = listOf(
        Note(id = 1, title = "Standup", content = "agenda", position = 1.0, syncId = "s1", tags = listOf("Work")),
        Note(id = 2, title = "Groceries", content = "eggs", position = 2.0, syncId = "s2", tags = listOf("Home")),
        Note(id = 3, title = "Roadmap", content = "plan", position = 3.0, syncId = "s3", tags = listOf("Work", "Ideas")),
        Note(id = 4, title = "Loose", content = "none", position = 4.0, syncId = "s4"),
    )

    private fun shown(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()

    // ---- editor ----

    @Test fun editorAddsTagOnDoneAndRemovesOnChipTap() {
        var tags by mutableStateOf(listOf("Existing"))
        val actions = object : NoteActions {
            override fun addTag(tag: String) { tags = normalizeTags(tags + tag) }
            override fun removeTag(tag: String) { tags = withoutTag(tags, tag) }
        }
        rule.setContent {
            NoteScreen(
                NoteUiState("Title here", listOf(NoteBlock.Text("body", "b1")), tags = tags, knownTags = listOf("Existing", "Travel")),
                actions,
            )
        }
        rule.onNodeWithText("Existing").assertIsDisplayed()
        rule.onNode(hasSetTextAction() and hasText("Add tag")).performTextInput("  road   trip ")
        // The placeholder is gone once there is text; find the field by what was typed.
        rule.onNode(hasSetTextAction() and hasText("road", substring = true)).performImeAction()
        rule.waitForIdle()
        assertEquals(listOf("Existing", "road trip"), tags)
        rule.onNodeWithText("road trip").assertIsDisplayed()

        rule.onNodeWithContentDescription("Remove tag Existing").performClick()
        rule.waitForIdle()
        assertEquals(listOf("road trip"), tags)
        // The chip is gone ("Existing" may still be offered as a suggestion while the field has focus).
        assertTrue(rule.onAllNodesWithContentDescription("Remove tag Existing").fetchSemanticsNodes().isEmpty())
    }

    @Test fun editorCommaAddsSeveralAndSuggestionAddsKnownTag() {
        var tags by mutableStateOf(emptyList<String>())
        val actions = object : NoteActions {
            override fun addTag(tag: String) { tags = normalizeTags(tags + tag) }
        }
        rule.setContent {
            NoteScreen(NoteUiState("T", listOf(NoteBlock.Text("b", "b1")), tags = tags, knownTags = listOf("Travel", "Work")), actions)
        }
        val field = rule.onNode(hasSetTextAction() and hasText("Add tag"))
        field.performTextInput("a, b,")
        rule.waitForIdle()
        assertEquals(listOf("a", "b"), tags)
        // Field is focused: typing narrows suggestions to known tags; tapping one adds it.
        rule.onNode(hasSetTextAction() and hasText("Add tag")).performTextInput("tra")
        rule.waitForIdle()
        rule.onNodeWithText("Travel").performClick()
        rule.waitForIdle()
        assertEquals(listOf("a", "b", "Travel"), tags)
    }

    @Test fun editorAtTheTagLimitReplacesTheFieldWithANote() {
        val full = (1..com.vayunmathur.notes.domain.MAX_TAGS_PER_NOTE).map { "t$it" }
        rule.setContent { NoteScreen(NoteUiState("T", listOf(NoteBlock.Text("b", "b1")), tags = full), object : NoteActions {}) }
        rule.onNodeWithText("A note can have up to 20 tags").assertExists()
        assertTrue(rule.onAllNodes(hasSetTextAction() and hasText("Add tag")).fetchSemanticsNodes().isEmpty())
    }

    // ---- list ----

    @Test fun filterRowShowsCountsAndFiltersTheList() {
        rule.setContent { NotesListScreen(NotesListUiState(notes()), RecordingListActions()) }
        // All + one chip per tag, most used first.
        rule.onNodeWithText("All").assertIsSelected()
        rule.onNode(hasText("Work") and hasText("2")).assertIsNotSelected()
        // (Robolectric's default window is short; the lazy list may not compose the 4th row.)
        assertTrue(shown("Standup") && shown("Groceries"))
        // Unfiltered: reorder handles exist.
        assertTrue(rule.onAllNodesWithContentDescription("Reorder").fetchSemanticsNodes().isNotEmpty())

        rule.onNode(hasText("Work") and hasText("2")).performClick()
        rule.waitForIdle()
        rule.onNode(hasText("Work") and hasText("2")).assertIsSelected()
        assertTrue(shown("Standup") && shown("Roadmap"))
        assertTrue(!shown("Groceries") && !shown("Loose"))
        // Filtered: no reorder handles, and the hint says why.
        assertTrue(rule.onAllNodesWithContentDescription("Reorder").fetchSemanticsNodes().isEmpty())
        rule.onNodeWithText("Showing one tag. Select All to reorder notes.").assertIsDisplayed()

        rule.onNodeWithText("All").performClick()
        rule.waitForIdle()
        assertTrue(shown("Groceries"))
        assertTrue(!shown("Showing one tag. Select All to reorder notes."))
    }

    @Test fun tappingTheSelectedTagAgainClearsTheFilter() {
        rule.setContent { NotesListScreen(NotesListUiState(notes()), RecordingListActions(), initialTagFilter = "home") }
        assertTrue(shown("Groceries") && !shown("Standup"))
        rule.onNode(hasText("Home") and hasText("1")).performClick()
        rule.waitForIdle()
        assertTrue(shown("Standup") && shown("Groceries"))
    }

    @Test fun searchMatchesTagNames() {
        rule.setContent { NotesListScreen(NotesListUiState(notes()), RecordingListActions(), initialSearchQuery = "idea") }
        assertTrue(shown("Roadmap"))
        assertTrue(!shown("Standup") && !shown("Groceries"))
    }

    @Test fun longPressRenameAndDelete() {
        val actions = RecordingListActions()
        rule.setContent { NotesListScreen(NotesListUiState(notes()), actions) }

        rule.onNode(hasText("Home") and hasText("1")).performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText("Rename").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Tag name").performTextReplacement("work")
        rule.waitForIdle()
        // Renaming onto an existing tag says it will merge.
        rule.onNodeWithText("Merges into the existing tag \"Work\"").assertExists()
        rule.onNode(hasText("Rename") and androidx.compose.ui.test.hasClickAction()).performClick()
        rule.waitForIdle()
        assertEquals(listOf("Home" to "work"), actions.renamed)

        rule.onNode(hasText("Ideas") and hasText("1")).performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText("Delete").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Delete tag \"Ideas\"?").assertExists()
        rule.onNodeWithText("Removes the tag from 1 note. The note itself is kept.").assertExists()
        rule.onNode(hasText("Delete") and androidx.compose.ui.test.hasClickAction()).performClick()
        rule.waitForIdle()
        assertEquals(listOf("Ideas"), actions.deleted)
    }

    @Test fun renameDialogWarnsAboutMergeAndConfirms() {
        var renamed: String? = null
        var dismissed = 0
        rule.setContent {
            RenameTagDialogHost(onRename = { renamed = it }, onDismiss = { dismissed++ })
        }
        // Unchanged name: Rename is disabled.
        rule.onNode(hasText("Rename") and androidx.compose.ui.test.hasClickAction()).performClick()
        assertEquals(null, renamed)
        rule.onNodeWithText("Tag name").performTextReplacement("  work ")
        rule.onNodeWithText("Merges into the existing tag \"Work\"").assertExists()
        rule.onNode(hasText("Rename") and androidx.compose.ui.test.hasClickAction()).performClick()
        assertEquals("work", renamed)
        assertEquals(1, dismissed)
    }

    @androidx.compose.runtime.Composable
    private fun RenameTagDialogHost(onRename: (String) -> Unit, onDismiss: () -> Unit) {
        com.vayunmathur.library.ui.DynamicTheme {
            com.vayunmathur.notes.ui.dialogs.RenameTagDialog(
                tag = "Home",
                existingTags = listOf("Work", "Home", "Ideas"),
                onRename = onRename,
                onDismiss = onDismiss,
            )
        }
    }

    @Test fun noFilterRowWithoutTagsAndCardsShowTags() {
        rule.setContent {
            NotesListScreen(NotesListUiState(listOf(Note(id = 1, title = "Only", content = "x", syncId = "o"))), RecordingListActions())
        }
        assertTrue(!shown("All"))
    }

    @Test fun cardsShowTheirTagsCompactly() {
        rule.setContent { NotesListScreen(NotesListUiState(notes()), RecordingListActions()) }
        assertTrue(shown("Work · Ideas"))
    }
}
