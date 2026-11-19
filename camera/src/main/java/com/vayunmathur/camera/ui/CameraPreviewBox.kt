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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.vayunmathur.camera.platform.BOKEH_SHADER
import com.vayunmathur.camera.platform.CameraMode
import com.vayunmathur.camera.platform.CameraViewModel
import com.vayunmathur.camera.platform.bokehBlurScale
import com.vayunmathur.camera.platform.buildColorAdjustmentMatrix
import com.vayunmathur.camera.platform.fallbackToDefaultMetering
import com.vayunmathur.camera.platform.lockFocusAndMetering
import com.vayunmathur.camera.platform.onViewfinderZoomRatio
import com.vayunmathur.camera.platform.startFocusAndMetering
import com.vayunmathur.camera.data.setBlurStrength
import com.vayunmathur.camera.data.setExposureCompensation
import com.vayunmathur.camera.platform.setExposureTimeIndex
import com.vayunmathur.camera.platform.setManualIsoIndex
import com.vayunmathur.camera.platform.setQrResult
import com.vayunmathur.camera.data.setShadows
import com.vayunmathur.camera.data.setWarmth
import com.vayunmathur.camera.platform.setZoomRatio
import com.vayunmathur.camera.platform.toggleNightModeOverride

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

/** Minimum gap between two tap-to-focus requests; faster repeats are one gesture, not two. */
private const val FOCUS_TAP_DEBOUNCE_MS = 120L

/** How long the focus ring stays up (its own fade runs inside [FocusIndicator]). */
private const val FOCUS_INDICATOR_LIFETIME_MS = 1_350L

/**
 * Normalises a coordinate-transformer output (surface-buffer pixels) into the
 * 0..1 range [androidx.camera.core.MeteringPoint] expects, clamped to the
 * surface. Pure so it stays unit-testable.
 *
 * The clamp matters: with `ContentScale.Crop` a tap at the box edge can map
 * just outside the buffer, and CameraX rejects an out-of-range point — the
 * future then fails and, before this fix, was discarded unread, so the tap
 * silently did nothing. A non-finite transform (matrix not populated yet) or
 * a degenerate surface falls back to the centre rather than dropping the tap.
 */
internal fun normalizedMeteringCoordinates(
    transformedX: Float,
    transformedY: Float,
    surfaceWidth: Float,
    surfaceHeight: Float,
): Pair<Float, Float> {
    if (!transformedX.isFinite() || !transformedY.isFinite() || surfaceWidth <= 0f || surfaceHeight <= 0f) {
        return 0.5f to 0.5f
    }
    return (transformedX / surfaceWidth).coerceIn(0f, 1f) to
        (transformedY / surfaceHeight).coerceIn(0f, 1f)
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
    // Tap-to-focus indicator: small in-screen sub-state (deliberately NOT
    // navigation — a focus point is transient state of this one screen).
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var focusTapId by remember { mutableIntStateOf(0) }
    var focusPointLocked by remember { mutableStateOf(false) }
    var lastFocusTapMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(focusTapId) {
        if (focusTapId > 0) {
            kotlinx.coroutines.delay(FOCUS_INDICATOR_LIFETIME_MS)
            focusPoint = null
        }
    }
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
            // Tap-to-focus is handled HERE, on the preview surface itself, in
            // the same single detector as long-press-to-lock. CameraXViewfinder's
            // built-in tap gesture is disabled below: it raced this detector,
            // gave no tap indicator (its onTapToFocus callback was never wired),
            // silently returned when the transformed point left 0..1, and its
            // result was invisible to the app. Pinch-to-zoom stays built-in.
            //
            // Keyed on surfaceRequest + mirrorPreview, NOT only on
            // activeSetting as before: with the old key the block captured the
            // first-composed surfaceRequest (null before the session binds)
            // for as long as the settings pane stayed closed, so every
            // long-press metering point came back null and was dropped.
            .pointerInput(surfaceRequest, state.mirrorPreview) {
                fun meteringPoint(tapOffset: Offset): androidx.camera.core.MeteringPoint? {
                    val request = surfaceRequest ?: return null
                    return try {
                        val transformed = with(coordinateTransformer) { tapOffset.transform() }
                        val width = request.resolution.width.toFloat()
                        val height = request.resolution.height.toFloat()
                        val (nx, ny) = normalizedMeteringCoordinates(transformed.x, transformed.y, width, height)
                        val factory = if (width > 0f && height > 0f) {
                            SurfaceOrientedMeteringPointFactory(width, height)
                        } else {
                            SurfaceOrientedMeteringPointFactory(1f, 1f)
                        }
                        val surfaceW = if (width > 0f) width else 1f
                        val surfaceH = if (height > 0f) height else 1f
                        factory.createPoint(nx * surfaceW, ny * surfaceH)
                    } catch (e: Exception) {
                        Log.status("TapToFocus", "metering-point conversion failed; using centre point", e)
                        try {
                            SurfaceOrientedMeteringPointFactory(1f, 1f).createPoint(0.5f, 0.5f)
                        } catch (_: Exception) {
                            null
                        }
                    }
                }

                fun showIndicator(tapOffset: Offset, locked: Boolean) {
                    // tapOffset is in the (possibly extra-mirrored) preview
                    // layer's coordinates; the indicator is drawn by a sibling
                    // outside that layer, so flip back into visual coordinates.
                    focusPoint = if (state.mirrorPreview) {
                        Offset(size.width - tapOffset.x, tapOffset.y)
                    } else {
                        tapOffset
                    }
                    focusPointLocked = locked
                    focusTapId++
                }
                detectTapGestures(
                    onTap = { tapOffset ->
                        val now = android.os.SystemClock.uptimeMillis()
                        if (now - lastFocusTapMs < FOCUS_TAP_DEBOUNCE_MS) return@detectTapGestures
                        lastFocusTapMs = now
                        // Indicator first, regardless of AF outcome: it proves
                        // the tap registered even if metering then fails.
                        showIndicator(tapOffset, locked = false)
                        val point = meteringPoint(tapOffset)
                        if (point == null) {
                            Log.status("TapToFocus", "tap with no surface request; falling back to default metering")
                            viewModel.fallbackToDefaultMetering()
                        } else {
                            viewModel.startFocusAndMetering(
                                FocusMeteringAction.Builder(point).build()
                            )
                        }
                    },
                    onLongPress = { tapOffset ->
                        showIndicator(tapOffset, locked = true)
                        val point = meteringPoint(tapOffset)
                        if (point == null) {
                            Log.status("TapToFocus", "long-press with no surface request; cannot lock metering")
                            viewModel.fallbackToDefaultMetering()
                        } else {
                            viewModel.lockFocusAndMetering(
                                FocusMeteringAction.Builder(point).disableAutoCancel().build()
                            )
                        }
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
            // Pinch-to-zoom stays built-in (1.7.0-alpha02); we just sync the
            // displayed zoom ratio so the zoom bar stays in step. Tap-to-focus
            // is DISABLED here — it is handled by the pointerInput on
            // previewModifier above, which shows the focus ring and observes
            // (and falls back from) the metering result. Leaving both enabled
            // double-metered every tap and let the built-in detector swallow
            // taps the app never saw.
            isTapToFocusEnabled = false,
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
        modifier = previewSize,
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

    // Above the grid/level overlays so the ring is never drawn under them;
    // below the settings column so its controls stay visually on top. The
    // Canvas carries no pointer input, so it never eats the next tap.
    focusPoint?.let { point ->
        FocusIndicator(
            point = point,
            tapId = focusTapId,
            locked = focusPointLocked,
            modifier = previewSize,
        )
    }

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
