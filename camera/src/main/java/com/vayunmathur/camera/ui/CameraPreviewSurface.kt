package com.vayunmathur.camera.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import com.vayunmathur.camera.R
import com.vayunmathur.camera.platform.CameraViewModel
import com.vayunmathur.camera.platform.noteCamera2BindRequested
import com.vayunmathur.camera.platform.onCamera2NightPreviewFailed
import com.vayunmathur.camera.platform.onCamera2PreviewFailed
import com.vayunmathur.camera.platform.onCamera2SloMoFailed
import com.vayunmathur.camera.platform.registerCamera2ExtensionSession
import com.vayunmathur.camera.platform.registerCamera2HighSpeedSession
import com.vayunmathur.camera.platform.registerCamera2SloMoControl
import com.vayunmathur.camera.platform.onViewfinderZoomRatio
import com.vayunmathur.camera.platform.retrySessionBind
import com.vayunmathur.camera.platform.camera2AnalysisConsumer
import com.vayunmathur.camera.platform.registerCamera2Session
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.ui.ErrorState
import com.vayunmathur.library.ui.R as UiR

/**
 * The viewfinder surface itself: the gated Camera2 preview when the
 * Camera2 engine serves, otherwise the CameraX viewfinder (or the
 * bind-failed / black fallback box). Extracted verbatim from
 * [CameraPreviewBox]; overlays, effects and gestures stay with the caller.
 */
@Composable
internal fun CameraPreviewSurface(
    state: CameraScreenState,
    viewModel: CameraViewModel,
    previewModifier: Modifier,
) {
    val useCamera2Preview = state.useCamera2Preview
    val useCamera2NightPreview = state.useCamera2NightPreview
    val useCamera2SloMoPreview = state.useCamera2SloMoPreview

    if (state.camera2Recording) {
        // The recording engine owns the camera + its preview texture.
        Camera2RecordingPreview(viewModel = viewModel, modifier = previewModifier)
        return
    }

    if (useCamera2SloMoPreview) {
        // Camera2 slo-mo preview (piece 4): behind the slo-mo gate and
        // SloMoRecordingReadiness — not composed live until piece 6.
        val sloMoCamera2Id = state.selectedLens?.logicalCameraId.orEmpty()
        if (sloMoCamera2Id.isNotEmpty()) {
            Camera2SloMoPreview(
                cameraId = sloMoCamera2Id,
                lensFacing = state.selectedLens?.facing,
                onSessionChanged = { session ->
                    viewModel.registerCamera2HighSpeedSession(session)
                },
                onControlChanged = { control ->
                    viewModel.registerCamera2SloMoControl(control)
                },
                onError = { viewModel.onCamera2SloMoFailed(sloMoCamera2Id) },
                modifier = previewModifier,
            )
        } else {
            Box(modifier = previewModifier.background(Color.Black))
        }
    }

    if (useCamera2NightPreview) {
        // Camera2 NIGHT extension preview: registers the live extension
        // session so the night shutter can serve from Camera2. Takes
        // precedence over both regular previews; a probe/configure
        // failure latches the bind and the branch below serves instead.
        val nightCamera2Id = state.selectedLens?.logicalCameraId.orEmpty()
        if (nightCamera2Id.isNotEmpty()) {
            Camera2NightPreview(
                cameraId = nightCamera2Id,
                lensFacing = state.selectedLens?.facing,
                onSessionChanged = { session ->
                    viewModel.registerCamera2ExtensionSession(session)
                },
                onError = { viewModel.onCamera2NightPreviewFailed(nightCamera2Id) },
                modifier = previewModifier,
            )
        } else {
            Box(modifier = previewModifier.background(Color.Black))
        }
    }
    if (useCamera2Preview && !useCamera2NightPreview && !useCamera2SloMoPreview) {
        // Camera2 preview behind gate (CameraScreenState.useCamera2Preview, PHOTO only).
        // TextureView-backed, same overlay tree / RenderEffects below.
        // Guard: never compose with empty cameraId — fall back to CameraX black/error branch below.
        val camera2Id = state.selectedLens?.logicalCameraId.orEmpty()
        if (camera2Id.isNotEmpty()) {
            Camera2Preview(
                cameraId = camera2Id,
                lensFacing = state.selectedLens?.facing,
                zoomRatio = state.zoomRatio,
                torchOn = state.torchEnabled,
                exposureCompEv = state.exposureComp,
                flashSetting = when (state.flashMode) {
                    com.vayunmathur.camera.platform.FlashMode.ON ->
                        com.vayunmathur.camera.domain.FlashSetting.ON
                    com.vayunmathur.camera.platform.FlashMode.AUTO ->
                        com.vayunmathur.camera.domain.FlashSetting.AUTO
                    com.vayunmathur.camera.platform.FlashMode.OFF ->
                        com.vayunmathur.camera.domain.FlashSetting.OFF
                },
                onSessionChanged = { session ->
                    viewModel.registerCamera2Session(session)
                },
                // Analysis wiring: the stable dispatching consumer;
                // the per-mode sink behind it (luma/QR/motion, bokeh,
                // panorama, video motion) is installed by the screen
                // effects — see Camera2AnalysisGlue.
                analysisConsumer = viewModel.camera2AnalysisConsumer(),
                onError = { viewModel.onCamera2PreviewFailed(camera2Id) },
                onBindRequested = { viewModel.noteCamera2BindRequested() },
                modifier = previewModifier,
            )
        } else {
            Box(modifier = previewModifier.background(Color.Black))
        }
    }
    if (!useCamera2Preview && !useCamera2NightPreview && !useCamera2SloMoPreview) {
        if (state.sessionBindFailed) {
            // Bind exhausted its retries: a visible, retryable error instead
            // of parking on a black preview until the next mode/lens change.
            Box(modifier = previewModifier.background(Color.Black), contentAlignment = Alignment.Center) {
                ErrorState(
                    title = stringResource(R.string.camera_bind_failed_title),
                    message = stringResource(R.string.camera_bind_failed_message),
                    retryLabel = stringResource(UiR.string.retry),
                    onRetry = { viewModel.retrySessionBind() },
                )
            }
        } else {
            // Brief gap between a mode's teardown and the next session's first frame.
            // Show a plain black fill (no debug text) so the switch reads as instant.
            Box(modifier = previewModifier.background(Color.Black))
        }
    }
}
