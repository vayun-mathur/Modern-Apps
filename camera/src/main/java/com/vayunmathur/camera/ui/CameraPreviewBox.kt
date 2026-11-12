package com.vayunmathur.camera.ui

import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.compose.CameraXViewfinder
import androidx.camera.viewfinder.compose.MutableCoordinateTransformer
import androidx.camera.viewfinder.core.ImplementationMode
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.log.Log
import com.vayunmathur.camera.util.BOKEH_SHADER
import com.vayunmathur.camera.util.CameraMode
import com.vayunmathur.camera.util.CameraViewModel
import com.vayunmathur.camera.util.bokehBlurScale
import com.vayunmathur.camera.util.buildColorAdjustmentMatrix
import com.vayunmathur.camera.util.lockFocusAndMetering
import com.vayunmathur.camera.util.onViewfinderZoomRatio
import com.vayunmathur.camera.util.setBlurStrength
import com.vayunmathur.camera.util.setExposureCompensation
import com.vayunmathur.camera.util.setExposureTimeIndex
import com.vayunmathur.camera.util.setManualIsoIndex
import com.vayunmathur.camera.util.setQrResult
import com.vayunmathur.camera.util.setShadows
import com.vayunmathur.camera.util.setWarmth
import com.vayunmathur.camera.util.setZoomRatio
import com.vayunmathur.camera.util.toggleNightModeOverride

/** Maps the CameraX selector facing int to the pure lens-facing model. */
private fun currentLensFacing(selectorInt: Int): com.vayunmathur.camera.domain.LensFacing =
    if (selectorInt == androidx.camera.core.CameraSelector.LENS_FACING_FRONT)
        com.vayunmathur.camera.domain.LensFacing.FRONT
    else com.vayunmathur.camera.domain.LensFacing.BACK

/**
 * The still-crop ratios in [CameraScreenState.previewAspectRatio] are portrait (3:4, 9:16,
 * ...). In a landscape window the sensor frame rotates with the device, so the preview
 * box uses the reciprocal — otherwise a portrait box in a wide window collapses to a
 * narrow strip down the middle (#827). Pure so it stays unit-testable.
 */
internal fun previewRatioForWindow(baseRatio: Float, maxWidth: Float, maxHeight: Float): Float {
    val isLandscape = maxWidth > maxHeight
    return if (isLandscape && baseRatio > 0f) 1f / baseRatio else baseRatio
}

/**
 * The letterboxed viewfinder plus its gesture handling, bokeh/color RenderEffects,
 * grid/level/night overlays, settings column and panorama overlay. Extracted verbatim
 * from [CameraScreen]; behavior identical.
 */
@Composable
internal fun BoxWithConstraintsScope.CameraPreviewBox(
    state: CameraScreenState,
    viewModel: CameraViewModel,
    animatedRotation: Float,
) {
    val cameraMode = state.cameraMode
    val surfaceRequest = state.surfaceRequest
    val maskBitmap = state.maskBitmap

    // Fit the preview inside the available area (letterboxed) so a tall ratio never
    // overflows onto the top/bottom bars and steals their touch events.
    // The still-crop ratios in state are portrait (3:4, 9:16, ...). In a landscape
    // window the sensor frame rotates with the device, so use the reciprocal —
    // otherwise a portrait box in a wide window collapses to a narrow strip (#827).
    val previewRatio = previewRatioForWindow(state.previewAspectRatio, maxWidth.value, maxHeight.value)
    val boxAspect = if (maxHeight.value > 0f) maxWidth.value / maxHeight.value else 1f
    val previewSize = if (previewRatio > boxAspect) {
        Modifier.fillMaxWidth().aspectRatio(previewRatio)
    } else {
        Modifier.fillMaxHeight().aspectRatio(previewRatio)
    }
    // RuntimeShader and the runtime-shader RenderEffect are API 33+. On older
    // releases the portrait preview renders without the background blur.
    val bokehShader = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        remember { lazy { RuntimeShader(BOKEH_SHADER) } }
    } else {
        null
    }
    val coordinateTransformer = remember { MutableCoordinateTransformer() }
    val previewModifier = previewSize
        // CameraX mirrors the front-camera preview by default; counter it when the
        // user turns selfie mirroring off so the preview matches the saved image (#632).
        .graphicsLayer { scaleX = if (state.mirrorPreview) -1f else 1f }
        .clip(RoundedCornerShape(12.dp))
        .then(
            run {
                // Warmth/shadows only render where the capture path bakes them (PHOTO/PORTRAIT):
                // elsewhere the capture drops them, so showing the grade in the viewfinder is a lie.
                val bakesStills = cameraMode == CameraMode.PHOTO || cameraMode == CameraMode.PORTRAIT
                val hasColorAdj = bakesStills && (state.warmth != 0f || state.shadows != 0f)
                // Snapshot mask bitmap at this composition point – don't !! inside graphicsLayer
                // or a race with DisposableEffect onDispose (null + recycle) causes NPE at 567.
                val currentMask = maskBitmap?.takeIf {
                    !it.isRecycled && it.width > 0 && it.height > 0
                }
                val hasBokeh = cameraMode == CameraMode.PORTRAIT && currentMask != null
                Log.dev("BokehDebug", "render mode=$cameraMode hasBokeh=$hasBokeh mask=${currentMask?.width}x${currentMask?.height}")
                if (hasBokeh || hasColorAdj) {
                    Modifier.graphicsLayer {
                        var effect: RenderEffect? = null

                        if (hasBokeh &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                        ) {
                            val localMask = currentMask
                            val shader = bokehShader?.value
                            if (shader != null &&
                                !localMask.isRecycled &&
                                size.width > 0 && size.height > 0
                            ) {
                                try {
                                    val maskShader = BitmapShader(localMask, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                                    val matrix = Matrix()
                                    matrix.setScale(size.width / localMask.width, size.height / localMask.height)
                                    maskShader.setLocalMatrix(matrix)
                                    shader.setInputShader("alphaMask", maskShader)
                                    shader.setFloatUniform("blurScale", bokehBlurScale(state.blurStrength))
                                    effect = RenderEffect.createRuntimeShaderEffect(shader, "cameraInput")
                                } catch (_: Exception) {
                                    // Mask may be recycled between snapshot and render – skip bokeh this frame
                                }
                            }
                        }

                        if (hasColorAdj) {
                            val colorEffect = RenderEffect.createColorFilterEffect(
                                ColorMatrixColorFilter(
                                    buildColorAdjustmentMatrix(state.warmth, state.shadows)
                                )
                            )
                            effect = if (effect != null) {
                                RenderEffect.createChainEffect(colorEffect, effect)
                            } else {
                                colorEffect
                            }
                        }

                        renderEffect = effect?.asComposeRenderEffect()
                    }
                } else {
                    Modifier.graphicsLayer { renderEffect = null }
                }
            }
        )
            // Tap-to-focus and pinch-to-zoom are handled by CameraXViewfinder's
            // built-in gestures (below). Long-press to lock AE/AF isn't built in, so
            // keep just that here.
            .pointerInput(state.activeSetting) {
                fun meteringPoint(tapOffset: Offset) = surfaceRequest?.let { request ->
                    val transformed = with(coordinateTransformer) { tapOffset.transform() }
                    val factory = SurfaceOrientedMeteringPointFactory(
                        request.resolution.width.toFloat(),
                        request.resolution.height.toFloat()
                    )
                    factory.createPoint(transformed.x, transformed.y)
                }
                detectTapGestures(
                    onLongPress = { tapOffset ->
                        val point = meteringPoint(tapOffset) ?: return@detectTapGestures
                        viewModel.lockFocusAndMetering(
                            FocusMeteringAction.Builder(point).disableAutoCancel().build()
                        )
                    }
                )
            }

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
            // Built-in tap-to-focus and pinch-to-zoom (1.7.0-alpha02). The viewfinder
            // applies focus/zoom on the SurfaceRequest's camera; we just sync the
            // displayed zoom ratio so the zoom bar stays in step.
            isTapToFocusEnabled = true,
            isPinchToZoomEnabled = true,
            onZoomRatioChanged = { ratio -> viewModel.onViewfinderZoomRatio(ratio) },
        )
    }
    if (surfaceRequest == null) {
        // Brief gap between a mode's teardown and the next session's first frame.
        // Show a plain black fill (no debug text) so the switch reads as instant.
        Box(modifier = previewModifier.background(Color.Black))
    }
    CaptureFlashOverlay(viewModel.shutterFeedback, previewSize.clip(RoundedCornerShape(12.dp)))

    CameraPreviewOverlays(
        gridEnabled = state.gridEnabled,
        levelEnabled = state.levelEnabled,
        isPhotoType = state.isPhotoType,
        roll = state.roll,
        previewSize = previewSize,
        timerCountdown = state.timerCountdown,
        longExposureProgress = state.longExposureProgress,
        longExposureRemaining = state.longExposureRemaining,
        lowLightDetected = state.lowLightDetected,
        nightExtAvailable = state.nightExtAvailable,
        nightModeActive = state.nightModeActive,
        onToggleNightOverride = { viewModel.toggleNightModeOverride() },
        iconRotation = animatedRotation,
        extensionStrength = state.extensionStrength,
        photoSessionActive = state.photoSessionActive,
        analysisStreamActive = state.analysisStreamActive,
    )

    CameraSettingsColumn(
        activeSetting = state.activeSetting,
        availableLenses = state.availableLenses.filter { it.facing == currentLensFacing(state.lensFacing) },
        selectedLens = state.selectedLens,
        onLensSelected = { viewModel.selectLens(it) },
        zoomRatio = state.zoomRatio,
        availableZoomLevels = state.availableZoomLevels,
        onZoomSelected = { viewModel.setZoomRatio(it) },
        exposureComp = state.exposureComp,
        onExposureComp = { viewModel.setExposureCompensation(it) },
        shadows = state.shadows,
        onShadows = { viewModel.setShadows(it) },
        warmth = state.warmth,
        onWarmth = { viewModel.setWarmth(it) },
        exposureTimeIndex = state.exposureTimeIndex,
        onExposureTimeIndex = { viewModel.setExposureTimeIndex(it) },
        blurStrength = state.blurStrength,
        onBlurStrength = { viewModel.setBlurStrength(it) },
        manualIsoIndex = state.manualIsoIndex,
        isoStops = state.isoStops,
        onManualIsoIndex = { viewModel.setManualIsoIndex(it) },
        cameraMode = cameraMode,
        onSelectSetting = { state.activeSetting = it },
        modifier = Modifier.align(Alignment.BottomCenter),
        qrResult = state.qrResult,
        onDismissQr = { viewModel.setQrResult(null) },
    )

    if ((cameraMode == CameraMode.PANORAMA || cameraMode == CameraMode.PHOTOSPHERE) && (state.panoSweeping || state.panoStitching)) {
        PanoramaOverlay(
            isSweeping = state.panoSweeping,
            isStitching = state.panoStitching,
            guideDots = state.panoDots,
            currentAngle = state.panoCurrentAngle,
            currentPitch = state.panoPitch,
            modifier = previewSize
        )
    }
}
