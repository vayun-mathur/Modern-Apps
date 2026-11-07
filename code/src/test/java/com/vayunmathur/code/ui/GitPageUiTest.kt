package com.vayunmathur.code.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.code.Route
import com.vayunmathur.code.util.EditorViewModel
import com.vayunmathur.code.util.clearGitMessage
import com.vayunmathur.library.util.NavBackStack
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test

/** Robolectric Compose tests for [GitPage]: no-repo empty state, credentials, busy/message. */
@RunWith(AndroidJUnit4::class)
class GitPageUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun vm() = EditorViewModel(ApplicationProvider.getApplicationContext())

    private fun backStack() = NavBackStack(arrayOf<Route>(Route.Git))

    @Test
    fun noRepoStateOffersInitAndClone() {
        rule.setContent { GitPage(vm(), backStack()) }
        rule.onNodeWithText("Initialize repository").assertIsDisplayed()
        rule.onNodeWithText("Clone").assertIsDisplayed()
        rule.onNodeWithText("This folder is not a git repository.").assertIsDisplayed()
    }

    @Test
    fun credentialsSectionRenders() {
        rule.setContent { GitPage(vm(), backStack()) }
        // Credentials fields are part of the page regardless of repo state.
        rule.onNodeWithText("Username", substring = true).assertExists()
    }

    @Test
    fun messageRowRendersAndClears() {
        val v = vm()
        v.gitMessage = "boom-failure-marker"
        rule.setContent { GitPage(v, backStack()) }
        rule.onNodeWithText("boom-failure-marker").assertIsDisplayed()
        v.clearGitMessage()
        // State updated; recomposition drops the row.
        rule.waitForIdle()
        rule.onNodeWithText("boom-failure-marker").assertDoesNotExist()
    }

    @Test
    fun busyIndicatorWhenGitBusy() {
        val v = vm()
        v.gitBusy = true
        rule.setContent { GitPage(v, backStack()) }
        // Page still renders its sections while busy.
        rule.onNodeWithText("Clone", substring = true).assertIsDisplayed()
    }
}
