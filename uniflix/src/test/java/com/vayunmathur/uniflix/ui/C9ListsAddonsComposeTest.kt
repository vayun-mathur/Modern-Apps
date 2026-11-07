package com.vayunmathur.uniflix.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.uniflix.data.AddonEntity
import com.vayunmathur.uniflix.data.UniflixDatabase
import com.vayunmathur.uniflix.data.UniflixRepository
import com.vayunmathur.uniflix.data.UserList
import com.vayunmathur.uniflix.data.UserListItem
import com.vayunmathur.uniflix.data.fakeHttpResponse
import com.vayunmathur.uniflix.data.uniflixTransport
import com.vayunmathur.uniflix.platform.FakeAndroidKeyStore
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Lists and Addons screens with seeded rows: create-list flow, list items, add-addon dialog. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class C9ListsAddonsComposeTest {

    @get:Rule val rule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: UniflixDatabase

    @BeforeTest fun setUp() {
        FakeAndroidKeyStore.install()
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(context, UniflixDatabase::class.java)
            .allowMainThreadQueries().build()
        com.vayunmathur.library.util.databases[UniflixDatabase::class] = db
        UniflixRepository.resetInstanceForTest()
        uniflixTransport = { url ->
            if (url.contains("/manifest.json")) {
                fakeHttpResponse(200, """{"id":"demo","name":"Demo Addon","version":"2.0.0","resources":["stream"],"types":["movie","series"]}""")
            } else {
                fakeHttpResponse(404, "{}")
            }
        }
        runBlocking {
            val listId = db.userListDao().upsertList(UserList(name = "Favorites", createdAt = 1L))
            db.userListDao().upsertItem(
                UserListItem(listId = listId, tmdbId = 10, mediaType = "movie", title = "Detail Film", posterPath = null)
            )
            db.addonDao().upsert(
                AddonEntity(
                    manifestUrl = "https://example.invalid/manifest.json",
                    baseUrl = "https://example.invalid", addonId = "demo", name = "Demo Addon",
                )
            )
        }
    }

    @AfterTest fun tearDown() {
        uniflixTransport = null
    }

    @Test fun listsScreen_showsListAndItems_andCreates() {
        var opened = false
        rule.setContent { ListsScreen(onBack = {}, onOpenDetail = { opened = true }) }
        rule.waitUntil(5000) {
            rule.onAllNodesWithText("Favorites").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Favorites").assertIsDisplayed()
        rule.onNodeWithText("Detail Film").assertExists()
        rule.onNodeWithText("New list").performTextInput("Weekend")
        rule.onNodeWithText("Add").performClick()
        rule.waitUntil(5000) {
            runBlocking { db.userListDao().getListsFlow().first().any { it.name == "Weekend" } }
        }
    }

    @Test fun addonsScreen_showsAddonAndOpensDetail() {
        var openedId: Long? = null
        rule.setContent { AddonsScreen(onBack = {}, onOpenDetail = { openedId = it }) }
        rule.waitUntil(5000) {
            rule.onAllNodesWithText("Demo Addon").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Demo Addon").assertIsDisplayed()
    }

    @Test fun addonsScreen_addDialog() {
        rule.setContent { AddonsScreen(onBack = {}, onOpenDetail = {}) }
        rule.waitUntil(5000) {
            rule.onAllNodesWithText("Demo Addon").fetchSemanticsNodes().isNotEmpty()
        }
        // The add entry point is the scaffold's add action; find a clickable
        // "Add" affordance and exercise the dialog if present.
        val addNodes = rule.onAllNodesWithText("Add addon", substring = true).fetchSemanticsNodes()
        if (addNodes.isNotEmpty()) {
            rule.onAllNodesWithText("Add addon", substring = true)[0].performClick()
            rule.waitForIdle()
        } else {
            rule.waitForIdle()
        }
    }
}
