package com.vayunmathur.flashcards.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.vayunmathur.flashcards.MainActivity
import com.vayunmathur.flashcards.data.Deck
import com.vayunmathur.flashcards.data.FlashcardsDatabase
import com.vayunmathur.flashcards.data.FlashcardsRepository
import com.vayunmathur.flashcards.data.Note
import com.vayunmathur.flashcards.util.FlashcardsViewModelHelper
import com.vayunmathur.library.util.databases
import kotlin.test.Test
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner

/** Seeds the in-memory database before the activity under test is launched. */
private class SeedDatabaseRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val app = ApplicationProvider.getApplicationContext<Application>()
            runCatching {
                androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
            }
            // Reuse the database already bound to the repository singleton when an
            // earlier test class in this JVM created one; otherwise seed a fresh one.
            val mem = synchronized(databases) {
                databases[FlashcardsDatabase::class] as? FlashcardsDatabase
                    ?: Room.inMemoryDatabaseBuilder(app, FlashcardsDatabase::class.java)
                        .allowMainThreadQueries().build()
                        .also { databases[FlashcardsDatabase::class] = it }
            }
            val repo = FlashcardsRepository.get(app)
            runBlocking {
                FlashcardsViewModelHelper.ensureBuiltInNoteTypes(repo)
                val deckId = mem.deckDao().upsert(Deck(name = "ActivityDeck", position = -3000.0))
                mem.noteDao().upsert(
                    Note(noteTypeId = 1, deckId = deckId, guid = "act-guid", flds = "ActivityFrontActivityBack", sortField = "ActivityFront"),
                )
            }
            base.evaluate()
        }
    }
}

@RunWith(RobolectricTestRunner::class)
class FlashcardsActivityTest {

    private val composeRule: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity> =
        createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(SeedDatabaseRule()).around(composeRule)

    @Test
    fun activityShowsTabsAndNavigates() {
        composeRule.onNodeWithText("Flashcards").assertIsDisplayed()
        composeRule.onNodeWithText("Decks").assertIsDisplayed()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            runCatching { composeRule.onNodeWithText("ActivityDeck").assertIsDisplayed(); true }.getOrDefault(false)
        }
        composeRule.onNodeWithText("ActivityDeck").assertIsDisplayed()
        composeRule.onNodeWithText("Stats").performClick()
        composeRule.onNodeWithText("Reviews").assertExists()
        composeRule.onNodeWithText("Settings").performClick()
        composeRule.onNodeWithText("Desired retention").assertExists()
        composeRule.onNodeWithText("Decks").performClick()
        composeRule.onNodeWithText("ActivityDeck").assertIsDisplayed()
    }

}
