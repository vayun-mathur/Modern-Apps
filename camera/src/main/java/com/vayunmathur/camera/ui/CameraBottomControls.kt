package com.vayunmathur.camera.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.provider.MediaStore
import com.vayunmathur.library.log.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.vayunmathur.camera.Route
import com.vayunmathur.camera.platform.CameraMode
import com.vayunmathur.camera.platform.CameraViewModel
import com.vayunmathur.camera.platform.captureVideoSnapshot
import com.vayunmathur.camera.platform.startBurst
import com.vayunmathur.camera.platform.stopBurst
import com.vayunmathur.camera.platform.togglePauseRecording
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.isExpandedWidth
import com.vayunmathur.library.util.NavBackStack

/**
 * Shutter row, mode selector and bottom bar below the preview, plus their adaptive
 * side-by-side placement on wide windows. The phone stack and the wide-layout slots
 * share one set of callbacks; behavior identical to the previously inline code.
 */
@Composable
internal fun CameraBottomControls(
    state: CameraScreenState,
    viewModel: CameraViewModel,
    backStack: NavBackStack<Route>,
    onCaptureResult: ((Bitmap?) -> Unit)?,
    onCapture: () -> Unit,
    animatedRotation: Float,
) {
    val context = LocalContext.current
    val cameraMode = state.cameraMode
    val expandedSide = isExpandedWidth()

    // A last-capture URI can be stale (image deleted) or absent (nothing shot
    // this session). Verify it still resolves, else open the gallery collection;
    // wrap the whole launch so a missing image never crashes the app.
    val onGallery: () -> Unit = {
        runCatching {
            val uri = state.lastCaptureUri?.takeIf {
                runCatching { context.contentResolver.getType(it) }.getOrNull() != null
            }
            val baseIntent = if (uri != null) {
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, context.contentResolver.getType(uri) ?: "image/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    clipData = ClipData.newRawUri(null, uri)
                }
            } else {
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_APP_GALLERY)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            try {
                if (!ExternalIntents.launch(context, baseIntent)) {
                    throw ActivityNotFoundException("System gallery not found")
                }
            } catch (_: ActivityNotFoundException) {
                val fallback = if (uri != null) {
                    Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, context.contentResolver.getType(uri) ?: "image/*")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        clipData = ClipData.newRawUri(null, uri)
                    }
                } else {
                    Intent(Intent.ACTION_VIEW, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
                }
                ExternalIntents.launch(context, Intent.createChooser(fallback, null))
            }
        }.onFailure { Log.status("CameraScreen", "Could not open image viewer", it) }
    }
    val onPickerChanged: (Boolean) -> Unit = { photo ->
        if (photo) {
            viewModel.switchCameraMode(CameraMode.PHOTO)
        } else {
            viewModel.switchCameraMode(CameraMode.VIDEO)
        }
    }

    // Expanded windows place the shutter and mode controls side by side
    // below the preview; short windows keep the phone stack.
    CameraWideLayout(
        shutter = {
            CameraShutterPane(
                cameraMode = cameraMode,
                isRecording = state.isRecording,
                isCapturing = state.isCapturing,
                recordingPaused = state.recordingPaused,
                videoSnapshotSupported = state.videoSnapshotSupported,
                galleryBitmap = state.galleryBitmap,
                onCaptureResult = onCaptureResult,
                onBurstStart = { viewModel.startBurst() },
                onBurstStop = { viewModel.stopBurst() },
                onCapture = onCapture,
                onPauseResume = { viewModel.togglePauseRecording() },
                onSnapshot = { viewModel.captureVideoSnapshot() },
                onFlipCamera = { viewModel.flipCamera() },
                onGallery = onGallery,
                iconRotation = animatedRotation,
                flipEnabled = !state.isSloMo,
            )
        },
        modes = {
            CameraModesPane(
                cameraMode = cameraMode,
                isPhotoType = state.isPhotoType,
                sloMoSupported = state.sloMoSupported,
                captureForResult = onCaptureResult != null,
                onModeSelected = { viewModel.switchCameraMode(it) },
                iconRotation = animatedRotation,
                onPickerChanged = onPickerChanged,
                onSettingsClick = { backStack.add(Route.Settings) },
                videoPickerEnabled = onCaptureResult == null,
            )
        },
        sideBySide = expandedSide,
    )
}
