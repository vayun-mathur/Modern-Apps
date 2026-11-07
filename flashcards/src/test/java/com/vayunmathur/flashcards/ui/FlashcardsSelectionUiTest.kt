package com.vayunmathur.flashcards.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.vayunmathur.flashcards.data.FIELD_SEPARATOR
import com.vayunmathur.flashcards.data.Note
import com.vayunmathur.flashcards.util.DeckOption
import com.vayunmathur.flashcards.util.NoteEditActions
import com.vayunmathur.flashcards.util.NoteEditUiState
import com.vayunmathur.flashcards.util.NoteListActions
import com.vayunmathur.flashcards.util.NoteListUiState
import com.vayunmathur.flashcards.util.NoteRow
import com.vayunmathur.flashcards.util.NoteTypeConfig
import com.vayunmathur.flashcards.util.StudyParams
import com.vayunmathur.library.ui.DynamicTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FlashcardsSelectionUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun row(id: Long, front: String) = NoteRow(
        Note(id = id, noteTypeId = 1, deckId = 1, guid = "g$id", flds = "$front${FIELD_SEPARATOR}back$id", sortField = front, tags = "t$id"),
        cardCount = 1,
    )

    private fun state() = NoteListUiState(
        deckName = "SelDeck",
        notes = listOf(row(1, "AlphaNote"), row(2, "BetaNote")),
        dueCount = 1,
        tags = listOf("t1"),
        decks = listOf(DeckOption(9, "TargetDeck")),
    )

    @Test
    fun selectionModeBulkToolbarActions() {
        var suspendedCall: Pair<List<Long>, Boolean>? = null
        var deleted: List<Long>? = null
        val actions = object : NoteListActions {
            override fun setSuspended(ids: List<Long>, suspended: Boolean) { suspendedCall = ids to suspended }
            override fun deleteNotes(ids: List<Long>) { deleted = ids }
        }
        rule.setContent { DynamicTheme { NoteListScreen(state(), actions) } }
        rule.onNodeWithText("AlphaNote").performTouchInput { longClick() }
        rule.onNodeWithText("1 selected").assertIsDisplayed()
        // Suspend via the visibility-off toolbar button (first icon button in selection bar).
        rule.onAllNodesWithContentDescription("Hide")[0].performClick()
        assertEquals(listOf(1L) to true, suspendedCall)
        // Re-select and delete.
        rule.onNodeWithText("BetaNote").performTouchInput { longClick() }
        rule.onNodeWithText("1 selected").assertIsDisplayed()
        rule.onNodeWithContentDescription("Delete").performClick()
        assertEquals(listOf(2L), deleted)
    }

    @Test
    fun selectionOverflowMoveTagAndResetDialogs() {
        var moved: Pair<List<Long>, Long>? = null
        var tagged: Pair<List<Long>, String>? = null
        var reset: List<Long>? = null
        val actions = object : NoteListActions {
            override fun moveNotes(ids: List<Long>, deckId: Long) { moved = ids to deckId }
            override fun addTag(ids: List<Long>, tag: String) { tagged = ids to tag }
            override fun resetScheduling(ids: List<Long>) { reset = ids }
        }
        rule.setContent { DynamicTheme { NoteListScreen(state(), actions) } }
        rule.onNodeWithText("AlphaNote").performTouchInput { longClick() }
        // Move dialog via folder icon.
        rule.onAllNodesWithContentDescription("Open folder")[0].performClick()
        rule.onNodeWithText("TargetDeck").performClick()
        assertEquals(listOf(1L) to 9L, moved)

        rule.onNodeWithText("AlphaNote").performTouchInput { longClick() }
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Add tag").performClick()
        rule.onNodeWithText("Tag").performTextInput("newtag")
        rule.onNodeWithText("Save").performClick()
        assertEquals(listOf(1L) to "newtag", tagged)

        rule.onNodeWithText("AlphaNote").performTouchInput { longClick() }
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Reset scheduling").performClick()
        rule.onNodeWithText("Reset these cards back to new? Their review history will be cleared.").assertIsDisplayed()
        val nodes = rule.onAllNodesWithText("Reset scheduling").fetchSemanticsNodes()
        assertTrue(nodes.isNotEmpty())
        rule.onAllNodesWithText("Reset scheduling")[nodes.size - 1].performClick()
        assertEquals(listOf(1L), reset)
    }

    @Test
    fun overflowCustomStudyAndExportCsv() {
        var exported = false; var custom: StudyParams? = null; var shared = false
        val actions = object : NoteListActions {
            override fun exportCsv() { exported = true }
            override fun customStudy(params: StudyParams, tags: Set<String>) { custom = params }
            override fun share() { shared = true }
        }
        rule.setContent { DynamicTheme { NoteListScreen(state(), actions) } }
        rule.onNodeWithContentDescription("Share").performClick()
        assertTrue(shared)
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Export CSV").performClick()
        assertTrue(exported)
        rule.onNodeWithContentDescription("More").performClick()
        rule.onNodeWithText("Custom study").performClick()
        rule.onNodeWithText("Start").performClick()
        assertEquals(com.vayunmathur.flashcards.util.StudyMode.AHEAD, custom?.mode)
    }

    @Test
    fun suspendedAndMultiCardRowBadges() {
        val suspendedRow = NoteRow(
            Note(id = 5, noteTypeId = 1, deckId = 1, guid = "g5", flds = "SusFront${FIELD_SEPARATOR}SusBack", sortField = "SusFront"),
            cardCount = 3, suspended = true,
        )
        rule.setContent {
            DynamicTheme {
                NoteListScreen(
                    NoteListUiState(deckName = "D", notes = listOf(suspendedRow), dueCount = 0),
                    NoteListActions.Noop,
                )
            }
        }
        rule.onNodeWithText("SusFront").assertIsDisplayed()
        rule.onNodeWithText("Suspended").assertIsDisplayed()
        rule.onNodeWithText("3 cards").assertIsDisplayed()
    }

    @Test
    fun noteEditTypeSwitchAndClozeAndImage() {
        var picked = false; var savedFields: List<String>? = null; var savedType = -1L
        val actions = object : NoteEditActions {
            override fun save(noteTypeId: Long, deckId: Long, fieldValues: List<String>, tags: String) {
                savedType = noteTypeId; savedFields = fieldValues
            }
        }
        val state = NoteEditUiState(
            initialNoteTypeId = 1, initialDeckId = 1,
            initialFieldValues = listOf("frontval", "backval"), isNew = true,
            noteTypes = listOf(
                NoteTypeConfig(1, "Basic", listOf("Front", "Back")),
                NoteTypeConfig(3, "Cloze", listOf("Text", "Back Extra")),
            ),
            decks = listOf(DeckOption(1, "Spanish")),
        )
        rule.setContent {
            DynamicTheme { NoteEditScreen(state, actions, onPickImage = { picked = true; it("img_1.png") }) }
        }
        // Switch to Cloze type via dropdown.
        rule.onNodeWithText("Basic").performClick()
        rule.onNodeWithText("Cloze").performClick()
        rule.onAllNodesWithText("Text")[0].assertExists()
        rule.onNodeWithContentDescription("Save").performClick()
        assertEquals(3L, savedType)
        assertEquals(2, savedFields?.size)
    }

    @Test
    fun noteEditTagsEditingIsSaved() {
        var savedTags: String? = null
        val actions = object : NoteEditActions {
            override fun save(noteTypeId: Long, deckId: Long, fieldValues: List<String>, tags: String) { savedTags = tags }
        }
        val state = NoteEditUiState(
            initialNoteTypeId = 1, initialDeckId = 1, initialTags = "old",
            noteTypes = listOf(NoteTypeConfig(1, "Basic", listOf("Front", "Back"))),
            decks = listOf(DeckOption(1, "Spanish")),
        )
        rule.setContent { DynamicTheme { NoteEditScreen(state, actions) } }
        rule.onNodeWithText("Tags").performTextInput(" newtag")
        rule.onNodeWithContentDescription("Save").performClick()
        assertTrue(savedTags?.contains("newtag") == true, "tags=$savedTags")
    }
}
