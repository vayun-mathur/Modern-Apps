package com.vayunmathur.camera.platform.camera2

import android.view.Surface

/**
 * Camera2 migration Phase 2: preview session core. Opens a `CameraDevice` on
 * the Phase-0 [Camera2Executor], configures a capture session whose only
 * output is the [PreviewSurfaceBridge] surface, and streams a
 * TEMPLATE_PREVIEW repeating request carrying the resolved controls.
 *
 * Phase 4a adds a JPEG `ImageReader` as a second output (when the lens
 * reports JPEG sizes) and [capture] for plain single stills; see
 * [StillCapturePlanner] and [StillEnginePolicy].
 *
 * The depth phase adds an optional YUV reprocess ring (third output,
 * [ReprocessRingConfig]): while streaming the ring holds the newest
 * already-exposed frames; [captureFromRing] serves a shutter from one
 * of them — reprocessed through `ImageWriter` on a reprocessable
 * session when the facade reports YUV reprocessing, direct-encoded
 * otherwise — with no AE-convergence wait or fresh exposure.
 *
 * The analysis phase adds an optional YUV analysis stream (another
 * output, [analysisConsumer]): when a consumer is supplied, a capped
 * YUV `ImageReader` feeds it plane views (see [AnalysisImageSource])
 * alongside preview/still. This stream is strictly best-effort: if its
 * output cannot be created, or the stream combination fails to
 * configure with it, the session drops the analysis output and
 * (re)configures preview+still without it — analysis never costs the
 * preview (the fallback-latch philosophy, applied per-stream).
 *
 * Threading: every public method posts onto the executor; every driver
 * callback arrives on the executor and is generation-checked through
 * [SessionStateMachine] before it may touch session state, so a callback
 * from a torn-down generation is dropped rather than resurrecting a closed
 * device.
 *
 * Stream selection is validated against the [CharacteristicsFacade] snapshot
 * before the device is opened (see [StreamSelection]): an unsupported
 * preview size fails fast here and never reaches a trial bind.
 *
 * Ownership: whoever constructs this owns it and must call [close] (e.g.
 * from a `DisposableEffect`). [close] is idempotent and terminal.
 */
class Camera2Session(
    internal val driver: Camera2Driver,
    internal val executor: Camera2Executor,
    internal val snapshot: LensCharacteristicsSnapshot,
    private val stateMachine: SessionStateMachine = SessionStateMachine(),
    private val listener: Listener? = null,
    internal val ringConfig: ReprocessRingConfig = ReprocessRingConfig.DISABLED,
    internal val rawRingConfig: RawRingConfig = RawRingConfig.DISABLED,
    /** Plane consumer for the analysis stream; null = no analysis output. */
    internal val analysisConsumer: AnalysisConsumer? = null,
    /** Whether the JPEG_R (Ultra HDR) output may be configured at all. */
    internal val ultraHdrEnabled: Boolean = true,
) {

    /** Session lifecycle notifications; all fire on the executor thread. */
    interface Listener {
        fun onStateChanged(state: SessionState) {}
        fun onError(message: String) {}
        fun onStreaming(previewSize: FrameSize) {}
    }

    /** One-shot still outcome; fires on the executor thread. */
    interface StillCallback {
        fun onStillCaptured(still: CapturedStill)
        fun onStillFailed(message: String)
    }

    /** RAW burst outcome; fires on the executor thread. Caller closes frames. */
    interface RawBurstCallback {
        fun onRawBurst(frames: List<RawHeldFrame>, metas: List<RingFrameMeta>)
        fun onRawBurstFailed(message: String)
    }

    val state: SessionState get() = stateMachine.state
    val generation: Long get() = stateMachine.generation

    /** The facade snapshot this session was built with (RAW metadata source). */
    val characteristics: LensCharacteristicsSnapshot get() = snapshot

    /** Retained preview `CaptureResult` metadata for [timestampNs], or null. */
    fun previewMetadataFor(timestampNs: Long): RawStillMetadata? = previewMetas[timestampNs]

    private var device: DeviceHandle? = null
    private var captureSession: CaptureSessionHandle? = null
    internal var previewSurface: Surface? = null
    private var previewSize: FrameSize? = null
    internal var stillOutput: StillOutput? = null
    internal var stillSize: FrameSize? = null
    internal var ultraHdrOutput: StillOutput? = null
    internal var ultraHdrSize: FrameSize? = null
    internal var ringOutput: RingOutput? = null
    internal var ringSize: FrameSize? = null
    private val ring = ReprocessRing<RingHeldFrame>(ringConfig.capacity)
    internal var analysisOutput: AnalysisOutput? = null
    internal var analysisSize: FrameSize? = null
    /** Set once a configure failure has dropped analysis: never re-add it. */
    internal var analysisComboDropped: Boolean = false
    internal var rawRingOutput: RawRingOutput? = null
    internal var rawRingSize: FrameSize? = null
    internal var rawRingFormat: RingFormat? = null
    private val rawRing = ReprocessRing<RawHeldFrame>(rawRingConfig.capacity)
    private var latestPreviewTimestampNs: Long? = null
    private val previewMetas = LinkedHashMap<Long, RawStillMetadata>()

    @Volatile
    private var latestPreviewMeta: RawStillMetadata? = null
    internal var pendingStill: PendingStill? = null
    internal var displayRotationDegrees: Int = 0
    private var controls: Camera2ControlState = Camera2ControlState.DEFAULT

    /** In-flight still: JPEG bytes and result metadata pair up by arrival. */
    internal class PendingStill(
        val params: ResolvedStillParams,
        val callback: StillCallback,
        val tag: StillOutputTag = StillOutputTag.JPEG,
        var rawMetadata: RawStillMetadata? = null,
        var jpegBytes: ByteArray? = null,
        var jpegWidth: Int = 0,
        var jpegHeight: Int = 0,
    )

    /** Current control state (user intent, unresolved). */
    val controlState: Camera2ControlState get() = controls

    /**
     * Opens [cameraId] and starts preview on [surface]. [requestedSize] is
     * validated against the snapshot first; a rejected size reports
     * [Listener.onError] and never opens the device. No-op unless CLOSED.
     */
    fun open(cameraId: String, surface: Surface, requestedSize: FrameSize) {
        executor.execute {
            if (stateMachine.state != SessionState.CLOSED) return@execute
            // LIMITED guaranteed combinations cap the preview target at
            // 1080p alongside JPEG(MAX)+YUV: never select a larger preview
            // size on such a lens when a capped size exists. The surface
            // pins its buffer to this same selection (see
            // StreamSelection.selectSessionPreviewSize).
            val selected = StreamSelection.selectSessionPreviewSize(
                requestedSize, snapshot, displayRotationDegrees,
            )
            if (selected == null) {
                reportError(
                    "no supported preview sizes for camera $cameraId " +
                        "(requested ${requestedSize.width}x${requestedSize.height})",
                )
                return@execute
            }
            previewSurface = surface
            previewSize = selected
            if (transition(SessionEvent.OPEN_REQUESTED) !is TransitionResult.Applied) {
                return@execute
            }
            val gen = stateMachine.generation
            runCatching {
                driver.openCamera(cameraId, DeviceCallbackImpl(gen))
            }.onFailure {
                dispatch(gen, SessionEvent.ERROR_OCCURRED)
                reportError("openCamera failed for $cameraId: ${it.message}")
            }
        }
    }

    /** Replaces the whole control state and re-issues the repeating request. */
    fun updateControls(next: Camera2ControlState) {
        executor.execute {
            controls = next
            issueRepeatingRequest()
        }
    }

    /** Applies a zoom ratio (control-bar / pinch), keeping other controls. */
    fun setZoomRatio(ratio: Float) {
        // Mutate on the executor: copying the (executor-owned) state on the
        // caller thread would race with other queued control updates.
        executor.execute {
            controls = controls.copy(zoomRatio = ratio)
            issueRepeatingRequest()
        }
    }

    /** Applies torch on/off, keeping other controls. */
    fun setTorch(on: Boolean) {
        executor.execute {
            controls = controls.copy(torchOn = on)
            issueRepeatingRequest()
        }
    }

    /** Applies exposure compensation in EV stops, keeping other controls. */
    /** Manual exposure pair (both null returns to AE); preview + stills inherit it. */
    fun setManualExposure(exposureTimeNs: Long?, sensitivityIso: Int?) {
        executor.execute {
            controls = controls.copy(
                exposureTimeNs = exposureTimeNs,
                sensitivityIso = sensitivityIso,
            )
            issueRepeatingRequest()
        }
    }

    /** Post-processing toggles (#662); preview + stills inherit them. */
    fun setPostProcessing(noiseReductionHighQuality: Boolean, edgeHighQuality: Boolean) {
        executor.execute {
            controls = controls.copy(
                noiseReductionHighQuality = noiseReductionHighQuality,
                edgeHighQuality = edgeHighQuality,
            )
            issueRepeatingRequest()
        }
    }

    /** Latest AE-converged (exposureNs, iso) from preview results, for manual seeding. */
    fun latestAeSnapshot(): Pair<Long?, Int?> =
        latestPreviewMeta.let { (it?.exposureTimeNs) to (it?.iso) }

    fun setExposureCompEv(ev: Float) {
        executor.execute {
            controls = controls.copy(exposureCompEv = ev)
            issueRepeatingRequest()
        }
    }

    /**
     * Tap-to-focus: transforms a normalised viewfinder tap into AF + AE
     * metering regions (see [MeteringTransform]) and re-issues the request.
     * [displayRotationDegrees] is the display rotation (0/90/180/270);
     * [mirrored] is true for a mirrored front preview.
     */
    fun tapToFocus(
        tapX: Float,
        tapY: Float,
        displayRotationDegrees: Int = 0,
        mirrored: Boolean = false,
    ) {
        executor.execute {
            val activeArray = Camera2Controls.activeArrayOf(snapshot)
            if (activeArray.width <= 0 || activeArray.height <= 0) return@execute
            val crop = Camera2Controls.currentCropRegion(controls, snapshot)
            val region = MeteringTransform.meteringRegionForTap(
                tapX = tapX,
                tapY = tapY,
                activeArray = activeArray,
                cropRegion = crop,
                sensorOrientationDegrees = snapshot.sensorOrientationDegrees,
                displayRotationDegrees = displayRotationDegrees,
                mirrored = mirrored,
            )
            controls = controls.copy(afRegion = region, aeRegion = region)
            issueRepeatingRequest()
        }
    }

    /** Clears tap metering, returning AF/AE to continuous full-frame. */
    fun clearMetering() {
        executor.execute {
            controls = controls.copy(afRegion = null, aeRegion = null)
            issueRepeatingRequest()
        }
    }

    /** Applies the user's still-flash setting, keeping other controls. */
    fun setFlashSetting(setting: com.vayunmathur.camera.domain.FlashSetting) {
        executor.execute {
            controls = controls.copy(flashSetting = setting)
            issueRepeatingRequest()
        }
    }

    /** Records the display rotation still orientations are computed from. */
    fun setDisplayRotationDegrees(degrees: Int) {
        executor.execute { displayRotationDegrees = degrees }
    }

    /** The JPEG still size configured at [open], or null when unavailable. */
    fun currentStillSize(): FrameSize? = stillSize

    /** True when a JPEG output exists (see [StillEnginePolicy]). */
    val hasJpegOutput: Boolean get() = stillOutput != null

    /** Facade-reported JPEG_R (Ultra HDR) support for this lens. */
    val ultraHdrSupportedJpegR: Boolean get() = snapshot.supportsJpegR

    /** True when a JPEG_R output is configured (Ultra HDR captures can be served). */
    val hasUltraHdrOutput: Boolean get() = ultraHdrOutput != null

    /** The JPEG_R still size configured at [open], or null when unavailable. */
    fun currentUltraHdrSize(): FrameSize? = ultraHdrSize

    /** True when the YUV analysis output is configured (see AnalysisSourcePolicy). */
    val hasAnalysisOutput: Boolean get() = analysisOutput != null

    /** The analysis size configured at [open], or null when unavailable/dropped. */
    fun currentAnalysisSize(): FrameSize? = analysisSize

    /** True when the YUV ring output is configured (see [StillEnginePolicy]). */
    val hasRingOutput: Boolean get() = ringOutput != null

    /** The ring size configured at [open], or null when unavailable. */
    fun currentRingSize(): FrameSize? = ringSize

    /** Frames currently held in the ring. */
    val ringFrameCount: Int get() = ring.size

    /** Ring frames evicted (oldest dropped) since [open]. */
    val ringEvictedFrames: Long get() = ring.evictedFrames

    /** Facade-reported YUV reprocess support for this lens. */
    val reprocessSupported: Boolean get() = snapshot.supportsYuvRingReprocess

    /** True when the RAW ring output is configured (see [RawNightPolicy]). */
    val hasRawRingOutput: Boolean get() = rawRingOutput != null

    /** The RAW ring size configured at [open], or null when unavailable. */
    fun currentRawRingSize(): FrameSize? = rawRingSize

    /** The RAW ring format configured at [open], or null when unavailable. */
    fun currentRawRingFormat(): RingFormat? = rawRingFormat

    /** Frames currently held in the RAW ring. */
    val rawRingFrameCount: Int get() = rawRing.size

    /** Facade-reported RAW ring support for this lens. */
    val rawSupported: Boolean get() = snapshot.supportsRawRing

    /**
     * Takes one still from the ring: picks the exposure-complete frame
     * nearest [shutterTimestampNs] (or the latest preview timestamp when
     * null), then either reprocesses it on the reprocessable session or
     * direct-encodes it, delivering through the same [StillCallback]
     * shape as [capture]. A ring miss (empty / nothing fully exposed)
     * and a runtime reprocess refusal both fall back to the regular
     * still capture — the shutter is never lost to the ring.
     */
    fun captureFromRing(
        callback: StillCallback,
        shutterTimestampNs: Long? = null,
        displayRotationDegrees: Int? = null,
        mirrored: Boolean = false,
    ) {
        executor.execute {
            val session = captureSession
            when {
                stateMachine.state != SessionState.STREAMING || session == null ->
                    callback.onStillFailed("ring capture requires a streaming session")
                ringOutput == null ->
                    callback.onStillFailed("no YUV ring output on this session")
                pendingStill != null ->
                    callback.onStillFailed("a still capture is already in flight")
                else -> {
                    val shutter = shutterTimestampNs
                        ?: latestPreviewTimestampNs
                        ?: ring.metas().maxOfOrNull { it.sensorTimestampNs }
                    val selection = shutter?.let { ring.select(it) }
                    if (selection !is RingSelection.Hit) {
                        // No already-exposed frame: take a fresh still.
                        startStillCapture(callback, displayRotationDegrees, mirrored)
                        return@execute
                    }
                    val held = ring.remove(selection.meta.sensorTimestampNs)
                    if (held == null) {
                        startStillCapture(callback, displayRotationDegrees, mirrored)
                        return@execute
                    }
                    val params = StillCapturePlanner.resolve(
                        state = controls,
                        snapshot = snapshot,
                        displayRotationDegrees = displayRotationDegrees
                            ?: this@Camera2Session.displayRotationDegrees,
                        mirrored = mirrored,
                    )
                    val output = stillOutput
                    val reprocessed = output != null && snapshot.supportsYuvRingReprocess &&
                        runCatching {
                            val pending = PendingStill(params, callback)
                            pendingStill = pending
                            val started = session.captureReprocess(
                                params, output.surface, held,
                                object : StillResultCallback {
                                    override fun onCompleted(metadata: RawStillMetadata) =
                                        onStillResult(pending, metadata)

                                    override fun onFailed(message: String) =
                                        failPending(pending, message)
                                },
                            )
                            if (!started && pendingStill === pending) pendingStill = null
                            started
                        }.getOrDefault(false)
                    if (reprocessed) {
                        // ImageWriter owns the held image from here.
                        return@execute
                    }
                    // Direct fallback: encode the already-exposed frame.
                    val jpeg = held.encodeJpeg()
                    // The ring's copy carries preview-result patches;
                    // the held frame's own meta may predate them.
                    val meta = selection.meta
                    held.close()
                    if (jpeg == null) {
                        startStillCapture(callback, displayRotationDegrees, mirrored)
                        return@execute
                    }
                    callback.onStillCaptured(
                        CapturedStill(
                            jpegBytes = jpeg,
                            jpegWidth = meta.width,
                            jpegHeight = meta.height,
                            metadata = StillMetadataMapper.map(
                                RawStillMetadata(
                                    sensorTimestampNs = meta.sensorTimestampNs,
                                    exposureTimeNs = meta.exposureTimeNs,
                                    iso = meta.iso,
                                    frameNumber = meta.frameNumber,
                                ),
                                params.jpegOrientationDegrees, params.mirrored,
                            ),
                        ),
                    )
                }
            }
        }
    }

    /**
     * Hands the newest [count] exposure-complete RAW ring frames (oldest
     * first, so frame 0 is the merge reference) to [callback]. The frames
     * are removed from the ring; the caller owns closing them. Fewer than
     * two frames is a failure — a one-frame "merge" would silently be a
     * single RAW shot, and the caller falls back to the YUV night path.
     */
    fun captureRawBurst(
        count: Int,
        callback: RawBurstCallback,
        shutterTimestampNs: Long? = null,
    ) {
        executor.execute {
            when {
                stateMachine.state != SessionState.STREAMING ->
                    callback.onRawBurstFailed("RAW burst requires a streaming session")
                rawRingOutput == null ->
                    callback.onRawBurstFailed("no RAW ring output on this session")
                else -> {
                    val shutter = shutterTimestampNs
                        ?: latestPreviewTimestampNs
                        ?: rawRing.metas().maxOfOrNull { it.sensorTimestampNs }
                    val burst = shutter?.let { rawRing.latestCompleteBurst(count, it) }
                        .orEmpty()
                    if (burst.size < 2) {
                        callback.onRawBurstFailed(
                            "RAW ring holds ${burst.size} complete frames, need 2+",
                        )
                        return@execute
                    }
                    val frames = burst.mapNotNull { (frame, meta) ->
                        rawRing.remove(meta.sensorTimestampNs)?.let { frame }
                    }
                    if (frames.size < 2) {
                        frames.forEach { runCatching { it.close() } }
                        callback.onRawBurstFailed("RAW ring frames vanished during burst selection")
                        return@execute
                    }
                    callback.onRawBurst(frames, burst.map { it.second })
                }
            }
        }
    }

    /**
     * Takes one plain still: issues `TEMPLATE_STILL_CAPTURE` carrying the
     * session's current control state plus flash resolution, and delivers
     * the JPEG bytes with mapped `CaptureResult` metadata to [callback]
     * once both halves arrive. Only one still may be in flight; a second
     * call, a non-streaming session, or a session without a JPEG output
     * fails via [StillCallback.onStillFailed] without touching the HAL.
     */
    fun capture(
        callback: StillCallback,
        displayRotationDegrees: Int? = null,
        mirrored: Boolean = false,
        ultraHdrRequested: Boolean = false,
    ) {
        executor.execute {
            val session = captureSession
            val output = stillOutput
            when {
                stateMachine.state != SessionState.STREAMING || session == null ->
                    callback.onStillFailed("still capture requires a streaming session")
                output == null ->
                    callback.onStillFailed("no JPEG output on this session")
                pendingStill != null ->
                    callback.onStillFailed("a still capture is already in flight")
                else -> startStillCapture(callback, displayRotationDegrees, mirrored, ultraHdrRequested)
            }
        }
    }

    /** Regular TEMPLATE_STILL_CAPTURE path (capture + ring-miss fallback). */
    private fun startStillCapture(
        callback: StillCallback,
        displayRotationDegrees: Int?,
        mirrored: Boolean,
        ultraHdrRequested: Boolean = false,
    ) {
        val session = captureSession ?: run {
            callback.onStillFailed("still capture requires a streaming session")
            return
        }
        val jpegOutput = stillOutput ?: run {
            callback.onStillFailed("no JPEG output on this session")
            return
        }
        if (pendingStill != null) {
            callback.onStillFailed("a still capture is already in flight")
            return
        }
        // JPEG_R only when requested and a JPEG_R output was configured;
        // every other case is the plain JPEG output (graceful fallback,
        // never a failed capture because Ultra HDR was requested).
        val tag = StillOutputRouter.select(ultraHdrRequested, ultraHdrOutput != null)
        val output = if (tag == StillOutputTag.ULTRA_HDR_JPEG_R) {
            ultraHdrOutput ?: jpegOutput
        } else {
            jpegOutput
        }
        val params = StillCapturePlanner.resolve(
            state = controls,
            snapshot = snapshot,
            displayRotationDegrees = displayRotationDegrees
                ?: this@Camera2Session.displayRotationDegrees,
            mirrored = mirrored,
        )
        val pending = PendingStill(params, callback, tag)
        pendingStill = pending
        runCatching {
            session.captureStill(params, output.surface, object : StillResultCallback {
                override fun onCompleted(metadata: RawStillMetadata) {
                    onStillResult(pending, metadata)
                }

                override fun onFailed(message: String) {
                    failPending(pending, message)
                }
            })
        }.onFailure { failPending(pending, "captureStill failed: ${it.message}") }
    }

    // Still byte/result pairing + delivery lives in Camera2SessionStillDelivery.kt.

    internal fun onRingFrame(frame: RingHeldFrame) {
        try {
            executor.execute {
                val evicted = ring.offer(frame.meta, frame)
                evicted?.close()
                // The preview result may have arrived before this image.
                previewMetas[frame.meta.sensorTimestampNs]?.let { raw ->
                    ring.attachMetadata(
                        frame.meta.sensorTimestampNs, raw.exposureTimeNs,
                        raw.frameNumber, raw.aeState, raw.iso,
                    )
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            frame.close()
        }
    }

    internal fun onRawRingFrame(frame: RawHeldFrame) {
        try {
            executor.execute {
                val evicted = rawRing.offer(frame.meta, frame)
                evicted?.close()
                previewMetas[frame.meta.sensorTimestampNs]?.let { raw ->
                    rawRing.attachMetadata(
                        frame.meta.sensorTimestampNs, raw.exposureTimeNs,
                        raw.frameNumber, raw.aeState, raw.iso,
                    )
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            frame.close()
        }
    }

    private fun onPreviewResult(raw: RawStillMetadata) {
        val timestamp = raw.sensorTimestampNs ?: return
        latestPreviewTimestampNs = timestamp
        latestPreviewMeta = raw
        previewMetas[timestamp] = raw
        while (previewMetas.size > PREVIEW_META_CAP) {
            previewMetas.remove(previewMetas.keys.first())
        }
        ring.attachMetadata(timestamp, raw.exposureTimeNs, raw.frameNumber, raw.aeState, raw.iso)
        rawRing.attachMetadata(timestamp, raw.exposureTimeNs, raw.frameNumber, raw.aeState, raw.iso)
    }


    /** The preview size chosen at [open], or null before a successful open. */
    fun currentPreviewSize(): FrameSize? = previewSize

    /**
     * Tears the session down: bumps the generation first (so callbacks
     * already in flight go stale), then closes the capture session and the
     * device. Idempotent.
     */
    fun close() {
        executor.execute {
            if (stateMachine.state == SessionState.CLOSED) return@execute
            val gen = stateMachine.beginTeardown()
            pendingStill?.let { pending ->
                pendingStill = null
                runCatching { pending.callback.onStillFailed("session closed during capture") }
            }
            runCatching { captureSession?.close() }
            captureSession = null
            runCatching { stillOutput?.close() }
            stillOutput = null
            stillSize = null
            runCatching { ultraHdrOutput?.close() }
            ultraHdrOutput = null
            ultraHdrSize = null
            runCatching { ringOutput?.close() }
            ringOutput = null
            ringSize = null
            ring.drainAll().forEach { runCatching { it.close() } }
            runCatching { analysisOutput?.close() }
            analysisOutput = null
            analysisSize = null
            runCatching { rawRingOutput?.close() }
            rawRingOutput = null
            rawRingSize = null
            rawRingFormat = null
            rawRing.drainAll().forEach { runCatching { it.close() } }
            previewMetas.clear()
            runCatching { device?.close() }
            device = null
            previewSurface = null
            dispatch(gen, SessionEvent.DEVICE_CLOSED)
            if (stateMachine.state == SessionState.CLOSING) {
                stateMachine.completeTeardown()
            }
            notifyState()
        }
    }

    // -- internals (executor thread only) ------------------------------------

    private fun issueRepeatingRequest() {
        val session = captureSession ?: return
        val surface = previewSurface ?: return
        if (stateMachine.state != SessionState.STREAMING) return
        val params = Camera2Controls.resolve(controls, snapshot)
        val targets = listOfNotNull(
            surface, ringOutput?.surface, rawRingOutput?.surface, analysisOutput?.surface,
        )
        runCatching {
            session.setRepeatingStreaming(params, targets, PreviewResultCallback { raw ->
                try {
                    executor.execute { onPreviewResult(raw) }
                } catch (e: java.util.concurrent.RejectedExecutionException) {
                    // Session is gone; preview metadata no longer matters.
                }
            })
        }.onFailure { reportError("setRepeatingPreview failed: ${it.message}") }
    }

    // Session configuration (outputs + capture-session creation) lives in
    // Camera2SessionConfigure.kt.
    internal fun transition(event: SessionEvent): TransitionResult {
        val result = stateMachine.transition(event)
        if (result is TransitionResult.Applied) notifyState()
        return result
    }

    internal fun dispatch(gen: Long, event: SessionEvent): TransitionResult {
        val result = stateMachine.dispatch(gen, event)
        if (result is TransitionResult.Applied) notifyState()
        return result
    }

    private fun notifyState() {
        listener?.onStateChanged(stateMachine.state)
    }

    internal fun reportError(message: String) {
        listener?.onError(message)
    }

    private companion object {
        const val PREVIEW_META_CAP: Int = 16
    }

    private inner class DeviceCallbackImpl(
        private val gen: Long,
    ) : DeviceCallback {
        override fun onOpened(device: DeviceHandle) {
            if (!stateMachine.isCurrent(gen)) {
                // Stale open completing after teardown: drop the handle.
                runCatching { device.close() }
                return
            }
            if (dispatch(gen, SessionEvent.DEVICE_OPENED) !is TransitionResult.Applied) {
                runCatching { device.close() }
                return
            }
            this@Camera2Session.device = device
            configureSession(device, gen)
        }

        override fun onClosed() {
            dispatch(gen, SessionEvent.DEVICE_CLOSED)
        }

        override fun onDisconnected() {
            if (!stateMachine.isCurrent(gen)) return
            dispatch(gen, SessionEvent.ERROR_OCCURRED)
            reportError("camera disconnected")
        }

        override fun onError(errorCode: Int) {
            if (!stateMachine.isCurrent(gen)) return
            dispatch(gen, SessionEvent.ERROR_OCCURRED)
            reportError("camera open error $errorCode")
        }
    }

    internal inner class SessionCallbackImpl(
        private val gen: Long,
    ) : CaptureSessionCallback {
        override fun onConfigured(session: CaptureSessionHandle) {
            if (!stateMachine.isCurrent(gen)) {
                runCatching { session.close() }
                return
            }
            if (dispatch(gen, SessionEvent.SESSION_CONFIGURED) !is TransitionResult.Applied) {
                runCatching { session.close() }
                return
            }
            captureSession = session
            issueRepeatingRequest()
            previewSize?.let { listener?.onStreaming(it) }
        }

        override fun onConfigureFailed() {
            if (!stateMachine.isCurrent(gen)) return
            // Stream-combo degrade: a combination that included the YUV
            // analysis surface failed — drop analysis and reconfigure
            // preview+still once. Only a failure without analysis (or a
            // second failure) is a real configure failure.
            val opened = device
            if (analysisOutput != null && !analysisComboDropped && opened != null) {
                dispatch(gen, SessionEvent.CONFIGURE_FAILED)
                dropAnalysisOutput()
                configureSession(opened, gen, reuseOutputs = true)
                return
            }
            dispatch(gen, SessionEvent.CONFIGURE_FAILED)
            reportError("capture session configure failed")
        }

        override fun onClosed() {
            // Session close is a consequence of device teardown; the device
            // callback drives the machine to CLOSED.
        }
    }
}
