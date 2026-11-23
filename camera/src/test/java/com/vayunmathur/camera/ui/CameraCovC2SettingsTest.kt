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
        // The picker is a physical-device picker: Auto plus connected
        // devices (only Auto under Robolectric, which has no input
        // hardware), never AudioSource-type labels like "Camcorder".
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
        // Open the dropdown by clicking the current (Auto) row and re-pick Auto.
        rule.onNodeWithText("Auto", substring = true).performClick()
        rule.waitForIdle()
        val autos = rule.onAllNodesWithText("Auto", substring = true).fetchSemanticsNodes()
        rule.onAllNodesWithText("Auto", substring = true)[autos.size - 1].performClick()
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
        var motionEnabled = true
        rule.setContent {
            CameraPostProcessingSettings(
                noiseReductionEnabled = noise, edgeSharpeningEnabled = edge,
                motionPhotoEnabled = motionEnabled,
                motionPhotoDuration = duration,
                motionPhotoBlockedByAvif = false,
                onNoiseReductionChange = { noise = it },
                onEdgeSharpeningChange = { edge = it },
                onMotionPhotoEnabledChange = { motionEnabled = it },
                onMotionDurationChange = { duration = it },
            )
        }
        rule.onAllNodes(isToggleable())[0].performClick()
        assertEquals(false, noise)
        rule.onAllNodes(isToggleable())[1].performClick()
        assertEquals(true, edge)
        // Duration dropdown: open and pick a different entry.
        rule.onNodeWithText("1 second", substring = true).performClick()
        rule.waitForIdle()
        val target = MotionPhotoDuration.entries.first { it != duration }
        rule.onAllNodes(hasText("${target.seconds}", substring = true))[0].performClick()
        assertNotEquals(MotionPhotoDuration.ONE, duration)
    }

    @Test fun motionPhotoOffHidesLengthAndAvifDisablesToggle() {
        var enabled = false
        rule.setContent {
            CameraPostProcessingSettings(
                noiseReductionEnabled = true, edgeSharpeningEnabled = true,
                motionPhotoEnabled = enabled,
                motionPhotoDuration = MotionPhotoDuration.THREE,
                motionPhotoBlockedByAvif = false,
                onNoiseReductionChange = {}, onEdgeSharpeningChange = {},
                onMotionPhotoEnabledChange = { enabled = it },
                onMotionDurationChange = {},
            )
        }
        // Off: the length row is gone.
        assertTrue(
            rule.onAllNodesWithText(str(com.vayunmathur.camera.R.string.settings_motion_duration))
                .fetchSemanticsNodes().isEmpty()
        )
        // Third toggle is Motion Photo; turning it on restores the control.
        rule.onAllNodes(isToggleable())[2].performClick()
        assertEquals(true, enabled)
    }

    @Test fun motionPhotoToggleDisabledWithReasonWhenAvifBlocks() {
        var enabled = true
        rule.setContent {
            CameraPostProcessingSettings(
                noiseReductionEnabled = true, edgeSharpeningEnabled = true,
                motionPhotoEnabled = enabled,
                motionPhotoDuration = MotionPhotoDuration.THREE,
                motionPhotoBlockedByAvif = true,
                onNoiseReductionChange = {}, onEdgeSharpeningChange = {},
                onMotionPhotoEnabledChange = { enabled = it },
                onMotionDurationChange = {},
            )
        }
        rule.onNodeWithText(
            str(com.vayunmathur.camera.R.string.settings_motion_photo_requires_jpeg)
        ).assertIsDisplayed()
        rule.onAllNodes(isToggleable())[2].assertIsNotEnabled()
        assertEquals(true, enabled, "AVIF-blocked toggle must not change")
    }
}
