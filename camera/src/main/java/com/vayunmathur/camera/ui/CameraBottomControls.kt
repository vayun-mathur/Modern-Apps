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
import com.vayunmathur.camera.platform.flipCamera
import com.vayunmathur.camera.platform.switchCameraMode
import com.vayunmathur.camera.platform.captureVideoSnapshot
import com.vayunmathur.camera.platform.startBurst
import com.vayunmathur.camera.platform.stopBurst
import com.vayunmathur.camera.platform.togglePauseRecording
import androidx.compose.ui.res.stringResource
import com.vayunmathur.camera.R
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.library.util.NavBackStack

/**
 * Shutter row, mode selector and bottom bar below the preview. Always stacked
 * in that order — rotating the phone never rearranges them (see
 * [captureControlsSideBySide]); only the icons inside rotate in place.
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
    // Mode switches rebind the session, which would stop an active recording:
    // gated in the ViewModel and explained here instead of silently killing the take.
    val messenger = rememberMessenger()
    val switchBlockedNote = stringResource(R.string.switch_blocked_while_recording)
    val onModeSelected: (CameraMode) -> Unit = { mode ->
        if (state.isRecording && mode != cameraMode) messenger.show(switchBlockedNote)
        else viewModel.switchCameraMode(mode)
    }
    val onPickerChanged: (Boolean) -> Unit = { photo ->
        if (photo) {
            onModeSelected(CameraMode.PHOTO)
        } else {
            onModeSelected(CameraMode.VIDEO)
        }
    }

    // Never side-by-side on rotation: shutter, then modes, stacked below the
    // preview at every device rotation.
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
                // QR lives on the photo side of the picker (its row and the
                // photo icon's selected state), though it captures nothing.
                isPhotoType = state.isPhotoType || state.isQr,
                sloMoSupported = state.sloMoSupported,
                captureForResult = onCaptureResult != null,
                onModeSelected = onModeSelected,
                iconRotation = animatedRotation,
                onPickerChanged = onPickerChanged,
                onSettingsClick = { backStack.add(Route.Settings) },
                videoPickerEnabled = onCaptureResult == null,
            )
        },
        sideBySide = captureControlsSideBySide(state.deviceRotation),
    )
}
