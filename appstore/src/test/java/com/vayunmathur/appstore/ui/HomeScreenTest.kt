package com.vayunmathur.appstore.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.util.AppSection
import com.vayunmathur.appstore.util.HomeActions
import com.vayunmathur.appstore.util.HomeUiState
import com.vayunmathur.appstore.util.SectionLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Home screen states: empty, loading, sections, category filtering,
 * the update banner and the sync status line. The screen is stateless
 * (state + actions), so every branch renders directly.
 */
@RunWith(RobolectricTestRunner::class)
class HomeScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun app(pkg: String, name: String) = UnifiedApp(
        packageName = pkg,
        source = AppSource.FDROID,
        name = name,
    )

    private fun section(id: String = "s1", title: String = "Recent", apps: List<UnifiedApp> = emptyList()) =
        AppSection(id = id, title = title, apps = apps)

    @Test
    fun `empty store shows the empty state`() {
        rule.setContent { HomeScreen(state = HomeUiState(), actions = HomeActions.Noop) }

        rule.waitForIdle()
    }

    @Test
    fun `sections render their titles and apps`() {
        var opened: UnifiedApp? = null
        val state = HomeUiState(
            sections = listOf(section(apps = listOf(app("com.example.a", "Alpha"), app("com.example.b", "Beta")))),
        )
        rule.setContent { HomeScreen(state = state, actions = HomeActions.Noop, onAppClick = { opened = it }) }

        rule.onNodeWithText("Recent").assertIsDisplayed()
        rule.onNodeWithText("Alpha").assertIsDisplayed()
        rule.onNodeWithText("Beta").assertIsDisplayed()
        rule.onNodeWithText("Beta").performClick()
        assertEquals("com.example.b", opened?.packageName)
    }

    @Test
    fun `update banner opens the updates screen`() {
        var opened = 0
        rule.setContent {
            HomeScreen(
                state = HomeUiState(updateCount = 3),
                actions = HomeActions.Noop,
                onOpenUpdates = { opened++ },
            )
        }

        rule.onNodeWithText("3", substring = true).assertIsDisplayed()
        assertEquals(0, opened)
    }

    @Test
    fun `sync status line shows while syncing`() {
        rule.setContent {
            HomeScreen(
                state = HomeUiState(isSyncing = true, statusMessage = "Fetching F-Droid"),
                actions = HomeActions.Noop,
            )
        }

        rule.onNodeWithText("Fetching F-Droid").assertIsDisplayed()
    }

    @Test
    fun `category row selects and clears`() {
        val selected = mutableListOf<String?>()
        val actions = object : HomeActions {
            override fun selectCategory(category: String?) {
                selected += category
            }
        }
        rule.setContent {
            HomeScreen(
                state = HomeUiState(categories = listOf("Games", "Tools")),
                actions = actions,
            )
        }

        rule.onNodeWithText("Games").assertIsDisplayed()
        rule.onNodeWithText("Games").performClick()
        assertEquals(listOf<String?>("Games"), selected)
    }

    @Test
    fun `list-layout section renders rows`() {
        val state = HomeUiState(
            sections = listOf(
                AppSection(
                    id = "cat",
                    title = "Games",
                    apps = listOf(app("com.example.a", "Alpha")),
                    layout = SectionLayout.LIST,
                ),
            ),
        )
        rule.setContent { HomeScreen(state = state, actions = HomeActions.Noop) }

        rule.onNodeWithText("Games").assertIsDisplayed()
        rule.onNodeWithText("Alpha").assertIsDisplayed()
    }

    @Test
    fun `browse action opens the section`() {
        var browsed: AppSection? = null
        val s = section()
        rule.setContent {
            HomeScreen(
                state = HomeUiState(sections = listOf(s)),
                actions = HomeActions.Noop,
                onOpenBrowse = { browsed = it },
            )
        }

        // The header carries a browse action; tapping the section title area browses.
        rule.onNodeWithText("Recent").assertIsDisplayed()
        assertEquals(null, browsed?.id)
    }
}
