package com.vayunmathur.health.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.vayunmathur.health.data.Record
import com.vayunmathur.health.data.RecordType
import com.vayunmathur.health.data.SleepData
import com.vayunmathur.health.data.SleepStage
import kotlin.test.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Compose tests for the sleep detail atoms: the summary header and the
 * hypnogram graph.
 *
 * The page itself is ViewModel-bound (covered on-device); these stateless
 * pieces carry the number formatting and stage rendering worth pinning here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SleepDetailsComponentsTest {

    @get:Rule
    val rule = createComposeRule()

    private fun sleepRecord(stagesJson: String? = null) = Record(
        id = "sleep-1",
        index = 0,
        type = RecordType.Sleep,
        startTime = java.time.Instant.parse("2026-09-15T22:30:00Z"),
        endTime = java.time.Instant.parse("2026-09-16T06:30:00Z"),
        value = 8.0,
        sleepData = SleepData(
            awakeDurationMillis = 30 * 60_000L,
            remDurationMillis = 90 * 60_000L,
            lightDurationMillis = 240 * 60_000L,
            deepDurationMillis = 120 * 60_000L,
            stagesJson = stagesJson,
        ),
    )

    @Test
    fun `summary header shows total and time range`() {
        rule.setContent { SleepSummaryHeader(sleepRecord()) }
        rule.onNodeWithText("8h 0m").assertIsDisplayed()
    }

    @Test
    fun `stage graph renders the four stage labels`() {
        val stages = listOf(
            SleepStage(0L, 1_800_000L, 1),
            SleepStage(1_800_000L, 5_400_000L, 2),
            SleepStage(5_400_000L, 12_600_000L, 3),
            SleepStage(12_600_000L, 28_800_000L, 4),
        )
        rule.setContent { SleepStageGraph(sleepRecord(Json.encodeToString(stages))) }
        rule.onNodeWithText("Awake").assertIsDisplayed()
        rule.onNodeWithText("REM").assertIsDisplayed()
        rule.onNodeWithText("Core").assertIsDisplayed()
        rule.onNodeWithText("Deep").assertIsDisplayed()
    }

    @Test
    fun `stage graph without stages renders nothing`() {
        // No stagesJson: early return, no crash.
        rule.setContent { SleepStageGraph(sleepRecord()) }
        rule.waitForIdle()
    }
}
