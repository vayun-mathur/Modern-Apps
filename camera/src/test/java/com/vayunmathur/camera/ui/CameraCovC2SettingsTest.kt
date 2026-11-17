package com.vayunmathur.camera.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.camera.platform.AudioInputSource
import com.vayunmathur.camera.platform.CameraViewModel
import com.vayunmathur.camera.platform.MotionPhotoDuration
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.library.util.NavKey
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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

    @Test fun settingsPageSelectsAudioSource() {
        val viewModel = vm()
        val backStack = NavBackStack<TestRoute>(arrayOf(TestRoute.Home))
        rule.setContent { SettingsPage(backStack, viewModel) }
        rule.onNode(hasScrollAction()).performScrollToNode(hasText(str(com.vayunmathur.camera.R.string.settings_audio_source)))
        val before = viewModel.audioInputSource.value
        val other = AudioInputSource.entries.first { it != before }
        // Open the dropdown by clicking the current value's label row.
        rule.onNodeWithText(str(before.labelRes), substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(str(other.labelRes), substring = true).performClick()
        assertEquals(other, viewModel.audioInputSource.value)
    }

    @Test fun postProcessingSettingsToggleAndDuration() {
        var noise = true
        var edge = false
        var duration = MotionPhotoDuration.ONE
        rule.setContent {
            CameraPostProcessingSettings(
                noiseReductionEnabled = noise, edgeSharpeningEnabled = edge,
                motionPhotoDuration = duration,
                onNoiseReductionChange = { noise = it },
                onEdgeSharpeningChange = { edge = it },
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
}
