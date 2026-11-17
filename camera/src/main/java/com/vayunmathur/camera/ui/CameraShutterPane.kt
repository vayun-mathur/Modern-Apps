package com.vayunmathur.camera.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.vayunmathur.camera.R
import com.vayunmathur.camera.platform.CameraMode

@Composable
internal fun CameraShutterPane(
    cameraMode: CameraMode,
    isRecording: Boolean,
    isCapturing: Boolean,
    recordingPaused: Boolean,
    videoSnapshotSupported: Boolean,
    galleryBitmap: android.graphics.Bitmap?,
    onCaptureResult: ((android.graphics.Bitmap?) -> Unit)?,
    onBurstStart: () -> Unit,
    onBurstStop: () -> Unit,
    onCapture: () -> Unit,
    onPauseResume: () -> Unit,
    onSnapshot: () -> Unit,
    onFlipCamera: () -> Unit,
    onGallery: () -> Unit,
    iconRotation: Float,
    flipEnabled: Boolean = true,
) {
    ShutterRow(
        cameraMode = cameraMode,
        isRecording = isRecording,
        isCapturing = isCapturing,
        recordingPaused = recordingPaused,
        videoSnapshotSupported = videoSnapshotSupported,
        galleryBitmap = galleryBitmap,
        burstEnabled = cameraMode == CameraMode.PHOTO && onCaptureResult == null,
        onBurstStart = onBurstStart,
        onBurstStop = onBurstStop,
        onCapture = onCapture,
        onPauseResume = onPauseResume,
        onSnapshot = onSnapshot,
        onFlipCamera = onFlipCamera,
        onGallery = onGallery,
        iconRotation = iconRotation,
        flipEnabled = flipEnabled
    )
}
