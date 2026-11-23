// RAW ANIMATION EXCEPTION: the panorama capture flash is imperative - an Animatable snapped to
// white as each guide dot is captured and released back to zero, with no declarative target to
// animate towards; the shutter and record-button springs are tuned to this viewfinder's own feel.
package com.vayunmathur.camera.ui

import android.graphics.Bitmap
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import com.vayunmathur.camera.Route
import com.vayunmathur.camera.platform.CameraMode
import com.vayunmathur.camera.platform.CameraViewModel
import com.vayunmathur.camera.platform.FlashMode
import com.vayunmathur.camera.platform.TimerDuration
import com.vayunmathur.camera.platform.capturePhotoForResult
import com.vayunmathur.camera.data.cycleAspectRatio
import com.vayunmathur.camera.data.setFlashMode
import com.vayunmathur.camera.platform.setQrResult
import com.vayunmathur.camera.data.setTimerDuration
import com.vayunmathur.camera.platform.startPanorama
import com.vayunmathur.camera.platform.startPhotosphere
import com.vayunmathur.camera.platform.stopPanorama
import com.vayunmathur.camera.platform.stopPhotosphere
import com.vayunmathur.camera.platform.takePhoto
import com.vayunmathur.camera.data.toggleGrid
import com.vayunmathur.camera.platform.toggleHighSpeedRecording
import com.vayunmathur.camera.data.toggleLevel
import com.vayunmathur.camera.platform.toggleMicMuted
import com.vayunmathur.camera.platform.toggleRecording
import com.vayunmathur.camera.data.toggleTorch
import com.vayunmathur.library.ui.Motion
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.animatedFloat
import com.vayunmathur.library.util.NavBackStack

@Composable
fun CameraScreen(
    backStack: NavBackStack<Route>,
    viewModel: CameraViewModel,
    onCaptureResult: ((Bitmap?) -> Unit)? = null
) {
    val view = LocalView.current
    val state = rememberCameraScreenState(viewModel)

    // The shutter action, shared by the on-screen button and the volume-key (hardware) shutter.
    val performCapture: () -> Unit = {
        // Post to the view's message queue: the hardware-shutter path collects shutterEvents
        // off the composition thread, and performHapticFeedback must run on the UI thread
        // (ThreadConstraint lint). post{} is a no-op hop when already on the UI thread.
        view.post {
            view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
        }
        if (state.longExposureProgress <= 0f) when {
            state.cameraMode == CameraMode.PHOTOSPHERE -> {
                if (state.panoSweeping) viewModel.stopPhotosphere() else viewModel.startPhotosphere()
            }
            state.cameraMode == CameraMode.PANORAMA -> {
                if (state.panoSweeping) viewModel.stopPanorama() else viewModel.startPanorama()
            }
            state.isSloMo -> viewModel.toggleHighSpeedRecording()
            state.isPhotoType -> {
                if (onCaptureResult != null) {
                    viewModel.capturePhotoForResult(
                        onSaved = { thumbnail -> onCaptureResult(thumbnail) },
                        onError = { /* stay in camera so the user can retry */ }
                    )
                } else {
                    viewModel.takePhoto()
                }
            }
            else -> viewModel.toggleRecording()
        }
    }
    val currentCapture by rememberUpdatedState(performCapture)

    CameraScreenEffects(state = state, viewModel = viewModel, onCapture = { currentCapture() })

    // Tracks physical orientation purely to rotate the on-screen control icons.
    val animatedRotation = animatedFloat(
        target = state.deviceRotation.toFloat(),
        spec = Motion.over(300)
    )

    // RAW SCAFFOLD EXCEPTION: full-bleed viewfinder with a black surface and its own custom
    // top/bottom control bars (TopBar/ShutterRow/ModeSelector/BottomBar). It has no Material
    // top app bar or title, so AppScaffold would inject an unwanted bar, and TopAppBarOverlay
    // provides neither the black container surface nor the system-inset padding the preview
    // relies on. The bare Scaffold(containerColor) is the correct primitive here.
    Scaffold(
        containerColor = Color.Black
    ) { padding ->
        BoxWithConstraints(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Fractional overlay offsets so pills scale with window height on
            // wide/short desktop windows instead of fixed dp (was 280/60/100dp).
            val qrBottomPadding = maxHeight * 0.3f
            val indicatorTopPadding = maxHeight * 0.07f
            val focusTopPadding = maxHeight * 0.12f
            Column(modifier = Modifier.fillMaxSize()) {
                CameraTopBar(
                    flashMode = state.flashMode,
                    torchEnabled = state.torchEnabled,
                    hasFlashUnit = state.hasFlashUnit,
                    screenFlashAvailable = state.screenFlashAvailable,
                    gridEnabled = state.gridEnabled,
                    levelEnabled = state.levelEnabled,
                    aspectRatio = state.aspectRatio,
                    stillCropEnabled = state.cameraMode == CameraMode.PHOTO || state.cameraMode == CameraMode.PORTRAIT,
                    isPhotoType = state.isPhotoType,
                    isVideoType = state.isVideoType,
                    micMuted = state.micMuted,
                    timerDuration = state.timerDuration,
                    onFlashToggle = {
                        if (state.torchEnabled) {
                            viewModel.toggleTorch()
                        } else {
                            val next = when (state.flashMode) {
                                FlashMode.OFF -> FlashMode.ON
                                FlashMode.ON -> FlashMode.AUTO
                                FlashMode.AUTO -> FlashMode.OFF
                            }
                            viewModel.setFlashMode(next)
                        }
                    },
                    onTorchToggle = { viewModel.toggleTorch() },
                    onGridToggle = { viewModel.toggleGrid() },
                    onLevelToggle = { viewModel.toggleLevel() },
                    onAspectCycle = { viewModel.cycleAspectRatio() },
                    onMicToggle = { viewModel.toggleMicMuted() },
                    onTimerCycle = {
                        val next = when (state.timerDuration) {
                            TimerDuration.NONE -> TimerDuration.ONE
                            TimerDuration.ONE -> TimerDuration.THREE
                            TimerDuration.THREE -> TimerDuration.FIVE
                            TimerDuration.FIVE -> TimerDuration.TEN
                            TimerDuration.TEN -> TimerDuration.NONE
                        }
                        viewModel.setTimerDuration(next)
                    },
                    iconRotation = animatedRotation
                )

                BoxWithConstraints(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clipToBounds(),
                    contentAlignment = Alignment.Center
                ) {
                    CameraPreviewBox(
                        state = state,
                        viewModel = viewModel,
                        animatedRotation = animatedRotation,
                    )
                }

                CameraBottomControls(
                    state = state,
                    viewModel = viewModel,
                    backStack = backStack,
                    onCaptureResult = onCaptureResult,
                    onCapture = { currentCapture() },
                    animatedRotation = animatedRotation,
                )
            }

            CameraStatusIndicators(
                qrResult = state.qrResult,
                onDismissQr = { viewModel.setQrResult(null) },
                context = LocalContext.current,
                qrBottomPadding = qrBottomPadding,
                isRecording = state.isRecording,
                recordingDuration = state.recordingDuration,
                indicatorTopPadding = indicatorTopPadding,
                burstActive = state.burstActive,
                burstCount = state.burstCount,
                focusLocked = state.focusLocked,
                focusTopPadding = focusTopPadding,
            )
        }
    }
}
