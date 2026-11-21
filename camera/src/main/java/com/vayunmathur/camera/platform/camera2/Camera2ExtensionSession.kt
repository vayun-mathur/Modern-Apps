package com.vayunmathur.camera.platform.camera2

import android.view.Surface

/**
 * Camera2 migration Phase 6: NIGHT extension session core.
 *
 * Opens a `CameraDevice` through the Phase-2 driver seam, configures an
 * `ExtensionSessionConfiguration(EXTENSION_NIGHT, preview + still)` session
 * (never a regular session — the seam fails configure instead, see
 * [DeviceHandle.createExtensionCaptureSession]), streams a TEMPLATE_PREVIEW
 * repeating request, and takes stills whose JPEG bytes pair with their
 * `CaptureResult` metadata into a [CapturedStill] — the exact shape the
 * Phase-4a save glue (`Camera2StillCapture`) already consumes.
 *
 * Up-front honesty: [open] probes the snapshot with
 * [Camera2ExtensionProbe] before touching the device; a lens without a
 * usable preview+JPEG pair fails via [Listener.onError] and the device is
 * never opened. A configure failure likewise reports an error and produces
 * no still — callers fall back through [ExtensionsEnginePolicy] rather than
 * receiving a plain capture mislabelled as a night shot.
 *
 * Night engagement on this path is *not* decided here: there is no Camera2
 * `getNightModeIndicator()` equivalent, so the ViewModel's luma hysteresis
 * (PhotoAnalyzer → `onLuminance`) decides when night is active, exactly as
 * documented in [Camera2ExtensionProbe]. This session only serves a night
 * capture once that decision has been made.
 *
 * Threading and teardown mirror [Camera2Session]: every public method
 * posts onto the executor, driver callbacks are generation-checked through
 * [SessionStateMachine], and [close] is idempotent and terminal.
 */
class Camera2ExtensionSession(
    private val driver: Camera2Driver,
    private val executor: Camera2Executor,
    private val snapshot: LensCharacteristicsSnapshot,
    private val stateMachine: SessionStateMachine = SessionStateMachine(),
    private val listener: Listener? = null,
) {

    /** Session lifecycle notifications; all fire on the executor thread. */
    interface Listener {
        fun onStateChanged(state: SessionState) {}
        fun onError(message: String) {}
        fun onStreaming(probe: ExtensionProbeResult) {}
    }

    /** One-shot still outcome; fires on the executor thread. */
    interface StillCallback {
        fun onStillCaptured(still: CapturedStill)
        fun onStillFailed(message: String)
    }

    val state: SessionState get() = stateMachine.state

    private var device: DeviceHandle? = null
    private var captureSession: CaptureSessionHandle? = null
    private var previewSurface: Surface? = null
    private var previewSize: FrameSize? = null
    private var stillOutput: StillOutput? = null
    private var stillSize: FrameSize? = null
    private var probeResult: ExtensionProbeResult? = null
    private var pendingStill: PendingStill? = null
    private var displayRotationDegrees: Int = 0
    private var controls: Camera2ControlState = Camera2ControlState.DEFAULT

    private class PendingStill(
        val params: ResolvedStillParams,
        val callback: StillCallback,
        var rawMetadata: RawStillMetadata? = null,
        var jpegBytes: ByteArray? = null,
        var jpegWidth: Int = 0,
        var jpegHeight: Int = 0,
    )

    /** The probe outcome decided at [open], or null before a successful open. */
    fun currentProbe(): ExtensionProbeResult? = probeResult

    /** The JPEG still size configured at [open], or null when unavailable. */
    fun currentStillSize(): FrameSize? = stillSize

    /** True when a JPEG output exists on the extension session. */
    val hasJpegOutput: Boolean get() = stillOutput != null

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

    /**
     * Opens [cameraId] as a NIGHT extension session on [surface]. The probe
     * runs first; an unusable lens reports [Listener.onError] and never
     * opens the device. No-op unless CLOSED.
     */
    fun open(cameraId: String, surface: Surface, requestedSize: FrameSize) {
        executor.execute {
            if (stateMachine.state != SessionState.CLOSED) return@execute
            val probe = Camera2ExtensionProbe.probeNight(snapshot)
            probeResult = probe
            if (!probe.usable) {
                reportError("Camera2 NIGHT extension unusable for camera $cameraId: ${probe.rejectionReason}")
                return@execute
            }
            val previewSizes = snapshot.extensionSupportFor(probe.mode)?.previewSizes
                .orEmpty().map { FrameSize.from(it) }.distinct()
            val selected = StreamSelection.selectPreviewSize(requestedSize, previewSizes)
            if (selected == null) {
                reportError("no extension preview sizes for camera $cameraId")
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

    /**
     * Takes one extension still: issues `TEMPLATE_STILL_CAPTURE` carrying
     * the session's current control state plus flash resolution, and
     * delivers the JPEG bytes with mapped metadata once both halves
     * arrive. Only one still may be in flight; misuse fails via
     * [StillCallback.onStillFailed] without touching the HAL.
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
                    callback.onStillFailed("extension still capture requires a streaming session")
                output == null ->
                    callback.onStillFailed("no JPEG output on this extension session")
                pendingStill != null ->
                    callback.onStillFailed("an extension still capture is already in flight")
                else -> {
                    val params = StillCapturePlanner.resolve(
                        state = controls,
                        snapshot = snapshot,
                        displayRotationDegrees = displayRotationDegrees
                            ?: this@Camera2ExtensionSession.displayRotationDegrees,
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
            }
        }
    }

    /** Tears the session down; idempotent and terminal. */
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

    private fun onStillResult(pending: PendingStill, metadata: RawStillMetadata) {
        try {
            executor.execute {
                if (pendingStill !== pending) return@execute
                pending.rawMetadata = metadata
                deliverPendingIfReady(pending)
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
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

    private fun issueRepeatingRequest() {
        val session = captureSession ?: return
        val surface = previewSurface ?: return
        if (stateMachine.state != SessionState.STREAMING) return
        val params = Camera2Controls.resolve(controls, snapshot)
        runCatching { session.setRepeatingPreview(params, surface) }
            .onFailure { reportError("setRepeatingPreview failed: ${it.message}") }
    }

    private fun configureSession(opened: DeviceHandle, gen: Long) {
        val surface = previewSurface ?: return
        val probe = probeResult ?: return
        if (transition(SessionEvent.CONFIGURE_REQUESTED) !is TransitionResult.Applied) return
        val jpegSize = probe.stillSize
        val output = jpegSize?.let { size ->
            runCatching { driver.createStillOutput(size) }.getOrNull()?.also { created ->
                created.setListener(JpegImageListener { bytes, width, height, _ ->
                    onJpegAvailable(bytes, width, height)
                })
            }
        }
        stillOutput = output
        stillSize = if (output != null) jpegSize else null
        if (output == null) {
            dispatch(gen, SessionEvent.CONFIGURE_FAILED)
            reportError("no JPEG still output for Camera2 NIGHT extension session")
            return
        }
        val surfaces = listOf(surface, output.surface)
        runCatching {
            opened.createExtensionCaptureSession(
                probe.mode,
                surfaces,
                SessionCallbackImpl(gen),
            )
        }.onFailure {
            dispatch(gen, SessionEvent.CONFIGURE_FAILED)
            reportError("createExtensionCaptureSession failed: ${it.message}")
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

    private inner class DeviceCallbackImpl(
        private val gen: Long,
    ) : DeviceCallback {
        override fun onOpened(device: DeviceHandle) {
            if (!stateMachine.isCurrent(gen)) {
                runCatching { device.close() }
                return
            }
            if (dispatch(gen, SessionEvent.DEVICE_OPENED) !is TransitionResult.Applied) {
                runCatching { device.close() }
                return
            }
            this@Camera2ExtensionSession.device = device
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
            probeResult?.let { listener?.onStreaming(it) }
        }

        override fun onConfigureFailed() {
            if (!stateMachine.isCurrent(gen)) return
            dispatch(gen, SessionEvent.CONFIGURE_FAILED)
            reportError("extension capture session configure failed")
        }

        override fun onClosed() {
            // Session close follows device teardown; device callback drives CLOSED.
        }
    }
}
