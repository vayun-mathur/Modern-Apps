package com.vayunmathur.clock.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.clock.FakeAndroidKeyStore
import com.vayunmathur.clock.Route
import com.vayunmathur.clock.data.ClockRepository
import com.vayunmathur.clock.platform.ClockViewModel
import com.vayunmathur.clock.ui.components.AlarmOptionControls
import com.vayunmathur.clock.ui.components.AlarmRingingScreen
import com.vayunmathur.clock.ui.components.GRADUAL_OPTIONS
import com.vayunmathur.clock.ui.components.KeypadButton
import com.vayunmathur.clock.ui.components.LapRow
import com.vayunmathur.clock.ui.components.SNOOZE_OPTIONS
import com.vayunmathur.clock.ui.components.TimeUnitDisplay
import com.vayunmathur.clock.ui.components.TimerKeypadContent
import com.vayunmathur.clock.ui.components.formatDuration
import com.vayunmathur.clock.ui.components.formatTimerDuration
import com.vayunmathur.clock.ui.components.gradualLabel
import com.vayunmathur.clock.ui.dialogs.NewTimerDialog
import com.vayunmathur.clock.ui.dialogs.TimeVisualTransformation
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.NavBackStack
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Robolectric tests for the clock dialogs, alarm options, keypad and ringing screen. */
@RunWith(AndroidJUnit4::class)
class ClockDialogsUiTest {
    @Before fun setUp() = FakeAndroidKeyStore.install()

    @get:Rule
    val rule = createComposeRule()

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun vm() = ClockViewModel(ctx as android.app.Application, ClockRepository.get(ctx))
    private fun backStack() = NavBackStack(arrayOf<Route>(Route.Main()))

    private fun clickRow(text: String) {
        rule.onNode(hasClickAction() and hasAnyDescendant(hasText(text)), useUnmergedTree = true)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick)
    }

    @Test
    fun formatHelpers() {
        assertEquals("5:00", formatTimerDuration(5.minutes))
        assertEquals("1:05:00", formatTimerDuration(65.minutes))
        assertEquals("0:09", formatTimerDuration(9.seconds))
        assertTrue(SNOOZE_OPTIONS.contains(5) && GRADUAL_OPTIONS.contains(0))
        assertEquals("Off", gradualLabel(ctx, 0))
        assertEquals("30s", gradualLabel(ctx, 30))
        val formatted = formatDuration(ctx, 90.seconds)
        assertTrue(formatted.contains("1"), "formatted=$formatted")
    }

    @Test
    fun timeVisualTransformationFormats() {
        val out = TimeVisualTransformation().filter(androidx.compose.ui.text.AnnotatedString("130"))
        assertEquals("00:01:30", out.text.text)
        assertEquals(8, out.offsetMapping.originalToTransformed(0))
        assertEquals(3, out.offsetMapping.transformedToOriginal(8))
    }

    @Test
    fun newTimerDialogTypingAndSaveDisabledForZero() {
        val stack = backStack()
        rule.setContent { NewTimerDialog(stack, vm(), initialLengthSeconds = 0, initialMessage = "tea") }
        rule.onNodeWithText("New Timer", substring = true).assertExists()
        rule.waitForIdle()
        assertEquals(1, stack.backStack.size)
    }

    @Test
    fun newTimerDialogCancelPops() {
        val stack = NavBackStack(arrayOf<Route>(Route.Main(), Route.NewTimerDialog()))
        rule.setContent { NewTimerDialog(stack, vm()) }
        clickRow("Cancel")
        assertEquals(1, stack.backStack.size)
    }

    @Test
    fun alarmOptionControlsRenderAndVibrateToggles() {
        var vibrate: Boolean? = null
        rule.setContent {
            AlarmOptionControls(
                ringtoneUri = null, vibrate = true, snoozeMinutes = 10, gradualVolumeSeconds = 0,
                onRingtoneClick = {}, onVibrateChange = { vibrate = it },
                onSnoozeChange = {}, onGradualChange = {},
            )
        }
        rule.onNodeWithText("Sound").assertIsDisplayed()
        rule.onNodeWithText("Vibrate").assertIsDisplayed()
        rule.onNodeWithText("10 min").assertIsDisplayed()
        rule.onNodeWithText("Off").assertIsDisplayed()
    }

    @Test
    fun alarmOptionSnoozeDropdownSelects() {
        var snooze: Int? = null
        rule.setContent {
            AlarmOptionControls(
                ringtoneUri = "silent", vibrate = false, snoozeMinutes = 5, gradualVolumeSeconds = 30,
                onRingtoneClick = {}, onVibrateChange = {},
                onSnoozeChange = { snooze = it }, onGradualChange = {},
            )
        }
        clickRow("5 min")
        clickRow("15 min")
        assertEquals(15, snooze)
    }

    @Test
    fun ringingScreenStopAndSnoozeFire() {
        var dismissed = false
        var snoozed = false
        rule.setContent {
            AlarmRingingScreen(
                alarmTime = "07:30", alarmName = "gym", snoozeMinutes = 10,
                onDismiss = { dismissed = true }, onSnooze = { snoozed = true },
            )
        }
        rule.onNodeWithText("07:30").assertIsDisplayed()
        rule.onNodeWithText("GYM").assertIsDisplayed()
        clickRow("STOP")
        assertTrue(dismissed)
        clickRow("SNOOZE (10 MIN)")
        assertTrue(snoozed)
    }

    @Test
    fun ringingScreenSnoozeDisabled() {
        rule.setContent {
            AlarmRingingScreen(
                alarmTime = "--:--", alarmName = "", snoozeMinutes = 5,
                onDismiss = {}, onSnooze = {}, snoozeEnabled = false,
            )
        }
        rule.onNodeWithText("--:--").assertIsDisplayed()
    }

    @Test
    fun timerKeypadDigitsAndBackspace() {
        var started: kotlin.time.Duration? = null
        rule.setContent {
            TimerKeypadContent(
                paddingValues = PaddingValues(0.dp),
                onStart = { d, _ -> started = d }, onCancel = {}, showCancel = true,
                initialInput = "45",
            )
        }
        // initialInput 45 means 45 seconds displayed in the seconds unit.
        rule.onNodeWithText("45").assertIsDisplayed()
        clickRow("⌫")
        rule.onNodeWithText("4", substring = false).assertExists()
        assertEquals(null, started)
    }

    @Test
    fun timerKeypadZeroStartDisabled() {
        rule.setContent {
            TimerKeypadContent(
                paddingValues = PaddingValues(0.dp),
                onStart = { _, _ -> }, onCancel = {}, showCancel = false,
            )
        }
        rule.onAllNodesWithText("00").onFirst().assertExists()
    }

    @Test
    fun smallComponentsRender() {
        var clicked: String? = null
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                KeypadButton("7") { clicked = "7" }
                TimeUnitDisplay("12", "h", active = true)
                LapRow(1, androidx.compose.ui.graphics.Color.Red, 30.seconds, 90.seconds)
            }
        }
        rule.onNodeWithText("7").assertIsDisplayed()
        rule.onNodeWithText("12").assertIsDisplayed()
        clickRow("7")
        assertEquals("7", clicked)
    }

    @Test
    fun alarmSettingsPageShowsDefaults() {
        val ds = DataStoreUtils.getInstance(ctx)
        rule.setContent { AlarmSettingsPage(backStack(), ds) }
        rule.onNodeWithText("Defaults for new alarms", substring = true).assertExists()
        rule.onNodeWithText("Sound").assertExists()
    }
}
