package com.vayunmathur.camera.ui

import android.content.Context
import android.hardware.camera2.CameraManager
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.vayunmathur.camera.domain.LensFacing
import com.vayunmathur.camera.platform.Camera2RecordingCore
import com.vayunmathur.camera.platform.camera2.AndroidCamera2Driver
import com.vayunmathur.camera.platform.camera2.Camera2Executor
import com.vayunmathur.camera.platform.camera2.Camera2HighSpeedSession
import com.vayunmathur.camera.platform.camera2.Camera2IdResolution
import com.vayunmathur.camera.platform.camera2.Camera2IdResolver
import com.vayunmathur.camera.platform.camera2.CharacteristicsFacade
import com.vayunmathur.camera.platform.camera2.HighSpeedPlanner
import com.vayunmathur.camera.platform.camera2.OnceErrorReporter
import com.vayunmathur.camera.platform.camera2.PreviewSurfaceBridge
import com.vayunmathur.camera.platform.camera2.PreviewSurfaceEvent
import com.vayunmathur.camera.platform.camera2.RecordingSessionOwnership
import com.vayunmathur.camera.platform.toCustomRecordingConfig
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Camera2 slo-mo viewfinder (piece 4): a TextureView whose surface feeds
 * a [Camera2HighSpeedSession] as its preview target, alongside the shared
 * core's encoder surface. Mirrors [Camera2Preview]/[Camera2NightPreview].
 *
 * Composed only when `CameraScreenState.useCamera2SloMoPreview` selects
 * it — which requires the slo-mo gate, a high-speed selection, no latched
 * fallback, and `SloMoRecordingReadiness.recordingEngineReady`. That flag
 * is false until device verification (piece 6), so today this composable
 * is never composed live and SLOW_MO preview/recording stay on the
 * CameraX high-speed session exactly as before. The plumbing exists so
 * flipping readiness after verification needs no new surface code.
 *
 * [cameraId] is the bind identity only; the session opens the id
 * [Camera2IdResolver] maps from [lensFacing], never this value.
 */
@Composable
internal fun Camera2SloMoPreview(
    cameraId: String,
    lensFacing: LensFacing?,
    onSessionChanged: (Camera2HighSpeedSession?) -> Unit = {},
    onError: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sessionHolder = remember(lensFacing) {
        Camera2SloMoPreviewSession(context, lensFacing, onError)
    }
    var session by remember(sessionHolder) { mutableStateOf<Camera2HighSpeedSession?>(null) }

    DisposableEffect(sessionHolder) {
        onDispose {
            onSessionChanged(null)
            sessionHolder.close()
        }
    }
    LaunchedEffect(sessionHolder) {
        sessionHolder.build()
        session = sessionHolder.session
        if (session != null) onSessionChanged(session)
        sessionHolder.deliverPendingError()
    }

    if (session == null) {
        Box(modifier = modifier.background(Color.Black))
        return
    }

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).also { view -> sessionHolder.bridge.attachTo(view) }
        },
        modifier = modifier,
    )
}

private class Camera2SloMoPreviewSession(
    private val context: Context,
    private val lensFacing: LensFacing?,
    onError: (String) -> Unit = {},
) {
    val executor = Camera2Executor("Camera2SloMoPreview-${lensFacing?.name ?: "unknown"}")

    @Volatile
    var session: Camera2HighSpeedSession? = null
        private set

    @Volatile
    private var core: Camera2RecordingCore? = null

    @Volatile
    private var resolvedCameraId: String? = null

    @Volatile
    private var closed = false

    private val errorGate = OnceErrorReporter(onError)

    @Volatile
    private var pendingInitError: String? = null

    val bridge: PreviewSurfaceBridge = PreviewSurfaceBridge { event ->
        val liveSession = session
        val liveCameraId = resolvedCameraId
        val liveCore = core
        if (liveSession == null || liveCameraId == null || liveCore == null) {
            return@PreviewSurfaceBridge
        }
        when (event) {
            is PreviewSurfaceEvent.Available -> {
                val encoderSurface = liveCore.prepareEncoderSurface()
                if (encoderSurface == null) {
                    errorGate.report("slo-mo encoder surface unavailable")
                } else {
                    liveSession.open(liveCameraId, encoderSurface, event.surface)
                }
            }
            is PreviewSurfaceEvent.SizeChanged -> {
                liveSession.close()
                val encoderSurface = liveCore.prepareEncoderSurface()
                if (encoderSurface == null) {
                    errorGate.report("slo-mo encoder surface unavailable")
                } else {
                    liveSession.open(liveCameraId, encoderSurface, event.surface)
                }
            }
            is PreviewSurfaceEvent.Destroyed -> liveSession.close()
        }
    }

    suspend fun build() {
        if (session != null || closed) return
        var built: Camera2HighSpeedSession? = null
        var builtCore: Camera2RecordingCore? = null
        try {
            withContext(Dispatchers.Default) {
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
                        val selection = HighSpeedPlanner.select(facade.snapshot)
                        if (selection == null) {
                            pendingInitError = "no constrained high-speed slo-mo point"
                            return@withContext
                        }
                        val driver = AndroidCamera2Driver(
                            manager,
                            android.os.Handler(executor.looper),
                        )
                        builtCore = Camera2RecordingCore(
                            config = selection.toCustomRecordingConfig()
                                .copy(audioEnabled = false),
                            initialBitrateBps = { SLOMO_PREVIEW_BITRATE_BPS },
                            ownership = RecordingSessionOwnership.EXTERNALLY_OWNED,
                        )
                        built = Camera2HighSpeedSession(
                            driver = driver,
                            executor = executor,
                            snapshot = facade.snapshot,
                            listener = object : Camera2HighSpeedSession.Listener {
                                override fun onError(message: String) {
                                    Log.status("Camera2SloMoPreview", message)
                                    errorGate.report(message)
                                }
                            },
                        )
                    }
                }
            }
        } catch (e: RuntimeException) {
            pendingInitError = "Camera2 slo-mo preview init failed for facing $lensFacing: ${e.message}"
        }
        if (closed) {
            built?.close()
            builtCore?.release()
            return
        }
        session = built
        core = builtCore
    }

    fun deliverPendingError() {
        val message = pendingInitError ?: return
        pendingInitError = null
        Log.status("Camera2SloMoPreview", message)
        errorGate.report(message)
    }

    fun close() {
        closed = true
        session?.close()
        session = null
        core?.release()
        core = null
        executor.shutdown()
    }

    private companion object {
        const val SLOMO_PREVIEW_BITRATE_BPS = 20_000_000
    }
}
