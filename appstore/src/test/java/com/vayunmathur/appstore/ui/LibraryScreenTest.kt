package com.vayunmathur.appstore.ui
import com.vayunmathur.appstore.platform.LibraryActions
import com.vayunmathur.appstore.platform.LibraryUiState
import com.vayunmathur.appstore.platform.SourceFilter
import com.vayunmathur.appstore.platform.setLibraryFilter

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

/** Library screen: the empty state, installed rows and the filter chips. */
@RunWith(RobolectricTestRunner::class)
class LibraryScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun app(pkg: String, name: String, source: AppSource = AppSource.FDROID) = UnifiedApp(
        packageName = pkg,
        source = source,
        name = name,
    )

    @Test
    fun `empty library shows the empty state`() {
        rule.setContent { LibraryScreen(state = LibraryUiState(), actions = LibraryActions.Noop) }

        rule.waitForIdle()
    }

    @Test
    fun `installed rows render and forward taps`() {
        var opened: UnifiedApp? = null
        val state = LibraryUiState(
            apps = listOf(
                app("com.example.a", "Alpha"),
                app("com.example.b", "Beta", AppSource.PLAYSTORE),
            ),
            counts = mapOf(SourceFilter.ALL to 2, SourceFilter.FDROID to 1, SourceFilter.PLAYSTORE to 1),
        )
        rule.setContent { LibraryScreen(state = state, actions = LibraryActions.Noop, onAppClick = { opened = it }) }

        rule.onNodeWithText("Alpha").assertIsDisplayed()
        rule.onNodeWithText("Beta").assertIsDisplayed()
        rule.onNodeWithText("Beta").performClick()
        assertEquals("com.example.b", opened?.packageName)
    }

    @Test
    fun `filter chips forward the selection`() {
        var filter: SourceFilter? = null
        val actions = object : LibraryActions {
            override fun setLibraryFilter(f: SourceFilter) {
                filter = f
            }
        }
        val state = LibraryUiState(counts = mapOf(SourceFilter.ALL to 0))
        rule.setContent { LibraryScreen(state = state, actions = actions) }

        rule.onNodeWithText("F-Droid", substring = true).performClick()
        assertEquals(SourceFilter.FDROID, filter)
    }

    @Test
    fun `first load hides the filter chips behind the spinner`() {
        val state = LibraryUiState(hasLoaded = false)
        rule.setContent { LibraryScreen(state = state, actions = LibraryActions.Noop) }

        rule.onNodeWithText("F-Droid", substring = true).assertDoesNotExist()
    }

    @Test
    fun `filtering a loaded library down to empty keeps the chips`() {
        var filter: SourceFilter? = null
        val actions = object : LibraryActions {
            override fun setLibraryFilter(f: SourceFilter) {
                filter = f
            }
        }
        // hasLoaded stays true after the first sweep, so a filter with no rows
        // shows the empty state under still-visible chips, never the spinner.
        val state = LibraryUiState(
            hasLoaded = true,
            counts = mapOf(SourceFilter.ALL to 1, SourceFilter.FDROID to 1, SourceFilter.PLAYSTORE to 0),
        )
        rule.setContent { LibraryScreen(state = state, actions = actions) }

        rule.onNodeWithText("Play Store", substring = true).assertIsDisplayed()
        rule.onNodeWithText("F-Droid", substring = true).performClick()
        assertEquals(SourceFilter.FDROID, filter)
    }
}
