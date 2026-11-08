package com.vayunmathur.library.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Slice 3/3 round 14: swipe actions, back-stack scaffolds, reminder picker. */
@RunWith(RobolectricTestRunner::class)
class Slice3Round14Test {

    @get:Rule val rule = createComposeRule()

    // ---- SwipeActionsBox ----

    private fun setSwipeRow(
        onStartToEnd: () -> Unit = {},
        onEndToStart: () -> Unit = {},
        enableStartToEnd: Boolean = true,
        enableEndToStart: Boolean = true,
    ) {
        rule.setContent {
            SwipeActionsBox(
                onStartToEnd = onStartToEnd,
                onEndToStart = onEndToStart,
                enableStartToEnd = enableStartToEnd,
                enableEndToStart = enableEndToStart,
                background = { Text("bg-$it") },
            ) {
                // Full-width touch target: swiping the narrow Text alone would
                // travel less than the 40%-of-width firing threshold.
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("swipe-area"),
                ) { Text("swipe-row") }
            }
        }
        rule.waitForIdle()
    }

    @Test fun swipeRightPastThresholdFiresStartToEnd() {
        var fired = 0
        setSwipeRow(onStartToEnd = { fired++ })
        rule.onNodeWithTag("swipe-area").performTouchInput { swipeRight() }
        rule.waitForIdle()
        assertEquals(1, fired)
    }

    @Test fun swipeLeftPastThresholdFiresEndToStart() {
        var fired = 0
        setSwipeRow(onEndToStart = { fired++ })
        rule.onNodeWithTag("swipe-area").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertEquals(1, fired)
    }

    @Test fun swipeOnDisabledSideDoesNotFire() {
        var fired = 0
        setSwipeRow(onEndToStart = { fired++ }, enableEndToStart = false)
        rule.onNodeWithTag("swipe-area").performTouchInput { swipeLeft() }
        rule.waitForIdle()
        assertEquals(0, fired)
    }

    @Test fun verticalSwipeDoesNotClaimGesture() {
        var fired = 0
        setSwipeRow(onStartToEnd = { fired++ }, onEndToStart = { fired++ })
        rule.onNodeWithTag("swipe-area").performTouchInput { swipeDown() }
        rule.waitForIdle()
        assertEquals(0, fired)
        rule.onNodeWithText("swipe-row").assertIsDisplayed()
    }

    // ---- Back-stack scaffold overloads ----

    private object SHome : com.vayunmathur.library.util.NavKey
    private object SDetail : com.vayunmathur.library.util.NavKey

    @Test fun appScaffoldBackStackBackButtonPops() {
        val stack = com.vayunmathur.library.util.NavBackStack(
            arrayOf<com.vayunmathur.library.util.NavKey>(SHome, SDetail),
        )
        rule.setContent {
            AppScaffold(
                title = "Stacked",
                backStack = stack,
                subtitle = "stack subtitle",
                size = AppBarSize.MediumFlexible,
                scrollBehavior = appBarScrollBehavior(),
            ) { Text("stack-body") }
        }
        rule.onNodeWithText("Stacked").assertIsDisplayed()
        rule.onNodeWithText("stack subtitle").assertIsDisplayed()
        rule.onNodeWithText("stack-body").assertIsDisplayed()
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitForIdle()
        assertEquals(listOf(SHome), stack.backStack)
    }

    @Test fun setupScaffoldBackStackBackButtonPops() {
        val stack = com.vayunmathur.library.util.NavBackStack(
            arrayOf<com.vayunmathur.library.util.NavKey>(SHome, SDetail),
        )
        var primary = 0
        rule.setContent {
            SetupScaffold(
                title = "Set up",
                backStack = stack,
                subtitle = "one step",
                primaryAction = SetupAction("Continue", onClick = { primary++ }),
                scrollBehavior = appBarScrollBehavior(),
            ) { Text("setup-body") }
        }
        rule.onNodeWithText("Set up").assertIsDisplayed()
        rule.onNodeWithText("setup-body").assertIsDisplayed()
        rule.onNodeWithText("Continue").performClick()
        assertEquals(1, primary)
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitForIdle()
        assertEquals(listOf(SHome), stack.backStack)
    }

    @Test fun setupActionDisabledButtonDoesNotFire() {
        var fired = 0
        rule.setContent {
            SetupScaffold(
                title = "Set up",
                primaryAction = SetupAction(
                    "Continue",
                    onClick = { fired++ },
                    enabled = false,
                ),
                secondaryAction = SetupAction("Skip", onClick = { fired++ }),
                scrollBehavior = appBarScrollBehavior(),
            ) { Text("setup-body") }
        }
        rule.onNodeWithText("Continue").assertIsNotEnabled()
        rule.onNodeWithText("Skip").performClick()
        assertEquals(1, fired)
    }

    // ---- DailyReminderSettingsSection ----

    @Test fun reminderTimePickerSaveAndCancelFlows() {
        var enabledValue = true
        val times = mutableListOf<Pair<Int, Int>>()
        val toggles = mutableListOf<Boolean>()
        rule.setContent {
            DailyReminderSettingsSection(
                enabled = enabledValue,
                hour = 7,
                minute = 30,
                onEnabledChange = { toggles += it },
                onTimeChange = { h, m -> times += h to m },
            )
        }
        rule.onNodeWithText("Daily puzzle reminder").assertIsDisplayed()
        rule.onNodeWithText("07:30").assertIsDisplayed()

        // Cancel first: no time reported.
        rule.onNodeWithText("Reminder time").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertTrue(times.isEmpty())

        // Reopen and save: the picked (initial) time is reported.
        rule.onNodeWithText("Reminder time").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Save").performClick()
        rule.waitForIdle()
        assertEquals(listOf(7 to 30), times)
    }

    @Test fun reminderSwitchToggles() {
        val toggles = mutableListOf<Boolean>()
        rule.setContent {
            DailyReminderSettingsSection(
                enabled = false,
                hour = 21,
                minute = 5,
                onEnabledChange = { toggles += it },
                onTimeChange = { _, _ -> },
            )
        }
        rule.onNodeWithText("21:05").assertIsDisplayed()
        // The switch itself carries the toggle semantics.
        rule.onNode(
            androidx.compose.ui.test.hasAnyAncestor(
                androidx.compose.ui.test.hasText("Daily puzzle reminder"),
            ) and androidx.compose.ui.test.isToggleable(),
        ).performClick()
        assertEquals(listOf(true), toggles)
    }

    // ---- DateString default-argument overloads ----

    @Test fun instantDefaultOverloadsMatchExplicitDefaults() {
        val instant = Instant.fromEpochSeconds(1_735_916_700L)
        val zone = TimeZone.currentSystemDefault()
        val locale = Locale.getDefault()
        assertEquals(
            DateString.dateShort(instant, zone, locale),
            DateString.dateShort(instant),
        )
        assertEquals(
            DateString.dateLong(instant, zone, locale),
            DateString.dateLong(instant),
        )
        assertEquals(
            DateString.time(instant, is24Hour = true, zone, locale),
            DateString.time(instant, is24Hour = true),
        )
        assertEquals(
            DateString.dateTime(instant, is24Hour = false, zone, locale),
            DateString.dateTime(instant, is24Hour = false),
        )
        // LocalDate default-locale forms match explicit-default calls too.
        val date = LocalDate(2025, 1, 3)
        assertEquals(DateString.dateShort(date, locale), DateString.dateShort(date))
        assertEquals(DateString.dateLong(date, locale), DateString.dateLong(date))
        assertEquals(
            DateString.monthDayYear(date, locale),
            DateString.monthDayYear(date),
        )
        assertEquals(
            DateString.dateFieldOrder(locale),
            DateString.dateFieldOrder(),
        )
    }
}
