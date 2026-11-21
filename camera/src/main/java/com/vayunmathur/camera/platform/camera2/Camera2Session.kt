package com.vayunmathur.camera.platform.camera2

import android.graphics.ImageFormat
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
    private val driver: Camera2Driver,
    private val executor: Camera2Executor,
    private val snapshot: LensCharacteristicsSnapshot,
    private val stateMachine: SessionStateMachine = SessionStateMachine(),
    private val listener: Listener? = null,
    private val ringConfig: ReprocessRingConfig = ReprocessRingConfig.DISABLED,
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

    val state: SessionState get() = stateMachine.state
    val generation: Long get() = stateMachine.generation

    private var device: DeviceHandle? = null
    private var captureSession: CaptureSessionHandle? = null
    private var previewSurface: Surface? = null
    private var previewSize: FrameSize? = null
    private var stillOutput: StillOutput? = null
    private var stillSize: FrameSize? = null
    private var ringOutput: RingOutput? = null
    private var ringSize: FrameSize? = null
    private val ring = ReprocessRing<RingHeldFrame>(ringConfig.capacity)
    private var latestPreviewTimestampNs: Long? = null
    private val previewMetas = LinkedHashMap<Long, RawStillMetadata>()
    private var pendingStill: PendingStill? = null
    private var displayRotationDegrees: Int = 0
    private var controls: Camera2ControlState = Camera2ControlState.DEFAULT

    /** In-flight still: JPEG bytes and result metadata pair up by arrival. */
    private class PendingStill(
        val params: ResolvedStillParams,
        val callback: StillCallback,
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
            val supported = StreamSelection.supportedPreviewSizes(snapshot)
            val selected = StreamSelection.selectPreviewSize(requestedSize, supported)
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
                else -> startStillCapture(callback, displayRotationDegrees, mirrored)
            }
        }
    }

    /** Regular TEMPLATE_STILL_CAPTURE path (capture + ring-miss fallback). */
    private fun startStillCapture(
        callback: StillCallback,
        displayRotationDegrees: Int?,
        mirrored: Boolean,
    ) {
        val session = captureSession ?: run {
            callback.onStillFailed("still capture requires a streaming session")
            return
        }
        val output = stillOutput ?: run {
            callback.onStillFailed("no JPEG output on this session")
            return
        }
        if (pendingStill != null) {
            callback.onStillFailed("a still capture is already in flight")
            return
        }
        val params = StillCapturePlanner.resolve(
            state = controls,
            snapshot = snapshot,
            displayRotationDegrees = displayRotationDegrees
                ?: this@Camera2Session.displayRotationDegrees,
            mirrored = mirrored,
        )
        val pending = PendingStill(params, callback)
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

    private fun onRingFrame(frame: RingHeldFrame) {
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

    private fun onPreviewResult(raw: RawStillMetadata) {
        val timestamp = raw.sensorTimestampNs ?: return
        latestPreviewTimestampNs = timestamp
        previewMetas[timestamp] = raw
        while (previewMetas.size > PREVIEW_META_CAP) {
            previewMetas.remove(previewMetas.keys.first())
        }
        ring.attachMetadata(timestamp, raw.exposureTimeNs, raw.frameNumber, raw.aeState, raw.iso)
    }

    private fun onStillResult(pending: PendingStill, metadata: RawStillMetadata) {
        // Real driver callbacks already land on the executor; fakes may
        // call synchronously. State is only touched on the executor.
        try {
            executor.execute {
                if (pendingStill !== pending) return@execute
                pending.rawMetadata = metadata
                deliverPendingIfReady(pending)
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // Session is gone; the capture outcome no longer matters.
        }
    }

    private fun onJpegAvailable(bytes: ByteArray, width: Int, height: Int) {
        try {
            executor.execute {
                val pending = pendingStill ?: return@execute
                pending.jpegBytes = bytes
                pending.jpegWidth = width
                pending.jpegHeight = height
                deliverPendingIfReady(pending)
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
        }
    }

    private fun deliverPendingIfReady(pending: PendingStill) {
        val raw = pending.rawMetadata ?: return
        val bytes = pending.jpegBytes ?: return
        pendingStill = null
        pending.callback.onStillCaptured(
            CapturedStill(
                jpegBytes = bytes,
                jpegWidth = pending.jpegWidth,
                jpegHeight = pending.jpegHeight,
                metadata = StillMetadataMapper.map(
                    raw, pending.params.jpegOrientationDegrees, pending.params.mirrored,
                ),
            ),
        )
    }

    private fun failPending(pending: PendingStill, message: String) {
        try {
            executor.execute {
                if (pendingStill !== pending) return@execute
                pendingStill = null
                pending.callback.onStillFailed(message)
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
        }
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
            runCatching { ringOutput?.close() }
            ringOutput = null
            ringSize = null
            ring.drainAll().forEach { runCatching { it.close() } }
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
        val targets = listOfNotNull(surface, ringOutput?.surface)
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

    private fun configureSession(opened: DeviceHandle, gen: Long) {
        val surface = previewSurface ?: return
        if (transition(SessionEvent.CONFIGURE_REQUESTED) !is TransitionResult.Applied) return
        // Second output: the JPEG still reader, when the lens reports JPEG
        // sizes and the driver can create it. Failure degrades to
        // preview-only (capture() then fails cleanly, policy excludes it).
        val jpegSize = StreamSelection.selectJpegSize(snapshot)
        val output = jpegSize?.let { size ->
            runCatching { driver.createStillOutput(size) }.getOrNull()?.also { created ->
                created.setListener(JpegImageListener { bytes, width, height, _ ->
                    onJpegAvailable(bytes, width, height)
                })
            }
        }
        stillOutput = output
        stillSize = if (output != null) jpegSize else null
        // Third output: the YUV ring, only when this session was built
        // with the ring enabled. Failure degrades to no-ring (the
        // policy then excludes the ring path, capture stays Phase 4a).
        val selectedRingSize = if (ringConfig.enabled) {
            StreamSelection.selectRingSize(snapshot)
        } else {
            null
        }
        val createdRing = selectedRingSize?.let { size ->
            runCatching { driver.createRingOutput(size) }.getOrNull()?.also { created ->
                created.setListener(RingFrameListener { frame -> onRingFrame(frame) })
            }
        }
        ringOutput = createdRing
        ringSize = if (createdRing != null) selectedRingSize else null
        val surfaces = listOfNotNull(surface, output?.surface, createdRing?.surface)
        val callback = SessionCallbackImpl(gen)
        runCatching {
            if (createdRing != null && snapshot.supportsYuvRingReprocess) {
                opened.createReprocessableCaptureSession(
                    ReprocessInputConfig(
                        selectedRingSize.width, selectedRingSize.height,
                        ImageFormat.YUV_420_888,
                    ),
                    surfaces,
                    callback,
                )
            } else {
                opened.createCaptureSession(surfaces, callback)
            }
        }.onFailure {
            dispatch(gen, SessionEvent.CONFIGURE_FAILED)
            reportError("createCaptureSession failed: ${it.message}")
        }
    }

    private fun transition(event: SessionEvent): TransitionResult {
        val result = stateMachine.transition(event)
        if (result is TransitionResult.Applied) notifyState()
        return result
    }

    private fun dispatch(gen: Long, event: SessionEvent): TransitionResult {
        val result = stateMachine.dispatch(gen, event)
        if (result is TransitionResult.Applied) notifyState()
        return result
    }

    private fun notifyState() {
        listener?.onStateChanged(stateMachine.state)
    }

    private fun reportError(message: String) {
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

    private inner class SessionCallbackImpl(
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
            dispatch(gen, SessionEvent.CONFIGURE_FAILED)
            reportError("capture session configure failed")
        }

        override fun onClosed() {
            // Session close is a consequence of device teardown; the device
            // callback drives the machine to CLOSED.
        }
    }
}
