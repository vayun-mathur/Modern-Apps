package com.vayunmathur.library.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ErrorState error-correlation id: a throwable-backed state logs via
 * `Log.errorWithId` and renders the minted id small under the message; an
 * authored-string state (domain message, nothing to correlate) renders none.
 */
@RunWith(RobolectricTestRunner::class)
class ErrorStateErrorIdTest {

    @get:Rule
    val rule = createComposeRule()

    @Test fun throwableErrorStateRendersErrorId() {
        rule.setContent {
            ErrorState(
                title = "Broken",
                message = "Nope",
                error = IOException("raw secret https://secret.example/x"),
            )
        }
        rule.onNodeWithText("Broken").assertIsDisplayed()
        rule.onNodeWithText("Nope").assertIsDisplayed()
        // Exactly one id line, formatted 'Error ID: xxxxxxxx' (8 lowercase hex).
        val idNodes = rule.onAllNodesWithText("Error ID:", substring = true)
            .fetchSemanticsNodes()
        assertEquals(1, idNodes.size)
        val idText = idNodes.single().config[SemanticsProperties.Text]
            .joinToString("") { it.text }
        assertTrue(
            Regex("""Error ID: [0-9a-f]{8}""").matches(idText),
            "expected 'Error ID: <8 hex>', was '$idText'",
        )
        // Raw exception text never reaches the UI.
        assertTrue(
            rule.onAllNodesWithText("secret", substring = true).fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test fun throwableErrorStateWithoutMessageUsesFriendlyMessageAndId() {
        rule.setContent {
            ErrorState(title = "Broken", error = IOException("raw secret"))
        }
        rule.onNodeWithText("Broken").assertIsDisplayed()
        // Friendly (offline/IO) wording, not the raw exception text.
        rule.onAllNodes(hasText("offline", substring = true)).fetchSemanticsNodes()
            .also { assertTrue(it.isNotEmpty(), "expected friendly offline message") }
        assertEquals(
            1,
            rule.onAllNodesWithText("Error ID:", substring = true).fetchSemanticsNodes().size,
        )
    }

    @Test fun authoredStringErrorStateRendersNoErrorId() {
        rule.setContent {
            ErrorState(title = "Broken", message = "That item no longer exists")
        }
        rule.onNodeWithText("Broken").assertIsDisplayed()
        rule.onNodeWithText("That item no longer exists").assertIsDisplayed()
        assertTrue(
            rule.onAllNodesWithText("Error ID:", substring = true).fetchSemanticsNodes().isEmpty(),
            "authored-string ErrorState must not render an error id",
        )
    }
}
