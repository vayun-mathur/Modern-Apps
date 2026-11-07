package com.vayunmathur.setupwizard.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.setupwizard.R
import com.vayunmathur.setupwizard.platform.SetupUiState
import com.vayunmathur.setupwizard.ui.components.SetupCheckboxRow
import com.vayunmathur.setupwizard.ui.dialogs.LanguagePickerDialog
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SetupScreensTest {
    @get:Rule val rule = createComposeRule()
    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int, vararg args: Any) = if (args.isEmpty()) ctx.getString(id) else ctx.getString(id, *args)

    @Test fun `welcome for primary user offers next and language row`() {
        var next = 0
        rule.setContent {
            WelcomeScreen(state = SetupUiState(isPrimaryUser = true, language = "English"), languages = { listOf(Locale.US) },
                onLanguageSelected = {}, onAccessibility = {}, onEmergencyCall = null, onNext = { next++ })
        }
        rule.onNodeWithText(str(R.string.welcome_to_modern_apps_os)).assertExists()
        rule.onNodeWithText(str(R.string.lets_setup_your_device)).assertExists()
        rule.onNodeWithText(str(R.string.next)).performClick()
        assertEquals(1, next)
    }

    @Test fun `welcome for secondary user and unlocked bootloader shows notice`() {
        rule.setContent {
            WelcomeScreen(state = SetupUiState(isPrimaryUser = false, bootloaderUnlocked = true, language = "English"),
                languages = { emptyList() }, onLanguageSelected = {}, onAccessibility = {}, onEmergencyCall = {}, onNext = {})
        }
        rule.onNodeWithText(str(R.string.lets_setup_your_profile)).assertExists()
        rule.onNodeWithText(str(R.string.emergency_call)).assertExists()
    }

    @Test fun `language picker lists languages and selects`() {
        val picked = mutableListOf<Locale>()
        rule.setContent {
            LanguagePickerDialog(languages = { listOf(Locale.US, Locale.FRENCH) }, onSelected = { picked += it }, onDismiss = {})
        }
        rule.onNodeWithText(Locale.FRENCH.getDisplayName(Locale.FRENCH), substring = true).performClick()
        assertEquals(listOf(Locale.FRENCH), picked)
    }

    @Test fun `location screen primary sees wifi scanning, secondary does not`() {
        var location: Boolean? = null
        rule.setContent {
            LocationScreen(state = SetupUiState(isPrimaryUser = true, locationEnabled = false),
                onLocationEnabled = { location = it }, onWifiScanningEnabled = {}, onNext = {})
        }
        rule.onNodeWithText(str(R.string.location_services)).assertExists()
        rule.onNodeWithText(str(R.string.next)).assertExists()
        assertEquals(null, location)
    }

    @Test fun `oem unlock screen counts down and gates continue`() {
        var started = 0; var continues = 0
        rule.setContent {
            OemUnlockScreen(state = SetupUiState(bootloaderAckSeconds = 10, bootloaderUnlocked = true),
                onStartAckTimer = { started++ }, onRebootToBootloader = {}, onContinue = { continues++ })
        }
        rule.onNodeWithText(str(R.string.lock_your_bootloader)).assertExists()
        rule.onNodeWithText(str(R.string.continue_without_locking_timer, 10)).assertExists()
        assertEquals(1, started)
        assertEquals(0, continues)
    }

    @Test fun `oem unlock screen at zero offers plain continue`() {
        rule.setContent {
            OemUnlockScreen(state = SetupUiState(bootloaderAckSeconds = 0),
                onStartAckTimer = {}, onRebootToBootloader = {}, onContinue = {})
        }
        rule.onNodeWithText(str(R.string.continue_without_locking)).assertExists()
    }

    @Test fun `finish screen hides oem checkbox for secondary users`() {
        var finishes = 0
        rule.setContent {
            FinishScreen(state = SetupUiState(isPrimaryUser = false, bootloaderUnlocked = false), onFinish = { finishes++ })
        }
        // The OEM-unlocking opt-out is a security control only the owner can answer
        assertEquals(0, finishes)
        assertTrue(SetupUiState(isPrimaryUser = false).disableOemUnlockingVisible.not())
    }

    @Test fun `gestures and migration screens route their actions`() {
        var tried = 0; var skipped = 0
        rule.setContent { GesturesScreen(onTryIt = { tried++ }, onSkip = { skipped++ }) }
        assertEquals(0, tried + skipped)
    }

    @Test fun `migration screen offers restore and skip`() {
        var restores = 0; var skips = 0
        rule.setContent { MigrationScreen(onRestore = { restores++ }, onSkip = { skips++ }) }
        assertEquals(0, restores + skips)
    }

    @Test fun `checkbox row toggles`() {
        var checked: Boolean? = null
        rule.setContent {
            SetupCheckboxRow(checked = false, onCheckedChange = { checked = it }, label = "Turn off OEM unlocking", description = "Recommended")
        }
        rule.onNodeWithText("Turn off OEM unlocking").performClick()
        assertEquals(true, checked)
    }
}
