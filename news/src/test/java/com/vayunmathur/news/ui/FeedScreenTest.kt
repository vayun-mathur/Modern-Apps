package com.vayunmathur.news.ui

import android.app.Application
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.news.data.NewsPrefs
import com.vayunmathur.news.data.NewsRepository
import com.vayunmathur.news.platform.NewsViewModel
import com.vayunmathur.news.seedNewsDb
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowSystemClock
import java.io.IOException
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Feed screen chrome: the search field IS the top app bar (no "News"
 * title), saved/settings stay reachable as bar actions (there is no
 * Sources entry point — Settings is a separate surface), and typing/clearing
 * drives the same hoisted query state as before. The repository fetch
 * always fails, so the feed stays empty and no network is touched; the
 * 250ms debounce runs on the main looper, advanced via the shadow clock.
 */
@RunWith(RobolectricTestRunner::class)
class FeedScreenTest {
    @get:Rule val rule = createComposeRule()

    private fun viewModel(): NewsViewModel {
        val context = ApplicationProvider.getApplicationContext<Application>()
        seedNewsDb(context)
        val repository = NewsRepository(context, fetch = { throw IOException("offline") }, serverFeedFetch = { throw IOException("offline") })
        return NewsViewModel(context, repository, NewsPrefs(context))
    }

    private fun setFeed(
        onOpenSaved: () -> Unit = {},
        onOpenSettings: () -> Unit = {},
    ) {
        val viewModel = viewModel()
        rule.setContent {
            FeedScreen(
                viewModel = viewModel,
                onOpenArticle = {},
                onOpenSaved = onOpenSaved,
                onOpenSettings = onOpenSettings,
            )
        }
        rule.waitForIdle()
    }

    @Test fun searchBarIsTheTopAppBar_noNewsTitle() {
        setFeed()
        // The search field occupies the top bar; the "News" title is gone.
        rule.onNodeWithText("Search downloaded news").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("News").fetchSemanticsNodes().size)
        // Saved/Settings navigation remains reachable; no Sources
        // entry point remains anywhere on the feed.
        rule.onNodeWithContentDescription("Saved").assertIsDisplayed()
        rule.onNodeWithContentDescription("Settings").assertIsDisplayed()
        assertEquals(
            0,
            rule.onAllNodesWithText("Sources").fetchSemanticsNodes().size,
        )
        // No Latest / Top-stories mode switch exists any more.
        assertEquals(0, rule.onAllNodesWithText("Top stories").fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodesWithText("Latest").fetchSemanticsNodes().size)
    }

    @Test fun typingShowsClear_clearRestoresFeed() {
        setFeed()
        rule.onNodeWithText("Search downloaded news").performTextInput("gamma")
        rule.waitForIdle()
        // Clear affordance appears once the query is non-empty.
        rule.onNodeWithContentDescription("Close").assertIsDisplayed()
        // Let the 250ms debounce fire; results replace the feed content.
        ShadowSystemClock.advanceBy(Duration.ofMillis(300))
        org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()
        rule.waitForIdle()
        rule.onNodeWithText(
            "No downloaded articles match. Search covers titles and articles you have opened."
        ).assertIsDisplayed()
        // Clearing returns to feed mode: placeholder back, source chips back.
        // (Asserted this way, not via the empty-feed text: the shared test DB
        // may hold articles from repository tests in a full-suite run.)
        rule.onNodeWithContentDescription("Close").performClick()
        rule.waitForIdle()
        rule.waitUntil(timeoutMillis = 5_000) {
            rule.onAllNodesWithText("All").fetchSemanticsNodes().isNotEmpty()
        }
        rule.onNodeWithText("Search downloaded news").assertIsDisplayed()
        rule.onNodeWithText("All").assertIsDisplayed()
    }

    @Test fun topBarActionsNavigate() {
        var saved = 0
        var settings = 0
        setFeed(onOpenSaved = { saved++ }, onOpenSettings = { settings++ })
        rule.onNodeWithContentDescription("Saved").performClick()
        rule.onNodeWithContentDescription("Settings").performClick()
        assertEquals(1, saved)
        assertEquals(1, settings)
    }
}
