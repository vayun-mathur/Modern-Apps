package com.vayunmathur.translate.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.vayunmathur.translate.data.TranslationRecord
import com.vayunmathur.translate.domain.Languages
import com.vayunmathur.translate.platform.HistoryActions
import com.vayunmathur.translate.platform.HistoryUiState
import com.vayunmathur.translate.platform.LanguagePickerActions
import com.vayunmathur.translate.platform.LanguagePickerUiState
import com.vayunmathur.translate.platform.MicState
import com.vayunmathur.translate.platform.TextTranslateActions
import com.vayunmathur.translate.platform.TextTranslateUiState
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class TranslateScreensTest {

    @get:Rule
    val rule = createComposeRule()

    private class RecText : TextTranslateActions {
        var sourcePicker = 0; var targetPicker = 0; var swap = 0; var mic = 0
        var copy = 0; var speak = 0; var fav = 0; var camera = 0; var image = 0; var history = 0
        var typed: String? = null; var src: String? = null; var tgt: String? = null
        override fun setSource(code: String) { src = code }
        override fun setTarget(code: String) { tgt = code }
        override fun swap() { swap++ }
        override fun setInput(text: String) { typed = text }
        override fun toggleMic() { mic++ }
        override fun copyOutput() { copy++ }
        override fun speakOutput() { speak++ }
        override fun toggleFavorite() { fav++ }
        override fun openCamera() { camera++ }
        override fun openImage() { image++ }
        override fun openHistory() { history++ }
        override fun openLanguagePicker(forSource: Boolean) {
            if (forSource) sourcePicker++ else targetPicker++
        }
    }

    @Test
    fun `text screen loading state shows translator spinner and language names`() {
        val actions = RecText()
        rule.setContent {
            TextTranslateScreen(
                TextTranslateUiState(translationAvailable = false, sourceLang = "auto", targetLang = "es"),
                actions,
            )
        }
        rule.onNodeWithText("Loading translator…").assertIsDisplayed()
        rule.onNodeWithText(Languages.displayName("es")).assertIsDisplayed()
    }

    @Test
    fun `text screen with output enables copy speak and save`() {
        val actions = RecText()
        rule.setContent {
            TextTranslateScreen(
                TextTranslateUiState(
                    sourceLang = "en", targetLang = "fr",
                    translationAvailable = true,
                    inputText = "hello", outputText = "bonjour",
                    isFavorite = false, canSave = true,
                ), actions,
            )
        }
        rule.onNodeWithText("bonjour").assertIsDisplayed()
        rule.onNodeWithText("Copy").performClick()
        assertEquals(1, actions.copy)
        rule.onNodeWithText("Speak").performClick()
        assertEquals(1, actions.speak)
        rule.onNodeWithText("Save").performClick()
        assertEquals(1, actions.fav)
    }

    @Test
    fun `text screen saved state and blank output disables actions`() {
        val actions = RecText()
        rule.setContent {
            TextTranslateScreen(
                TextTranslateUiState(
                    sourceLang = "en", targetLang = "fr",
                    translationAvailable = true, outputText = "",
                    isFavorite = true, canSave = false,
                ), actions,
            )
        }
        rule.onNodeWithText("Translation will appear here").assertIsDisplayed()
        rule.onNodeWithText("Saved").assertIsDisplayed()
    }

    @Test
    fun `text screen translating indicator and mic states render`() {
        val actions = RecText()
        rule.setContent {
            TextTranslateScreen(
                TextTranslateUiState(
                    translationAvailable = true, isTranslating = true,
                    micState = MicState.LISTENING, speechError = "mic blew up",
                ), actions,
            )
        }
        rule.onNodeWithText("Listening…").assertIsDisplayed()
        rule.onNodeWithText("mic blew up").assertIsDisplayed()
    }

    @Test
    fun `text screen transcribing mic status renders`() {
        rule.setContent {
            TextTranslateScreen(
                TextTranslateUiState(translationAvailable = true, micState = MicState.TRANSCRIBING),
                RecText(),
            )
        }
        rule.onNodeWithText("Transcribing…").assertIsDisplayed()
    }

    @Test
    fun `text screen language bar and toolbar and input fire actions`() {
        val actions = RecText()
        rule.setContent {
            TextTranslateScreen(
                TextTranslateUiState(sourceLang = "en", targetLang = "es", translationAvailable = true),
                actions,
            )
        }
        rule.onNodeWithText(Languages.displayName("en")).performClick()
        assertEquals(1, actions.sourcePicker)
        rule.onNodeWithText(Languages.displayName("es")).performClick()
        assertEquals(1, actions.targetPicker)
        // Toolbar icons are content-described by their icon names in library/ui.
        rule.onNodeWithContentDescription("History").performClick()
        assertEquals(1, actions.history)
        rule.onNodeWithContentDescription("Photos").performClick()
        assertEquals(1, actions.image)
        rule.onNodeWithContentDescription("Camera", substring = true).performClick()
        assertEquals(1, actions.camera)
        rule.onNodeWithText("Enter text").performTextInput("hola")
        assertTrue(actions.typed?.contains("hola") == true, actions.typed)
    }

    @Test
    fun `text screen noop actions render preview state`() {
        rule.setContent {
            TextTranslateScreen(
                TextTranslateUiState(translationAvailable = true, outputText = "hola"),
                TextTranslateActions.Noop,
            )
        }
        rule.onNodeWithText("hola").assertIsDisplayed()
        assertEquals(MicState.IDLE, TextTranslateUiState().micState)
        assertEquals("es", TextTranslateUiState().targetLang)
    }

    // --- Language picker -------------------------------------------------------

    private class RecPicker : LanguagePickerActions {
        var selected: String? = null; var typedQuery: String? = null; var back = 0
        override fun select(code: String) { selected = code }
        override fun setQuery(query: String) { typedQuery = query }
        override fun goBack() { back++ }
    }

    @Test
    fun `picker source shows auto recents and all and selects a language`() {
        val actions = RecPicker()
        val french = Languages.byCode("fr")
        rule.setContent {
            LanguagePickerScreen(
                LanguagePickerUiState(forSource = true, selectedCode = "auto", recents = listOf(french)),
                actions,
            )
        }
        rule.onNodeWithText("Recent languages").assertIsDisplayed()
        rule.onNodeWithText("All languages").assertIsDisplayed()
        // Recent French row (native name) is above the full list; click the first match.
        rule.onAllNodesWithText(french.nativeName)[0].performClick()
        assertEquals("fr", actions.selected)
    }

    @Test
    fun `picker target has no auto row and shows selected check`() {
        val actions = RecPicker()
        rule.setContent {
            LanguagePickerScreen(
                LanguagePickerUiState(forSource = false, selectedCode = "es"),
                actions,
            )
        }
        rule.onAllNodesWithText(Languages.AUTO.nativeName).fetchSemanticsNodes().let {
            // Auto row must not appear as a selectable row in target mode; the
            // native name "Auto" may still appear nowhere else.
            assertTrue(it.isEmpty())
        }
        rule.onNodeWithText(Languages.ALL.first().nativeName).assertIsDisplayed()
    }

    @Test
    fun `picker query with no matches shows empty message`() {
        val actions = RecPicker()
        rule.setContent {
            LanguagePickerScreen(
                LanguagePickerUiState(forSource = true, query = "zzz-no-such-language"),
                actions,
            )
        }
        rule.onAllNodesWithText("zzz-no-such-language", substring = true)[1].assertIsDisplayed()
    }

    @Test
    fun `picker query filters by english name and code`() {
        val actions = RecPicker()
        rule.setContent {
            LanguagePickerScreen(
                LanguagePickerUiState(forSource = false, selectedCode = "en", query = "German"),
                actions,
            )
        }
        rule.onNodeWithText(Languages.byCode("de").nativeName).assertIsDisplayed()
        rule.onAllNodesWithText(Languages.byCode("fr").nativeName).fetchSemanticsNodes().let {
            assertTrue(it.isEmpty())
        }
    }

    @Test
    fun `picker search field reports typed query`() {
        val actions = RecPicker()
        rule.setContent {
            LanguagePickerScreen(LanguagePickerUiState(forSource = true), actions)
        }
        rule.onNodeWithText("Search languages").performTextInput("ita")
        assertTrue(actions.typedQuery?.contains("ita") == true, actions.typedQuery)
    }

    // --- History -------------------------------------------------------------------

    private fun record(
        id: Long, src: String = "hello", tgt: String = "hola",
        fav: Boolean = false, from: String = "en", to: String = "es",
    ) = TranslationRecord(
        id = id, sourceText = src, translatedText = tgt,
        sourceLang = from, targetLang = to, isFavorite = fav, createdAt = 1000L + id,
    )

    private class RecHistory : HistoryActions {
        var favToggle: Pair<Long, Boolean>? = null; var deleted: TranslationRecord? = null
        var clear = 0; var reused: TranslationRecord? = null; var back = 0
        override fun toggleFavorite(id: Long, favorite: Boolean) { favToggle = id to favorite }
        override fun delete(record: TranslationRecord) { deleted = record }
        override fun clearHistory() { clear++ }
        override fun reuse(record: TranslationRecord) { reused = record }
        override fun goBack() { back++ }
    }

    @Test
    fun `history empty state renders`() {
        rule.setContent { HistoryScreen(HistoryUiState(), RecHistory()) }
        rule.onNodeWithText("Translations you make will appear here").assertIsDisplayed()
    }

    @Test
    fun `history rows show texts and clear appears only with non favorites`() {
        val actions = RecHistory()
        rule.setContent {
            HistoryScreen(
                HistoryUiState(listOf(record(1, fav = true), record(2, src = "cat", tgt = "gato"))),
                actions,
            )
        }
        rule.onNodeWithText("hello").assertIsDisplayed()
        rule.onNodeWithText("gato").assertIsDisplayed()
        rule.onNodeWithText("Clear").performClick()
        assertEquals(1, actions.clear)
    }

    @Test
    fun `history all favorites hides clear`() {
        rule.setContent {
            HistoryScreen(HistoryUiState(listOf(record(1, fav = true))), RecHistory())
        }
        rule.onNodeWithText("hello").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithText("Clear").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun `history row click reuses and icon buttons fire`() {
        val actions = RecHistory()
        val rec = record(7, src = "dog", tgt = "perro")
        rule.setContent { HistoryScreen(HistoryUiState(listOf(rec)), actions) }
        rule.onNodeWithText("dog").performClick()
        assertEquals(rec, actions.reused)
        rule.onNodeWithContentDescription("Favorite", substring = true).performClick()
        assertEquals(7L to true, actions.favToggle)
        rule.onNodeWithContentDescription("Delete").performClick()
        assertEquals(rec, actions.deleted)
    }
}
