package com.vayunmathur.camera.ui

import android.content.Context
import android.hardware.camera2.CameraManager
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.vayunmathur.camera.domain.LensFacing
import com.vayunmathur.camera.platform.camera2.AndroidCamera2Driver
import com.vayunmathur.camera.platform.camera2.Camera2Executor
import com.vayunmathur.camera.platform.camera2.Camera2IdResolution
import com.vayunmathur.camera.platform.camera2.Camera2IdResolver
import com.vayunmathur.camera.platform.camera2.Camera2Session
import com.vayunmathur.camera.platform.camera2.CharacteristicsFacade
import com.vayunmathur.camera.platform.camera2.FrameSize
import com.vayunmathur.camera.platform.camera2.OnceErrorReporter
import com.vayunmathur.camera.platform.camera2.PreviewSurfaceBridge
import com.vayunmathur.camera.platform.camera2.RawRingConfig
import com.vayunmathur.camera.platform.camera2.RawRingGate
import com.vayunmathur.camera.platform.camera2.ReprocessRingConfig
import com.vayunmathur.camera.platform.camera2.ReprocessRingGate
import com.vayunmathur.camera.platform.camera2.PreviewSurfaceEvent
import com.vayunmathur.library.log.Log

/**
 * Camera2 (Phase 2) viewfinder: a TextureView whose surface feeds a
 * [Camera2Session]. Rendered only when the preview-engine gate selects
 * Camera2 (PHOTO + gate on — see `PreviewEnginePolicy`); the CameraX
 * viewfinder path is untouched otherwise.
 *
 * The [modifier] carries the same graphicsLayer RenderEffects / clipping as
 * the CameraX preview, so warmth/bokeh grading composites identically over
 * this TextureView (EMBEDDED-style texture rendering under the overlay tree).
 *
 * Ownership: this composition owns the executor and the session; both are
 * torn down when it leaves composition (mode switch, lens flip, gate off).
 *
 * [cameraId] is the bind identity only (the selected lens's CameraX-derived
 * id, used for the fallback-latch key and as the remember key). It is NEVER
 * handed to CameraManager: the session is opened with the id
 * [Camera2IdResolver] maps from [lensFacing] over
 * `CameraManager.cameraIdList`. A mapping or characteristics failure is an
 * error path (one [onError] via the holder's one-shot gate, delivered after
 * composition), never an exception escaping composition.
 */
@Composable
internal fun Camera2Preview(
    cameraId: String,
    lensFacing: LensFacing?,
    zoomRatio: Float,
    torchOn: Boolean,
    exposureCompEv: Float,
    flashSetting: com.vayunmathur.camera.domain.FlashSetting =
        com.vayunmathur.camera.domain.FlashSetting.OFF,
    onSessionChanged: (Camera2Session?) -> Unit = {},
    onError: (String) -> Unit = {},
    /** Plane consumer for the analysis stream; null = no analysis output. */
    analysisConsumer: com.vayunmathur.camera.platform.camera2.AnalysisConsumer? = null,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sessionHolder = remember(cameraId, lensFacing) {
        Camera2PreviewSession(context, lensFacing, onError, analysisConsumer)
    }

    DisposableEffect(sessionHolder) {
        onSessionChanged(sessionHolder.session)
        onDispose {
            onSessionChanged(null)
            sessionHolder.close()
        }
    }
    // Init/mapping failures are delivered here, after composition, exactly
    // once: reporting them from remember{} would mutate ViewModel state
    // mid-composition and re-enter this composable in the same pass.
    LaunchedEffect(sessionHolder) { sessionHolder.deliverPendingError() }
    LaunchedEffect(sessionHolder, zoomRatio) { sessionHolder.session?.setZoomRatio(zoomRatio) }
    LaunchedEffect(sessionHolder, torchOn) { sessionHolder.session?.setTorch(torchOn) }
    LaunchedEffect(sessionHolder, exposureCompEv) {
        sessionHolder.session?.setExposureCompEv(exposureCompEv)
    }
    LaunchedEffect(sessionHolder, flashSetting) {
        sessionHolder.session?.setFlashSetting(flashSetting)
    }

    val session = sessionHolder.session
    if (session == null) {
        // Failed bind: the fallback latch (signalled via onError) switches
        // this bind to CameraX; until that recomposition lands, black-fill
        // instead of constructing another Camera2PreviewSession.
        Box(modifier = modifier.background(Color.Black))
        return
    }

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).also { view ->
                session.setDisplayRotationDegrees(
                    (view.display?.rotation ?: 0) * 90,
                )
                sessionHolder.bridge.attachTo(view)
            }
        },
        modifier = modifier.pointerInput(sessionHolder) {
            detectTapGestures { offset ->
                if (size.width > 0 && size.height > 0) {
                    session.tapToFocus(
                        tapX = offset.x / size.width,
                        tapY = offset.y / size.height,
                    )
                }
            }
        },
    )
}

/**
 * Executor + bridge + session for one resolved Camera2 id; built once,
 * closed once. Construction never throws: a facing with no
 * `cameraIdList` match, or a guarded characteristics read that fails,
 * leaves [session] null and records the reason for
 * [deliverPendingError] (one-shot through [OnceErrorReporter], shared with
 * the session listener, so init and runtime errors together signal the
 * fallback latch at most once per holder).
 */
private class Camera2PreviewSession(
    context: Context,
    lensFacing: LensFacing?,
    onError: (String) -> Unit = {},
    analysisConsumer: com.vayunmathur.camera.platform.camera2.AnalysisConsumer? = null,
) {
    val executor = Camera2Executor("Camera2Preview-${lensFacing?.name ?: "unknown"}")
    val session: Camera2Session?
    val bridge: PreviewSurfaceBridge

    private val errorGate = OnceErrorReporter(onError)

    @Volatile
    private var pendingInitError: String? = null

    init {
        var built: Camera2Session? = null
        var resolvedCameraId: String? = null
        try {
            val manager = context.getSystemService(CameraManager::class.java)
                ?: throw IllegalStateException("no CameraManager")
            when (
                val resolution = Camera2IdResolver.resolve(
                    lensFacing,
                    CharacteristicsFacade.cameraIdFacings(manager),
                )
            ) {
                is Camera2IdResolution.Unavailable -> pendingInitError = resolution.reason
                is Camera2IdResolution.Resolved -> {
                    resolvedCameraId = resolution.cameraId
                    val facade = CharacteristicsFacade.tryFromCameraManager(
                        manager, resolution.cameraId,
                    ) ?: throw IllegalArgumentException(
                        "getCameraCharacteristics failed for Camera2 id ${resolution.cameraId}",
                    )
                    val driver = AndroidCamera2Driver(
                        manager,
                        android.os.Handler(executor.looper),
                    )
                    built = Camera2Session(
                        driver = driver,
                        executor = executor,
                        snapshot = facade.snapshot,
                        ringConfig = ReprocessRingConfig(enabled = ReprocessRingGate.ENABLED),
                        rawRingConfig = RawRingConfig(enabled = RawRingGate.ENABLED),
                        analysisConsumer = analysisConsumer,
                        listener = object : Camera2Session.Listener {
                            override fun onError(message: String) {
                                Log.status("Camera2Preview", message)
                                // Configure/bind/open failures drive the one-shot
                                // CameraX fallback latch in the ViewModel; the
                                // gate keeps a failing session from signalling
                                // it (and re-entering composition) repeatedly.
                                errorGate.report(message)
                            }
                        },
                    )
                }
            }
        } catch (e: RuntimeException) {
            // Any characteristics-fetch failure — an unknown-device error
            // or a HAL binder failure — is an error path here; it must
            // never escape composition as a thrown exception.
            pendingInitError = "Camera2 preview init failed for facing $lensFacing: ${e.message}"
        }
        session = built
        val liveSession = built
        val liveCameraId = resolvedCameraId
        bridge = PreviewSurfaceBridge { event ->
            if (liveSession == null || liveCameraId == null) return@PreviewSurfaceBridge
            when (event) {
                is PreviewSurfaceEvent.Available -> liveSession.open(
                    cameraId = liveCameraId,
                    surface = event.surface,
                    requestedSize = FrameSize(event.width, event.height),
                )
                is PreviewSurfaceEvent.SizeChanged -> {
                    // No resize API on the Phase-2 session: reopen at the new
                    // size so the stream is re-validated up front.
                    liveSession.close()
                    liveSession.open(
                        cameraId = liveCameraId,
                        surface = event.surface,
                        requestedSize = FrameSize(event.width, event.height),
                    )
                }
                is PreviewSurfaceEvent.Destroyed -> liveSession.close()
            }
        }
    }

    /** Delivers a recorded init failure to onError, at most once. */
    fun deliverPendingError() {
        val message = pendingInitError ?: return
        pendingInitError = null
        Log.status("Camera2Preview", message)
        errorGate.report(message)
    }

    fun close() {
        session?.close()
        executor.shutdown()
    }
}
