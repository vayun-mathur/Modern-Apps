package com.vayunmathur.camera.platform

import android.content.Context
import android.graphics.Bitmap
import android.hardware.camera2.CameraMetadata
import com.vayunmathur.camera.data.setFlashMode
import com.vayunmathur.camera.data.setTimerDuration
import com.vayunmathur.camera.data.toggleTorch
import com.vayunmathur.camera.domain.AdaptivePreviewRate
import com.vayunmathur.camera.domain.LatestOnlySlot
import com.vayunmathur.camera.domain.PreviewFrame
import com.vayunmathur.camera.domain.PreviewFrameProcessor
import com.vayunmathur.camera.domain.PreviewJpegEncoder
import com.vayunmathur.camera.domain.WearCameraOp
import com.vayunmathur.camera.domain.WearCameraProtocol
import com.vayunmathur.camera.domain.WearCameraState
import com.vayunmathur.camera.domain.WearOpDecision
import com.vayunmathur.camera.domain.WearOpKind
import com.vayunmathur.camera.domain.Yuv420ToArgb
import com.vayunmathur.camera.domain.clampWearZoom
import com.vayunmathur.camera.domain.decodeWearCameraOp
import com.vayunmathur.camera.domain.encodeWearCameraState
import com.vayunmathur.camera.domain.planWearCameraOp
import com.vayunmathur.camera.domain.previewDimensions
import com.vayunmathur.camera.domain.wearZoomPreset
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.wearlink.WearLinkListener
import com.vayunmathur.library.wearlink.WearLinkNode
import com.vayunmathur.library.wearlink.WearLinkState
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The camera control seam the watch ops execute against: every call is one
 * existing CameraViewModel action (mode/lens/zoom/flash/timer setters,
 * takePhoto/toggleRecording), so a remote op can do nothing the on-screen
 * controls cannot. Tests drive this interface with a fake instead of a
 * camera.
 */
interface WearCameraControls {
    fun shutter()
    fun toggleRecording()
    fun setMode(modeName: String)
    fun setZoom(ratio: Float)
    fun setFlash(flashName: String)
    fun setTorch(enabled: Boolean)
    fun flipLens()
    fun setTimer(seconds: Int)
}

/** [WearCameraControls] over the live [CameraViewModel] extension actions. */
class ViewModelWearControls(private val viewModel: CameraViewModel) : WearCameraControls {
    override fun shutter() {
        if (viewModel.cameraMode.value.name in WearCameraProtocol.VIDEO_MODES) {
            viewModel.toggleRecording()
        } else {
            viewModel.takePhoto()
        }
    }

    override fun toggleRecording() = viewModel.toggleRecording()

    override fun setMode(modeName: String) {
        val mode = runCatching { CameraMode.valueOf(modeName) }.getOrNull() ?: return
        viewModel.switchCameraMode(mode)
    }

    override fun setZoom(ratio: Float) = viewModel.setZoomRatio(ratio)

    override fun setFlash(flashName: String) {
        val mode = runCatching { FlashMode.valueOf(flashName) }.getOrNull() ?: return
        viewModel.setFlashMode(mode)
        viewModel.applyImageCaptureFlashMode()
    }

    override fun setTorch(enabled: Boolean) {
        if (viewModel.torchEnabled.value != enabled) viewModel.toggleTorch()
    }

    override fun flipLens() = viewModel.flipCamera()

    override fun setTimer(seconds: Int) {
        val duration = TimerDuration.entries.firstOrNull { it.seconds == seconds } ?: return
        viewModel.setTimerDuration(duration)
    }
}

/** Executes one planned op; rejects are logged and mirrored back via state, never faked. */
fun executeWearOp(op: WearCameraOp, state: WearCameraState, controls: WearCameraControls): WearOpDecision {
    val decision = planWearCameraOp(op, state)
    if (decision is WearOpDecision.Reject) return decision
    when (op.kind) {
        WearOpKind.SHUTTER -> controls.shutter()
        WearOpKind.RECORD_TOGGLE -> controls.toggleRecording()
        WearOpKind.SET_MODE -> controls.setMode(op.mode)
        WearOpKind.SET_ZOOM -> controls.setZoom(clampWearZoom(op.zoomRatio, state))
        WearOpKind.ZOOM_PRESET -> wearZoomPreset(state, op.zoomPresetIndex)?.let { controls.setZoom(it) }
        WearOpKind.SET_FLASH -> controls.setFlash(op.flash)
        WearOpKind.SET_TORCH -> controls.setTorch(op.torch)
        WearOpKind.FLIP_LENS -> controls.flipLens()
        WearOpKind.SET_TIMER -> controls.setTimer(op.timerSeconds)
        WearOpKind.PREVIEW_START, WearOpKind.PREVIEW_STOP -> Unit // handled by the sync, not the camera
    }
    return decision
}

/** Builds the mirrored state from the ViewModel's public flows. */
fun CameraViewModel.wearCameraState(
    previewStreaming: Boolean,
    lastCaptureAtMillis: Long = 0L,
    lastCaptureKind: String = "",
): WearCameraState {
    val (zoomMin, zoomMax) = currentZoomRange()
    return WearCameraState(
        mode = cameraMode.value.name,
        frontLens = lensFacing.value == CameraMetadata.LENS_FACING_FRONT,
        zoomRatio = zoomRatio.value,
        zoomPresets = availableZoomLevels.value.map { it.second },
        zoomMin = zoomMin,
        zoomMax = zoomMax,
        flash = flashMode.value.name,
        hasFlashUnit = hasFlashUnit.value,
        torch = torchEnabled.value,
        timerSeconds = timerDuration.value.seconds,
        timerCountdown = timerCountdown.value,
        recording = isRecording.value || camera2Recording.value,
        recordingSeconds = recordingDurationSec.value,
        capturing = isCapturing.value,
        previewStreaming = previewStreaming,
        lastCaptureAtMillis = lastCaptureAtMillis,
        lastCaptureKind = lastCaptureKind,
    )
}

/** Android JPEG encoder behind the pure pipeline seam (Bitmap.compress). */
private object BitmapPreviewEncoder : PreviewJpegEncoder {
    override fun encode(argb: IntArray, width: Int, height: Int, quality: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(argb, width, height, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        bitmap.recycle()
        return out.toByteArray()
    }
}

/** One raw analysis frame waiting for the preview pump (sensor-oriented ARGB). */
private class RawPreviewFrame(val argb: IntArray, val width: Int, val height: Int, val rotationDegrees: Int)

/**
 * Phone side of the Camera watch companion over WearLink (RFCOMM,
 * :library:wearlink): mirrored state + last-capture thumbnail are data
 * items, watch ops are messages executed through [ViewModelWearControls],
 * and the live viewfinder streams analysis frames as preview messages.
 *
 * Viewfinder design point: frames come from the phone's existing Camera2
 * analysis stream (small frames already), are converted and downscaled to
 * at most [WearCameraProtocol.PREVIEW_MAX_WIDTH] px wide, JPEG-encoded
 * once at that size, and sent at an adaptive
 * [WearCameraProtocol.PREVIEW_MIN_FPS]..[WearCameraProtocol.PREVIEW_MAX_FPS]
 * fps. The hand-off is latest-frame-only ([LatestOnlySlot]): the analysis
 * tap never blocks, converts only while the slot is free, and drops
 * (counted) otherwise, so the phone's capture/analysis path cannot stall
 * behind the watch link. Captured media stays on the phone; only the
 * thumbnail crosses the link. Nothing here wakes a dead process.
 */
class WearCameraSync private constructor(context: Context) {

    companion object {
        private const val TAG = "WearCameraSync"
        private const val STATE_PUBLISH_INTERVAL_MS = 250L
        private const val PREVIEW_POLL_MS = 20L
        private const val NANOS_PER_MS = 1_000_000L

        @Volatile
        private var instance: WearCameraSync? = null

        /** The process-wide sync (activity/view-model must agree on one). */
        fun get(context: Context): WearCameraSync = instance ?: synchronized(this) {
            instance ?: WearCameraSync(context.applicationContext).also { instance = it }
        }
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Set by MainActivity once the camera ViewModel exists. */
    @Volatile
    var viewModel: CameraViewModel? = null

    private val _linkState = MutableStateFlow<WearLinkState>(WearLinkState.Stopped)

    @Volatile
    private var previewWanted = false
    private val previewSlot = LatestOnlySlot<RawPreviewFrame>()
    private val previewRate = AdaptivePreviewRate()
    private val previewProcessor = PreviewFrameProcessor(BitmapPreviewEncoder)
    private var previewJob: Job? = null
    private var stateJob: Job? = null
    private var frameSequence = 0L
    private var lastThumbnail: Bitmap? = null

    @Volatile
    private var lastCaptureAtMillis = 0L

    @Volatile
    private var lastCaptureKind = "" 

    private val listener = object : WearLinkListener {
        override fun onMessage(path: String, body: ByteArray) {
            if (path != WearCameraProtocol.OP_PATH) return
            val op = decodeWearCameraOp(body) ?: run {
                Log.status(TAG, "ignoring malformed wear op")
                return
            }
            when (op.kind) {
                WearOpKind.PREVIEW_START -> startPreview()
                WearOpKind.PREVIEW_STOP -> stopPreview()
                else -> {
                    val vm = viewModel ?: return
                    scope.launch {
                        val state = withContext(Dispatchers.Main) { vm.wearCameraState(previewWanted, lastCaptureAtMillis, lastCaptureKind) }
                        val decision = withContext(Dispatchers.Main) {
                            executeWearOp(op, state, ViewModelWearControls(vm))
                        }
                        if (decision is WearOpDecision.Reject) {
                            Log.status(TAG, "rejected wear op ${op.kind}: ${decision.reason}")
                        }
                        publishState()
                    }
                }
            }
        }
    }

    private val node = WearLinkNode(appContext, WearCameraProtocol.CAPABILITY, listener)
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch {
            node.state.collect { linkState ->
                _linkState.value = linkState
                if (linkState is WearLinkState.Connected) {
                    publishState()
                    publishThumbnail(lastThumbnail)
                } else if (previewWanted) {
                    // Link down: the stream stops honestly; the watch must
                    // ask again on the next session.
                    stopPreview()
                }
            }
        }
        node.start()
        startStateLoop()
    }

    // -- Mirrored state + thumbnail -------------------------------------------

    private fun startStateLoop() {
        if (stateJob != null) return
        stateJob = scope.launch {
            val vm = viewModel
            if (vm != null) {
                launch { vm.galleryThumbnail.collect { thumb -> onThumbnailChanged(thumb) } }
            }
            while (true) {
                publishState()
                delay(STATE_PUBLISH_INTERVAL_MS)
            }
        }
    }

    private suspend fun publishState() {
        val vm = viewModel ?: return
        if (node.state.value !is WearLinkState.Connected) return
        val state = withContext(Dispatchers.Main) { vm.wearCameraState(previewWanted, lastCaptureAtMillis, lastCaptureKind) }
        node.putData(WearCameraProtocol.STATE_PATH, encodeWearCameraState(state))
    }

    private fun onThumbnailChanged(thumb: Bitmap?) {
        if (thumb == null || thumb === lastThumbnail) return
        lastThumbnail = thumb
        val vm = viewModel
        if (vm != null) {
            lastCaptureAtMillis = System.currentTimeMillis()
            lastCaptureKind =
                if (vm.cameraMode.value.name in WearCameraProtocol.VIDEO_MODES) "VIDEO" else "PHOTO"
        }
        scope.launch { publishThumbnail(thumb) }
    }

    private suspend fun publishThumbnail(thumb: Bitmap?) {
        if (thumb == null) return
        if (node.state.value !is WearLinkState.Connected) return
        val (width, height) = previewDimensions(thumb.width, thumb.height)
        val scaled = if (width == thumb.width) thumb else Bitmap.createScaledBitmap(thumb, width, height, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.JPEG, WearCameraProtocol.PREVIEW_JPEG_QUALITY, out)
        if (scaled !== thumb) scaled.recycle()
        node.putData(WearCameraProtocol.THUMBNAIL_PATH, out.toByteArray())
    }

    // -- Live viewfinder --------------------------------------------------------

    private fun startPreview() {
        val vm = viewModel ?: return
        if (previewWanted) return
        previewWanted = true
        previewRate.reset()
        previewSlot.clear()
        vm.wearPreviewTap = { frame ->
            // Latest-only: convert only while the pump is idle, so the
            // analysis thread never queues frames behind a slow link.
            if (previewWanted && !previewSlot.isOccupied) {
                val argb = Yuv420ToArgb.convert(frame.planes, frame.width, frame.height)
                if (argb != null) {
                    previewSlot.offer(RawPreviewFrame(argb, frame.width, frame.height, frame.rotationDegrees))
                }
            }
            Unit
        }
        previewJob = scope.launch { previewPump() }
        scope.launch { publishState() }
    }

    private fun stopPreview() {
        previewWanted = false
        viewModel?.wearPreviewTap = null
        previewJob?.cancel()
        previewJob = null
        previewSlot.clear()
        scope.launch { publishState() }
    }

    private suspend fun previewPump() {
        while (previewWanted) {
            val raw = previewSlot.take()
            if (raw == null) {
                delay(PREVIEW_POLL_MS)
                continue
            }
            val startedAt = System.nanoTime()
            val encoded = previewProcessor.process(raw.argb, raw.width, raw.height)
            if (encoded == null) {
                previewRate.onSendFailed()
                continue
            }
            frameSequence++
            val body = PreviewFrame.encode(
                sequence = frameSequence,
                timestampMillis = System.currentTimeMillis(),
                width = encoded.width,
                height = encoded.height,
                rotationDegrees = raw.rotationDegrees,
                jpeg = encoded.jpeg,
            )
            val sent = node.sendMessage(WearCameraProtocol.PREVIEW_FRAME_PATH, body)
            val durationMs = (System.nanoTime() - startedAt) / NANOS_PER_MS
            if (sent) previewRate.onFrameSent(durationMs) else previewRate.onSendFailed()
            val waitMs = (previewRate.intervalMillis - durationMs).coerceAtLeast(0L)
            if (waitMs > 0L) delay(waitMs)
        }
    }

}
