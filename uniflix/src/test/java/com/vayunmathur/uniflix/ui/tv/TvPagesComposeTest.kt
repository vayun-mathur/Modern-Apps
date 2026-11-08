package com.vayunmathur.uniflix.ui.tv

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.uniflix.data.uniflixTransport
import com.vayunmathur.uniflix.domain.MediaType
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** TV tab/detail pages in their offline (empty/failed) states. */
@RunWith(RobolectricTestRunner::class)
class TvPagesComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @AfterTest fun tearDown() { uniflixTransport = null }

    @Test fun browseTabRendersOffline() {
        composeRule.setContent {
            DynamicTheme { TvBrowseTab(mediaType = MediaType.MOVIE, onOpenDetail = {}) }
        }
        // Catalog is unreachable, so the tab settles into its empty/loading shell
        // without crashing; reaching idle is the assertion.
        composeRule.waitForIdle()
    }

    @Test fun browseSeriesTabRendersOffline() {
        composeRule.setContent {
            DynamicTheme { TvBrowseTab(mediaType = MediaType.TV, onOpenDetail = {}) }
        }
        composeRule.waitForIdle()
    }

    @Test fun searchTabRendersOffline() {
        composeRule.setContent {
            DynamicTheme { TvSearchTab(onOpenDetail = {}) }
        }
        composeRule.waitForIdle()
    }

    @Test fun detailPageShowsFailureAndBackPops() {
        uniflixTransport = { throw java.io.IOException("offline test double") }
        val stack = NavBackStack<TvRoute>(arrayOf(TvRoute.Main(), TvRoute.Detail("movie", 42)))
        composeRule.setContent {
            DynamicTheme {
                TvDetailPage(backStack = stack, mediaType = "movie", tmdbId = 42)
            }
        }
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithText("Detail failed to load.").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Detail failed to load.").assertIsDisplayed()
        composeRule.onNode(hasClickAction())
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
        assertEquals(TvRoute.Main(), stack.backStack.last())
        assertEquals(1, stack.backStack.size)
    }
}
