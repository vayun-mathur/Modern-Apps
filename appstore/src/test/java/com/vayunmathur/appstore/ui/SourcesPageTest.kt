package com.vayunmathur.appstore.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.util.AppStoreViewModel
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Sources screen against the real ViewModel: every source card renders
 * with its repo URL, the auto-install row is present, and the trust entry
 * point fires its callback.
 */
@RunWith(RobolectricTestRunner::class)
class SourcesPageTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var db: AppDatabase
    private lateinit var vm: AppStoreViewModel

    @BeforeTest
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        vm = AppStoreViewModel(app, db)
    }

    // DB not closed: the ViewModel's background coroutines keep
    // collecting its flows (see the matching fix in the ViewModel
    // tests); the in-memory DB dies with the Robolectric sandbox.

    @Test
    fun `all source cards and the auto install row render`() {
        rule.setContent { SourcesPage(viewModel = vm) }
        rule.onNodeWithText("Modern Apps", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Automatically install updates", substring = true).assertIsDisplayed()
        rule.onNodeWithText("F-Droid", substring = true).assertIsDisplayed()
        rule.onNodeWithText("https://f-droid.org/repo", substring = true).assertExists()
        // Below the scroll fold on the Robolectric display; existence is what matters.
        rule.onNodeWithText("play.google.com", substring = true).assertExists()
    }

    @Test
    fun `trust button opens the trust page`() {
        var opened = 0
        rule.setContent { SourcesPage(viewModel = vm, onOpenTrust = { opened++ }) }
        // The trust entry point is the first button on the page.
        rule.onNodeWithText("How apps are checked", substring = true).assertIsDisplayed()
        rule.onNodeWithText("How apps are checked", substring = true).performClick()
        assertEquals(1, opened)
    }
}
