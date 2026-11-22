package com.vayunmathur.camera.ui

import android.content.Context
import android.hardware.camera2.CameraManager
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import com.vayunmathur.camera.platform.camera2.AndroidCamera2Driver
import com.vayunmathur.camera.platform.camera2.Camera2Executor
import com.vayunmathur.camera.platform.camera2.Camera2ExtensionSession
import com.vayunmathur.camera.platform.camera2.CharacteristicsFacade
import com.vayunmathur.camera.platform.camera2.FrameSize
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
 */
@Composable
internal fun Camera2NightPreview(
    cameraId: String,
    onSessionChanged: (Camera2ExtensionSession?) -> Unit = {},
    onError: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val sessionHolder = remember(cameraId) { Camera2NightPreviewSession(context, cameraId, onError) }

    DisposableEffect(sessionHolder) {
        onSessionChanged(sessionHolder.session)
        onDispose {
            onSessionChanged(null)
            sessionHolder.close()
        }
    }

    AndroidView(
        factory = { ctx ->
            TextureView(ctx).also { view ->
                sessionHolder.session.setDisplayRotationDegrees(
                    (view.display?.rotation ?: 0) * 90,
                )
                sessionHolder.bridge.attachTo(view)
            }
        },
        modifier = modifier,
    )
}

/** Executor + bridge + extension session for one camera id; built once, closed once. */
private class Camera2NightPreviewSession(
    context: Context,
    private val cameraId: String,
    private val onError: (String) -> Unit = {},
) {
    val executor = Camera2Executor("Camera2NightPreview-$cameraId")
    val session: Camera2ExtensionSession
    val bridge: PreviewSurfaceBridge

    init {
        val manager = context.getSystemService(CameraManager::class.java)
        val facade = CharacteristicsFacade.fromCameraManager(manager, cameraId)
        val driver = AndroidCamera2Driver(
            manager,
            android.os.Handler(executor.looper),
        )
        session = Camera2ExtensionSession(
            driver = driver,
            executor = executor,
            snapshot = facade.snapshot,
            listener = object : Camera2ExtensionSession.Listener {
                override fun onError(message: String) {
                    Log.status("Camera2NightPreview", message)
                    onError(message)
                }
            },
        )
        bridge = PreviewSurfaceBridge { event ->
            when (event) {
                is PreviewSurfaceEvent.Available -> session.open(
                    cameraId = cameraId,
                    surface = event.surface,
                    requestedSize = FrameSize(event.width, event.height),
                )
                is PreviewSurfaceEvent.SizeChanged -> {
                    session.close()
                    session.open(
                        cameraId = cameraId,
                        surface = event.surface,
                        requestedSize = FrameSize(event.width, event.height),
                    )
                }
                is PreviewSurfaceEvent.Destroyed -> session.close()
            }
        }
    }

    fun close() {
        session.close()
        executor.shutdown()
    }
}
