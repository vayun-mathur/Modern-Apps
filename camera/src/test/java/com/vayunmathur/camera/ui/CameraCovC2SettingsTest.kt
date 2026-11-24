package com.vayunmathur.camera.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.camera.platform.CameraViewModel
import com.vayunmathur.camera.platform.MotionPhotoDuration
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Cov-c2: SettingsPage interactions (dropdowns, switches, post-processing). */
@RunWith(AndroidJUnit4::class)
class CameraCovC2SettingsTest {

    @get:Rule val rule = createComposeRule()

    @Serializable
    private sealed interface TestRoute : NavKey {
        @Serializable data object Home : TestRoute
    }

    private fun vm() = CameraViewModel(ApplicationProvider.getApplicationContext())
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun str(id: Int): String = context.getString(id)

    @Test fun settingsPageTogglesMirrorAndPostProcessing() {
        val viewModel = vm()
        val backStack = NavBackStack<TestRoute>(arrayOf(TestRoute.Home))
        rule.setContent { SettingsPage(backStack, viewModel) }
        rule.onNodeWithText(str(com.vayunmathur.camera.R.string.settings_mirror_front)).assertIsDisplayed()
        val mirrorBefore = viewModel.mirrorFront.value
        // Toggleable nodes are the switches; the first is Selfie mirroring.
        rule.onAllNodes(isToggleable())[0].performClick()
        assertEquals(!mirrorBefore, viewModel.mirrorFront.value)
    }

    @Test fun settingsPageSelectsAudioInputDevice() {
        // The picker lists only real physical devices (none under
        // Robolectric, which has no input hardware), never a synthetic
        // default row or AudioSource-type labels like "Camcorder".
        val viewModel = vm()
        val backStack = NavBackStack<TestRoute>(arrayOf(TestRoute.Home))
        rule.setContent { SettingsPage(backStack, viewModel) }
        rule.onNode(hasScrollAction()).performScrollToNode(
            hasText(str(com.vayunmathur.camera.R.string.settings_audio_input_device))
        )
        assertEquals(
            com.vayunmathur.camera.platform.camera2.AudioInputDeviceSelection.AUTO,
            viewModel.audioInputDevice.value,
        )
        assertTrue(
            rule.onAllNodesWithText("Camcorder", substring = true).fetchSemanticsNodes().isEmpty(),
            "Settings must not offer AudioSource-type choices",
        )
        // No synthetic row: with no real devices the button shows the
        // record-time system-route placeholder, and opening it offers no
        // "Auto" (or any other synthetic) entry to pick.
        assertTrue(
            rule.onAllNodesWithText("Auto", substring = true).fetchSemanticsNodes().isEmpty(),
            "Settings must not offer a synthetic Auto audio device",
        )
        rule.onNodeWithText(
            str(com.vayunmathur.camera.R.string.settings_audio_input_device_unselected),
            substring = true,
        ).performClick()
        rule.waitForIdle()
        assertTrue(
            rule.onAllNodesWithText("Auto", substring = true).fetchSemanticsNodes().isEmpty(),
            "Audio dropdown must contain only real devices",
        )
        assertEquals(
            com.vayunmathur.camera.platform.camera2.AudioInputDeviceSelection.AUTO,
            viewModel.audioInputDevice.value,
        )
    }

    @Test fun settingsPageHasNoAudioCodecRow() {
        // Opus-only: audio encoding is not selectable, so Settings must
        // not offer an audio-codec row (the audio input device row stays).
        val viewModel = vm()
        val backStack = NavBackStack<TestRoute>(arrayOf(TestRoute.Home))
        rule.setContent { SettingsPage(backStack, viewModel) }
        rule.onNode(hasScrollAction()).performScrollToNode(
            hasText(str(com.vayunmathur.camera.R.string.settings_audio_input_device))
        )
        assertTrue(
            rule.onAllNodesWithText("Audio codec", substring = true).fetchSemanticsNodes().isEmpty(),
            "Settings must not show an audio-codec picker (Opus is the only option)",
        )
    }

    @Test fun postProcessingSettingsToggleAndDuration() {
        var noise = true
        var edge = false
        var duration = MotionPhotoDuration.ONE
        rule.setContent {
            CameraPostProcessingSettings(
                noiseReductionEnabled = noise, edgeSharpeningEnabled = edge,
                motionPhotoDuration = duration,
                motionPhotoBlockedByAvif = false,
                onNoiseReductionChange = { noise = it },
                onEdgeSharpeningChange = { edge = it },
                onMotionDurationChange = { duration = it },
            )
        }
        rule.onAllNodes(isToggleable())[0].performClick()
        assertEquals(false, noise)
        rule.onAllNodes(isToggleable())[1].performClick()
        assertEquals(true, edge)
        // Single dropdown: open and pick a different entry.
        rule.onNodeWithText("1 second", substring = true).performClick()
        rule.waitForIdle()
        val target = MotionPhotoDuration.entries.first { it != duration && it.isEnabled }
        rule.onAllNodes(hasText("${target.seconds}", substring = true))[0].performClick()
        assertNotEquals(MotionPhotoDuration.ONE, duration)
    }

    @Test fun motionPhotoSingleDropdownOffersOffAndLengths() {
        var duration = MotionPhotoDuration.THREE
        rule.setContent {
            CameraPostProcessingSettings(
                noiseReductionEnabled = true, edgeSharpeningEnabled = true,
                motionPhotoDuration = duration,
                motionPhotoBlockedByAvif = false,
                onNoiseReductionChange = {}, onEdgeSharpeningChange = {},
                onMotionDurationChange = { duration = it },
            )
        }
        // One control only: no Motion Photo toggle remains.
        assertEquals(2, rule.onAllNodes(isToggleable()).fetchSemanticsNodes().size)
        // The dropdown offers Off alongside the lengths; picking Off lands.
        rule.onNodeWithText("3 seconds", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(com.vayunmathur.camera.R.string.settings_motion_photo_off))
            .performClick()
        assertEquals(MotionPhotoDuration.OFF, duration)
    }

    @Test fun motionPhotoDropdownDisabledShowingOffWhenAvifBlocks() {
        var duration = MotionPhotoDuration.THREE
        rule.setContent {
            CameraPostProcessingSettings(
                noiseReductionEnabled = true, edgeSharpeningEnabled = true,
                motionPhotoDuration = duration,
                motionPhotoBlockedByAvif = true,
                onNoiseReductionChange = {}, onEdgeSharpeningChange = {},
                onMotionDurationChange = { duration = it },
            )
        }
        rule.onNodeWithText(
            str(com.vayunmathur.camera.R.string.settings_motion_photo_requires_jpeg)
        ).assertIsDisplayed()
        // Honest forced Off: the disabled button shows Off, stored value kept.
        rule.onNodeWithText(str(com.vayunmathur.camera.R.string.settings_motion_photo_off))
            .assertIsNotEnabled()
        assertEquals(MotionPhotoDuration.THREE, duration, "AVIF block must not rewrite the stored setting")
    }
}
