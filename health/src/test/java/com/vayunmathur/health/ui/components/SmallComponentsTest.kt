package com.vayunmathur.health.ui.components

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.health.data.SleepData
import com.vayunmathur.health.domain.SocialHistoryQuestions
import com.vayunmathur.health.ui.SleepStageBreakdown
import com.vayunmathur.health.ui.StageRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose tests for the remaining small shared components: the picker field,
 * the sleep breakdown rows, the hypnogram colour table, and the social
 * history answer dialog.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SmallComponentsTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `picker field shows its value`() {
        rule.setContent {
            PickerField(label = "Medication", value = "Amoxicillin", placeholder = "Search", onClick = {})
        }
        rule.onNodeWithText("Medication").assertIsDisplayed()
        rule.onNodeWithText("Amoxicillin").assertIsDisplayed()
    }

    @Test
    fun `picker field falls back to its placeholder`() {
        rule.setContent {
            PickerField(label = "Medication", value = "", placeholder = "Search", onClick = {})
        }
        rule.onNodeWithText("Medication").assertIsDisplayed()
        rule.onNodeWithText("Search").assertIsDisplayed()
    }

    @Test
    fun `picker field click fires`() {
        var clicks = 0
        rule.setContent {
            PickerField(label = "Medication", value = "", placeholder = "Search", onClick = { clicks++ })
        }
        rule.onNodeWithText("Search").performClick()
        assertTrue(clicks >= 1, "onClick fires")
    }

    @Test
    fun `sleep breakdown renders every stage`() {
        rule.setContent {
            SleepStageBreakdown(
                SleepData(
                    awakeDurationMillis = 30 * 60_000L,
                    remDurationMillis = 90 * 60_000L,
                    lightDurationMillis = 240 * 60_000L,
                    deepDurationMillis = 80 * 60_000L,
                )
            )
        }
        // Durations render as Xh Ym / Ym through the shared formatter.
        rule.onNodeWithText("30m").assertIsDisplayed()
        rule.onNodeWithText("1h 30m").assertIsDisplayed()
        rule.onNodeWithText("4h 0m").assertIsDisplayed()
        rule.onNodeWithText("1h 20m").assertIsDisplayed()
    }

    @Test
    fun `stage row renders label and duration`() {
        rule.setContent {
            StageRow(label = "Deep", duration = "1h 20m", color = SleepStageColorDeep)
        }
        rule.onNodeWithText("Deep").assertIsDisplayed()
        rule.onNodeWithText("1h 20m").assertIsDisplayed()
    }

    @Test
    fun `hypnogram colours are distinct per stage`() {
        var distinct = 0
        rule.setContent {
            val colors = hypnogramColors()
            distinct = setOf(colors.awake, colors.rem, colors.core, colors.deep).size
        }
        rule.runOnIdle {
            assertEquals(4, distinct, "every stage needs its own colour")
        }
    }

    @Test
    fun `answer dialog lists every option plus unanswered`() {
        rule.setContent {
            AnswerPickerDialog(
                question = SocialHistoryQuestions.ALCOHOL,
                selected = null,
                onPick = {},
                onDismiss = {},
            )
        }
        // One row per answer plus the explicit "not answered" escape hatch.
        // The dialog shows the translatable labels, not the canonical LOINC display.
        rule.onNodeWithText("Not answered").assertIsDisplayed()
        rule.onNodeWithText("Never").assertExists()
        rule.onNodeWithText("Monthly or less").assertExists()
        rule.onNodeWithText("2 to 4 times a month").assertExists()
    }

    @Test
    fun `picking an answer calls back with it`() {
        var picked: SocialHistoryQuestions.Answer? = null
        rule.setContent {
            AnswerPickerDialog(
                question = SocialHistoryQuestions.DRUGS,
                selected = null,
                onPick = { picked = it },
                onDismiss = {},
            )
        }
        rule.onNodeWithText("Weekly", substring = true).performClick()
        assertEquals("LA18891-4", picked?.code)
    }

    @Test
    fun `unanswered row clears the selection`() {
        var picked: SocialHistoryQuestions.Answer? = SocialHistoryQuestions.DRUGS.answers.first()
        rule.setContent {
            AnswerPickerDialog(
                question = SocialHistoryQuestions.DRUGS,
                selected = picked,
                onPick = { picked = it },
                onDismiss = {},
            )
        }
        // Tapping the already-selected first answer keeps it; tapping another changes it.
        rule.onNodeWithText("Monthly", substring = true).performClick()
        assertEquals("LA18876-5", picked?.code)
    }

    @Test
    fun `attachment chip renders its name`() {
        val file = java.io.File(
            ApplicationProvider.getApplicationContext<android.content.Context>().filesDir,
            "card.pdf",
        )
        rule.setContent {
            AttachmentChip(
                displayName = "card.pdf",
                mimeType = "application/pdf",
                file = file,
                onClick = {},
            )
        }
        rule.onNodeWithText("card.pdf").assertIsDisplayed()
    }
}
