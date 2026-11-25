package com.vayunmathur.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.camera.platform.AspectRatioOption
import com.vayunmathur.camera.platform.CameraMode
import com.vayunmathur.camera.platform.FlashMode
import com.vayunmathur.camera.platform.TimerDuration
import com.vayunmathur.library.ui.DynamicTheme

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/** Desktop-wide (1280dp >= 840dp): the wide controls layout (shutter beside modes). */
private const val EXPANDED = "spec:width=1280dp,height=800dp,dpi=240"

/**
 * Listing-image renders for `:camera`, from Compose previews.
 *
 * Layoutlib cannot open a camera, so the live viewfinder can never be
 * preview-rendered (with no SurfaceRequest it is a black box). Instead of a
 * black/empty pane, the captures below draw [FakeCameraPreview] — a
 * deterministic, synthetic screenshot-only scene (see that file; it lives in
 * `src/screenshotTest` and never ships) — behind the REAL controls:
 * [CameraTopBar], the real grid overlay, [CameraShutterPane] and
 * [CameraModesPane]. This is the fixture-side use of the same provider shape
 * as the `screenshotFakePreview` parameter on `CameraScreen` /
 * `CameraPreviewSurface`, whose production default is null and which no
 * production caller passes; the fixtures compose the panes directly because
 * `CameraScreen` needs a `CameraViewModel` that cannot run in Layoutlib.
 * The fake scene is representative viewfinder content, not a claim that a
 * real camera frame rendered here.
 *
 * `./gradlew :camera:metadata` renders these previews and collects them into
 * `metadata_data/photos/camera/phone/` and `.../large-tablet/`.
 *
 * Rules (same as every other module's MetadataPreviews): order comes from the
 * function names, each preview needs `@PreviewTest` plus a `group`, and previews
 * are class members.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-wide-controls-expanded", device = EXPANDED, group = "large-tablet", showSystemUi = true)
    @Composable
    fun Preview1WideControlsExpanded() {
        DynamicTheme(darkTheme = true) {
            Column(Modifier.fillMaxSize().background(Color.Black)) {
                // Fake viewfinder scene (screenshot-only) above the wide controls,
                // with the real grid overlay the live preview would show.
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    FakeCameraPreview(Modifier.fillMaxSize())
                    GridOverlay(Modifier.fillMaxSize())
                }
                CameraWideLayout(
                    shutter = { FixtureShutterPane() },
                    modes = { FixtureModesPane() },
                    modifier = Modifier.fillMaxWidth(),
                    sideBySide = true,
                )
            }
        }
    }

    @PreviewTest
    @Preview(name = "2-camera", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview2CameraPhone() {
        DynamicTheme(darkTheme = true) {
            // Mirrors CameraScreen's phone structure: top bar, viewfinder,
            // then shutter + modes stacked below it.
            Column(Modifier.fillMaxSize().background(Color.Black)) {
                CameraTopBar(
                    flashMode = FlashMode.OFF,
                    torchEnabled = false,
                    hasFlashUnit = true,
                    gridEnabled = true,
                    levelEnabled = false,
                    aspectRatio = AspectRatioOption.RATIO_4_3,
                    stillCropEnabled = true,
                    isPhotoType = true,
                    isVideoType = false,
                    micMuted = false,
                    timerDuration = TimerDuration.NONE,
                    onFlashToggle = {},
                    onTorchToggle = {},
                    onGridToggle = {},
                    onLevelToggle = {},
                    onAspectCycle = {},
                    onMicToggle = {},
                    onTimerCycle = {},
                    iconRotation = 0f,
                )
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    FakeCameraPreview(Modifier.fillMaxSize())
                    GridOverlay(Modifier.fillMaxSize())
                }
                CameraWideLayout(
                    shutter = { FixtureShutterPane() },
                    modes = { FixtureModesPane() },
                    modifier = Modifier.fillMaxWidth(),
                    sideBySide = false,
                )
            }
        }
    }
}

@Composable
private fun FixtureShutterPane() {
    CameraShutterPane(
        cameraMode = CameraMode.PHOTO,
        isRecording = false,
        isCapturing = false,
        recordingPaused = false,
        videoSnapshotSupported = false,
        galleryBitmap = null,
        onCaptureResult = null,
        onBurstStart = {},
        onBurstStop = {},
        onCapture = {},
        onPauseResume = {},
        onSnapshot = {},
        onFlipCamera = {},
        onGallery = {},
        iconRotation = 0f,
    )
}

@Composable
private fun FixtureModesPane() {
    CameraModesPane(
        cameraMode = CameraMode.PHOTO,
        isPhotoType = true,
        sloMoSupported = true,
        captureForResult = false,
        onModeSelected = {},
        iconRotation = 0f,
        onPickerChanged = {},
        onSettingsClick = {},
    )
}
