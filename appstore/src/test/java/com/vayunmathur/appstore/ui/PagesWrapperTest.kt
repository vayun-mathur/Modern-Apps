package com.vayunmathur.appstore.ui

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
import com.vayunmathur.appstore.data.DefaultRepos
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.toEntity
import com.vayunmathur.appstore.util.AppStoreViewModel
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The page wrappers against the real ViewModel: each binds its state
 * flow and renders. The library wrapper must attribute this test app
 * to Modern Apps from the seeded catalogue index.
 */
@RunWith(RobolectricTestRunner::class)
class PagesWrapperTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var vm: AppStoreViewModel
    private lateinit var ownPackage: String

    @BeforeTest
    fun setUp() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        ownPackage = app.packageName
        val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java)
            .setDriver(BundledSQLiteDriver())
            .allowMainThreadQueries()
            .build()
        runBlocking {
            db.cachedAppDao().upsert(
                UnifiedApp(
                    packageName = ownPackage,
                    source = AppSource.MODERN_APPS,
                    name = "This Store",
                    repoUrl = DefaultRepos.MODERN_APPS.url,
                ).toEntity(),
            )
        }
        // Not closed: the ViewModel scope keeps collecting DB flows; the
        // in-memory DB dies with the Robolectric sandbox.
        vm = AppStoreViewModel(app, db)
    }

    private fun exists(text: String) =
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `home page renders the empty store`() {
        rule.setContent {
            HomePage(viewModel = vm, onAppClick = {}, onOpenUpdates = {}, onOpenBrowse = {})
        }
        rule.waitForIdle()
        assertTrue(exists("Nothing here yet") || vm.home.value.sections.isNotEmpty())
    }

    @Test
    fun `library page attributes the installed app to its catalogue source`() {
        rule.setContent { LibraryPage(viewModel = vm, onAppClick = {}) }
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline &&
            vm.library.value.apps.none { it.packageName == ownPackage }
        ) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper())
                .idleFor(200, java.util.concurrent.TimeUnit.MILLISECONDS)
            Thread.sleep(50)
        }
        assertTrue(exists("App Store"), "library apps were ${vm.library.value.apps.map { it.name to it.packageName }}")
        assertTrue(
            vm.library.value.apps.first { it.packageName == ownPackage }.source ==
                AppSource.MODERN_APPS,
        )
    }

    @Test
    fun `updates page renders`() {
        rule.setContent { UpdatesPage(viewModel = vm, onAppClick = {}) }
        rule.waitForIdle()
    }

    @Test
    fun `search page renders the search field`() {
        rule.setContent {
            SearchPage(viewModel = vm, onAppClick = {})
        }
        rule.waitForIdle()
        assertTrue(exists("Search") || exists("search"))
    }
}
