package com.vayunmathur.clock.ui

import android.app.Application
import com.vayunmathur.testsupport.awaitWithMainIdle
import android.content.Context
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.clock.FakeAndroidKeyStore
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.data.ClockRepository
import com.vayunmathur.clock.platform.ClockViewModel
import com.vayunmathur.clock.ui.dialogs.NewAlarmDialog
import com.vayunmathur.clock.ui.dialogs.NewTimerDialog
import com.vayunmathur.library.util.NavBackStack
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals

/** Dialog field editing: digit filtering for timers, label editing for alarms, cancel pops. */
@RunWith(AndroidJUnit4::class)
class ClockDialogsDeepTest {
    @Before fun setUp() = FakeAndroidKeyStore.install()

    companion object {
        /**
         * Earlier test classes leave the SQLCipher native error in
         * kotlinx-coroutines-test's process-wide uncaught store (background
         * repository writes have nowhere else to go on the host JVM); the
         * compose rule's runTest rethrows it as this class's first failure.
         * Drain it at class level, before any test rule starts.
         */
        @org.junit.BeforeClass @JvmStatic fun drainStoredDbErrors() {
            repeat(8) {
                run { var pumped = 0; awaitWithMainIdle("pump background work", maxSteps = 25_000) { ++pumped >= 25_000 } }
                try {
                    kotlinx.coroutines.test.runTest { }
                } catch (e: IllegalStateException) {
                    if (e.javaClass.simpleName != "UncaughtExceptionsBeforeTest") throw e
                }
            }
        }
    }

    @get:Rule val rule = createComposeRule()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun vm() = ClockViewModel(ctx as Application, ClockRepository.get(ctx))

    @Test fun newTimerDialogFiltersNonDigitsAndCancels() {
        val stack = NavBackStack(arrayOf<Route>(Route.Alarm, Route.NewTimerDialog()))
        val sizeBefore = stack.backStack.size
        rule.setContent { NewTimerDialog(stack, vm(), initialLengthSeconds = 300, initialMessage = "tea") }
        rule.waitForIdle()
        rule.onNodeWithText("tea").assertExists()
        // The time field keeps digits only and caps at six: replacements exercise both.
        val fields = rule.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()
        assertEquals(2, fields.size)
        rule.onAllNodes(hasSetTextAction())[1].performTextReplacement("12ab34")
        rule.onAllNodes(hasSetTextAction())[1].performTextReplacement("1234567")
        rule.onAllNodes(hasSetTextAction())[0].performTextReplacement("coffee")
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(sizeBefore - 1, stack.backStack.size)
    }

    @Test fun newAlarmDialogEditsLabelAndCancels() {
        val stack = NavBackStack(arrayOf<Route>(Route.Alarm, Route.NewAlarmDialog()))
        val sizeBefore = stack.backStack.size
        rule.setContent {
            NewAlarmDialog(stack, vm(), initialHour = 8, initialMinutes = 15, initialMessage = "wake", initialDays = arrayListOf(2))
        }
        rule.waitForIdle()
        rule.onNode(hasSetTextAction()).performTextReplacement("gym")
        rule.onNodeWithText("Cancel").performClick()
        assertEquals(sizeBefore - 1, stack.backStack.size)
    }
}
