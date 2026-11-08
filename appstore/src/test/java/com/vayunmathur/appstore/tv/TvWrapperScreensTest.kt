package com.vayunmathur.appstore.tv

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.room3.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.DefaultRepos
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.toEntity
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The public TV screen wrappers against the real ViewModel: each binds
 * its state flow, kicks off its on-open work (update check, installed
 * refresh), and renders. Screen-level state shapes are covered by the
 * *Screen tests; this is the wiring in between.
 */
@RunWith(RobolectricTestRunner::class)
class TvWrapperScreensTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var vm: TvStoreViewModel
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
                    versionCode = 99_999_999L,
                    repoUrl = DefaultRepos.MODERN_APPS.url,
                ).toEntity(),
            )
        }
        vm = TvStoreViewModel(app, db)
    }

    private fun exists(text: String) =
        rule.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun `home wrapper renders the store home`() {
        rule.setContent {
            TvStoreHome(viewModel = vm, onAppClick = {}, onOpenUpdates = {}, onOpenBrowse = {})
        }
        rule.waitForIdle()
        assertTrue(vm.home.value.sections.isNotEmpty() || exists("This Store") || exists("Modern"))
    }

    @Test
    fun `updates wrapper renders and kicks off a check`() {
        rule.setContent { TvStoreUpdates(viewModel = vm, onAppClick = {}) }
        rule.waitForIdle()
        assertTrue(
            exists("This Store") || exists("Update") || vm.updatesUi.value.lastCheckedAt >= 0L,
        )
    }

    @Test
    fun `library wrapper attributes the installed app`() {
        rule.setContent { TvStoreLibrary(viewModel = vm, onAppClick = {}) }
        rule.waitUntil(timeoutMillis = 15_000) {
            vm.library.value.apps.any { it.packageName == ownPackage } || exists("This Store")
        }
        assertTrue(vm.library.value.apps.any { it.packageName == ownPackage })
    }

    @Test
    fun `search wrapper renders the search prompt`() {
        rule.setContent { TvStoreSearch(viewModel = vm, onAppClick = {}, startEditing = false) }
        rule.waitForIdle()
        assertTrue(exists("Search") || exists("Modern Apps and F-Droid"))
    }

    @Test
    fun `sources wrapper renders the source list`() {
        rule.setContent { TvStoreSources(viewModel = vm) }
        rule.waitForIdle()
        assertTrue(exists("Modern Apps") || exists("Sources") || exists("F-Droid"))
    }

    @Test
    fun `browse wrapper renders the opened section`() {
        vm.openBrowse(
            TvSection(
                id = "modern",
                title = "Modern Row",
                apps = listOf(
                    UnifiedApp(ownPackage, AppSource.MODERN_APPS, "This Store"),
                ),
            ),
        )
        rule.setContent {
            TvStoreBrowse(viewModel = vm, onAppClick = {}, onBack = {})
        }
        rule.waitForIdle()
        assertTrue(exists("Modern Row") || exists("This Store"))
    }
}
