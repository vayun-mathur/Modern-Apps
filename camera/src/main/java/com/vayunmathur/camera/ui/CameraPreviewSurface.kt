package com.vayunmathur.camera.ui

import androidx.camera.core.DynamicRange
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.viewfinder.compose.MutableCoordinateTransformer
import androidx.camera.viewfinder.core.ImplementationMode
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
import com.vayunmathur.camera.platform.onCamera2NightPreviewFailed
import com.vayunmathur.camera.platform.onCamera2PreviewFailed
import com.vayunmathur.camera.platform.registerCamera2ExtensionSession
import com.vayunmathur.camera.platform.onViewfinderZoomRatio
import com.vayunmathur.camera.platform.retrySessionBind
import com.vayunmathur.camera.platform.camera2AnalysisConsumer
import com.vayunmathur.camera.platform.camera2AnalysisServes
import com.vayunmathur.camera.platform.registerCamera2Session
import com.vayunmathur.camera.platform.setScreenFlash
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
    coordinateTransformer: MutableCoordinateTransformer,
) {
    val useCamera2Preview = state.useCamera2Preview
    val useCamera2NightPreview = state.useCamera2NightPreview
    val surfaceRequest = state.surfaceRequest

    if (useCamera2NightPreview) {
        // Camera2 NIGHT extension preview: registers the live extension
        // session so the night shutter can serve from Camera2. Takes
        // precedence over both regular previews; a probe/configure
        // failure latches the bind and the branch below serves instead.
        val nightCamera2Id = state.selectedLens?.logicalCameraId.orEmpty()
        if (nightCamera2Id.isNotEmpty()) {
            Camera2NightPreview(
                cameraId = nightCamera2Id,
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
    if (useCamera2Preview && !useCamera2NightPreview) {
        // Camera2 preview behind gate (CameraScreenState.useCamera2Preview, PHOTO only).
        // TextureView-backed, same overlay tree / RenderEffects below.
        // Guard: never compose with empty cameraId — fall back to CameraX black/error branch below.
        val camera2Id = state.selectedLens?.logicalCameraId.orEmpty()
        if (camera2Id.isNotEmpty()) {
            Camera2Preview(
                cameraId = camera2Id,
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
                // Analysis wiring: luma-only plane consumer when policy
                // selects Camera2; QR/bitmap sessions stay CameraX (they
                // never compose this preview).
                analysisConsumer = if (viewModel.camera2AnalysisServes()) {
                    viewModel.camera2AnalysisConsumer()
                } else {
                    null
                },
                onError = { viewModel.onCamera2PreviewFailed(camera2Id) },
                modifier = previewModifier,
            )
        } else {
            Box(modifier = previewModifier.background(Color.Black))
        }
    }
    if (!useCamera2Preview && !useCamera2NightPreview) {
    surfaceRequest?.let { request ->
        LaunchedEffect(request) {
            Log.dev("NightPreview", "CameraXViewfinder COMPOSED with request res=${request.resolution} dynamicRange=${request.dynamicRange} useNightPreview=${state.useNightPreview} sessionKind=${state.sessionKind} zoomRatio=${state.zoomRatio} levels=${state.availableZoomLevels} thread=${Thread.currentThread().name}")
        }
        DisposableEffect(request) {
            Log.dev("NightPreview", "CameraXViewfinder DisposableEffect ATTACH request res=${request.resolution} useNightPreview=${state.useNightPreview}")
            onDispose {
                Log.dev("NightPreview", "CameraXViewfinder DisposableEffect DETACH request res=${request.resolution} useNightPreview=${state.useNightPreview} – surface will be invalidated, if next request fails to emit we go black")
            }
        }
        CameraXViewfinder(
            surfaceRequest = request,
            modifier = previewModifier,
            // HDR (HLG 10-bit) video previews must render through a SurfaceView
            // (EXTERNAL): the EMBEDDED/TextureView path doesn't tone-map the BT.2020
            // HLG buffer, which shows up as a reddish/pink cast in video mode. SDR
            // (photo) stays EMBEDDED so graphicsLayer RenderEffects (warmth, bokeh)
            // still composite onto the preview.
            implementationMode = if (
                request.dynamicRange.bitDepth != DynamicRange.BIT_DEPTH_8_BIT
            ) {
                ImplementationMode.EXTERNAL
            } else {
                ImplementationMode.EMBEDDED
            },
            coordinateTransformer = coordinateTransformer,
            alignment = Alignment.Center,
            // Crop (fill) so the camera feed fills the ratio-shaped box for the
            // selected aspect ratio (1:1 / 3:2 / 16:9 / 4:3). Fit letterboxed the native
            // 4:3 frame inside the box, making the preview shrink instead of reshape.
            contentScale = ContentScale.Crop,
            // Pinch-to-zoom stays built-in (1.7.0-alpha02); we just sync the
            // displayed zoom ratio so the zoom bar stays in step. Tap-to-focus
            // is DISABLED here — it is handled by the tapToFocus modifier on
            // previewModifier above, which shows the focus ring and observes
            // (and falls back from) the metering result. Leaving both enabled
            // double-metered every tap and let the built-in detector swallow
            // taps the app never saw.
            isTapToFocusEnabled = false,
            isPinchToZoomEnabled = true,
            onZoomRatioChanged = { ratio -> viewModel.onViewfinderZoomRatio(ratio) },
            // CameraX owns the screen-flash UI (white overlay + window brightness):
            // hand its ScreenFlash to the ViewModel so ImageCapture can invoke it
            // for front captures on lenses with no flash unit. Never hand-roll
            // brightness here.
            onScreenFlashReady = { screenFlash -> viewModel.setScreenFlash(screenFlash) },
        )
    }
    if (!useCamera2Preview && !useCamera2NightPreview && surfaceRequest == null) {
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
}
