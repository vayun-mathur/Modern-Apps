package com.vayunmathur.camera.platform.camera2

import android.view.Surface

/**
 * Camera2 migration Phase 5: constrained high-speed session core.
 *
 * Opens a `CameraDevice` through the Phase-2 driver seam, configures a
 * constrained high-speed capture session over an encoder surface plus a
 * preview surface, and streams a `TEMPLATE_RECORD` repeating burst pinned
 * to the selection's exact FPS range (see [HighSpeedPlanner]).
 *
 * This class owns the camera/session only — not the codec or muxer. The
 * caller supplies the encoder surface (the Phase-1 engine's MediaCodec
 * input, configured from [HighSpeedSelection.encoderPlan]) and keeps
 * ownership of it; coupling that engine to this session is the remaining
 * live-path work, so [SloMoEnginePolicy] keeps recording on CameraX until
 * it is done and device-verified.
 *
 * Threading and teardown mirror [Camera2Session]: every public method
 * posts onto the executor, driver callbacks are generation-checked through
 * [SessionStateMachine], and [close] is idempotent and terminal.
 */
class Camera2HighSpeedSession(
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
        fun onStreaming(selection: HighSpeedSelection) {}
    }

    val state: SessionState get() = stateMachine.state

    private var device: DeviceHandle? = null
    private var captureSession: CaptureSessionHandle? = null
    private var selection: HighSpeedSelection? = null

    // Reopen serialization, mirroring Camera2Session: a device close only
    // completes at its real onClosed, so an open in that window is parked.
    private var awaitingDeviceClosedGeneration: Long? = null
    private var deviceCallbackGeneration: Long? = null
    private var deviceClosedGeneration: Long? = null
    private var pendingOpen: PendingOpen? = null

    private class PendingOpen(
        val cameraId: String,
        val encoderSurface: Surface,
        val previewSurface: Surface,
    )

    /**
     * Opens [cameraId] for slo-mo: [encoderSurface] receives the HFR frames
     * for the codec, [previewSurface] the viewfinder. The operating point
     * is re-selected from the snapshot here (never trusted from the
     * caller); when the lens offers no honest point, [Listener.onError]
     * fires and the device is never opened.
     */
    fun open(cameraId: String, encoderSurface: Surface, previewSurface: Surface) {
        executor.execute {
            if (awaitingDeviceClosedGeneration != null) {
                pendingOpen = PendingOpen(cameraId, encoderSurface, previewSurface)
                return@execute
            }
            openOnExecutor(cameraId, encoderSurface, previewSurface)
        }
    }

    /** The [open] body; must run on the executor thread (or a device callback on it). */
    private fun openOnExecutor(cameraId: String, encoderSurface: Surface, previewSurface: Surface) {
            if (stateMachine.state != SessionState.CLOSED) return
            val selected = HighSpeedPlanner.select(snapshot)
            if (selected == null) {
                reportError("no constrained high-speed slo-mo point for camera $cameraId")
                return
            }
            selection = selected
            if (transition(SessionEvent.OPEN_REQUESTED) !is TransitionResult.Applied) {
                return
            }
            val gen = stateMachine.generation
            deviceCallbackGeneration = gen
            runCatching {
                driver.openCamera(cameraId, DeviceCallbackImpl(gen, encoderSurface, previewSurface))
            }.onFailure {
                dispatch(gen, SessionEvent.ERROR_OCCURRED)
                reportError("openCamera failed for $cameraId: ${it.message}")
            }
    }

    /** The operating point chosen at [open], or null before a successful open. */
    fun currentSelection(): HighSpeedSelection? = selection

    /**
     * Recording stop boundary (SloMoEnginePolicy piece 3): stops the
     * repeating high-speed request first, and only then invokes
     * [eosSignal], so no frame can enter the encoder after EOS. The
     * session and device stay open and the caller-owned encoder/preview
     * surfaces are never closed here; [close] remains the teardown.
     * A no-op unless streaming.
     */
    fun stopRecording(eosSignal: () -> Unit) {
        executor.execute {
            if (stateMachine.state != SessionState.STREAMING) return@execute
            runCatching { captureSession?.stopRepeating() }
                .onFailure { reportError("stopRepeating failed: ${it.message}") }
            eosSignal()
        }
    }

    /**
     * Tears the session down: bumps the generation first (so callbacks
     * already in flight go stale), then closes the capture session and the
     * device. Idempotent. The encoder/preview surfaces are caller-owned
     * and are not released here.
     */
    fun close() {
        executor.execute {
            pendingOpen = null
            if (stateMachine.state == SessionState.CLOSED) return@execute
            val gen = stateMachine.beginTeardown()
            runCatching { captureSession?.close() }
            captureSession = null
            val closingDevice = device
            if (closingDevice != null) {
                awaitingDeviceClosedGeneration =
                    deviceCallbackGeneration?.takeIf { it != deviceClosedGeneration }
                runCatching { closingDevice.close() }
                    .onFailure { awaitingDeviceClosedGeneration = null }
            }
            device = null
            dispatch(gen, SessionEvent.DEVICE_CLOSED)
            if (stateMachine.state == SessionState.CLOSING) {
                stateMachine.completeTeardown()
            }
            notifyState()
        }
    }

    // -- internals (executor thread only) ------------------------------------

    private fun configureSession(
        opened: DeviceHandle,
        gen: Long,
        encoderSurface: Surface,
        previewSurface: Surface,
    ) {
        if (transition(SessionEvent.CONFIGURE_REQUESTED) !is TransitionResult.Applied) return
        runCatching {
            opened.createConstrainedHighSpeedCaptureSession(
                listOf(encoderSurface, previewSurface),
                SessionCallbackImpl(gen, encoderSurface, previewSurface),
            )
        }.onFailure {
            dispatch(gen, SessionEvent.CONFIGURE_FAILED)
            reportError("createConstrainedHighSpeedCaptureSession failed: ${it.message}")
        }
    }

    private fun issueRepeatingRequest(encoderSurface: Surface, previewSurface: Surface) {
        val session = captureSession ?: return
        val selected = selection ?: return
        if (stateMachine.state != SessionState.STREAMING) return
        val params = HighSpeedPlanner.resolveRequest(selected)
        runCatching {
            session.setRepeatingHighSpeed(params, listOf(encoderSurface, previewSurface))
        }.onFailure { reportError("setRepeatingHighSpeed failed: ${it.message}") }
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
        private val encoderSurface: Surface,
        private val previewSurface: Surface,
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
            this@Camera2HighSpeedSession.device = device
            configureSession(device, gen, encoderSurface, previewSurface)
        }

        override fun onClosed() {
            deviceClosedGeneration = gen
            if (awaitingDeviceClosedGeneration == gen) {
                awaitingDeviceClosedGeneration = null
                val pending = pendingOpen
                pendingOpen = null
                if (pending != null && stateMachine.state == SessionState.CLOSED) {
                    openOnExecutor(pending.cameraId, pending.encoderSurface, pending.previewSurface)
                }
                return
            }
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
        private val encoderSurface: Surface,
        private val previewSurface: Surface,
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
            issueRepeatingRequest(encoderSurface, previewSurface)
            selection?.let { listener?.onStreaming(it) }
        }

        override fun onConfigureFailed() {
            if (!stateMachine.isCurrent(gen)) return
            dispatch(gen, SessionEvent.CONFIGURE_FAILED)
            reportError("constrained high-speed session configure failed")
        }

        override fun onClosed() {
            // Session close follows device teardown; the device callback
            // drives the machine to CLOSED.
        }
    }
}
