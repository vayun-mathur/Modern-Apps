package com.vayunmathur.news.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.news.data.NewsPrefs
import com.vayunmathur.news.data.NewsRepository
import com.vayunmathur.news.platform.NewsViewModel
import com.vayunmathur.news.seedNewsDb
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Sources is now a read-only directory: source names and languages are
 * listed, there are no enable/disable switches anywhere on the screen.
 */
@RunWith(RobolectricTestRunner::class)
class SourcesScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun directoryListsSourcesWithoutToggles() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        seedNewsDb(context)
        val repository = NewsRepository(context, fetch = { throw IOException("offline") })
        val viewModel = NewsViewModel(context, repository, NewsPrefs(context))
        rule.setContent { SourcesScreen(viewModel = viewModel, onBack = {}) }
        rule.waitForIdle()
        rule.onNodeWithText("Sources").assertIsDisplayed()
        rule.onNodeWithText("BBC News").assertIsDisplayed()
        // No toggleable (switch) semantics on a read-only directory screen.
        assertEquals(
            0,
            rule.onAllNodes(androidx.compose.ui.test.isToggleable()).fetchSemanticsNodes().size,
        )
    }
}
