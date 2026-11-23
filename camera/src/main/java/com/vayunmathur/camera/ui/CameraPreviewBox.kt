package com.vayunmathur.camera.ui

import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.log.Log
import com.vayunmathur.camera.platform.BOKEH_SHADER
import com.vayunmathur.camera.platform.CameraMode
import com.vayunmathur.camera.platform.CameraViewModel
import com.vayunmathur.camera.platform.bokehBlurScale
import com.vayunmathur.camera.platform.buildColorAdjustmentMatrix
import com.vayunmathur.camera.platform.fallbackToDefaultMetering
import com.vayunmathur.camera.platform.lockFocusAndMeteringAt
import com.vayunmathur.camera.platform.onViewfinderZoomRatio
import com.vayunmathur.camera.platform.startFocusAndMeteringAt
import com.vayunmathur.camera.data.setBlurStrength
import com.vayunmathur.camera.data.setExposureCompensation
import com.vayunmathur.camera.platform.setExposureTimeIndex
import com.vayunmathur.camera.platform.setManualIsoIndex
import com.vayunmathur.camera.platform.setQrResult
import com.vayunmathur.camera.data.setShadows
import com.vayunmathur.camera.data.setWarmth
import com.vayunmathur.camera.platform.selectLens
import com.vayunmathur.camera.platform.setZoomRatio
import com.vayunmathur.camera.platform.toggleNightModeOverride
import com.vayunmathur.camera.R
import com.vayunmathur.library.ui.rememberMessenger

/** Maps the CameraX selector facing int to the pure lens-facing model. */
private fun currentLensFacing(selectorInt: Int): com.vayunmathur.camera.domain.LensFacing =
    if (selectorInt == android.hardware.camera2.CameraMetadata.LENS_FACING_FRONT)
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
    val tapToFocusState = remember { TapToFocusState() }
    val useCamera2Preview = state.useCamera2Preview
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
            // Tap-to-focus (gesture, metering, indicator) is owned by
            // TapToFocus.kt; fractions route to the Camera2 session's
            // metering transform (CameraViewModel.tapToFocusAt).
            .then(
                Modifier.tapToFocus(
                    state = tapToFocusState,
                    mirrorPreview = state.mirrorPreview,
                    onFocus = { x, y -> viewModel.startFocusAndMeteringAt(x, y) },
                    onLockFocus = { x, y -> viewModel.lockFocusAndMeteringAt(x, y) },
                    onFallback = { viewModel.fallbackToDefaultMetering() },
                )
            )

    CameraPreviewSurface(
        state = state,
        viewModel = viewModel,
        previewModifier = previewModifier,
    )

    CaptureFlashOverlay(viewModel.shutterFeedback, previewSize.clip(RoundedCornerShape(12.dp)))

    CameraPreviewOverlays(
        gridEnabled = state.gridEnabled,
        levelEnabled = state.levelEnabled,
        isPhotoType = state.isPhotoType,
        roll = state.roll,
        modifier = previewSize,
        timerCountdown = state.timerCountdown,
        longExposureProgress = state.longExposureProgress,
        longExposureRemaining = state.longExposureRemaining,
        lowLightDetected = state.lowLightDetected,
        nightExtAvailable = state.nightExtAvailable,
        nightModeActive = state.nightModeActive,
        onToggleNightOverride = { viewModel.toggleNightModeOverride() },
        iconRotation = animatedRotation,
        extensionStrength = state.visibleExtensionStrength,
        photoSessionActive = state.photoSessionActive,
        analysisStreamActive = state.analysisStreamActive,
    )

    // Above the grid/level overlays so the ring is never drawn under them;
    // below the settings column so its controls stay visually on top.
    TapToFocus(state = tapToFocusState, modifier = previewSize)

    // A lens switch rebinds the session, which would stop an active recording:
    // gated in the ViewModel and explained here instead of silently killing the take.
    val lensMessenger = rememberMessenger()
    val lensBlockedNote = stringResource(R.string.switch_blocked_while_recording)
    CameraSettingsColumn(
        activeSetting = state.activeSetting,
        availableLenses = state.availableLenses.filter { it.facing == currentLensFacing(state.lensFacing) },
        selectedLens = state.selectedLens,
        onLensSelected = { lens ->
            if (state.isRecording && lens != state.selectedLens) lensMessenger.show(lensBlockedNote)
            else viewModel.selectLens(lens)
        },
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
