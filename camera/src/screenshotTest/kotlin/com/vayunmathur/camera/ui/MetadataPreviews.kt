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
import com.vayunmathur.camera.platform.CameraMode
import com.vayunmathur.library.ui.DynamicTheme

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/** Desktop-wide (1280dp >= 840dp): the wide controls layout (shutter beside modes). */
private const val EXPANDED = "spec:width=1280dp,height=800dp,dpi=240"

/**
 * Listing-image renders for `:camera`'s *wide* layout, from Compose previews.
 *
 * Camera stays a hybrid: Layoutlib cannot open a camera, so the live viewfinder can
 * never be preview-rendered (with no SurfaceRequest it is a black box). The phone
 * listing images are therefore still hand-captured on a real device into
 * `metadata_data/photos/camera/phone/`. What a preview *can* render honestly is the
 * part that actually changes with width — the wide controls layout
 * ([CameraWideLayout] with the real [CameraShutterPane] and [CameraModesPane]) —
 * so this file captures exactly that at expanded width, collected into
 * `metadata_data/photos/camera/large-tablet/`. The dark pane above the controls is
 * the viewfinder's placeholder, standing where the live preview sits on device;
 * replace the collected PNG with a hand capture from a real tablet before a store
 * submission, exactly as the phone images are produced.
 *
 * `./gradlew :camera:metadata` renders this preview and collects it. The collect
 * task only replaces buckets a run actually rendered, so the hand-captured `phone/`
 * bucket survives it.
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
                // Viewfinder placeholder: the live preview pane (unrenderable in
                // Layoutlib) above the wide controls.
                Box(Modifier.fillMaxWidth().weight(1f))
                CameraWideLayout(
                    shutter = {
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
                    },
                    modes = {
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
                    },
                    modifier = Modifier.fillMaxWidth(),
                    sideBySide = true,
                )
            }
        }
    }
}
