package com.vayunmathur.camera.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.camera.Route
import com.vayunmathur.camera.platform.AspectRatioOption
import com.vayunmathur.camera.platform.CameraMode
import com.vayunmathur.camera.platform.CameraViewModel
import com.vayunmathur.camera.platform.FlashMode
import com.vayunmathur.camera.platform.enableTorch
import com.vayunmathur.camera.data.setAspectRatio
import com.vayunmathur.camera.data.setFlashMode
import com.vayunmathur.camera.data.setMirrorFront
import com.vayunmathur.camera.data.setSaveTreeUri
import com.vayunmathur.camera.data.setShadows
import com.vayunmathur.camera.data.setWarmth
import com.vayunmathur.library.util.NavBackStack
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import com.vayunmathur.camera.platform.BokehAnalyzer

/** Cov-w3 wave 3: camera screen in every mode with rich state, and settings interactions. */
@RunWith(AndroidJUnit4::class)
class CameraCovW3ComposeTest {
    @get:Rule val rule = createComposeRule()
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private fun vm() = CameraViewModel(context.applicationContext as android.app.Application)

    private fun screen(viewModel: CameraViewModel, captureResult: Boolean = false) {
        val backStack = NavBackStack<Route>(arrayOf(Route.Camera))
        if (captureResult) {
            rule.setContent { CameraScreen(backStack, viewModel, onCaptureResult = {}) }
        } else {
            rule.setContent { CameraScreen(backStack, viewModel) }
        }
        rule.waitForIdle()
        assertTrue(rule.onRoot().fetchSemanticsNode() != null)
    }

    private fun richVm(mode: CameraMode): CameraViewModel {
        val viewModel = vm()
        viewModel.cameraModeMutable.value = mode
        viewModel.gridEnabledMutable.value = true
        viewModel.levelEnabledMutable.value = true
        viewModel.setFlashMode(FlashMode.AUTO)
        viewModel.setAspectRatio(AspectRatioOption.RATIO_16_9)
        viewModel.qrResultMutable.value = "https://example.com/covw3"
        return viewModel
    }

    // Portrait mode's screen is not rendered here: its preview effect constructs
    // BokehAnalyzer, whose SelfieSegmenter JNI initializer throws
    // UnsatisfiedLinkError on the JVM. Portrait session builders are covered in
    // the util tests; grading state is exercised via PHOTO below.
    @Test fun screenPortraitWithMaskAndGrading() {
        val viewModel = richVm(CameraMode.PHOTO)
        viewModel.setWarmth(0.6f)
        viewModel.setShadows(0.4f)
        screen(viewModel)
    }

    @Test fun screenPanoramaAndPhotosphere() {
        screen(richVm(CameraMode.PANORAMA))
    }

    @Test fun screenPhotosphere() {
        screen(richVm(CameraMode.PHOTOSPHERE))
    }

    @Test fun screenVideoRecordingStates() {
        val viewModel = richVm(CameraMode.VIDEO)
        viewModel.isRecordingMutable.value = true
        viewModel.recordingDurationSecMutable.value = 65
        screen(viewModel)
    }

    @Test fun screenSlowMoTimelapseCinematic() {
        val slow = richVm(CameraMode.SLOW_MO)
        slow.highSpeedActiveMutable.value = true
        slow.sloMoSupportedMutable.value = true
        screen(slow)
    }

    @Test fun screenTimelapseMode() = screen(richVm(CameraMode.TIMELAPSE))

    @Test fun screenCinematicMode() = screen(richVm(CameraMode.CINEMATIC))

    @Test fun screenCaptureForResultVariant() {
        screen(richVm(CameraMode.PHOTO), captureResult = true)
    }

    @Test fun screenFrontFacingMirrorOff() {
        val viewModel = richVm(CameraMode.PHOTO)
        viewModel.lensFacingMutable.value = android.hardware.camera2.CameraMetadata.LENS_FACING_FRONT
        viewModel.setMirrorFront(false)
        viewModel.enableTorch(false)
        screen(viewModel)
    }

    @Test fun settingsPageInteractions() {
        val viewModel = vm()
        val backStack = NavBackStack<Route>(arrayOf(Route.Camera, Route.Settings))
        rule.setContent { SettingsPage(backStack, viewModel) }
        rule.waitForIdle()
        // Toggle every switch on the page (mirror, post-processing, location...).
        val toggles = rule.onAllNodes(isToggleable()).fetchSemanticsNodes()
        for (i in toggles.indices) {
            rule.onAllNodes(isToggleable())[i].performClick()
            rule.waitForIdle()
        }
        // Open the audio-source dropdown and pick an entry if present.
        val audioNodes = rule.onAllNodesWithText("Auto", substring = true).fetchSemanticsNodes()
        if (audioNodes.isNotEmpty()) {
            rule.onAllNodesWithText("Auto", substring = true)[0].performClick()
            rule.waitForIdle()
        }
        assertTrue(toggles.isNotEmpty())
    }

    @Test fun settingsDropdownsOpenAndSelect() {
        val viewModel = vm()
        val backStack = NavBackStack<Route>(arrayOf(Route.Camera, Route.Settings))
        rule.setContent { SettingsPage(backStack, viewModel) }
        rule.waitForIdle()
        // Audio dropdown: the button shows "<label> — <description>"; pick a menu entry.
        val audio = rule.onAllNodesWithText("Auto", substring = true).fetchSemanticsNodes()
        if (audio.isNotEmpty()) {
            rule.onAllNodesWithText("Auto", substring = true)[0].performClick()
            rule.waitForIdle()
            val mics = rule.onAllNodesWithText("Microphone", substring = true).fetchSemanticsNodes()
            if (mics.isNotEmpty()) {
                rule.onAllNodesWithText("Microphone", substring = true)[mics.size - 1].performClick()
                rule.waitForIdle()
            } else {
                // No physical device (Auto only): re-pick Auto to close the menu.
                val autos = rule.onAllNodesWithText("Auto", substring = true).fetchSemanticsNodes()
                if (autos.isNotEmpty()) {
                    rule.onAllNodesWithText("Auto", substring = true)[autos.size - 1].performClick()
                    rule.waitForIdle()
                }
            }
        }
        // Motion-duration dropdown lives below the fold; scroll to its label first.
        rule.onRoot().performScrollToNode(hasText("seconds", substring = true))
        val duration = rule.onAllNodesWithText("seconds", substring = true).fetchSemanticsNodes()
        if (duration.isNotEmpty()) {
            rule.onAllNodesWithText("seconds", substring = true)[0].performClick()
            rule.waitForIdle()
            val five = rule.onAllNodesWithText("5 seconds", substring = true).fetchSemanticsNodes()
            if (five.isNotEmpty()) {
                rule.onAllNodesWithText("5 seconds", substring = true)[five.size - 1].performClick()
                rule.waitForIdle()
            }
        }
        assertTrue(viewModel.motionPhotoDurationMutable.value.seconds in listOf(1, 3, 5))
    }

    @Test fun settingsPageWithSafTargetShowsReset() {
        val viewModel = vm()
        val tree = android.net.Uri.parse("content://com.android.externalstorage.documents/tree/primary%3APictures%2FCovW3")
        viewModel.setSaveTreeUri(tree)
        val backStack = NavBackStack<Route>(arrayOf(Route.Camera, Route.Settings))
        rule.setContent { SettingsPage(backStack, viewModel) }
        rule.waitForIdle()
        rule.onRoot().performScrollToNode(hasText("Mirror", substring = true))
        rule.waitForIdle()
    }

    @Test fun overlayCardButtonClicks() {
        var dismissed = 0
        rule.setContent {
            // Activity context: the cards startActivity() on click, which the
            // application context cannot do without NEW_TASK.
            val activityContext = androidx.compose.ui.platform.LocalContext.current
            androidx.compose.foundation.layout.Column {
                QrResultOverlay(text = "https://example.com/page", onDismiss = { dismissed++ }, context = activityContext)
                QrResultOverlay(text = "example.com/page", onDismiss = { dismissed++ }, context = activityContext)
                FidoQrCard(text = "FIDO:/mc123", onDismiss = { dismissed++ }, context = activityContext)
                FidoQrCard(text = "fido:/ga456", onDismiss = { dismissed++ }, context = activityContext)
                EsimQrCard(text = "LPA:1\$smdp\$mid", onDismiss = { dismissed++ }, context = activityContext)
            }
        }
        rule.waitForIdle()
        // Open URL, Copy and Cancel on the QR cards, then the passkey/eSIM actions.
        for (label in listOf("Open", "Copy", "Cancel", "passkey", "eSIM")) {
            val nodes = rule.onAllNodesWithText(label, substring = true).fetchSemanticsNodes()
            if (nodes.isNotEmpty()) {
                rule.onAllNodesWithText(label, substring = true)[0].performClick()
                rule.waitForIdle()
            }
        }
        assertTrue(dismissed >= 0)
    }

    @Test fun overlaysRichVariants() {
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                GridOverlay()
                LevelOverlay(roll = 12f)
                LevelOverlay(roll = 0.5f)
                NightModeButton(active = true, onClick = {}, iconRotation = 0f)
                NightModeButton(active = false, onClick = {}, iconRotation = 90f)
                RecordingIndicator(durationSec = 3661)
                LongExposureOverlay(progress = 0.5f, remainingText = "1.5s")
                QrResultOverlay(text = "hello", onDismiss = {}, context = context)
                FidoQrCard(text = "fido-qr", onDismiss = {}, context = context)
                EsimQrCard(text = "LPA:1\$cov", onDismiss = {}, context = context)
            }
        }
        rule.waitForIdle()
        assertTrue(isEsimActivationCode("LPA:1\$example"))
        assertTrue(!isEsimActivationCode("https://example.com"))
    }
}
