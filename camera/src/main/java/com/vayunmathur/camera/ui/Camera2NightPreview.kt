package com.vayunmathur.camera.ui

import android.content.Context
import android.hardware.camera2.CameraManager
import android.view.TextureView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.vayunmathur.camera.domain.LensFacing
import com.vayunmathur.camera.platform.camera2.AndroidCamera2Driver
import com.vayunmathur.camera.platform.camera2.Camera2Executor
import com.vayunmathur.camera.platform.camera2.Camera2ExtensionSession
import com.vayunmathur.camera.platform.camera2.Camera2IdResolution
import com.vayunmathur.camera.platform.camera2.Camera2IdResolver
import com.vayunmathur.camera.platform.camera2.CharacteristicsFacade
import com.vayunmathur.camera.platform.camera2.FrameSize
import com.vayunmathur.camera.platform.camera2.OnceErrorReporter
import com.vayunmathur.camera.platform.camera2.PreviewSurfaceBridge
import com.vayunmathur.camera.platform.camera2.PreviewSurfaceEvent
import com.vayunmathur.library.log.Log

/**
 * Camera2 NIGHT-extension viewfinder: a TextureView whose surface feeds a
 * [Camera2ExtensionSession]. Composed only when
 * `CameraScreenState.useCamera2NightPreview` selects the Camera2 extension
 * (PHOTO + night engaged + persisted night gate on, no latched failure);
 * the regular Camera2/CameraX preview is composed otherwise.
 *
 * Registration is the point of this composable: the session is handed to
 * the ViewModel via [onSessionChanged] (which calls
 * `registerCamera2ExtensionSession`) for its whole composition lifetime,
 * so `tryCaptureCamera2NightExtension` finds a live, probed session at
 * shutter time. On dispose it unregisters first, then closes.
 *
 * The session re-probes at open: an unusable lens never opens the device
 * and reports through [onError], which latches the bind in the ViewModel
 * so this composable is not recomposed in a retry loop — the regular
 * preview serves instead (capture order CameraX-extension → own-burst
 * intact).
 *
 * Ownership: this composition owns the executor and the session; both are
 * torn down when it leaves composition.
 *
 * [cameraId] is the bind identity only (fallback-latch key / remember key);
 * it is NEVER handed to CameraManager. The session opens the id
 * [Camera2IdResolver] maps from [lensFacing] over
 * `CameraManager.cameraIdList`, and a mapping/characteristics failure is a
 * one-shot [onError] delivered after composition, never an exception
 * escaping composition (see `Camera2Preview`).
 */
@Composable
internal fun Camera2NightPreview(
    cameraId: String,
    lensFacing: LensFacing?,
    onSessionChanged: (Camera2ExtensionSession?) -> Unit = {},
    onError: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sessionHolder = remember(cameraId, lensFacing) {
        Camera2NightPreviewSession(context, lensFacing, onError)
    }

    DisposableEffect(sessionHolder) {
        onSessionChanged(sessionHolder.session)
        onDispose {
            onSessionChanged(null)
            sessionHolder.close()
        }
    }
    LaunchedEffect(sessionHolder) { sessionHolder.deliverPendingError() }

    val session = sessionHolder.session
    if (session == null) {
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
        modifier = modifier,
    )
}

/**
 * Executor + bridge + extension session for one resolved Camera2 id; built
 * once, closed once. Construction never throws (see
 * `Camera2PreviewSession`): failures leave [session] null and are reported
 * once via [deliverPendingError] / the shared one-shot error gate.
 */
private class Camera2NightPreviewSession(
    context: Context,
    lensFacing: LensFacing?,
    onError: (String) -> Unit = {},
) {
    val executor = Camera2Executor("Camera2NightPreview-${lensFacing?.name ?: "unknown"}")
    val session: Camera2ExtensionSession?
    val bridge: PreviewSurfaceBridge

    private val errorGate = OnceErrorReporter(onError)

    @Volatile
    private var pendingInitError: String? = null

    init {
        var built: Camera2ExtensionSession? = null
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
                    built = Camera2ExtensionSession(
                        driver = driver,
                        executor = executor,
                        snapshot = facade.snapshot,
                        listener = object : Camera2ExtensionSession.Listener {
                            override fun onError(message: String) {
                                Log.status("Camera2NightPreview", message)
                                errorGate.report(message)
                            }
                        },
                    )
                }
            }
        } catch (e: RuntimeException) {
            pendingInitError =
                "Camera2 night preview init failed for facing $lensFacing: ${e.message}"
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
        Log.status("Camera2NightPreview", message)
        errorGate.report(message)
    }

    fun close() {
        session?.close()
        executor.shutdown()
    }
}
