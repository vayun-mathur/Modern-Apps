package com.vayunmathur.parentalcontrols.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.parentalcontrols.data.AppRule
import com.vayunmathur.parentalcontrols.data.BedtimeSchedule
import com.vayunmathur.parentalcontrols.data.DowntimeSchedule
import com.vayunmathur.parentalcontrols.platform.Enforcer
import com.vayunmathur.parentalcontrols.platform.SupervisableApp
import com.vayunmathur.parentalcontrols.platform.SupervisionUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ParentalControlsScreensTest {

    @get:Rule
    val rule = createComposeRule()

    // ---- PIN gate / setup ----

    @Test
    fun pinGateFiltersNonDigitsAndUnlocks() {
        var verified: String? = null; var cancelled = false
        val actions = PinGateActions(onVerify = { verified = it; true }, onCancel = { cancelled = true })
        rule.setContent { DynamicTheme { PinGateScreen(error = null, lockedOut = false, actions = actions) } }
        rule.onNodeWithText("Parent PIN").assertIsDisplayed()
        rule.onNodeWithText("Enter the parent PIN to change these settings.").assertIsDisplayed()
        rule.onNodeWithText("PIN").performTextInput("12a34")
        rule.onNodeWithText("Unlock").performClick()
        assertEquals("1234", verified)
        rule.onNodeWithText("Cancel").performClick()
        assertTrue(cancelled)
    }

    @Test
    fun pinGateShortEntryCannotUnlockAndErrorShows() {
        var called = false
        val actions = PinGateActions(onVerify = { called = true; false }, onCancel = {})
        rule.setContent { DynamicTheme { PinGateScreen(error = "Wrong PIN. Try again.", lockedOut = false, actions = actions) } }
        rule.onNodeWithText("Wrong PIN. Try again.").assertIsDisplayed()
        rule.onNodeWithText("PIN").performTextInput("12")
        rule.onNodeWithText("Unlock").assertIsNotEnabled()
        assertTrue(!called)
    }

    @Test
    fun pinGateLockedOutDisablesEntry() {
        rule.setContent {
            DynamicTheme { PinGateScreen(error = "Too many tries. Try again later.", lockedOut = true, actions = PinGateActions({ true }, {})) }
        }
        rule.onNodeWithText("Too many tries. Try again later.").assertIsDisplayed()
        rule.onNodeWithText("Unlock").assertIsNotEnabled()
    }

    @Test
    fun pinSetupRequiresMatchingEntries() {
        var saved: String? = null
        val actions = PinSetupActions(onSet = { saved = it; true }, onCancel = {})
        rule.setContent { DynamicTheme { PinSetupScreen(actions) } }
        rule.onNodeWithText("Set parent PIN").assertIsDisplayed()
        rule.onNodeWithText("New PIN").performTextInput("1234")
        rule.onNodeWithText("Repeat PIN").performTextInput("9999")
        rule.onNodeWithText("PINs do not match.").assertIsDisplayed()
        rule.onNodeWithText("Save").assertIsNotEnabled()
        // Correct the confirmation: clear via replacement semantics of the field is not
        // available, so this test only pins the mismatch state; matching flow below.
        assertEquals(null, saved)
    }

    @Test
    fun pinSetupMatchingEntriesSave() {
        var saved: String? = null; var cancelled = false
        val actions = PinSetupActions(onSet = { saved = it; true }, onCancel = { cancelled = true })
        rule.setContent { DynamicTheme { PinSetupScreen(actions) } }
        rule.onNodeWithText("New PIN").performTextInput("2468")
        rule.onNodeWithText("Repeat PIN").performTextInput("2468")
        rule.onNodeWithText("Save").performClick()
        assertEquals("2468", saved)
        rule.onNodeWithText("Cancel").performClick()
        assertTrue(cancelled)
    }

    // ---- Lock screens ----

    @Test
    fun lockScreenBedtimeHasNoBonus() {
        var asked = false; var closed = false
        rule.setContent {
            DynamicTheme { LockScreen(Enforcer.BlockReason.Bedtime, "Game", LockActions({ asked = true }, { closed = true })) }
        }
        rule.onNodeWithText("Bedtime").assertIsDisplayed()
        rule.onNodeWithText("Game").assertIsDisplayed()
        rule.onNodeWithText("This app is unavailable during bedtime hours.").assertIsDisplayed()
        rule.onNodeWithText("Ask a parent for more time").assertDoesNotExist()
        rule.onNodeWithText("OK").performClick()
        assertTrue(closed)
        assertTrue(!asked)
    }

    @Test
    fun lockScreenAppLimitOffersBonus() {
        var asked = false
        rule.setContent {
            DynamicTheme { LockScreen(Enforcer.BlockReason.AppLimit, null, LockActions({ asked = true }, {})) }
        }
        rule.onNodeWithText("App limit reached").assertIsDisplayed()
        rule.onNodeWithText("Ask a parent for more time").performClick()
        assertTrue(asked)
    }

    @Test
    fun lockScreenOtherReasonsShowTheirCopy() {
        rule.setContent {
            DynamicTheme { LockScreen(Enforcer.BlockReason.SchoolTime, "Reader", LockActions({}, {})) }
        }
        rule.onNodeWithText("School time").assertIsDisplayed()
        rule.onNodeWithText("Reader").assertIsDisplayed()
    }

    // ---- Dashboard ----

    private fun dashboardActions(record: MutableList<String>) = DashboardActions(
        onSetControls = { record.add("controls:$it") },
        onOpenDailyLimit = { record.add("daily") },
        onOpenAppLimits = { record.add("apps") },
        onOpenDowntime = { record.add("downtime") },
        onOpenWebFilters = { record.add("web") },
        onOpenPin = { record.add("pin") },
    )

    @Test
    fun dashboardShowsSummariesAndNavigates() {
        val record = mutableListOf<String>()
        val state = SupervisionUiState(
            controlsEnabled = true,
            dailyLimitMinutes = 90,
            downtime = DowntimeSchedule(enabled = true, startMinute = 12 * 60, endMinute = 14 * 60),
            apps = listOf(SupervisableApp("com.a", "Alpha", AppRule("com.a", dailyLimitMinutes = 30))),
            loading = false,
        )
        rule.setContent { DynamicTheme { DashboardScreen(state, dashboardActions(record)) } }
        rule.onNodeWithText("Parental controls").assertIsDisplayed()
        rule.onNodeWithText("Controls for this phone").assertIsDisplayed()
        rule.onNodeWithText("1 h 30 min").assertIsDisplayed()
        rule.onNodeWithText("App limits set").assertIsDisplayed()
        rule.onNodeWithText("12:00 PM – 2:00 PM").assertIsDisplayed()
        rule.onNodeWithText("Daily limit").performClick()
        rule.onNodeWithText("App limits").performClick()
        rule.onRoot().performTouchInput { swipeUp() }
        rule.onNodeWithText("Downtime").performClick()
        rule.onNodeWithText("Web content filters").performClick()
        rule.onRoot().performTouchInput { swipeUp() }
        rule.onNodeWithText("Manage your PIN").performClick()
        assertEquals(listOf("daily", "apps", "downtime", "web", "pin"), record)
    }

    @Test
    fun dashboardDefaultsSummaries() {
        val record = mutableListOf<String>()
        rule.setContent { DynamicTheme { DashboardScreen(SupervisionUiState(loading = false), dashboardActions(record)) } }
        rule.onNodeWithText("Not set").assertIsDisplayed()
        rule.onNodeWithText("No app limits").assertIsDisplayed()
        rule.onNodeWithText("Off").assertIsDisplayed()
        rule.onNodeWithText("Controls for this phone").performClick()
        assertEquals(listOf("controls:false"), record)
    }

    // ---- App limits / daily limit pickers ----

    @Test
    fun appLimitsPickerSetsAndClearsLimit() {
        var change: Pair<String, Int?>? = null
        val state = SupervisionUiState(
            apps = listOf(
                SupervisableApp("com.a", "Alpha", AppRule("com.a", dailyLimitMinutes = 30)),
                SupervisableApp("com.b", "Beta", null),
            ),
            loading = false,
        )
        rule.setContent { DynamicTheme { AppLimitsScreen(state, AppLimitsActions { p, m -> change = p to m }) } }
        rule.onNodeWithText("App limits").assertIsDisplayed()
        rule.onNodeWithText("Alpha").assertIsDisplayed()
        rule.onNodeWithText("30 min").assertIsDisplayed()
        rule.onNodeWithText("Beta").performClick()
        rule.onNodeWithText("1 h").performClick()
        assertEquals("com.b" to 60, change)
        rule.onNodeWithText("Alpha").performClick()
        // "No limit" appears both as the row summary and the dialog option; the dialog one is last.
        val nodes = rule.onAllNodesWithText("No limit").fetchSemanticsNodes()
        rule.onAllNodesWithText("No limit")[nodes.size - 1].performClick()
        assertEquals("com.a" to null, change)
    }

    @Test
    fun appLimitsLoadingState() {
        rule.setContent { DynamicTheme { AppLimitsScreen(SupervisionUiState(), AppLimitsActions { _, _ -> }) } }
        rule.onNodeWithText("Loading apps…").assertIsDisplayed()
    }

    @Test
    fun dailyLimitPickerSetsBudget() {
        var change: Int? = -1; var called = false
        rule.setContent {
            DynamicTheme { DailyLimitScreen(null, DailyLimitActions { called = true; change = it }) }
        }
        rule.onNodeWithText("Daily limit").assertIsDisplayed()
        rule.onNodeWithText("No limit").assertIsDisplayed()
        rule.onNodeWithText("Daily budget").performClick()
        rule.onNodeWithText("2 h").performClick()
        assertTrue(called)
        assertEquals(120, change)
    }

    @Test
    fun dailyLimitShowsCurrentBudget() {
        rule.setContent { DynamicTheme { DailyLimitScreen(45, DailyLimitActions {}) } }
        rule.onNodeWithText("45 min").assertIsDisplayed()
    }

    // ---- Window schedule (downtime/school shared) ----

    @Test
    fun windowScheduleTogglesDaysAndApps() {
        var day = -1; var appChange: Pair<String, Boolean>? = null; var enabledChange: Boolean? = null
        val actions = WindowScheduleActions(
            onEnabledChange = { enabledChange = it },
            onStartChange = { _, _ -> },
            onEndChange = { _, _ -> },
            onToggleDay = { day = it },
            onAppAllowChange = { p, a -> appChange = p to a },
        )
        rule.setContent {
            DynamicTheme {
                WindowScheduleScreen(
                    title = "Downtime", enableLabel = "Use a downtime schedule", enableHint = "hint",
                    startLabel = "Starts at", endLabel = "Ends at", daysLabel = "Days", appsLabel = "Approved apps",
                    dayInitials = "M,T,W,T,F,S,S",
                    enabled = true, startMinute = 720, endMinute = 840, daysMask = 127,
                    apps = listOf(SupervisableApp("com.a", "Alpha", AppRule("com.a", allowedInDowntime = true))),
                    loading = false, actions = actions,
                )
            }
        }
        rule.onNodeWithText("Downtime").assertIsDisplayed()
        rule.onNodeWithText("12:00").assertIsDisplayed()
        rule.onNodeWithText("14:00").assertIsDisplayed()
        rule.onNodeWithText("Use a downtime schedule").performClick()
        assertEquals(false, enabledChange)
        // First day chip (Monday) toggles day 0. Chips share single-letter labels, pick the first.
        rule.onAllNodesWithText("M")[0].performClick()
        assertEquals(0, day)
        rule.onRoot().performTouchInput { swipeUp() }
        rule.onNodeWithText("Alpha").performClick()
        assertEquals("com.a" to false, appChange)
    }

    @Test
    fun windowScheduleTimePickerSavesStart() {
        var start: Pair<Int, Int>? = null
        val actions = WindowScheduleActions({}, { h, m -> start = h to m }, { _, _ -> }, {}, { _, _ -> })
        rule.setContent {
            DynamicTheme {
                WindowScheduleScreen(
                    title = "School time", enableLabel = "Use", enableHint = "hint",
                    startLabel = "Starts at", endLabel = "Ends at", daysLabel = "Days", appsLabel = "Learning apps",
                    dayInitials = "M,T,W,T,F,S,S",
                    enabled = true, startMinute = 480, endMinute = 900, daysMask = 31,
                    apps = emptyList(), loading = false, actions = actions,
                )
            }
        }
        rule.onNodeWithText("Starts at").performClick()
        val saves = rule.onAllNodesWithText("Save").fetchSemanticsNodes()
        assertTrue(saves.isNotEmpty())
        rule.onAllNodesWithText("Save")[saves.size - 1].performClick()
        assertEquals(8 to 0, start)
    }

    // ---- Bedtime ----

    @Test
    fun bedtimeScreenShowsAppsAndToggles() {
        var toggledDay = -1; var appChange: Pair<String, Boolean>? = null; var enabledChange: Boolean? = null
        val actions = BedtimeActions(
            onEnabledChange = { enabledChange = it },
            onStartChange = { _, _ -> },
            onEndChange = { _, _ -> },
            onToggleDay = { toggledDay = it },
            onAppBedtimeChange = { p, b -> appChange = p to b },
        )
        val state = SupervisionUiState(
            schedule = BedtimeSchedule(enabled = true),
            apps = listOf(SupervisableApp("com.a", "Alpha", AppRule("com.a", blockedAtBedtime = true))),
            loading = false,
        )
        rule.setContent { DynamicTheme { BedtimeScreen(state, actions) } }
        rule.onNodeWithText("Child bedtime").assertIsDisplayed()
        rule.onNodeWithText("Use a bedtime schedule").assertIsDisplayed()
        rule.onRoot().performTouchInput { swipeUp() }
        rule.onNodeWithText("Alpha").assertIsDisplayed()
        rule.onNodeWithText("Alpha").performClick()
        assertEquals("com.a" to false, appChange)
        assertTrue(enabledChange == null)
        assertTrue(toggledDay == -1)
    }

    @Test
    fun bedtimeTimePickersSaveStartAndEnd() {
        var start: Pair<Int, Int>? = null; var end: Pair<Int, Int>? = null; var day = -1
        val actions = BedtimeActions(
            onEnabledChange = {},
            onStartChange = { h, m -> start = h to m },
            onEndChange = { h, m -> end = h to m },
            onToggleDay = { day = it },
            onAppBedtimeChange = { _, _ -> },
        )
        val state = SupervisionUiState(schedule = BedtimeSchedule(enabled = true), loading = false)
        rule.setContent { DynamicTheme { BedtimeScreen(state, actions) } }
        rule.onNodeWithText("Starts at").performClick()
        var saves = rule.onAllNodesWithText("Save").fetchSemanticsNodes()
        assertTrue(saves.isNotEmpty())
        rule.onAllNodesWithText("Save")[saves.size - 1].performClick()
        assertEquals(21 to 0, start)
        rule.onNodeWithText("Ends at").performClick()
        saves = rule.onAllNodesWithText("Save").fetchSemanticsNodes()
        rule.onAllNodesWithText("Save")[saves.size - 1].performClick()
        assertEquals(7 to 0, end)
        // Day chips: first chip is Monday (day 0).
        rule.onAllNodesWithText("M")[0].performClick()
        assertEquals(0, day)
    }

    // ---- Web filters ----

    @Test
    fun webFiltersHoistedToggle() {
        var change: Boolean? = null
        rule.setContent { DynamicTheme { WebContentFiltersScreen(enabled = false, onEnabledChange = { change = it }) } }
        rule.onNodeWithText("Web content filters").assertIsDisplayed()
        rule.onNodeWithText("Filter explicit sites").performClick()
        assertEquals(true, change)
    }

    @Test
    fun webFiltersSelfOwnedToggle() {
        rule.setContent { DynamicTheme { WebContentFiltersScreen() } }
        rule.onNodeWithText("Filter explicit sites").assertIsDisplayed()
        rule.onNodeWithText("Filter explicit sites").performClick()
        rule.onNodeWithText("Filter explicit sites").assertIsDisplayed()
    }
}
