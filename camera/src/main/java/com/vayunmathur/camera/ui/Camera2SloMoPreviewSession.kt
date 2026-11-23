package com.vayunmathur.camera.ui

import android.content.Context
import android.hardware.camera2.CameraManager
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

internal class Camera2SloMoPreviewSession(
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
    private var selection: com.vayunmathur.camera.platform.camera2.HighSpeedSelection? = null

    @Volatile
    private var lastPreviewSurface: android.view.Surface? = null

    @Volatile
    private var recordingFile: java.io.File? = null

    @Volatile
    private var recordCallback: ((java.io.File, Boolean) -> Unit)? = null

    @Volatile
    private var recordStartedAtMs: Long = 0L

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
                lastPreviewSurface = event.surface
                // Constrained high-speed sessions reject outputs outside
                // the HFR size list: pin the preview texture's buffers
                // to the selection size before configuring.
                selection?.let { bridge.setDefaultBufferSize(it.size.width, it.size.height) }
                val encoderSurface = liveCore.prepareEncoderSurface()
                if (encoderSurface == null) {
                    errorGate.report("slo-mo encoder surface unavailable")
                } else {
                    liveSession.open(liveCameraId, encoderSurface, event.surface)
                }
            }
            is PreviewSurfaceEvent.SizeChanged -> {
                liveSession.close()
                selection?.let { bridge.setDefaultBufferSize(it.size.width, it.size.height) }
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
                            pendingInitError = "no constrained high-speed slo-mo point " +
                                "(id=${resolution.cameraId} facing=$lensFacing " +
                                "snapshotFacing=${facade.snapshot.lensFacing} " +
                                "constrained=${facade.snapshot.supportsConstrainedHighSpeedVideo})"
                            return@withContext
                        }
                        this@Camera2SloMoPreviewSession.selection = selection
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

    /** The record control the ViewModel drives; session/core stay owned here. */
    fun recordingControl(): com.vayunmathur.camera.platform.SloMoRecordingControl =
        com.vayunmathur.camera.platform.SloMoRecordingControl(
            start = { file, callback -> startRecording(file, callback) },
            stop = { stopRecording() },
        )

    private fun startRecording(
        file: java.io.File,
        callback: (java.io.File, Boolean) -> Unit,
    ) {
        val liveSession = session
        val liveCore = core
        if (liveSession == null || liveCore == null || closed) {
            callback(file, false)
            return
        }
        recordingFile = file
        recordCallback = callback
        recordStartedAtMs = android.os.SystemClock.elapsedRealtime()
        try {
            liveCore.setupMuxerAndAudio(file)
            liveCore.startVideoCodec()
            liveCore.markSessionStreaming()
            liveCore.startDrain()
        } catch (e: Exception) {
            Log.error("Camera2SloMoPreview", "slo-mo recording failed to start", e)
            recordingFile = null
            recordCallback = null
            callback(file, false)
        }
    }

    private fun stopRecording() {
        val liveSession = session
        val liveCore = core
        val file = recordingFile
        val callback = recordCallback
        val wallMs = android.os.SystemClock.elapsedRealtime() - recordStartedAtMs
        recordingFile = null
        recordCallback = null
        if (liveSession == null || liveCore == null || file == null || callback == null) return
        // Drain/muxer finalization blocks; run it off every UI/executor thread.
        Thread({
            var ok = false
            try {
                liveSession.stopRecording { liveCore.signalVideoEos() }
                liveCore.joinDrainAndAudio()
                liveCore.finishMuxer()
                val validation =
                    com.vayunmathur.camera.platform.Camera2VideoRecordingEngine.validateOutput(file)
                ok = validation.readable && sloMoOutputStretched(file, wallMs)
                Log.debug(
                    "Camera2SloMoPreview",
                    "slo-mo finalized readable=${validation.readable} stretchedOk=$ok wallMs=$wallMs",
                )
            } catch (e: Exception) {
                Log.error("Camera2SloMoPreview", "slo-mo finalize failed", e)
            } finally {
                rebuildCoreForNextRecording()
                callback(file, ok)
            }
        }, "Camera2SloMoFinalize").start()
    }

    /**
     * Piece-6 read-back: walk the muxed video samples and prove the
     * output is slow (playback-rate spacing over a stretched span),
     * not realtime — see [com.vayunmathur.camera.platform.camera2.SloMoStretchMath].
     */
    private fun sloMoOutputStretched(file: java.io.File, wallMs: Long): Boolean {
        val sel = selection ?: return false
        val extractor = android.media.MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            var videoTrack = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i)
                    .getString(android.media.MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    videoTrack = i
                    break
                }
            }
            if (videoTrack < 0) return false
            extractor.selectTrack(videoTrack)
            var frames = 0
            var firstPtsUs = -1L
            var lastPtsUs = -1L
            while (true) {
                val pts = extractor.sampleTime
                if (pts < 0) break
                if (firstPtsUs < 0) firstPtsUs = pts
                lastPtsUs = pts
                frames++
                if (!extractor.advance()) break
            }
            val stretched = com.vayunmathur.camera.platform.camera2.SloMoStretchMath.isStretched(
                frameCount = frames,
                firstPtsUs = firstPtsUs,
                lastPtsUs = lastPtsUs,
                playbackFps = sel.playbackFps,
                captureFps = sel.captureFps,
                wallDurationMs = wallMs,
            )
            Log.debug(
                "Camera2SloMoPreview",
                "read-back frames=$frames firstPtsUs=$firstPtsUs lastPtsUs=$lastPtsUs " +
                    "spanUs=${lastPtsUs - firstPtsUs} effectiveFps=" +
                    com.vayunmathur.camera.platform.camera2.SloMoStretchMath
                        .effectiveFps(frames, firstPtsUs, lastPtsUs) +
                    " capture=${sel.captureFps} playback=${sel.playbackFps} wallMs=$wallMs -> $stretched",
            )
            stretched
        } catch (e: Exception) {
            Log.error("Camera2SloMoPreview", "slo-mo read-back failed", e)
            false
        } finally {
            extractor.release()
        }
    }

    /**
     * The shared core is spent after one recording (its lifecycle is
     * terminal at FINALIZED): release it, build a fresh one for the
     * same selection, and reopen the session onto the last preview
     * surface so the viewfinder and the next recording work.
     */
    private fun rebuildCoreForNextRecording() {
        val sel = selection ?: return
        try { core?.release() } catch (_: Exception) {}
        val fresh = Camera2RecordingCore(
            config = sel.toCustomRecordingConfig().copy(audioEnabled = false),
            initialBitrateBps = { SLOMO_PREVIEW_BITRATE_BPS },
            ownership = RecordingSessionOwnership.EXTERNALLY_OWNED,
        )
        core = fresh
        val liveSession = session
        val cameraId = resolvedCameraId
        val previewSurface = lastPreviewSurface
        if (liveSession != null && cameraId != null && previewSurface != null && !closed) {
            liveSession.close()
            val encoderSurface = fresh.prepareEncoderSurface()
            if (encoderSurface != null) {
                liveSession.open(cameraId, encoderSurface, previewSurface)
            }
        }
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
