package com.vayunmathur.speech

import android.Manifest
import android.app.Application
import android.provider.Settings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class MainActivityTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    @Before
    fun grantMicrophone() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>())
            .grantPermissions(Manifest.permission.RECORD_AUDIO)
    }

    @Test
    fun activityLaunchesSetupScreenPastPermissionGate() {
        rule.waitForIdle()
        rule.onNodeWithText("2. Set as speech recognizer").assertIsDisplayed()
        rule.onNodeWithText("Open voice input settings").assertIsDisplayed()
    }

    @Test
    fun voiceInputSettingsButtonLaunchesASettingsIntent() {
        rule.waitForIdle()
        rule.onNodeWithText("Open voice input settings").performClick()
        rule.waitForIdle()
        val intent = shadowOf(rule.activity).nextStartedActivity
        assertNotNull(intent, "no activity launched for voice input settings")
        assertTrue(
            intent.action in listOf(
                Settings.ACTION_LOCALE_SETTINGS,
                Settings.ACTION_VOICE_INPUT_SETTINGS,
                Settings.ACTION_SETTINGS,
            ),
            "unexpected settings action ${intent.action}",
        )
    }

    @Test
    fun ttsSettingsButtonLaunchesASettingsIntent() {
        rule.waitForIdle()
        rule.onNodeWithText("Open text-to-speech settings").performScrollTo()
        rule.onNodeWithText("Open text-to-speech settings").performClick()
        rule.waitForIdle()
        val intent = shadowOf(rule.activity).nextStartedActivity
        assertNotNull(intent, "no activity launched for TTS settings")
        assertEquals("com.android.settings.TTS_SETTINGS", intent.action)
    }

    @Test
    fun activityShowsBundledLanguageInVoicePicker() {
        rule.waitForIdle()
        rule.onNodeWithText("English (English)").performScrollTo()
        rule.onNodeWithText("English (English)").assertIsDisplayed()
    }
}
