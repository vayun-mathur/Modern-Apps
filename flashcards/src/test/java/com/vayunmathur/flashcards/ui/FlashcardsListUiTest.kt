package com.vayunmathur.flashcards.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTouchInput
import com.vayunmathur.flashcards.data.Deck
import com.vayunmathur.flashcards.data.FIELD_SEPARATOR
import com.vayunmathur.flashcards.data.Note
import com.vayunmathur.flashcards.util.DeckListActions
import com.vayunmathur.flashcards.util.DeckListUiState
import com.vayunmathur.flashcards.util.DeckSummary
import com.vayunmathur.flashcards.util.NoteListActions
import com.vayunmathur.flashcards.util.NoteListUiState
import com.vayunmathur.flashcards.util.NoteRow
import com.vayunmathur.flashcards.util.StudyParams
import com.vayunmathur.library.ui.DynamicTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FlashcardsListUiTest {

    @get:Rule
    val rule = createComposeRule()

    // ---- Deck list ----

    @Test
    fun deckListEmptyState() {
        rule.setContent { DynamicTheme { DeckListScreen(DeckListUiState(), DeckListActions.Noop) } }
        rule.onNodeWithText("No decks yet").assertIsDisplayed()
        rule.onNodeWithText("Tap + to create your first deck").assertIsDisplayed()
    }

    @Test
    fun deckListShowsDecksAndOpensAndReviews() {
        var opened = -1L; var reviewed = -1L
        val actions = object : DeckListActions {
            override fun openDeck(id: Long) { opened = id }
            override fun startReview(deckId: Long) { reviewed = deckId }
        }
        val state = DeckListUiState(
            listOf(
                DeckSummary(Deck(id = 3, name = "Spanish"), dueCount = 2, newCount = 1, totalCount = 9, mastery = 0.5f),
                DeckSummary(Deck(id = 4, name = "French"), dueCount = 0, newCount = 0, totalCount = 3, mastery = 1f),
            ),
        )
        rule.setContent { DynamicTheme { DeckListScreen(state, actions) } }
        rule.onNodeWithText("Spanish").assertIsDisplayed()
        rule.onNodeWithText("2 due · 1 new · 9 cards").assertIsDisplayed()
        rule.onNodeWithText("50%").assertIsDisplayed()
        rule.onNodeWithText("French").performClick()
        assertEquals(4L, opened)
        // Only the deck with due/new cards gets a Play button.
        rule.onNodeWithContentDescription("Play").performClick()
        assertEquals(3L, reviewed)
    }

    @Test
    fun deckListAddDeckDialog() {
        var added: String? = null
        val actions = object : DeckListActions {
            override fun addDeck(name: String) { added = name }
        }
        rule.setContent { DynamicTheme { DeckListScreen(DeckListUiState(), actions) } }
        rule.onNodeWithContentDescription("Add").performClick()
        rule.onNodeWithText("New deck").assertIsDisplayed()
        rule.onNodeWithText("Deck name").performTextInput("  Geography ")
        rule.onNodeWithText("Add").performClick()
        assertEquals("Geography", added)
    }

    @Test
    fun deckListLongPressDeleteDialogConfirmsAndCancels() {
        var deleted: String? = null
        val actions = object : DeckListActions {
            override fun deleteDeck(deck: Deck) { deleted = deck.name }
        }
        val state = DeckListUiState(
            listOf(DeckSummary(Deck(id = 3, name = "Spanish"), dueCount = 0, newCount = 0, totalCount = 9, mastery = 0.5f)),
        )
        rule.setContent { DynamicTheme { DeckListScreen(state, actions) } }
        rule.onNodeWithText("Spanish").performTouchInput { longClick() }
        rule.onNodeWithText("Delete \"Spanish\" and all its cards?").assertIsDisplayed()
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(null, deleted)
        rule.onNodeWithText("Spanish").performTouchInput { longClick() }
        // Confirm is the destructive Delete button in the dialog (last Delete text node).
        val deleteNodes = rule.onAllNodesWithText("Delete").fetchSemanticsNodes()
        assertTrue(deleteNodes.isNotEmpty())
        rule.onAllNodesWithText("Delete")[deleteNodes.size - 1].performClick()
        assertEquals("Spanish", deleted)
    }

    // ---- Note list ----

    private fun noteRow(id: Long, front: String, back: String, tags: String = "", cards: Int = 1, suspended: Boolean = false) =
        NoteRow(
            Note(id = id, noteTypeId = 1, deckId = 1, guid = "g$id", flds = "$front$FIELD_SEPARATOR$back", sortField = front, tags = tags),
            cardCount = cards, suspended = suspended,
        )

    @Test
    fun noteListEmptyState() {
        rule.setContent {
            DynamicTheme { NoteListScreen(NoteListUiState(deckName = "Spanish"), NoteListActions.Noop) }
        }
        rule.onNodeWithText("Spanish").assertIsDisplayed()
        rule.onNodeWithText("No cards yet").assertIsDisplayed()
    }

    @Test
    fun noteListShowsNotesStudyAndOpens() {
        var opened = -1L; var studied: Set<String>? = null; var added = false
        val actions = object : NoteListActions {
            override fun openNote(id: Long) { opened = id }
            override fun study(tags: Set<String>) { studied = tags }
            override fun addNote() { added = true }
        }
        val state = NoteListUiState(
            deckName = "Spanish",
            notes = listOf(noteRow(1, "hola", "hello", cards = 2), noteRow(2, "gato", "cat", tags = "animals")),
            dueCount = 3,
            tags = listOf("animals"),
        )
        rule.setContent { DynamicTheme { NoteListScreen(state, actions) } }
        rule.onNodeWithText("hola").assertIsDisplayed()
        rule.onNodeWithText("hello").assertIsDisplayed()
        rule.onNodeWithText("2 cards").assertIsDisplayed()
        rule.onNodeWithText("Study").performClick()
        assertEquals(emptySet(), studied)
        // Tag chip filters to the tagged note only.
        rule.onNodeWithText("animals").performClick()
        rule.onNodeWithText("gato").assertIsDisplayed()
        rule.onNodeWithText("gato").performClick()
        assertEquals(2L, opened)
        rule.onNodeWithContentDescription("Add").performClick()
        assertTrue(added)
    }

    @Test
    fun noteListSearchFiltersNotes() {
        var custom: StudyParams? = null
        val actions = object : NoteListActions {
            override fun customStudy(params: StudyParams, tags: Set<String>) { custom = params }
        }
        val state = NoteListUiState(
            deckName = "Spanish",
            notes = listOf(noteRow(1, "hola", "hello"), noteRow(2, "gato", "cat")),
            dueCount = 0,
        )
        rule.setContent { DynamicTheme { NoteListScreen(state, actions) } }
        rule.onNodeWithText("Search cards").performTextInput("gat")
        rule.onNodeWithText("gato").assertIsDisplayed()
        rule.onNodeWithText("hola").assertDoesNotExist()
    }

    @Test
    fun markdownContentRendersTextAndImageAlt() {
        rule.setContent { DynamicTheme { MarkdownContent(text = "Hello **world**") } }
        rule.onNodeWithText("world", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Hello", substring = true).assertIsDisplayed()
    }

    @Test
    fun tagFilterRowToggles() {
        var toggled: String? = null
        rule.setContent {
            DynamicTheme { TagFilterRow(listOf("a", "b"), setOf("a")) { toggled = it } }
        }
        rule.onNodeWithText("b").performClick()
        assertEquals("b", toggled)
    }

    @Test
    fun moveDeckDialogPicksDeck() {
        var picked = -1L
        rule.setContent {
            DynamicTheme {
                MoveDeckDialog(
                    decks = listOf(com.vayunmathur.flashcards.util.DeckOption(5, "Target"), com.vayunmathur.flashcards.util.DeckOption(null, "All")),
                    onPick = { picked = it },
                    onDismiss = {},
                )
            }
        }
        rule.onNodeWithText("Move to deck").assertIsDisplayed()
        rule.onNodeWithText("Target").performClick()
        assertEquals(5L, picked)
    }

    @Test
    fun tagDialogConfirmsTrimmedTag() {
        var confirmed: String? = null
        rule.setContent { DynamicTheme { TagDialog("Add tag", { confirmed = it }, {}) } }
        rule.onNodeWithText("Tag").performTextInput("  verbs ")
        rule.onNodeWithText("Save").performClick()
        assertEquals("verbs", confirmed)
    }

    @Test
    fun customStudyDialogStartsWithDefaults() {
        var started: StudyParams? = null
        rule.setContent { DynamicTheme { CustomStudyDialog({ started = it }, {}) } }
        rule.onNodeWithText("Custom study").assertIsDisplayed()
        rule.onNodeWithText("Study ahead").assertIsDisplayed()
        rule.onNodeWithText("Start").performClick()
        assertEquals(com.vayunmathur.flashcards.util.StudyMode.AHEAD, started?.mode)
        assertEquals(20, started?.count)
        assertEquals(3, started?.daysAhead)
    }

    @Test
    fun customStudyDialogCramModeHidesDays() {
        var started: StudyParams? = null
        rule.setContent { DynamicTheme { CustomStudyDialog({ started = it }, {}) } }
        rule.onNodeWithText("Cram (ignore schedule)").performClick()
        rule.onNodeWithText("Days ahead").assertDoesNotExist()
        rule.onNodeWithText("Start").performClick()
        assertEquals(com.vayunmathur.flashcards.util.StudyMode.CRAM, started?.mode)
        assertTrue(started!!.previewOnly)
    }
}
