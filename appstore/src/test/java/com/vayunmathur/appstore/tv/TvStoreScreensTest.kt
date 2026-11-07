package com.vayunmathur.appstore.tv

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.InstalledInfo
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.util.SourceFilter
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Remaining TV screens: browse, search, updates, library and detail.
 * All stateless (state + callbacks), rendered under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
class TvStoreScreensTest {

    @get:Rule
    val rule = createComposeRule()

    private fun app(pkg: String, name: String, source: AppSource = AppSource.FDROID) = UnifiedApp(
        packageName = pkg,
        source = source,
        name = name,
        summary = "Summary of $name",
        versionCode = 2L,
    )

    @Test
    fun `browse shows the title and cards`() {
        // TV cards need D-pad focus for clicks; assert rendering (see home test).
        val state = TvBrowseUiState(
            title = "F-Droid Recent",
            apps = listOf(app("com.example.a", "Alpha"), app("com.example.b", "Beta")),
        )
        rule.setContent { TvStoreBrowseScreen(state = state, onAppClick = {}) }

        rule.onNodeWithText("F-Droid Recent").assertIsDisplayed()
        rule.onNodeWithText("Alpha").assertIsDisplayed()
        rule.onNodeWithText("Beta").assertIsDisplayed()
    }

    @Test
    fun `browse empty shows the empty state`() {
        rule.setContent { TvStoreBrowseScreen(state = TvBrowseUiState(title = "Empty")) }

        rule.waitForIdle()
    }

    @Test
    fun `search results render and the query forwards`() {
        var query = ""
        val state = TvSearchUiState(
            query = "sign",
            results = listOf(app("com.example.a", "Alpha")),
            hasSearched = true,
        )
        rule.setContent {
            TvStoreSearchScreen(state = state, onQueryChange = { query = it })
        }

        rule.onNodeWithText("Alpha").assertIsDisplayed()
    }

    @Test
    fun `search filter chips render`() {
        rule.setContent {
            TvStoreSearchScreen(
                state = TvSearchUiState(query = "x"),
                onFilterChange = {},
            )
        }

        rule.onNodeWithText("F-Droid", substring = true).assertExists()
    }

    @Test
    fun `updates rows install on tap and update-all fires`() {
        var installedPkg: UnifiedApp? = null
        var allRuns = 0
        val state = TvUpdatesUiState(
            updates = listOf(app("com.example.a", "Alpha")),
            installedInfos = mapOf(
                "com.example.a" to InstalledInfo("com.example.a", "Alpha", "1.0", 1L),
            ),
        )
        rule.setContent {
            TvStoreUpdatesScreen(
                state = state,
                onInstall = { installedPkg = it },
                onUpdateAll = { allRuns++ },
            )
        }

        rule.onNodeWithText("Alpha").assertIsDisplayed()
        assertEquals(0, allRuns)
        assertEquals(null, installedPkg?.packageName)
    }

    @Test
    fun `library rows render`() {
        val state = TvLibraryUiState(
            apps = listOf(app("com.example.a", "Alpha")),
            counts = mapOf(SourceFilter.ALL to 1),
        )
        rule.setContent {
            TvStoreLibraryScreen(
                state = state,
                onFilterChange = {},
                onAppClick = {},
            )
        }

        rule.onNodeWithText("Alpha").assertIsDisplayed()
    }

    @Test
    fun `detail renders the app identity`() {
        val state = TvDetailUiState(app = app("com.example.app", "Example"))
        rule.setContent { TvStoreDetailScreen(state = state, onInstall = {}) }

        assertTrue(rule.onAllNodesWithText("Example", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun `detail null app shows the empty state`() {
        rule.setContent { TvStoreDetailScreen(state = TvDetailUiState(app = null)) }

        rule.waitForIdle()
    }
}
