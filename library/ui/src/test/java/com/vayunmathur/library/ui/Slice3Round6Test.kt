package com.vayunmathur.library.ui

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.time.Instant

/** Slice 3/3 round 6: history scrubber state transitions. */
@RunWith(RobolectricTestRunner::class)
class Slice3Round6Test {
    @get:Rule val rule = createComposeRule()

    private fun stateAt(iso: String, nowMode: Boolean = false) = HistoryScrubberState(
        timeZone = TimeZone.UTC,
        initialInstant = Instant.parse(iso),
        initialNowMode = nowMode,
        disallowFuture = false,
    )

    @Test fun nudgeShiftsInstantAndLeavesNowMode() {
        val s = stateAt("2026-06-15T12:00:00Z", nowMode = true)
        s.nudge(-3600)
        assertEquals(Instant.parse("2026-06-15T11:00:00Z"), s.instant)
        assertFalse(s.nowMode)
        s.nudge(7200)
        assertEquals(Instant.parse("2026-06-15T13:00:00Z"), s.instant)
    }

    @Test fun setSecondOfDayClampsAndKeepsDate() {
        val s = stateAt("2026-06-15T12:00:00Z")
        s.setSecondOfDay(0)
        assertEquals(0, s.secondOfDay)
        assertEquals(LocalDate(2026, 6, 15), s.date)
        s.setSecondOfDay(999_999)
        assertEquals(86_399, s.secondOfDay)
        s.setSecondOfDay(-5)
        assertEquals(0, s.secondOfDay)
    }

    @Test fun setDateKeepsTimeOfDay() {
        val s = stateAt("2026-06-15T12:30:45Z")
        s.setDate(LocalDate(2026, 1, 2))
        assertEquals(LocalDate(2026, 1, 2), s.date)
        assertEquals(12 * 3600 + 30 * 60 + 45, s.secondOfDay)
        assertFalse(s.nowMode)
    }

    @Test fun goNowEntersNowModeNearPresent() {
        val s = stateAt("2020-01-01T00:00:00Z")
        s.goNow()
        assertTrue(s.nowMode)
        assertTrue(s.instant > Instant.parse("2024-01-01T00:00:00Z"))
    }

    @Test fun tickNowNoOpsOutsideNowMode() {
        val s = stateAt("2026-06-15T12:00:00Z", nowMode = false)
        val before = s.instant
        s.tickNow()
        assertEquals(before, s.instant)
    }

    @Test fun scrubberCardStepButtonNudgesState() {
        lateinit var state: HistoryScrubberState
        rule.setContent {
            androidx.compose.foundation.layout.Box {
                state = rememberHistoryScrubberState(
                    initialInstant = Instant.parse("2026-06-15T12:00:00Z"),
                    initialNowMode = false,
                    timeZone = TimeZone.UTC,
                )
                HistoryScrubberCard(
                    state = state,
                    steps = listOf(HistoryStep("-1h", -3600)),
                    onDateChipClick = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("-1h").performClick()
        rule.waitForIdle()
        assertEquals(Instant.parse("2026-06-15T11:00:00Z"), state.instant)
    }

    @Test fun topAppBarOverlayTitleOnlyRenders() {
        rule.setContent {
            TopAppBarOverlay { Text("only-title") }
        }
        rule.onNodeWithText("only-title").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithText("missing").fetchSemanticsNodes().isEmpty())
    }
}
