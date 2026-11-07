package com.vayunmathur.flashcards.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.vayunmathur.flashcards.util.DeckOption
import com.vayunmathur.flashcards.util.DeckPreset
import com.vayunmathur.flashcards.util.NoteEditActions
import com.vayunmathur.flashcards.util.NoteEditUiState
import com.vayunmathur.flashcards.util.NoteTypeConfig
import com.vayunmathur.flashcards.util.NoteTypeEditActions
import com.vayunmathur.flashcards.util.NoteTypeEditUiState
import com.vayunmathur.flashcards.util.NoteTypeListActions
import com.vayunmathur.flashcards.util.NoteTypeListUiState
import com.vayunmathur.flashcards.util.NoteTypeSummary
import com.vayunmathur.flashcards.util.SettingsActions
import com.vayunmathur.flashcards.util.SettingsUiState
import com.vayunmathur.flashcards.util.TemplateDraft
import com.vayunmathur.flashcards.util.ThemeMode
import com.vayunmathur.library.ui.DynamicTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FlashcardsEditSettingsUiTest {

    @get:Rule
    val rule = createComposeRule()

    // ---- Settings ----

    @Test
    fun settingsShowsSectionsAndThemeSwitch() {
        var theme = -1; var managed = false; var exported = false; var optimized = false
        val actions = object : SettingsActions {
            override fun setThemeMode(mode: Int) { theme = mode }
            override fun manageNoteTypes() { managed = true }
            override fun exportCollection() { exported = true }
            override fun optimizeFsrs() { optimized = true }
        }
        val state = SettingsUiState(
            desiredRetention = 0.9, newPerDay = 20, maxReviews = 200,
            reminderHour = 20, reminderMinute = 30, themeMode = ThemeMode.SYSTEM,
            presets = listOf(DeckPreset("Focus", 10, 100, 0.95)),
        )
        rule.setContent { DynamicTheme { SettingsScreen(state, actions) } }
        rule.onNodeWithText("Settings").assertIsDisplayed()
        rule.onNodeWithText("Desired retention").assertIsDisplayed()
        rule.onNodeWithText("90%").assertIsDisplayed()
        rule.onNodeWithText("New cards per day").assertIsDisplayed()
        rule.onNodeWithText("Max reviews per day").assertIsDisplayed()
        rule.onNodeWithText("Daily reminder").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("20:30").assertExists()
        rule.onNodeWithText("Dark").performScrollTo().performClick()
        assertEquals(ThemeMode.DARK, theme)
        rule.onNodeWithText("Manage note types").performScrollTo().performClick()
        assertTrue(managed)
        rule.onNodeWithText("Export collection").performScrollTo().performClick()
        assertTrue(exported)
        rule.onNodeWithText("Optimize FSRS parameters").performScrollTo().performClick()
        assertTrue(optimized)
        // Preset row visible (below fold may exist)
        rule.onNodeWithText("Focus").assertExists()
        rule.onNodeWithText("Apply preset").assertExists()
    }

    @Test
    fun settingsPresetDialogSaves() {
        var saved: String? = null; var applied: String? = null; var deleted: String? = null
        val actions = object : SettingsActions {
            override fun saveDeckPreset(name: String) { saved = name }
            override fun applyDeckPreset(name: String) { applied = name }
            override fun deleteDeckPreset(name: String) { deleted = name }
        }
        val state = SettingsUiState(presets = listOf(DeckPreset("Focus", 10, 100, 0.95)))
        rule.setContent { DynamicTheme { SettingsScreen(state, actions) } }
        rule.onNodeWithText("Focus").assertExists()
        rule.onNodeWithText("Apply preset").performScrollTo().performClick()
        assertEquals("Focus", applied)
        rule.onNodeWithText("Save preset").performScrollTo().performClick()
        rule.onNodeWithText("Preset name").performTextInput("  Morning ")
        rule.onNodeWithText("Save").performClick()
        assertEquals("Morning", saved)
    }

    @Test
    fun settingsSwitchesToggle() {
        var autoPlay: Boolean? = null; var reminder: Boolean? = null
        val actions = object : SettingsActions {
            override fun setAutoPlay(enabled: Boolean) { autoPlay = enabled }
            override fun setReminderEnabled(enabled: Boolean) { reminder = enabled }
        }
        rule.setContent { DynamicTheme { SettingsScreen(SettingsUiState(autoPlay = false, reminderEnabled = false), actions) } }
        rule.onNodeWithText("Auto-play audio").performScrollTo().performClick()
        assertEquals(true, autoPlay)
        rule.onNodeWithText("Daily reminder").performScrollTo().performClick()
        assertEquals(true, reminder)
    }

    // ---- Note edit ----

    private fun editState(isNew: Boolean = true) = NoteEditUiState(
        initialNoteTypeId = 1, initialDeckId = 1,
        initialFieldValues = listOf("hola", "hello"), initialTags = "greetings",
        isNew = isNew,
        noteTypes = listOf(NoteTypeConfig(1, "Basic", listOf("Front", "Back")), NoteTypeConfig(3, "Cloze", listOf("Text", "Back Extra"))),
        decks = listOf(DeckOption(1, "Spanish"), DeckOption(2, "French")),
    )

    @Test
    fun noteEditNewShowsFieldsAndSaves() {
        var savedType = -1L; var savedDeck = -1L; var savedFields: List<String>? = null; var savedTags: String? = null
        val actions = object : NoteEditActions {
            override fun save(noteTypeId: Long, deckId: Long, fieldValues: List<String>, tags: String) {
                savedType = noteTypeId; savedDeck = deckId; savedFields = fieldValues; savedTags = tags
            }
        }
        rule.setContent { DynamicTheme { NoteEditScreen(editState(), actions) } }
        rule.onNodeWithText("New card").assertIsDisplayed()
        rule.onNodeWithText("Basic").assertIsDisplayed()
        rule.onNodeWithText("Spanish").assertIsDisplayed()
        rule.onNodeWithText("Front").assertIsDisplayed()
        rule.onNodeWithText("Back").assertIsDisplayed()
        rule.onNodeWithContentDescription("Save").performClick()
        assertEquals(1L, savedType)
        assertEquals(1L, savedDeck)
        assertEquals(listOf("hola", "hello"), savedFields)
        assertEquals("greetings", savedTags)
    }

    @Test
    fun noteEditExistingShowsDeleteAndSuspend() {
        var deleted = false; var suspended: Boolean? = null
        val actions = object : NoteEditActions {
            override fun deleteNote() { deleted = true }
            override fun setSuspended(s: Boolean) { suspended = s }
        }
        rule.setContent { DynamicTheme { NoteEditScreen(editState(isNew = false).copy(isSuspended = false), actions) } }
        rule.onNodeWithText("Edit card").assertIsDisplayed()
        rule.onNodeWithContentDescription("Delete").performClick()
        assertTrue(deleted)
    }

    @Test
    fun noteEditDeckDropdownChangesDeck() {
        var savedDeck = -1L
        val actions = object : NoteEditActions {
            override fun save(noteTypeId: Long, deckId: Long, fieldValues: List<String>, tags: String) { savedDeck = deckId }
        }
        rule.setContent { DynamicTheme { NoteEditScreen(editState(), actions) } }
        rule.onNodeWithText("Spanish").performClick()
        rule.onNodeWithText("French").performClick()
        rule.onNodeWithContentDescription("Save").performClick()
        assertEquals(2L, savedDeck)
    }

    // ---- Note type list / edit ----

    @Test
    fun noteTypeListShowsAndOpens() {
        var opened = -1L; var added = false
        val actions = object : NoteTypeListActions {
            override fun openNoteType(id: Long) { opened = id }
            override fun addNoteType() { added = true }
        }
        val state = NoteTypeListUiState(
            listOf(
                NoteTypeSummary(1, "Basic", 2, 1, 5, false),
                NoteTypeSummary(9, "Custom", 3, 2, 0, true),
            ),
        )
        rule.setContent { DynamicTheme { NoteTypeListScreen(state, actions) } }
        rule.onNodeWithText("Manage note types").assertIsDisplayed()
        rule.onNodeWithText("Basic").assertIsDisplayed()
        rule.onNodeWithText("2 fields · 1 templates · 5 notes").assertIsDisplayed()
        rule.onNodeWithText("Custom").performClick()
        assertEquals(9L, opened)
        rule.onNodeWithContentDescription("Add").performClick()
        assertTrue(added)
    }

    @Test
    fun noteTypeEditNewSavesWithFieldsAndTemplates() {
        var name: String? = null; var fields: List<String>? = null; var templates: List<TemplateDraft>? = null
        val actions = object : NoteTypeEditActions {
            override fun save(n: String, css: String, type: Int, f: List<String>, t: List<TemplateDraft>) {
                name = n; fields = f; templates = t
            }
        }
        val state = NoteTypeEditUiState(
            id = 0, name = "", fields = listOf("Front", "Back"),
            templates = listOf(TemplateDraft("Card 1", "{{Front}}", "{{Back}}")), isNew = true,
        )
        rule.setContent { DynamicTheme { NoteTypeEditScreen(state, actions) } }
        rule.onNodeWithText("New note type").assertIsDisplayed()
        rule.onNodeWithText("Fields").assertIsDisplayed()
        rule.onNodeWithText("Templates").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("Add field").performClick()
        rule.onNodeWithContentDescription("Save").performClick()
        assertEquals("Note type", name) // blank name defaults
        assertEquals(3, fields?.size)
        assertEquals(1, templates?.size)
    }

    @Test
    fun noteTypeEditExistingCanDeleteAndAddTemplate() {
        var deleted = false; var savedTemplates: List<TemplateDraft>? = null
        val actions = object : NoteTypeEditActions {
            override fun delete() { deleted = true }
            override fun save(n: String, css: String, type: Int, f: List<String>, t: List<TemplateDraft>) { savedTemplates = t }
        }
        val state = NoteTypeEditUiState(
            id = 9, name = "Custom", fields = listOf("Front", "Back"),
            templates = listOf(TemplateDraft("Card 1", "{{Front}}", "{{Back}}")), isNew = false,
        )
        rule.setContent { DynamicTheme { NoteTypeEditScreen(state, actions) } }
        rule.onNodeWithText("Edit note type").assertIsDisplayed()
        rule.onNodeWithText("Custom").assertIsDisplayed()
        rule.onNodeWithText("Add template").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Save").performClick()
        assertEquals(2, savedTemplates?.size)
        // Delete button below fold
        rule.onAllNodesWithText("Delete")[0].performScrollTo().performClick()
        assertTrue(deleted)
    }

    @Test
    fun noteTypeEditorSectionPreviewRenders() {
        rule.setContent {
            DynamicTheme {
                NoteTypeEditorSection(TemplateDraft("Card 1", "{{Front}}", "{{Back}}"), listOf("Front", "Back"), isCloze = false)
            }
        }
        rule.onNodeWithText("Preview").assertIsDisplayed()
        rule.onNodeWithText("Front").assertIsDisplayed()
    }

    @Test
    fun noteTypeEditorSectionClozePreview() {
        rule.setContent {
            DynamicTheme {
                NoteTypeEditorSection(TemplateDraft("Cloze", "{{cloze:Text}}", "{{cloze:Text}}"), listOf("Text", "Back Extra"), isCloze = true)
            }
        }
        rule.onNodeWithText("Preview").assertIsDisplayed()
        rule.onNodeWithText("answer", substring = true).assertExists()
    }
}
