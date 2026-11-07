package com.vayunmathur.flashcards.ui

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.flashcards.Route
import com.vayunmathur.flashcards.data.Deck
import com.vayunmathur.flashcards.data.FlashcardsDatabase
import com.vayunmathur.flashcards.data.FlashcardsRepository
import com.vayunmathur.flashcards.data.getAllNotesImpl
import com.vayunmathur.flashcards.util.FlashcardsViewModel
import com.vayunmathur.flashcards.util.ThemeMode
import com.vayunmathur.flashcards.util.addDeckImpl
import com.vayunmathur.flashcards.util.saveNoteImpl
import com.vayunmathur.flashcards.util.setDesiredRetentionImpl
import com.vayunmathur.flashcards.util.setThemeModeImpl
import com.vayunmathur.flashcards.util.setAutoPlayImpl
import com.vayunmathur.flashcards.util.setReminderEnabledImpl
import com.vayunmathur.flashcards.util.upsertDeckImpl
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.databases
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class FlashcardsPagesTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var app: Application
    private lateinit var repo: FlashcardsRepository
    private lateinit var vm: FlashcardsViewModel

    companion object {
        private var sharedDb: FlashcardsDatabase? = null
    }

    @BeforeTest
    fun setUp() {
        app = ApplicationProvider.getApplicationContext<Application>()
        runCatching {
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        }
        val mem = sharedDb ?: Room.inMemoryDatabaseBuilder(app, FlashcardsDatabase::class.java)
            .allowMainThreadQueries().build().also { sharedDb = it }
        synchronized(databases) { databases[FlashcardsDatabase::class] = mem }
        repo = FlashcardsRepository.get(app)
        vm = FlashcardsViewModel(app, repo)
        val scope = CoroutineScope(Dispatchers.Default + Job())
        scope.launch { vm.noteTypes.collect {} }
        scope.launch { vm.decks.collect {} }
        scope.launch { vm.cards.collect {} }
        scope.launch { vm.notes.collect {} }
        scope.launch { vm.settings.collect {} }
        scope.launch { vm.deckPresets.collect {} }
        await("built-ins") { vm.noteTypes.value.size >= 3 }
        // The settings combine only emits once the mapNotNull flows have values.
        vm.setReminderEnabledImpl(false)
        vm.setAutoPlayImpl(false)
        vm.setDesiredRetentionImpl(0.9)
    }

    private fun await(label: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            rule.waitForIdle()
            if (condition()) return
            Thread.sleep(50)
        }
        error("timed out waiting for $label")
    }

    private fun deckWithNote(deckName: String, front: String): Deck {
        vm.addDeckImpl(deckName)
        await("deck $deckName") { vm.decks.value.any { it.name == deckName } }
        val deck = vm.decks.value.first { it.name == deckName }
        vm.saveNoteImpl(0, 1L, deck.id, listOf(front, "back-$front"), "pagetag")
        await("note $front") { runBlocking { repo.getAllNotesImpl().any { it.sortField == front } } }
        return deck
    }

    @Test
    fun deckListPageBindsAndNavigates() {
        val deck = deckWithNote("PageDeckList", "PageFront1")
        // Pin this deck to the top of the list so it is composed in the shared-database list.
        vm.upsertDeckImpl(deck.copy(position = -1000.0))
        await("deck pinned") { vm.decks.value.firstOrNull { it.id == deck.id }?.position == -1000.0 }
        val stack = NavBackStack<Route>(arrayOf(Route.Main))
        rule.setContent { DynamicTheme { DeckListPage(stack, vm) } }
        rule.onNodeWithText("PageDeckList").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText("PageDeckList").performScrollTo().performClick()
        assertEquals(Route.CardList(deck.id), stack.last())
    }

    @Test
    fun deckListPagePlayPushesReview() {
        deckWithNote("PageDeckPlay", "PagePlayFront")
        val stack = NavBackStack<Route>(arrayOf(Route.Main))
        rule.setContent { DynamicTheme { DeckListPage(stack, vm) } }
        rule.onAllNodesWithContentDescription("Play")[0].performClick()
        assertTrue(stack.last() is Route.Review)
    }

    @Test
    fun noteListPageBindsAndNavigates() {
        val deck = deckWithNote("PageNoteDeck", "PageNoteFront")
        val stack = NavBackStack<Route>(arrayOf(Route.Main, Route.CardList(deck.id)))
        rule.setContent { DynamicTheme { NoteListPage(stack, vm, deck.id) } }
        rule.onNodeWithText("PageNoteFront").assertIsDisplayed()
        rule.onNodeWithText("Study").assertIsDisplayed()
        rule.onNodeWithText("Study").performClick()
        assertTrue(stack.last() is Route.Review)
        rule.onNodeWithText("PageNoteFront").performClick()
        assertTrue(stack.last() is Route.NoteEdit)
        // Add-note FAB replaces/pushes an editor for a new note.
        rule.onNodeWithContentDescription("Add").performClick()
        assertEquals(0L, (stack.last() as Route.NoteEdit).noteId)
    }

    @Test
    fun noteEditPageBindsAndSaves() {
        val deck = deckWithNote("PageEditDeck", "PageEditFront")
        val note = runBlocking { repo.getAllNotesImpl().first { it.sortField == "PageEditFront" } }
        val stack = NavBackStack<Route>(arrayOf(Route.Main, Route.NoteEdit(deck.id, note.id)))
        rule.setContent { DynamicTheme { NoteEditPage(stack, vm, deck.id, note.id) } }
        rule.onNodeWithText("Edit card").assertIsDisplayed()
        rule.onNodeWithText("PageEditDeck").assertIsDisplayed()
        rule.onNodeWithContentDescription("Save").performClick()
        // Saving pops the editor off the stack.
        await("editor popped") { stack.backStack.size == 1 }
        assertEquals(Route.Main, stack.last())
    }

    @Test
    fun noteTypePagesBind() {
        val stack = NavBackStack<Route>(arrayOf(Route.Main))
        rule.setContent { DynamicTheme { NoteTypeListPage(stack, vm) } }
        rule.onNodeWithText("Basic").assertIsDisplayed()
        rule.onNodeWithText("Basic").performClick()
        assertEquals(Route.NoteTypeEdit(1L), stack.last())
    }

    @Test
    fun noteTypeEditPageBindsExisting() {
        val stack = NavBackStack<Route>(arrayOf(Route.Main, Route.NoteTypeEdit(1L)))
        rule.setContent { DynamicTheme { NoteTypeEditPage(stack, vm, 1L) } }
        rule.onNodeWithText("Edit note type").assertIsDisplayed()
        rule.onNodeWithText("Basic").assertIsDisplayed()
    }

    @Test
    fun noteTypeEditPageBindsNew() {
        val stack = NavBackStack<Route>(arrayOf(Route.Main))
        rule.setContent { DynamicTheme { NoteTypeEditPage(stack, vm, 0L) } }
        rule.onNodeWithText("New note type").assertIsDisplayed()
    }

    @Test
    fun reviewPageBindsAndGrades() {
        val deck = deckWithNote("PageReviewDeck", "PageReviewFront")
        val stack = NavBackStack<Route>(arrayOf(Route.Main, Route.Review(deck.id)))
        rule.setContent { DynamicTheme { ReviewPage(stack, vm, deck.id) } }
        await("review front") {
            runCatching { rule.onNodeWithText("PageReviewFront").assertIsDisplayed(); true }.getOrDefault(false)
        }
        rule.onNodeWithText("Show answer").performClick()
        rule.onNodeWithText("Again").assertIsDisplayed()
        rule.onNodeWithText("Good").performClick()
        await("session done after grade") { vm.review.value.done }
        rule.onNodeWithText("All caught up!").assertIsDisplayed()
    }

    @Test
    fun reviewPageWithModeParamsStartsCramSession() {
        val deck = deckWithNote("PageCramDeck", "PageCramFront")
        val stack = NavBackStack<Route>(arrayOf(Route.Main))
        // mode 4 = CRAM
        rule.setContent { DynamicTheme { ReviewPage(stack, vm, deck.id, mode = 4, count = 5, daysAhead = 2, tags = listOf("pagetag")) } }
        await("cram front") {
            runCatching { rule.onNodeWithText("PageCramFront").assertIsDisplayed(); true }.getOrDefault(false)
        }
    }

    @Test
    fun statsPageBinds() {
        deckWithNote("PageStatsDeck", "PageStatsFront")
        val stack = NavBackStack<Route>(arrayOf(Route.Main))
        rule.setContent { DynamicTheme { StatsPage(stack, vm) } }
        rule.onNodeWithText("Stats").assertIsDisplayed()
        rule.onNodeWithText("All decks").assertExists()
        rule.onNodeWithText("PageStatsDeck").assertExists()
        rule.onNodeWithText("PageStatsDeck").performClick()
        rule.onNodeWithText("Reviews").assertIsDisplayed()
    }

    @Test
    fun settingsPageBindsAndWritesThrough() {
        val stack = NavBackStack<Route>(arrayOf(Route.Main))
        rule.setContent { DynamicTheme { SettingsPage(stack, vm) } }
        rule.onNodeWithText("Settings").assertIsDisplayed()
        rule.onNodeWithText("Dark").performScrollTo().performClick()
        await("theme written") {
            runBlocking { vm.ds.getLongAwait(FlashcardsViewModel.KEY_THEME_MODE) } == ThemeMode.DARK.toLong() ||
                vm.settings.value.themeMode == ThemeMode.DARK
        }
        await("theme in settings") { vm.settings.value.themeMode == ThemeMode.DARK }
        rule.onNodeWithText("Manage note types").performScrollTo().performClick()
        assertEquals(Route.NoteTypeList, stack.last())
        // Restore the default theme for other tests sharing this DataStore.
        vm.setDesiredRetentionImpl(0.9)
        vm.setThemeModeImpl(ThemeMode.SYSTEM)
        await("theme restored") { vm.settings.value.themeMode == ThemeMode.SYSTEM }
    }

    @Test
    fun navigationHostShowsTabs() {
        val deck = deckWithNote("NavDeck", "NavFront")
        vm.upsertDeckImpl(deck.copy(position = -2000.0))
        await("nav deck pinned") { vm.decks.value.firstOrNull { it.id == deck.id }?.position == -2000.0 }
        rule.setContent { DynamicTheme { com.vayunmathur.flashcards.Navigation(vm) } }
        rule.onNodeWithText("Decks").assertIsDisplayed()
        rule.onNodeWithText("NavDeck").assertIsDisplayed()
        rule.onNodeWithText("Stats").performClick()
        rule.onNodeWithText("Reviews").assertExists()
        rule.onNodeWithText("Settings").performClick()
        rule.onNodeWithText("Desired retention").assertExists()
    }
}
