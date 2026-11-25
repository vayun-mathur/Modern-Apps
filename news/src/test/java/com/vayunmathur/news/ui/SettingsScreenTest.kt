package com.vayunmathur.news.ui

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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

/** Settings holds the Local news location setting; tapping it navigates to the picker. */
@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {
    @get:Rule val rule = createComposeRule()

    @Test fun localLocationSettingRowNavigatesToPicker() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        seedNewsDb(context)
        val repository = NewsRepository(context, fetch = { throw IOException("offline") }, serverFeedFetch = { throw IOException("offline") })
        val viewModel = NewsViewModel(context, repository, NewsPrefs(context))
        var opened = 0
        rule.setContent {
            SettingsScreen(viewModel = viewModel, onBack = {}, onOpenLocalLocation = { opened++ })
        }
        rule.waitForIdle()
        rule.onNodeWithText("Settings").assertIsDisplayed()
        rule.onNodeWithText("Local news location").assertIsDisplayed()
        rule.onNodeWithText("Local news location").performClick()
        assertEquals(1, opened)
    }
}
