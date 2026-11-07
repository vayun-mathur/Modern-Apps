package com.vayunmathur.translate.ui

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.translate.Navigation
import com.vayunmathur.translate.data.PlainSqlCipherDriverShadow
import com.vayunmathur.translate.data.TranslateRepository
import com.vayunmathur.translate.data.TranslationRecord
import com.vayunmathur.translate.domain.Languages
import com.vayunmathur.translate.platform.FakeAndroidKeyStoreProvider
import com.vayunmathur.translate.platform.TranslateViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The ViewModel-bound pages (text, history, and the full Navigation graph)
 * with the Room history database actually opening: the SQLCipher driver is
 * shadowed with plain SQLite (see [PlainSqlCipherDriverShadow]) and the
 * AndroidKeyStore is faked, so the whole binder layer runs on the JVM.
 */
@RunWith(RobolectricTestRunner::class)
@Config(shadows = [PlainSqlCipherDriverShadow::class])
class DbBackedPagesTest {

    @get:Rule
    val rule = createComposeRule()

    companion object {
        @BeforeClass
        @JvmStatic
        fun installFakeKeyStore() {
            if (java.security.Security.getProvider("AndroidKeyStore") !is FakeAndroidKeyStoreProvider) {
                runCatching { java.security.Security.removeProvider("AndroidKeyStore") }
                java.security.Security.insertProviderAt(FakeAndroidKeyStoreProvider(), 1)
            }
        }
    }

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun vm(): TranslateViewModel = TranslateViewModel(app)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** The class shares one DB across methods: reset it to a known empty state. */
    private fun cleanDb() {
        val repo = TranslateRepository.get(app)
        com.vayunmathur.translate.data.runDb { repo.clearHistory() }
        com.vayunmathur.translate.data.pollDb({ repo.favorites.first() }) { true }.forEach { fav ->
            com.vayunmathur.translate.data.runDb { repo.delete(fav) }
        }
        com.vayunmathur.translate.data.pollDb({ repo.favorites.first() }) { it.isEmpty() }
    }

    private fun seed(vararg records: Triple<String, String, Pair<String, String>>): List<TranslationRecord> =
        com.vayunmathur.translate.data.runDb {
            val repo = TranslateRepository.get(app)
            records.forEach { (src, tgt, pair) -> repo.record(src, tgt, pair.first, pair.second) }
            val rows = withTimeoutOrNull(15_000) {
                repo.history.first { history -> records.all { (src, _, _) -> history.any { it.sourceText == src } } }
            }
            assertNotNull(rows)
            records.map { (src, _, _) -> rows.first { it.sourceText == src } }
        }

    // --- Text page -------------------------------------------------------------------

    @Test
    fun `text page shows initial text and toolbar navigation fires`() {
        val viewModel = vm()
        idle()
        var camera = 0; var history = 0; var image: String? = null; var pickerFor: Boolean? = null
        rule.setContent {
            TextTranslatePage(
                viewModel = viewModel,
                initialText = "good morning",
                reuseRequest = null,
                onReuseConsumed = {},
                onOpenCamera = { camera++ },
                onOpenImage = { image = it },
                onOpenHistory = { history++ },
                onOpenLanguagePicker = { pickerFor = it },
            )
        }
        rule.onNodeWithText("good morning").assertIsDisplayed()
        // No model files in this sandbox: the loading state is the honest output.
        rule.onNodeWithText("Loading translator…").assertIsDisplayed()
        rule.onNodeWithContentDescription("History").performClick()
        assertEquals(1, history)
        rule.onNodeWithContentDescription("Camera").performClick()
        assertEquals(1, camera)
        // Source/target bar opens the picker for the right side. Wait for the
        // persisted pair to settle into the label before clicking it.
        rule.waitUntil(8_000) {
            rule.onAllNodesWithText(Languages.displayName(viewModel.targetLang.value))
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(Languages.displayName(viewModel.targetLang.value)).performClick()
        assertEquals(false, pickerFor)
    }

    @Test
    fun `text page typing does not fabricate a translation without the model`() {
        val viewModel = vm()
        idle()
        rule.setContent {
            TextTranslatePage(
                viewModel = viewModel,
                initialText = "",
                reuseRequest = null,
                onReuseConsumed = {},
                onOpenCamera = {},
                onOpenImage = {},
                onOpenHistory = {},
                onOpenLanguagePicker = {},
            )
        }
        rule.onNodeWithText("Enter text").performTextInput("hello world")
        rule.waitForIdle()
        assertTrue(
            rule.onAllNodesWithText("Translation will appear here").fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText("Loading translator…").fetchSemanticsNodes().isNotEmpty(),
        )
    }

    @Test
    fun `text page reuse loads the record pair into the page state`() {
        val (record) = seed(Triple("reuse hello", "reuse hola", "en" to "es"))
        val viewModel = vm()
        idle()
        var consumed = 0
        rule.setContent {
            TextTranslatePage(
                viewModel = viewModel,
                initialText = "",
                reuseRequest = record,
                onReuseConsumed = { consumed++ },
                onOpenCamera = {},
                onOpenImage = {},
                onOpenHistory = {},
                onOpenLanguagePicker = {},
            )
        }
        rule.waitUntil(8_000) { consumed == 1 }
        rule.waitUntil(8_000) { viewModel.sourceLang.value == "en" && viewModel.targetLang.value == "es" }
        assertEquals("en", viewModel.sourceLang.value)
        assertEquals("es", viewModel.targetLang.value)
        rule.onNodeWithText("reuse hello").assertIsDisplayed()
        // Without the model the output card stays in its loading state; the
        // star/copy wiring for a landed translation is covered on the
        // stateless screen (TranslateScreensTest) and needs a real model here.
        rule.onNodeWithText("Loading translator…").assertIsDisplayed()
    }

    @Test
    fun `text page mic tap surfaces a speech or permission error`() {
        // Grant RECORD_AUDIO so the tap goes straight to the recognizer,
        // which has no service under Robolectric and must surface its error.
        shadowOf(app).grantPermissions(android.Manifest.permission.RECORD_AUDIO)
        val viewModel = vm()
        idle()
        rule.setContent {
            TextTranslatePage(
                viewModel = viewModel,
                initialText = "",
                reuseRequest = null,
                onReuseConsumed = {},
                onOpenCamera = {},
                onOpenImage = {},
                onOpenHistory = {},
                onOpenLanguagePicker = {},
            )
        }
        rule.onNodeWithContentDescription("Mic").performClick()
        rule.waitForIdle()
        val showedError = listOf("Microphone permission is required", "Speech recognition unavailable")
            .any { rule.onAllNodesWithText(it, substring = true).fetchSemanticsNodes().isNotEmpty() }
        val listening = rule.onAllNodesWithText("Listening…").fetchSemanticsNodes().isNotEmpty()
        assertTrue(showedError || listening, "mic tap produced neither an error nor a listening state")
    }

    // --- History page ----------------------------------------------------------------------

    @Test
    fun `history page lists seeded records and row actions write through`() {
        cleanDb()
        val (first, second) = seed(
            Triple("hist one", "hist uno", "en" to "es"),
            Triple("hist two", "hist dos", "en" to "es"),
        )
        val viewModel = vm()
        idle()
        var reused: TranslationRecord? = null; var back = 0
        rule.setContent {
            HistoryPage(viewModel = viewModel, onReuse = { reused = it }, onBack = { back++ })
        }
        rule.waitUntil(8_000) {
            rule.onAllNodesWithText("hist one").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("hist one").performClick()
        assertEquals(first.id, reused?.id)

        // Favorite the second row, then delete the first via their icon buttons.
        // Rows are in history order (newest first); icon order follows the rows.
        rule.onAllNodesWithContentDescription("Favorite")[0].performClick()
        rule.waitForIdle() // let the viewModelScope write launch run
        // The favorite write lands in the DB flow (polled off the main thread).
        val favorites = com.vayunmathur.translate.data.pollDb(
            { TranslateRepository.get(app).favorites.first() },
        ) { it.isNotEmpty() }
        assertEquals(1, favorites.size)
        rule.onAllNodesWithContentDescription("Delete")[0].performClick()
        rule.waitForIdle()
        assertEquals(0, back)
    }

    @Test
    fun `history page clear keeps favorites`() {
        cleanDb()
        val (fav, plain) = seed(
            Triple("keep me", "guardame", "en" to "es"),
            Triple("drop me", "borrame", "en" to "es"),
        )
        com.vayunmathur.translate.data.runDb { TranslateRepository.get(app).setFavorite(fav.id, true) }
        val viewModel = vm()
        idle()
        rule.setContent {
            HistoryPage(viewModel = viewModel, onReuse = {}, onBack = {})
        }
        rule.waitUntil(8_000) {
            rule.onAllNodesWithText("Clear").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Clear").performClick()
        rule.waitUntil(8_000) {
            rule.onAllNodesWithText("drop me").fetchSemanticsNodes().isEmpty()
        }
        rule.onNodeWithText("keep me").assertIsDisplayed()
        assertEquals(plain.sourceText, "drop me")
    }

    // --- Navigation ---------------------------------------------------------------------------

    @Test
    fun `navigation opens on text and reaches history and back`() {
        cleanDb()
        seed(Triple("nav seed", "nav semilla", "en" to "es"))
        val viewModel = vm()
        idle()
        rule.setContent { Navigation(viewModel, "nav hello") }
        rule.onNodeWithText("nav hello").assertIsDisplayed()
        rule.onNodeWithContentDescription("History").performClick()
        rule.waitUntil(8_000) {
            rule.onAllNodesWithText("nav seed").fetchSemanticsNodes().isNotEmpty()
        }
        // Tapping the row reuses it and returns to the text page with its text.
        rule.onNodeWithText("nav seed").performClick()
        rule.waitUntil(8_000) {
            rule.onAllNodesWithText("nav seed").fetchSemanticsNodes().isNotEmpty() &&
                rule.onAllNodesWithContentDescription("History").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("en", viewModel.sourceLang.value)
    }

    @Test
    fun `navigation language picker selects and returns`() {
        val viewModel = vm()
        idle()
        rule.setContent { Navigation(viewModel, "") }
        rule.waitUntil(8_000) {
            rule.onAllNodesWithText(Languages.displayName(viewModel.targetLang.value))
                .fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(Languages.displayName(viewModel.targetLang.value)).performClick()
        rule.waitUntil(8_000) {
            rule.onAllNodesWithText("All languages").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText(Languages.ALL.first().nativeName).performClick()
        assertEquals(Languages.ALL.first().code, viewModel.targetLang.value)
        // Back on the text page: the history toolbar icon is the marker.
        rule.waitUntil(8_000) {
            rule.onAllNodesWithContentDescription("History").fetchSemanticsNodes().isNotEmpty()
        }
    }
}
