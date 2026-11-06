package com.vayunmathur.maps.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.maps.data.MapPreferences
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * The first-launch Google consent dialog and its DataStore gates.
 * Slice-2 coverage for maps (basename G, outside the slice-1 dirs).
 *
 * The dialog writes through [MapSettingsViewModel] (fire-and-forget on its
 * own scope over DataStore's own IO threads): the assertions observe the
 * dialog's own effects — which button dismisses it with which callback —
 * plus the synchronous gates that need no write to converge.
 */
@RunWith(AndroidJUnit4::class)
class GoogleConsentDialogSlice2Test {

    @get:Rule
    val rule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun ds() = DataStoreUtils.getInstance(context)

    @Test
    fun `consent is needed until asked`() {
        rule.runOnIdle {
            // Fresh installs never asked: the gate reads the off default.
            assertTrue(shouldShowGoogleConsent(ds()))
        }
    }

    @Test
    fun `google services default off`() {
        rule.runOnIdle {
            assertFalse(googleServicesEnabled(ds()))
        }
    }

    @Test
    fun `enabling dismisses the dialog and calls back`() {
        val vm = com.vayunmathur.maps.util.MapSettingsViewModel(
            context as android.app.Application,
        )
        var done = 0
        rule.setContent { GoogleConsentDialog(settingsViewModel = vm, onDone = { done++ }) }
        rule.onNodeWithText("Enable Google services?").assertIsDisplayed()
        rule.onNodeWithText("Enable").performClick()
        rule.waitForIdle()
        // The choice is recorded (dialog gone) and the caller is told.
        rule.onNodeWithText("Enable Google services?").assertDoesNotExist()
        assertTrue(done >= 1, "onDone fires on confirm")
    }

    @Test
    fun `dismissing dismisses the dialog and calls back`() {
        val vm = com.vayunmathur.maps.util.MapSettingsViewModel(
            context as android.app.Application,
        )
        var done = 0
        rule.setContent { GoogleConsentDialog(settingsViewModel = vm, onDone = { done++ }) }
        rule.onNodeWithText("Keep offline").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Enable Google services?").assertDoesNotExist()
        assertTrue(done >= 1, "onDone fires on dismiss")
    }
}
