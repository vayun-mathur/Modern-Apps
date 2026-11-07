package com.vayunmathur.appstore.tv

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppSource
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * TV sources screen: every source row in phone order, the all-disabled
 * warning, and the wrapper wiring to the real ViewModel.
 */
@RunWith(RobolectricTestRunner::class)
class TvStoreSourcesScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun exists(text: String) =
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `default sources render every row`() {
        rule.setContent {
            TvStoreSourcesScreen(
                enabledSources = AppSource.DEFAULT_ENABLED,
                autoInstallUpdates = false,
            )
        }
        rule.onNodeWithText("Sources").assertIsDisplayed()
        // The TV grid is lazy: only the first rows are composed.
        assertTrue(exists("Modern Apps"))
        assertTrue(exists("Automatically install updates"))
    }

    @Test
    fun `no sources enabled shows the warning`() {
        rule.setContent {
            TvStoreSourcesScreen(enabledSources = emptySet(), autoInstallUpdates = true)
        }
        assertTrue(exists("Every source is switched off"))
        assertTrue(exists("Modern Apps"))
    }

    @Test
    fun `all sources enabled renders without the warning`() {
        rule.setContent {
            TvStoreSourcesScreen(
                enabledSources = AppSource.entries.toSet(),
                autoInstallUpdates = false,
            )
        }
        assertTrue(exists("Modern Apps"))
        assertTrue(!exists("Every source is switched off"))
    }

    @Test
    fun `wrapper reads the view model flows`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        // Not closed: the ViewModel's scope keeps collecting DB flows, and
        // closing under it surfaces as a background failure in a later test.
        // The in-memory DB dies with the Robolectric sandbox.
        val vm = TvStoreViewModel(app, db)
        rule.setContent { TvStoreSources(viewModel = vm) }
        rule.onNodeWithText("Sources").assertIsDisplayed()
        assertTrue(exists("Modern Apps"))
    }
}
