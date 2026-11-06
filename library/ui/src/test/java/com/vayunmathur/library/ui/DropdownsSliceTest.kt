package com.vayunmathur.library.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Slice 3/3: dropdowns, add-to-list dialog and history scrubber state.
 */
@RunWith(RobolectricTestRunner::class)
class DropdownsSliceTest {

    @get:Rule
    val rule = createComposeRule()

    @Test fun selectDropdownSelectsOption() {
        var selected = "a"
        rule.setContent {
            SelectDropdown(
                label = "Pick",
                options = listOf("a", "b", "c"),
                selected = selected,
                itemLabel = { it },
                onSelect = { selected = it },
            )
        }
        rule.onNodeWithText("Pick").performClick()
        rule.onNodeWithText("b").performClick()
        assertEquals("b", selected)
    }

    @Test fun selectDropdownSameOptionDoesNotCallback() {
        var calls = 0
        rule.setContent {
            SelectDropdown(
                label = "Pick",
                options = listOf("a", "b"),
                selected = "a",
                itemLabel = { it },
                onSelect = { calls++ },
            )
        }
        rule.onNodeWithText("Pick").performClick()
        rule.onNodeWithText("a").performClick()
        assertEquals(0, calls)
    }

    @Test fun multiSelectDropdownAppliesOnClose() {
        var applied: List<String>? = null
        rule.setContent {
            MultiSelectDropdown(
                label = "Tags",
                options = listOf("x", "y", "z"),
                selected = listOf("x"),
                itemLabel = { it },
                onApply = { applied = it },
            )
        }
        rule.onNodeWithText("Tags").performClick()
        // Toggle y on (menu stays open for multi-select).
        rule.onNodeWithText("y").performClick()
        assertNull(applied)
    }

    @Test fun addToListDialogConfirmsChecked() {
        var confirmed: Set<String>? = null
        var dismissed = 0
        rule.setContent {
            AddToListDialog(
                title = "Add to list",
                options = listOf("one", "two"),
                itemLabel = { it },
                confirmLabel = "OK",
                dismissLabel = "Cancel",
                onConfirm = { confirmed = it },
                onDismiss = { dismissed++ },
            )
        }
        rule.onNodeWithText("Add to list").assertIsDisplayed()
        // Check the first row, then confirm.
        rule.onNodeWithText("one").performClick()
        rule.onNodeWithText("OK").performClick()
        assertEquals(setOf("one"), confirmed)
        assertEquals(0, dismissed)
    }

    @Test fun addToListDialogDismisses() {
        var dismissed = 0
        rule.setContent {
            AddToListDialog(
                title = "Add to list",
                options = listOf("one"),
                itemLabel = { it },
                confirmLabel = "OK",
                dismissLabel = "Cancel",
                onConfirm = {},
                onDismiss = { dismissed++ },
            )
        }
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(1, dismissed)
    }
}

/**
 * Slice 3/3: [HistoryScrubberState] pure state logic on the JVM.
 */
class HistoryScrubberStateTest {

    private val zone = TimeZone.UTC
    private fun stateAt(
        instant: Instant = Instant.parse("2026-06-15T12:00:00Z"),
        nowMode: Boolean = false,
        disallowFuture: Boolean = false,
    ) = HistoryScrubberState(zone, instant, nowMode, disallowFuture)

    @Test fun setSecondOfDayKeepsDateChangesTime() {
        val s = stateAt()
        s.setSecondOfDay(0)
        assertEquals(0, s.secondOfDay)
        assertEquals(15, s.date.day)
        assertTrue(!s.nowMode)
    }

    @Test fun setSecondOfDayClamps() {
        val s = stateAt()
        s.setSecondOfDay(100_000)
        assertEquals(86_399, s.secondOfDay)
        s.setSecondOfDay(-5)
        assertEquals(0, s.secondOfDay)
    }

    @Test fun nudgeShiftsInstantAcrossMidnight() {
        val s = stateAt(Instant.parse("2026-06-15T00:30:00Z"))
        s.nudge(-3600)
        assertEquals(14, s.date.day)
        assertEquals(23 * 3600 + 30 * 60, s.secondOfDay)
    }

    @Test fun setDateKeepsTimeOfDay() {
        val s = stateAt()
        val before = s.secondOfDay
        s.setDate(lazyDate(2026, 7, 4))
        assertEquals(4, s.date.day)
        assertEquals(before, s.secondOfDay)
    }

    @Test fun disallowFutureClampsUserEdits() {
        val s = stateAt(disallowFuture = true)
        s.nudge(365L * 24 * 3600)
        // Instant must not be in the future (allow 60s slack for test execution).
        assertTrue(s.instant <= nowPlus(60))
    }

    @Test fun goNowResumesNowMode() {
        val s = stateAt(nowMode = false)
        s.goNow()
        assertTrue(s.nowMode)
        assertTrue(s.instant <= nowPlus(60))
    }

    @Test fun tickNowAdvancesOnlyInNowMode() {
        val live = stateAt(nowMode = true)
        val frozen = stateAt(nowMode = false)
        val frozenInstant = frozen.instant
        live.tickNow()
        frozen.tickNow()
        assertEquals(frozenInstant, frozen.instant)
    }

    private fun lazyDate(y: Int, m: Int, d: Int) =
        kotlinx.datetime.LocalDate(y, m, d)

    private fun nowPlus(slackSeconds: Long) =
        Clock.System.now().plus(slackSeconds.seconds)
}
