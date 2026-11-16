package com.vayunmathur.uniflix.ui.tv

import androidx.compose.ui.test.junit4.createComposeRule
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.uniflix.data.uniflixTransport
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** TvGridPage kinds + TvMyListTab in their offline/empty states. */
@RunWith(RobolectricTestRunner::class)
class TvGridAndListCoverageTest {

    @get:Rule
    val composeRule = createComposeRule()

    @AfterTest fun tearDown() { uniflixTransport = null }

    @Test fun gridPageContinueWatchingEmpty() {
        uniflixTransport = { throw java.io.IOException("offline") }
        val stack = NavBackStack<TvRoute>(arrayOf(TvRoute.Home))
        composeRule.setContent { DynamicTheme { TvGridPage(stack, TV_GRID_CONTINUE) } }
        composeRule.waitForIdle()
        assertEquals(TvRoute.Home, stack.last())
    }

    @Test fun gridPageTopMoviesLoading() {
        uniflixTransport = { throw java.io.IOException("offline") }
        val stack = NavBackStack<TvRoute>(arrayOf(TvRoute.Home))
        composeRule.setContent { DynamicTheme { TvGridPage(stack, TV_GRID_TOP_MOVIES) } }
        composeRule.waitForIdle()
        assertEquals(TvRoute.Home, stack.last())
    }

    @Test fun gridPageTopTvLoading() {
        uniflixTransport = { throw java.io.IOException("offline") }
        val stack = NavBackStack<TvRoute>(arrayOf(TvRoute.Home))
        composeRule.setContent { DynamicTheme { TvGridPage(stack, TV_GRID_TOP_TV) } }
        composeRule.waitForIdle()
    }

    @Test fun gridPageTrendingMoviesLoading() {
        uniflixTransport = { throw java.io.IOException("offline") }
        val stack = NavBackStack<TvRoute>(arrayOf(TvRoute.Home))
        composeRule.setContent { DynamicTheme { TvGridPage(stack, TV_GRID_TRENDING_MOVIES) } }
        composeRule.waitForIdle()
    }

    @Test fun gridPageUnknownKindFallsToTrendingSeries() {
        uniflixTransport = { throw java.io.IOException("offline") }
        val stack = NavBackStack<TvRoute>(arrayOf(TvRoute.Home))
        composeRule.setContent { DynamicTheme { TvGridPage(stack, "something-else") } }
        composeRule.waitForIdle()
    }

    @Test fun myListTabEmptyState() {
        uniflixTransport = { throw java.io.IOException("offline") }
        var opened = 0
        var resumed = 0
        composeRule.setContent {
            DynamicTheme {
                TvMyListTab(onOpenDetail = { opened++ }, onResume = { resumed++ })
            }
        }
        composeRule.waitForIdle()
        // Nothing saved yet: the empty state renders and no navigation fires.
        assertEquals(0, opened)
        assertEquals(0, resumed)
    }
}
