package com.vayunmathur.email.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import com.vayunmathur.testsupport.awaitSemantics
import org.robolectric.annotation.Config

/**
 * Renders the real [EmlViewerScreen] with an unopenable URI: the parse fails
 * on IO, so the error branch (message + retry) must appear instead of a
 * spinner or a blank page. A broken error path leaves users staring at an
 * infinite loader for a corrupt file. The parseable-file success path is
 * in [EmlViewerSuccessTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmlViewerScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun `unparseable uri surfaces the error state with retry`() {
        composeRule.setContent {
            EmlViewerScreen(uriString = "content://invalid/nonexistent.eml", onBack = {})
        }
        // Terminal semantics wait with the main looper idled on each
        // check (shared helper): without that pump the IO parse's resume
        // sat queued past a plain waitUntil under load.
        composeRule.awaitSemantics("eml error") {
            composeRule.onAllNodesWithText("Couldn't open this .eml file", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Couldn't open this .eml file", substring = true).assertIsDisplayed()
        composeRule.onNodeWithText("Retry").assertIsDisplayed()
    }

    @Test
    fun `retry re-runs the parse and stays on the error state`() {
        composeRule.setContent {
            EmlViewerScreen(uriString = "content://invalid/nonexistent.eml", onBack = {})
        }
        composeRule.awaitSemantics("retry button") {
            composeRule.onAllNodesWithText("Retry").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Retry").performClick()
        // Terminal semantics wait with the main looper idled on each
        // check (shared helper): without that pump the IO parse's resume
        // sat queued past a plain waitUntil under load.
        composeRule.awaitSemantics("eml error") {
            composeRule.onAllNodesWithText("Couldn't open this .eml file", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Couldn't open this .eml file", substring = true).assertIsDisplayed()
    }

    @Test
    fun `back callback fires from the scaffold`() {
        var backs = 0
        composeRule.setContent {
            EmlViewerScreen(uriString = "content://invalid/nonexistent.eml", onBack = { backs++ })
        }
        composeRule.awaitSemantics("retry button") {
            composeRule.onAllNodesWithText("Retry").fetchSemanticsNodes().isNotEmpty()
        }
        // The scaffold back button carries the "Back" content description.
        composeRule.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }
}
