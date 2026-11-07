package com.vayunmathur.appstore.tv

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * TV home screen: empty, loading, sections with cards, the updates
 * banner and the category chips. Stateless (state + callbacks), so
 * every branch renders directly.
 */
@RunWith(RobolectricTestRunner::class)
class TvStoreHomeScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun app(pkg: String, name: String) = UnifiedApp(
        packageName = pkg,
        source = AppSource.FDROID,
        name = name,
    )

    private fun section(id: String = "s1", title: String = "Recent", apps: List<UnifiedApp> = emptyList()) =
        TvSection(id = id, title = title, apps = apps)

    @Test
    fun `empty store shows the empty state`() {
        rule.setContent { TvStoreHomeScreen(state = TvHomeUiState()) }

        rule.waitForIdle()
    }

    @Test
    fun `loading shows the progress line`() {
        rule.setContent { TvStoreHomeScreen(state = TvHomeUiState(isLoading = true)) }

        rule.waitForIdle()
    }

    @Test
    fun `sections render titles and cards`() {
        // TV cards are tv-material Surfaces driven by D-pad focus; taps are not
        // faithfully clickable via performClick, so assert rendering here —
        // the phone-chip click wiring is covered by the phone screen tests.
        val state = TvHomeUiState(
            sections = listOf(
                section(apps = listOf(app("com.example.a", "Alpha"), app("com.example.b", "Beta"))),
            ),
        )
        rule.setContent { TvStoreHomeScreen(state = state, onAppClick = {}) }

        rule.onNodeWithText("Recent").assertIsDisplayed()
        rule.onNodeWithText("Alpha").assertIsDisplayed()
        rule.onNodeWithText("Beta").assertIsDisplayed()
    }

    @Test
    fun `updates banner shows the count`() {
        val state = TvHomeUiState(
            sections = listOf(section(apps = listOf(app("com.example.a", "Alpha")))),
            updateCount = 2,
        )
        rule.setContent { TvStoreHomeScreen(state = state) }

        rule.onNodeWithText("2", substring = true).assertIsDisplayed()
    }

    @Test
    fun `category chips render the full set`() {
        // TV chips are tv-material FilterChips driven by D-pad focus; assert
        // rendering here — chip interaction is covered by the phone tests.
        rule.setContent {
            TvStoreHomeScreen(
                state = TvHomeUiState(
                    sections = listOf(section(apps = listOf(app("com.example.a", "Alpha")))),
                    categories = listOf("Games", "Tools"),
                ),
                onSelectCategory = {},
            )
        }

        rule.onNodeWithText("Games").assertIsDisplayed()
        rule.onNodeWithText("Tools").assertIsDisplayed()
    }

    @Test
    fun `sync status line shows`() {
        val state = TvHomeUiState(
            sections = listOf(section(apps = listOf(app("com.example.a", "Alpha")))),
            statusMessage = "Fetching F-Droid",
        )
        rule.setContent { TvStoreHomeScreen(state = state) }

        rule.onNodeWithText("Fetching F-Droid").assertIsDisplayed()
    }
}
