package com.vayunmathur.auto.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.auto.R as AutoR
import com.vayunmathur.auto.platform.MessagingPrefs
import com.vayunmathur.auto.platform.MusicCapturePrefs
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Auto module: [MessagingConsentCard], [MusicCaptureCard] and
 * [MicPermissionCard] — the consent gates. Each card renders its headline,
 * body and switch/list rows from the stored prefs; toggling the switch
 * writes the pref back. Listener/grant rows fail closed (missing until
 * granted) under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
class ConsentCardsTest {

    @get:Rule
    val rule = createComposeRule()

    private val appContext: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `messaging card renders its consent switch`() {
        MessagingPrefs.setMirroringConsented(appContext, false)
        rule.setContent { MessagingConsentCard() }
        rule.onNodeWithText(appContext.getString(AutoR.string.messaging_headline)).assertExists()
        rule.onNodeWithText(appContext.getString(AutoR.string.messaging_body)).assertExists()
        rule.onNodeWithText(appContext.getString(AutoR.string.messaging_consent)).assertExists()
        // Fail-closed: no listener binding under Robolectric.
        rule.onNodeWithText(appContext.getString(AutoR.string.messaging_listener_missing))
            .assertExists()
        rule.onNodeWithText(appContext.getString(AutoR.string.messaging_open_settings))
            .assertExists()
    }

    @Test
    fun `messaging consent switch writes the pref`() {
        MessagingPrefs.setMirroringConsented(appContext, false)
        rule.setContent { MessagingConsentCard() }
        rule.onNodeWithText(appContext.getString(AutoR.string.messaging_consent)).performClick()
        assertTrue(MessagingPrefs.isMirroringConsented(appContext))
        rule.onNodeWithText(appContext.getString(AutoR.string.messaging_consent)).performClick()
        assertFalse(MessagingPrefs.isMirroringConsented(appContext))
    }

    @Test
    fun `messaging consent on shows the on copy`() {
        MessagingPrefs.setMirroringConsented(appContext, true)
        try {
            rule.setContent { MessagingConsentCard() }
            rule.onNodeWithText(appContext.getString(AutoR.string.messaging_consent_on))
                .assertExists()
        } finally {
            MessagingPrefs.setMirroringConsented(appContext, false)
        }
    }

    @Test
    fun `music card renders its consent and grant rows`() {
        MusicCapturePrefs.setMusicCaptureConsented(appContext, false)
        MusicCaptureGrant.clear()
        rule.setContent { MusicCaptureCard() }
        rule.onNodeWithText(appContext.getString(AutoR.string.music_capture_headline)).assertExists()
        rule.onNodeWithText(appContext.getString(AutoR.string.music_capture_body)).assertExists()
        rule.onNodeWithText(appContext.getString(AutoR.string.music_capture_consent)).assertExists()
        // Fail-closed: no projection grant stored.
        rule.onNodeWithText(appContext.getString(AutoR.string.music_capture_projection_missing))
            .assertExists()
    }

    @Test
    fun `music consent switch writes the pref`() {
        MusicCapturePrefs.setMusicCaptureConsented(appContext, false)
        rule.setContent { MusicCaptureCard() }
        rule.onNodeWithText(appContext.getString(AutoR.string.music_capture_consent)).performClick()
        assertTrue(MusicCapturePrefs.isMusicCaptureConsented(appContext))
        rule.onNodeWithText(appContext.getString(AutoR.string.music_capture_consent)).performClick()
        assertFalse(MusicCapturePrefs.isMusicCaptureConsented(appContext))
        MusicCaptureGrant.clear()
    }

    @Test
    fun `mic card renders the permission row as missing`() {
        rule.setContent { MicPermissionCard() }
        rule.onNodeWithText(appContext.getString(AutoR.string.mic_headline)).assertExists()
        rule.onNodeWithText(appContext.getString(AutoR.string.mic_body)).assertExists()
        rule.onNodeWithText(appContext.getString(AutoR.string.mic_permission)).assertExists()
        // Robolectric grants nothing: the missing copy shows.
        rule.onNodeWithText(appContext.getString(AutoR.string.mic_permission_missing))
            .assertExists()
    }
}
