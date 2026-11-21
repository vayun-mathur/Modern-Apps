package com.vayunmathur.clock.ui

import androidx.compose.ui.test.junit4.createComposeRule
import com.vayunmathur.testsupport.awaitWithMainIdle
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.clock.ui.components.AlarmRingingScreen
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals

/** The ringing screen's two actions, with snooze enabled and disabled. */
@RunWith(AndroidJUnit4::class)
class AlarmRingingScreenTest {


    companion object {
        /** Drain stored background-DB errors from earlier classes (see ClockDialogsDeepTest). */
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

    @Test fun stopAndSnoozeReport() {
        val calls = mutableListOf<String>()
        rule.setContent {
            AlarmRingingScreen(
                alarmTime = "7:30",
                alarmName = "Gym",
                snoozeMinutes = 10,
                onDismiss = { calls += "dismiss" },
                onSnooze = { calls += "snooze" },
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("GYM").assertExists()
        rule.onNodeWithText("SNOOZE (10 MIN)", substring = true).performClick()
        rule.onNodeWithText("STOP").performClick()
        assertEquals(listOf("snooze", "dismiss"), calls)
    }

    @Test fun snoozeDisabledDoesNotReport() {
        val calls = mutableListOf<String>()
        rule.setContent {
            AlarmRingingScreen(
                alarmTime = "22:15",
                alarmName = "",
                snoozeMinutes = 5,
                onDismiss = { calls += "dismiss" },
                onSnooze = { calls += "snooze" },
                snoozeEnabled = false,
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("SNOOZE (5 MIN)", substring = true).performClick()
        rule.onNodeWithText("STOP").performClick()
        assertEquals(listOf("dismiss"), calls)
    }
}
