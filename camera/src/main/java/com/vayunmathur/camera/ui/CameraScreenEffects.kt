package com.vayunmathur.camera.ui

import android.content.Context
import com.vayunmathur.library.log.Log
import android.view.OrientationEventListener
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
import com.vayunmathur.camera.platform.BokehAnalyzer
import com.vayunmathur.camera.platform.CameraMode
import com.vayunmathur.camera.platform.CameraViewModel
import com.vayunmathur.camera.platform.PhotoAnalyzer
import com.vayunmathur.camera.platform.addMotionFrame
import com.vayunmathur.camera.platform.applyExposureCompensation
import com.vayunmathur.camera.platform.clearCamera2Fallback
import com.vayunmathur.camera.platform.clearCamera2NightFallback
import com.vayunmathur.camera.platform.applyImageCaptureFlashMode
import com.vayunmathur.camera.platform.enableTorch
import com.vayunmathur.camera.platform.onLuminance
import com.vayunmathur.camera.platform.refreshNightExtensionUsable
import com.vayunmathur.camera.platform.runSessionBindWithRetry
import com.vayunmathur.camera.platform.setQrResult
import com.vayunmathur.camera.platform.setAnalysisSink
import com.vayunmathur.camera.platform.onSceneMotionSample
import com.vayunmathur.camera.platform.camera2.toBitmapFromPlanes
import com.vayunmathur.camera.platform.teardownSession
import com.vayunmathur.camera.data.updateLocation
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
    val useCamera2Preview = state.useCamera2Preview
    val useCamera2NightPreview = state.useCamera2NightPreview
    val useCamera2SloMoPreview = state.useCamera2SloMoPreview

    // Tracks physical orientation purely to rotate the on-screen control icons. Capture output
    // rotation is handled by CameraX via SessionConfig.setAutoRotationEnabled(true).
    DisposableEffect(Unit) {
        val listener = object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                // Unknown angle keeps the last rotation (helper returns null).
                state.deviceRotation = deviceRotationForOrientation(orientation) ?: return
            }
        }
        listener.enable()
        onDispose { listener.disable() }
    }

    LaunchedEffect(Unit) {
        viewModel.updateLocation()
    }

    // Lens facing / flash-unit changes flip the resolution (hardware <-> screen)
    // without a flashMode change, so they re-apply too; the session rebind that
    // follows picks the same resolution up in its ImageCapture builder.
    LaunchedEffect(state.flashMode, state.lensFacing, state.hasFlashUnit) {
        viewModel.applyImageCaptureFlashMode()
    }

    LaunchedEffect(state.torchEnabled) {
        viewModel.enableTorch(state.torchEnabled)
    }

    LaunchedEffect(state.exposureComp) {
        viewModel.applyExposureCompensation(state.exposureComp)
    }

    // Re-render (not close) the settings pane on a mode switch: a pane the
    // new mode also offers (BRIGHTNESS/ZOOM in VIDEO) stays open with its
    // correct control; a photo-only pane (SHADOWS/WARMTH/EXPOSURE_TIME/
    // ISO/PORTRAIT_BLUR) is cleared so the column falls back to the new
    // mode's lens/zoom bar instead of showing the stale photo slider, and
    // does not resurrect when switching back. CameraSettingsColumn applies
    // the same effectiveSettingForMode check at render time.
    LaunchedEffect(cameraMode) {
        if (effectiveSettingForMode(state.activeSetting, cameraMode) != state.activeSetting) {
            state.activeSetting = null
        }
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
    // A new lens/mode is a fresh bind: clear the Camera2 extension-preview
    // failure latch so the next night engagement gets one honest attempt.
    LaunchedEffect(selectedLens, cameraMode) {
        viewModel.clearCamera2NightFallback()
    }
    LaunchedEffect(state.availableZoomLevels) {
        Log.dev("NightPreview", "CameraScreen availableZoomLevels changed=${state.availableZoomLevels} currentZoom=${state.zoomRatio} useNightPreview=$useNightPreview – vendor NIGHT often reports only [1x], making zoom bar appear to 'disappear' except 1x")
    }

    // Analyzer selection for the photo modes. Keyed on photoSessionActive so the analyzer is
    // re-applied to the freshly-bound ImageAnalysis after every (re)bind (mode switch / flip), and
    // on lensFacing + selectedLens to match the DisposableEffect below, which detaches on a
    // flip or lens switch.
    LaunchedEffect(cameraMode, lensFacing, selectedLens) {
        when {
            cameraMode == CameraMode.SLOW_MO -> {
                state.maskBitmap = null
                viewModel.setAnalysisSink(null)
                viewModel.setQrResult(null)
            }
            cameraMode == CameraMode.PORTRAIT -> {
                // Bokeh sink lifecycle (create/close) managed by DisposableEffect below.
            }
            cameraMode == CameraMode.PANORAMA || cameraMode == CameraMode.PHOTOSPHERE -> {
                state.maskBitmap = null
                // Converted bitmaps off the Camera2 analysis stream;
                // offerFrame takes ownership and recycles undelivered
                // previous bitmaps (no leak across threads).
                viewModel.setAnalysisSink { frame ->
                    frame.toBitmapFromPlanes()?.let { bmp ->
                        viewModel.panoramaEngine.offerFrame(bmp, frame.rotationDegrees)
                    }
                }
            }
            state.isVideoType -> {
                state.maskBitmap = null
                val motion = com.vayunmathur.camera.platform.VideoMotionAnalyzer { sample ->
                    viewModel.onSceneMotionSample(sample)
                }
                viewModel.setAnalysisSink { frame -> motion.onYPlane(frame.yPlane) }
            }
            state.isPhotoType -> {
                state.maskBitmap = null
                val analyzer = PhotoAnalyzer(
                    onLuminance = { viewModel.onLuminance(it) },
                    onQrDetected = { viewModel.setQrResult(it) },
                    // Motion-Photo ring buffer only in plain PHOTO mode.
                    onMotionFrame = if (cameraMode == CameraMode.PHOTO) {
                        { bmp, ts, rot -> viewModel.addMotionFrame(bmp, ts, rot) }
                    } else null,
                )
                viewModel.setAnalysisSink(analyzer::onAnalysisFrame)
            }
            else -> {
                state.maskBitmap = null
                viewModel.setAnalysisSink(null)
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
                isFrontFacing = lensFacing == android.hardware.camera2.CameraMetadata.LENS_FACING_FRONT
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
            }.also { bokeh ->
                // Converted bitmaps off the Camera2 analysis stream,
                // rotated by the frame's metadata (BokehAnalyzer.onFrame).
                viewModel.setAnalysisSink { frame ->
                    frame.toBitmapFromPlanes()?.let { bmp ->
                        bokeh.onFrame(bmp, frame.rotationDegrees)
                    }
                }
            }
        } else null
        onDispose {
            // Only detach what this effect attached. It also keys on lensFacing, which the
            // analyzer-selection effect above does not, so an unconditional clear stripped
            // PhotoAnalyzer off the photo stream on every camera flip.
            if (analyzer != null) {
                viewModel.setAnalysisSink(null)
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
    // for the next mode switch. The keys are the APPLIED snapshot (bindTimeSettings), not the
    // live desired values: while recording the snapshot is held, so a Settings change made
    // mid-recording (Settings is a separate route over this screen) persists without tearing
    // down the session — and the recording — and only rebinds when the snapshot releases at
    // stop. Aspect ratio is applied live (setCropAspectRatio) — no rebind.
    // The photo session's capture mode (ZSL or not) is fixed at bind time too, so it rebinds when
    // manual exposure or night mode makes ZSL unsafe, or safe again (#914). Other kinds ignore it.
    val zslCompatible by viewModel.zslCompatible.collectAsState()
    val zslKey = if (sessionKind == SessionKind.PHOTO) zslCompatible else null
    val bindSettings by viewModel.bindTimeSettings.collectAsState()
    LaunchedEffect(cameraMode, lensFacing, selectedLens, sessionKind, useNightPreview, useCamera2Preview, useCamera2NightPreview, useCamera2SloMoPreview, zslKey, bindSettings, state.sessionBindRetry, lifecycleOwner) {
        // Camera2 preview engine (Phase 2, PHOTO, no other outputs yet) owns a
        // camera via Camera2Session directly. SetupPhotoSession() would double-open
        // the same cameraId — Camera2 open fails ERROR_CAMERA_IN_USE. So keep full
        // CameraX teardown lifecycle, skip CameraX Preview/ImageCapture/ImageAnalysis
        // preview binding, and let Camera2Preview compose the surface.
        // Same for the Camera2 NIGHT extension preview: its extension
        // session owns the camera, so CameraX (including its own night
        // extension session) must not bind alongside it.
        if (useCamera2Preview || useCamera2NightPreview || useCamera2SloMoPreview) {
            lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.sessionBindFailedMutable.value = false
                // Resume is a fresh bind attempt: clear the one-shot
                // Camera2 fallback latch too, otherwise a bind that failed
                // once keeps useCamera2Preview latched off forever and the
                // user is parked on the manual-retry error across resumes.
                viewModel.clearCamera2Fallback()
                viewModel.teardownSession()
                // Session-active flags the analyzer/UI effects key on;
                // the Camera2 composables own the actual sessions.
                viewModel.photoSessionActiveMutable.value =
                    sessionKind == SessionKind.PHOTO || sessionKind == SessionKind.PORTRAIT ||
                    sessionKind == SessionKind.PANORAMA
                viewModel.videoSessionActiveMutable.value = sessionKind == SessionKind.VIDEO
                viewModel.highSpeedActiveMutable.value = sessionKind == SessionKind.HIGH_SPEED
                viewModel.nightPreviewActiveMutable.value = useCamera2NightPreview
                try { awaitCancellation() } finally { viewModel.teardownSession() }
            }
            Log.dev("NightPreview", "CameraScreen repeatOnLifecycle block EXIT – lifecycle dropped below STARTED")
            return@LaunchedEffect
        }
    }
}
