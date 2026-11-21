@file:OptIn(kotlin.concurrent.atomics.ExperimentalAtomicApi::class)

package com.vayunmathur.translate.ui

import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import com.vayunmathur.library.log.Log
import android.util.Rational
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.ViewPort
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.LifecycleOwner
import com.vayunmathur.translate.platform.TranslateViewModel
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import android.graphics.Rect

/** CameraX binding: preview + analysis tied to one field of view, with OCR fill. */
// Broad catches are deliberate: CameraX and the OCR pipeline throw undocumented
// RuntimeExceptions (not just CameraException/IOException), which read as
// "frame dropped" or "camera unavailable" rather than a crash.
@Suppress("TooGenericExceptionCaught")
@Composable
internal fun CameraBinding(
    context: android.content.Context,
    lifecycleOwner: LifecycleOwner,
    scope: CoroutineScope,
    viewModel: TranslateViewModel,
    previewView: PreviewView,
    analysis: ImageAnalysis,
    analysisExecutor: ExecutorService,
    inFlight: AtomicBoolean,
    lastMs: AtomicLong,
    pausedValue: () -> Boolean,
    viewSize: IntSize,
    boundRotation: Int,
    onRotationChange: (Int) -> Unit,
    frameW: MutableState<Int>,
    frameH: MutableState<Int>,
    overlays: MutableState<List<OverlayBox>>,
    frozenFrame: MutableState<Bitmap?>,
    camera: MutableState<Camera?>,
    provider: MutableState<androidx.camera.lifecycle.ProcessCameraProvider?>,
) {
    // `Context.getDisplay()` throws on a non-visual context, hence the guard.
    fun displayRotation(): Int {
        val display = previewView.display ?: runCatching { context.display }.getOrNull()
        return display?.rotation ?: Surface.ROTATION_0
    }

    // The activity handles `orientation` itself (see AndroidManifest configChanges), so
    // nothing recreates on rotation. Without this the analyser keeps reporting the
    // rotation from bind time and every box lands 90° out of place.
    DisposableEffect(analysis) {
        val displayManager = context.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) = Unit
            override fun onDisplayRemoved(displayId: Int) = Unit
            override fun onDisplayChanged(displayId: Int) {
                if (previewView.display?.displayId == displayId) {
                    // Rebind rather than just retarget the analyser: the ViewPort is
                    // built for a rotation, so retargeting alone would leave the two
                    // streams disagreeing about what they are looking at again.
                    onRotationChange(displayRotation())
                }
            }
        }
        displayManager?.registerDisplayListener(listener, null)
        onDispose { displayManager?.unregisterDisplayListener(listener) }
    }

    // Release the camera when the screen goes away. Without this the torch stays lit,
    // the camera indicator stays up, and frames keep being handed to a dead executor.
    DisposableEffect(Unit) {
        onDispose {
            camera.value?.cameraControl?.enableTorch(false)
            analysis.clearAnalyzer()
            provider.value?.unbindAll()
            analysisExecutor.shutdown()
        }
    }

    // One frozen-frame Bitmap capture per pause episode (see the analyser).
    val pauseFrozen = remember { AtomicBoolean(false) }

    LaunchedEffect(viewSize, boundRotation) {
        if (viewSize.width <= 0 || viewSize.height <= 0) return@LaunchedEffect
        val cameraProvider = ProcessCameraProvider.awaitInstance(context)
        provider.value = cameraProvider
        val preview = Preview.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()
            )
            .build()
            .also { it.surfaceProvider = previewView.surfaceProvider }

        analysis.setAnalyzer(analysisExecutor) { proxy ->
            val now = System.currentTimeMillis()
            if (pausedValue()) {
                // Frozen-frame capture keeps the Bitmap path: the paused UI
                // displays a Bitmap, so one is materialised once per pause
                // episode (not per live pass). Frames arriving while an OCR
                // pass or this capture is in flight are simply dropped.
                if (pauseFrozen.load() || inFlight.load()) {
                    proxy.close()
                    return@setAnalyzer
                }
                pauseFrozen.store(true)
                inFlight.store(true)
                val frozenBmp = try {
                    proxy.toBitmap()
                } catch (e: Exception) {
                    Log.error(TAG, "toBitmap failed", e)
                    null
                }
                val frozenRotation = proxy.imageInfo.rotationDegrees
                val frozenCrop = Rect(proxy.cropRect)
                proxy.close()
                if (frozenBmp == null) {
                    inFlight.store(false)
                    return@setAnalyzer
                }
                scope.launch {
                    try {
                        frozenFrame.value = withContext(Dispatchers.Default) {
                            cropAndRotate(frozenBmp, frozenCrop, frozenRotation)
                                .also { if (it !== frozenBmp) frozenBmp.recycle() }
                        }
                    } catch (e: Exception) {
                        Log.error(TAG, "Frozen frame capture failed", e)
                    } finally {
                        inFlight.store(false)
                    }
                }
                return@setAnalyzer
            }
            pauseFrozen.store(false)
            if (inFlight.load() || now - lastMs.load() < ANALYSIS_INTERVAL_MS) {
                proxy.close()
                return@setAnalyzer
            }
            inFlight.store(true)
            val rotation = proxy.imageInfo.rotationDegrees
            // The ViewPort's share of this buffer: exactly what the preview is showing.
            // Copied because the proxy's own Rect is recycled with it.
            val crop = Rect(proxy.cropRect)
            val plane = proxy.planes.firstOrNull()
            val buffer = plane?.buffer?.takeIf {
                bufferPathEligible(it.isDirect, plane.pixelStride, rotation)
            }
            if (buffer != null) {
                // Plane-direct live pass: native reads the RGBA plane in
                // place and does the crop/rotate, so no Bitmap, getPixels
                // IntArray or JNI pixel-array copy happens at all. The
                // proxy must stay open until native has read the plane —
                // closing it here would let CameraX reuse the buffer
                // under the read — so it is closed in the finally below.
                val frameWidth = proxy.width
                val frameHeight = proxy.height
                val rowStride = plane.rowStride
                val pixelStride = plane.pixelStride
                scope.launch {
                    try {
                        val result = viewModel.ocr.recognizeDetailedBuffer(
                            buffer, frameWidth, frameHeight, rowStride, pixelStride,
                            crop.left, crop.top, crop.right, crop.bottom, rotation,
                        )
                        if (result == null) {
                            Log.error(TAG, "Buffer OCR failed; frame dropped")
                        } else {
                            val size = uprightFrameSize(frameWidth, frameHeight, crop, rotation)
                            frameW.value = size.width
                            frameH.value = size.height
                            overlays.value = result.boxes.map { box ->
                                OverlayBox(box.corners.map { Offset(it.x, it.y) }, box.text)
                            }
                            // frozenFrame is deliberately not updated: no
                            // Bitmap exists on this path. It is captured
                            // once when the user pauses (above).
                        }
                    } catch (e: Exception) {
                        Log.error(TAG, "Frame analysis failed", e)
                    } finally {
                        proxy.close()
                        // Measure the gap from the *end* of the pass, so a slow OCR run
                        // doesn't immediately trigger the next one.
                        lastMs.store(System.currentTimeMillis())
                        inFlight.store(false)
                    }
                }
                return@setAnalyzer
            }
            // Bitmap fallback (non-direct plane, non-RGBA stride, or a
            // non-quarter-turn rotation): the frozen-frame path, unchanged.
            val bmp = try {
                proxy.toBitmap()
            } catch (e: Exception) {
                Log.error(TAG, "toBitmap failed", e)
                null
            }
            proxy.close()
            if (bmp == null) {
                inFlight.store(false)
                lastMs.store(System.currentTimeMillis())
                return@setAnalyzer
            }
            // Only the state writes belong on the main thread; the rotate is a
            // full-frame copy and OCR suspends onto Dispatchers.Default itself.
            scope.launch {
                try {
                    // Cropping to the viewport before OCR means the boxes come back in
                    // the coordinates of the region the user can actually see, and OCR
                    // stops spending its time on the strips either side that the preview
                    // never shows.
                    val upright = withContext(Dispatchers.Default) {
                        cropAndRotate(bmp, crop, rotation).also { if (it !== bmp) bmp.recycle() }
                    }
                    val result = viewModel.ocr.recognizeDetailed(upright)
                    frameW.value = upright.width
                    frameH.value = upright.height
                    overlays.value = result.boxes.map { box ->
                        OverlayBox(box.corners.map { Offset(it.x, it.y) }, box.text)
                    }
                    frozenFrame.value = upright
                } catch (e: Exception) {
                    Log.error(TAG, "Frame analysis failed", e)
                } finally {
                    // Measure the gap from the *end* of the pass, so a slow OCR run
                    // doesn't immediately trigger the next one.
                    lastMs.store(System.currentTimeMillis())
                    inFlight.store(false)
                }
            }
        }

        try {
            cameraProvider.unbindAll()
            // A ViewPort is what makes the preview and the analysis stream agree on a
            // field of view. Bound as two loose use cases they each got their own crop
            // rect off a different resolution (1600×1200 vs 1280×960) — which is why the
            // frozen still was framed differently from the live preview, and why every
            // OCR box landed somewhere the text wasn't. With one, CameraX crops both to
            // the same region and hands us that region as `ImageProxy.cropRect`.
            val viewPort = ViewPort
                .Builder(Rational(viewSize.width, viewSize.height), boundRotation)
                .setScaleType(ViewPort.FILL_CENTER) // matches PreviewView's scale type
                .build()
            camera.value = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                UseCaseGroup.Builder()
                    .setViewPort(viewPort)
                    .addUseCase(preview)
                    .addUseCase(analysis)
                    .build(),
            )
            // Safe (and required) only after binding — setting it on the builder would
            // change the frame [ANALYSIS_SIZE] is resolved in.
            analysis.targetRotation = boundRotation
        } catch (e: Exception) {
            Log.error(TAG, "Camera bind failed", e)
        }
    }
}
