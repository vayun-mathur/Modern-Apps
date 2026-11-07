package com.vayunmathur.uniflix.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.uniflix.data.AddonEntity
import com.vayunmathur.uniflix.data.UniflixDatabase
import com.vayunmathur.uniflix.data.UniflixRepository
import com.vayunmathur.uniflix.platform.FakeAndroidKeyStore
import com.vayunmathur.uniflix.ui.tv.TvAddonDetailPage
import com.vayunmathur.uniflix.ui.tv.TvAddonsTab
import com.vayunmathur.uniflix.ui.tv.TvHomeTab
import com.vayunmathur.uniflix.ui.tv.TvMyListTab
import com.vayunmathur.uniflix.ui.tv.TvRoute
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * DB-backed screens (addons, lists, continue-watching, TV tabs) with the
 * cached database registry pre-seeded with an in-memory UniflixDatabase:
 * UniflixRepository then reads real Room rows with no SQLCipher/KeyStore.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class C9DbScreensTest {

    @get:Rule val rule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    companion object {
        private var sharedDb: UniflixDatabase? = null
        private fun sharedDb(context: android.content.Context): UniflixDatabase {
            sharedDb?.let { return it }
            com.vayunmathur.library.util.databases[UniflixDatabase::class]?.let {
                @Suppress("UNCHECKED_CAST")
                sharedDb = it as UniflixDatabase
                return it as UniflixDatabase
            }
            return Room.inMemoryDatabaseBuilder(context, UniflixDatabase::class.java)
                .allowMainThreadQueries()
                .build().also {
                    com.vayunmathur.library.util.databases[UniflixDatabase::class] = it
                    sharedDb = it
                }
        }
    }

    @BeforeTest fun open() {
        FakeAndroidKeyStore.install()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val db = sharedDb(context)
        runBlocking {
            if (db.addonDao().count() == 0) {
                db.addonDao().upsert(
                    AddonEntity(
                        manifestUrl = "https://example.invalid/manifest.json",
                        baseUrl = "https://example.invalid", addonId = "demo", name = "Demo Addon",
                    )
                )
            }
        }
    }

    private fun repo() = UniflixRepository.get(context)

    @Test fun repository_flowsReadSeededRows() = runBlocking {
        val addons = repo().addonCount()
        kotlin.test.assertTrue(addons >= 1, "addons=$addons")
        repo().upsertWatchlist(
            com.vayunmathur.uniflix.data.WatchlistEntry(
                key = "movie:1", tmdbId = 1, mediaType = "movie", title = "Seeded Movie", posterPath = null, addedAt = 1L,
            )
        )
        kotlin.test.assertTrue(repo().isWatchlisted("movie:1"))
        repo().removeWatchlist("movie:1")
        kotlin.test.assertFalse(repo().isWatchlisted("movie:1"))
    }

    @Test fun addonsScreen_showsSeededAddon() {
        rule.setContent { AddonsScreen(onBack = {}, onOpenDetail = {}) }
        rule.waitUntil(5000) {
            rule.onAllNodesWithText("Demo Addon").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Demo Addon").assertIsDisplayed()
    }

    @Test fun addonDetailScreen_showsAddon() {
        rule.setContent { AddonDetailScreen(addonDbId = 1L, onBack = {}) }
        rule.waitUntil(5000) {
            rule.onAllNodesWithText("Demo Addon").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Demo Addon").assertExists()
    }

    @Test fun listsScreen_renders() {
        rule.setContent { ListsScreen(onBack = {}, onOpenDetail = {}) }
        rule.waitForIdle()
    }

    @Test fun continueWatchingScreen_renders() {
        rule.setContent { ContinueWatchingScreen(onBack = {}, onResume = {}) }
        rule.waitForIdle()
    }

    @Test fun tvAddonsTab_showsSeededAddon() {
        rule.setContent { TvAddonsTab(onOpenDetail = {}) }
        rule.waitUntil(5000) {
            rule.onAllNodesWithText("Demo Addon").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Demo Addon").assertExists()
    }

    @Test fun tvHome_renders() {
        rule.setContent {
            TvHomeTab(onOpenDetail = {}, onResume = {}, onOpenGrid = {}, onOpenTab = {})
        }
        rule.waitForIdle()
    }

    @Test fun tvMyList_renders() {
        rule.setContent {
            TvMyListTab(onOpenDetail = {}, onResume = {})
        }
        rule.waitForIdle()
    }

    @Test fun tvAddonDetail_renders() {
        val stack = NavBackStack<TvRoute>(arrayOf(TvRoute.Main()))
        rule.setContent { TvAddonDetailPage(stack, addonDbId = 1L) }
        rule.waitForIdle()
    }
}
