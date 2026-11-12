package com.vayunmathur.camera.ui

import android.content.Context
import com.vayunmathur.library.log.Log
import android.view.OrientationEventListener
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.vayunmathur.camera.util.BokehAnalyzer
import com.vayunmathur.camera.util.CameraMode
import com.vayunmathur.camera.util.CameraViewModel
import com.vayunmathur.camera.util.PhotoAnalyzer
import com.vayunmathur.camera.util.addMotionFrame
import com.vayunmathur.camera.util.applyExposureCompensation
import com.vayunmathur.camera.util.applyImageCaptureFlashMode
import com.vayunmathur.camera.util.enableTorch
import com.vayunmathur.camera.util.onLuminance
import com.vayunmathur.camera.util.refreshNightExtensionUsable
import com.vayunmathur.camera.util.setBokehAnalyzer
import com.vayunmathur.camera.util.setImageAnalyzer
import com.vayunmathur.camera.util.setPhotoAnalyzer
import com.vayunmathur.camera.util.setQrResult
import com.vayunmathur.camera.util.setupHighSpeedSession
import com.vayunmathur.camera.util.setupNightPreviewSession
import com.vayunmathur.camera.util.setupPanoramaSession
import com.vayunmathur.camera.util.setupPhotoSession
import com.vayunmathur.camera.util.setupPortraitSession
import com.vayunmathur.camera.util.setupVideoSession
import com.vayunmathur.camera.util.teardownSession
import com.vayunmathur.camera.util.updateLocation
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay

/**
 * All side-effects owned by [CameraScreen]: orientation tracking, one-shot VM sync,
 * analyzer selection, the PORTRAIT bokeh segmenter lifecycle, and unified session binding.
 * Behavior is identical to the previously inline effects; only the location moved.
 */
@Composable
internal fun CameraScreenEffects(
    state: CameraScreenState,
    viewModel: CameraViewModel,
    onCapture: () -> Unit,
) {
    val context = LocalContext.current
    val cameraMode = state.cameraMode
    val lensFacing = state.lensFacing
    val selectedLens = state.selectedLens
    val sessionKind = state.sessionKind
    val useNightPreview = state.useNightPreview

    // Tracks physical orientation purely to rotate the on-screen control icons. Capture output
    // rotation is handled by CameraX via SessionConfig.setAutoRotationEnabled(true).
    DisposableEffect(Unit) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                state.deviceRotation = when {
                    orientation in 45..134 -> 270
                    orientation in 135..224 -> 180
                    orientation in 225..314 -> 90
                    else -> 0
                }
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }

    LaunchedEffect(Unit) {
        viewModel.updateLocation()
    }

    LaunchedEffect(state.flashMode) {
        viewModel.applyImageCaptureFlashMode()
    }

    LaunchedEffect(state.torchEnabled) {
        viewModel.enableTorch(state.torchEnabled)
    }

    LaunchedEffect(state.exposureComp) {
        viewModel.applyExposureCompensation(state.exposureComp)
    }

    // Close mode-specific settings when leaving the mode that offers them.
    LaunchedEffect(cameraMode) {
        val portraitOnly = state.activeSetting == CameraSetting.PORTRAIT_BLUR && cameraMode != CameraMode.PORTRAIT
        val photoOnly = state.activeSetting == CameraSetting.ISO && cameraMode != CameraMode.PHOTO
        if (portraitOnly || photoOnly) state.activeSetting = null
    }

    // Whether to offer the NIGHT extension. Reactive: the ViewModel recomputes it on lens/mode
    // change (off-main; support probe + weekly failure cache) AND flips it false immediately if a
    // night bind fails, so a broken extender (GrapheneOS/Pixel) stops engaging night after one try.
    LaunchedEffect(lensFacing, selectedLens, cameraMode) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            viewModel.refreshNightExtensionUsable(cameraMode)
        }
    }
    LaunchedEffect(useNightPreview) {
        Log.dev("NightPreview", "CameraScreen useNightPreview recomputed=$useNightPreview sessionKind=$sessionKind cameraMode=$cameraMode nightModeActive=${state.nightModeActive} nightExtAvailable=${state.nightExtAvailable} lensFacing=$lensFacing")
    }
    val surfaceRequest = state.surfaceRequest
    LaunchedEffect(surfaceRequest) {
        Log.dev("NightPreview", "CameraScreen surfaceRequest changed res=${surfaceRequest?.resolution} frameType=${surfaceRequest?.javaClass?.simpleName} useNightPreview=$useNightPreview sessionKind=$sessionKind thread=${Thread.currentThread().name} lensFacing=${lensFacing}")
        if (surfaceRequest == null && useNightPreview) {
            Log.status("NightPreview", "CameraScreen WARNING: surfaceRequest is NULL while useNightPreview=true – preview will be black until Provider re-emits. This is the black-frame trap you described: teardown -> delay(250) -> setupNightPreviewSession() tears down standard photo session surface before extension emits!")
        }
        if (surfaceRequest == null && !useNightPreview) {
            Log.status("NightPreview", "CameraScreen tracing maybe delayed rebind? surface null while normal photo – expected during 250ms delay window")
        }
    }
    LaunchedEffect(state.availableZoomLevels) {
        Log.dev("NightPreview", "CameraScreen availableZoomLevels changed=${state.availableZoomLevels} currentZoom=${state.zoomRatio} useNightPreview=$useNightPreview – vendor NIGHT often reports only [1x], making zoom bar appear to 'disappear' except 1x")
    }

    // Analyzer selection for the photo modes. Keyed on photoSessionActive so the analyzer is
    // re-applied to the freshly-bound ImageAnalysis after every (re)bind (mode switch / flip), and
    // on lensFacing + selectedLens to match the DisposableEffect below, which detaches on a
    // flip or lens switch.
    LaunchedEffect(cameraMode, state.photoSessionActive, lensFacing, selectedLens) {
        when {
            cameraMode == CameraMode.SLOW_MO -> {
                state.maskBitmap = null
                viewModel.setImageAnalyzer(null)
                viewModel.setQrResult(null)
            }
            state.isPhotoType -> {
                when (cameraMode) {
                    CameraMode.PORTRAIT -> {
                        // Analyzer lifecycle (create/close) managed by DisposableEffect below.
                    }
                    CameraMode.PANORAMA, CameraMode.PHOTOSPHERE -> {
                        state.maskBitmap = null
                        viewModel.setImageAnalyzer(
                            ImageAnalysis.Analyzer { imageProxy ->
                                // Atomic publish; captureFrame() takes ownership exactly once, and
                                // offerFrame() recycles any undelivered previous bitmap (no leak,
                                // no use-after-free across the analyzer/sensor threads).
                                viewModel.panoramaEngine.offerFrame(
                                    imageProxy.toBitmap(),
                                    imageProxy.imageInfo.rotationDegrees
                                )
                                imageProxy.close()
                            }
                        )
                    }
                    else -> {
                        state.maskBitmap = null
                        viewModel.setPhotoAnalyzer(
                            PhotoAnalyzer(
                                onLuminance = { viewModel.onLuminance(it) },
                                onQrDetected = { viewModel.setQrResult(it) },
                                // Motion-Photo ring buffer only in plain PHOTO mode.
                                onMotionFrame = if (cameraMode == CameraMode.PHOTO) {
                                    { bmp, ts, rot -> viewModel.addMotionFrame(bmp, ts, rot) }
                                } else null
                            )
                        )
                    }
                }
            }
            else -> {
                state.maskBitmap = null
                viewModel.setQrResult(null)
            }
        }
    }

    // Owns the PORTRAIT bokeh segmenter so it is closed (and its mask recycled) on mode change.
    // Keyed on photoSessionActive (re-attach after a rebind) and lensFacing (front/back changes
    // the frame orientation/mirroring the analyzer must apply).
    // Runs on dedicated bokeh executor so main thread stays free for preview rendering.
    // Final capture ImageCapture is still max-res – only analysis is capped.
    // Mask callback comes from bokeh thread; post recycle+set to main to avoid racing with
    // the RenderEffect reading the previous Bitmap on the UI thread.
    val mainHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    DisposableEffect(cameraMode, state.photoSessionActive, lensFacing, selectedLens) {
        val analyzer = if (cameraMode == CameraMode.PORTRAIT) {
            BokehAnalyzer(
                context,
                isFrontFacing = lensFacing == CameraSelector.LENS_FACING_FRONT
            ) { mask ->
                Log.dev("BokehDebug", "mask arrived ${mask.width}x${mask.height} recycled=${mask.isRecycled}")
                if (mask.isRecycled) return@BokehAnalyzer
                mainHandler.post {
                    // If mask was recycled while message queued, drop it
                    if (mask.isRecycled) {
                        Log.dev("BokehDebug", "mask recycled before post ran")
                        return@post
                    }
                    val prev = state.maskBitmap
                    if (prev != null && prev !== mask && !prev.isRecycled) {
                        try { prev.recycle() } catch (_: Exception) {}
                    }
                    state.maskBitmap = mask
                    Log.dev("BokehDebug", "mask stored ${mask.width}x${mask.height}")
                }
            }.also {
                viewModel.setBokehAnalyzer(it)
            }
        } else null
        onDispose {
            // Only detach what this effect attached. It also keys on lensFacing, which the
            // analyzer-selection effect above does not, so an unconditional clear stripped
            // PhotoAnalyzer off the photo stream on every camera flip.
            if (analyzer != null) {
                viewModel.setImageAnalyzer(null)
                analyzer.close()
            }
            // Post recycle to avoid racing with graphicsLayer reading maskBitmap
            mainHandler.post {
                state.maskBitmap?.let { bmp ->
                    if (!bmp.isRecycled) {
                        try { bmp.recycle() } catch (_: Exception) {}
                    }
                }
                state.maskBitmap = null
            }
        }
    }

    // Unified session binding, made lifecycle-aware so the camera is released when the app is
    // backgrounded and rebound on resume. Without this the ManualLifecycleOwner stays RESUMED,
    // the OS reclaims the camera while we're away, and the preview comes back frozen.
    val lifecycleOwner = LocalLifecycleOwner.current
    // Codec/audio/mirror are fixed at bind time (Recorder mime, AudioSource, MirrorMode), so
    // they join the effect keys: changing them in Settings rebinds instead of silently waiting
    // for the next mode switch. Aspect ratio is applied live (setCropAspectRatio) — no rebind.
    // The photo session's capture mode (ZSL or not) is fixed at bind time too, so it rebinds when
    // manual exposure or night mode makes ZSL unsafe, or safe again (#914). Other kinds ignore it.
    val zslCompatible by viewModel.zslCompatible.collectAsState()
    val zslKey = if (sessionKind == SessionKind.PHOTO) zslCompatible else null
    LaunchedEffect(cameraMode, lensFacing, selectedLens, sessionKind, useNightPreview, zslKey, state.videoCodec, state.audioInputSource, state.mirrorFront, lifecycleOwner) {
        Log.dev("NightPreview", "CameraScreen session LaunchedEffect START keys zsl=$zslKey lensFacing=$lensFacing selectedLens=${selectedLens?.labelKey} sessionKind=$sessionKind useNightPreview=$useNightPreview codec=${state.videoCodec} audio=${state.audioInputSource} mirror=${state.mirrorFront} lifecycle=${lifecycleOwner.lifecycle.currentState} thread=${Thread.currentThread().name}")
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            Log.dev("NightPreview", "CameraScreen repeatOnLifecycle STARTED – calling teardownSession()")
            viewModel.teardownSession()
            // Brief yield so the previous session's surface detaches before the next binds; short
            // enough that a mode switch reads as instant, long enough to avoid a bind-over-teardown race.
            delay(40)
            val bindStart = System.currentTimeMillis()
            val bindSuccess = try {
                when (sessionKind) {
                    SessionKind.HIGH_SPEED -> viewModel.setupHighSpeedSession()
                    SessionKind.VIDEO -> viewModel.setupVideoSession()
                    SessionKind.PANORAMA -> viewModel.setupPanoramaSession()
                    SessionKind.PORTRAIT -> viewModel.setupPortraitSession()
                    SessionKind.PHOTO ->
                        if (useNightPreview) viewModel.setupNightPreviewSession()
                        else viewModel.setupPhotoSession()
                }
            } catch (e: IllegalStateException) {
                Log.error("NightPreview", "CameraScreen session binding THREW (was not logged before) kind=$sessionKind useNightPreview=$useNightPreview nightExtAvailable=${state.nightExtAvailable}", e)
                false
            } catch (e: IllegalArgumentException) {
                Log.error("NightPreview", "CameraScreen session binding THREW (was not logged before) kind=$sessionKind useNightPreview=$useNightPreview nightExtAvailable=${state.nightExtAvailable}", e)
                false
            }
            Log.dev("NightPreview", "CameraScreen session bind finished success=$bindSuccess took=${System.currentTimeMillis() - bindStart}ms surface after=${viewModel.surfaceRequest.value?.resolution} zoomLevels=${viewModel.availableZoomLevels.value} zoomRatio=${viewModel.zoomRatio.value}")
            if (!bindSuccess) {
                Log.error("NightPreview", "CameraScreen BIND FAILED – this produces solid black preview and zoom bar showing only 1x (fallback?). Check logcat for NightPreview tag – exception was previously swallowed as warning")
            }
            try {
                awaitCancellation()
            } finally {
                Log.dev("NightPreview", "CameraScreen session coroutine cancelled/finished, calling teardownSession()")
                viewModel.teardownSession()
            }
        }
        Log.dev("NightPreview", "CameraScreen repeatOnLifecycle block EXIT – lifecycle dropped below STARTED")
    }

    // Hardware volume-key shutter.
    val currentCapture = rememberUpdatedState(onCapture)
    LaunchedEffect(Unit) {
        viewModel.foregroundRoute = "camera"
        try {
            viewModel.shutterEvents.collect { currentCapture.value() }
        } finally {
            viewModel.foregroundRoute = null
        }
    }
}
