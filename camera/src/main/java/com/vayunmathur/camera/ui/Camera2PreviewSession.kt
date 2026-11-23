package com.vayunmathur.camera.ui

import android.content.Context
import android.hardware.camera2.CameraManager
import android.view.TextureView
import com.vayunmathur.camera.domain.LensFacing
import com.vayunmathur.camera.platform.camera2.AndroidCamera2Driver
import com.vayunmathur.camera.platform.camera2.Camera2Executor
import com.vayunmathur.camera.platform.camera2.Camera2IdResolution
import com.vayunmathur.camera.platform.camera2.Camera2IdResolver
import com.vayunmathur.camera.platform.camera2.Camera2Session
import com.vayunmathur.camera.platform.camera2.CharacteristicsFacade
import com.vayunmathur.camera.platform.camera2.FrameSize
import com.vayunmathur.camera.platform.camera2.LensCharacteristicsSnapshot
import com.vayunmathur.camera.platform.camera2.OnceErrorReporter
import com.vayunmathur.camera.platform.camera2.PreviewSurfaceBridge
import com.vayunmathur.camera.platform.camera2.PreviewSurfaceEvent
import com.vayunmathur.camera.platform.camera2.RawRingConfig
import com.vayunmathur.camera.platform.camera2.RawRingGate
import com.vayunmathur.camera.platform.camera2.ReprocessRingConfig
import com.vayunmathur.camera.platform.camera2.ReprocessRingGate
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Executor + bridge + session for one resolved Camera2 id; built once via
 * [build] (off the main thread), closed once. Build never throws: a facing
 * with no `cameraIdList` match, or a guarded characteristics read that
 * fails, leaves [session] null and records the reason for
 * [deliverPendingError] (one-shot through [OnceErrorReporter], shared with
 * the session listener, so init and runtime errors together signal the
 * fallback latch at most once per holder).
 */
internal class Camera2PreviewSession(
    private val context: Context,
    private val lensFacing: LensFacing?,
    onError: (String) -> Unit = {},
    private val analysisConsumer: com.vayunmathur.camera.platform.camera2.AnalysisConsumer? = null,
) {
    val executor = Camera2Executor("Camera2Preview-${lensFacing?.name ?: "unknown"}")

    @Volatile
    var session: Camera2Session? = null
        private set

    @Volatile
    private var resolvedCameraId: String? = null

    @Volatile
    private var closed = false

    /** Invoked (on the session executor) when a pre-streaming failure should rebuild degraded. */
    @Volatile
    var rebuildHandler: (() -> Unit)? = null

    /** Output-profile rung; advances only on pre-streaming failures. */
    @Volatile
    private var profileIndex = 0

    /** True once the current session has streamed a frame (failures after this are real errors). */
    @Volatile
    private var streamed = false

    /** One rebuild at a time: a dying session reports several errors for one failure. */
    private val rebuildInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var lastError: String? = null

    private var facadeSnapshot: com.vayunmathur.camera.platform.camera2.LensCharacteristicsSnapshot? = null

    val bridge: PreviewSurfaceBridge = PreviewSurfaceBridge { event ->
        val liveSession = session
        val liveCameraId = resolvedCameraId
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

    private val errorGate = OnceErrorReporter(onError)

    @Volatile
    private var pendingInitError: String? = null

    /**
     * Resolves the Camera2 id from [lensFacing] over `CameraManager` and
     * reads the characteristics snapshot — all HAL binder work, so it runs
     * on `Dispatchers.Default`, never on the composition/main thread, and
     * never awaits CameraX initialisation. Safe to call once; a close that
     * raced the build closes the freshly built session immediately.
     */
    suspend fun build() {
        if (session != null || closed) return
        var built: Camera2Session? = null
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
                        facadeSnapshot = facade.snapshot
                        built = buildSessionForProfile(facade.snapshot, manager)
                    }
                }
            }
        } catch (e: RuntimeException) {
            // Any characteristics-fetch failure — an unknown-device error
            // or a HAL binder failure — is an error path here; it must
            // never escape composition as a thrown exception.
            pendingInitError = "Camera2 preview init failed for facing $lensFacing: ${e.message}"
        }
        if (closed) {
            built?.close()
            return
        }
        session = built
    }

    /**
     * Builds one session for the current output profile. Optional
     * outputs are shed rung by rung (JPEG_R + RAW ring, then the YUV
     * ring, then analysis): some HALs refuse the full combination at
     * configure time (a max-res JPEG_R or RAW stream beside the max-res
     * JPEG), and a degraded live preview beats a dead one — the still
     * routes re-check what the session actually configured.
     */
    private fun buildSessionForProfile(
        snapshot: com.vayunmathur.camera.platform.camera2.LensCharacteristicsSnapshot,
        manager: CameraManager,
    ): Camera2Session {
        streamed = false
        val driver = AndroidCamera2Driver(
            manager,
            android.os.Handler(executor.looper),
        )
        return Camera2Session(
            driver = driver,
            executor = executor,
            snapshot = snapshot,
            ringConfig = ReprocessRingConfig(enabled = profileIndex < 2 && ReprocessRingGate.ENABLED),
            rawRingConfig = RawRingConfig(enabled = profileIndex < 1 && RawRingGate.ENABLED),
            analysisConsumer = if (profileIndex < 3) analysisConsumer else null,
            ultraHdrEnabled = profileIndex < 1,
            listener = object : Camera2Session.Listener {
                override fun onStreaming(previewSize: com.vayunmathur.camera.platform.camera2.FrameSize) {
                    streamed = true
                }

                override fun onError(message: String) {
                    Log.status("Camera2Preview", message)
                    lastError = message
                    // A fatal session/device error rebuilds one rung
                    // down instead of surfacing: a HAL can even report
                    // configure success and then die before the first
                    // frame, so "streaming" is not proof of health.
                    // Only exhausting the ladder reports through.
                    if (!closed && profileIndex < MAX_PROFILE_INDEX &&
                        rebuildInFlight.compareAndSet(false, true)
                    ) {
                        Log.status(
                            "Camera2Preview",
                            "profile $profileIndex failed; rebuilding degraded",
                        )
                        rebuildHandler?.invoke()
                    } else if (profileIndex >= MAX_PROFILE_INDEX) {
                        errorGate.report(message)
                    }
                }
            },
        )
    }

    /** Closes the failed session and builds the next profile rung; null when exhausted/closed. */
    suspend fun rebuildNext(): Camera2Session? {
        if (closed || profileIndex >= MAX_PROFILE_INDEX) {
            rebuildInFlight.set(false)
            return null
        }
        profileIndex++
        val snapshot = facadeSnapshot ?: return null
        session?.close()
        session = null
        val built = withContext(Dispatchers.Default) {
            val manager = context.getSystemService(CameraManager::class.java) ?: return@withContext null
            buildSessionForProfile(snapshot, manager)
        }
        if (closed) {
            built?.close()
            rebuildInFlight.set(false)
            return null
        }
        if (built == null && !closed) {
            errorGate.report(lastError ?: "Camera2 preview failed to build")
        }
        rebuildInFlight.set(false)
        session = built
        // The bridge will not re-fire Available for the existing
        // texture, so open the rebuilt session on the live surface.
        val liveSurface = bridge.state.surface
        val cameraId = resolvedCameraId
        if (built != null && liveSurface != null && cameraId != null) {
            built.open(
                cameraId = cameraId,
                surface = liveSurface,
                requestedSize = com.vayunmathur.camera.platform.camera2.FrameSize(
                    bridge.state.width, bridge.state.height,
                ),
            )
        }
        return built
    }

    /** Delivers a recorded init failure to onError, at most once. */
    fun deliverPendingError() {
        val message = pendingInitError ?: return
        pendingInitError = null
        Log.status("Camera2Preview", message)
        errorGate.report(message)
    }

    /** True after [close]; pending rebuilds must not run. */
    fun isClosedForRebuild(): Boolean = closed

    fun close() {
        closed = true
        session?.close()
        session = null
        executor.shutdown()
    }

    private companion object {
        /** Last degrade rung (preview + JPEG only). */
        const val MAX_PROFILE_INDEX = 3
    }
}
