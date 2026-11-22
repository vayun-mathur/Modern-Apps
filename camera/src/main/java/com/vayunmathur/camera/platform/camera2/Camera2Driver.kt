package com.vayunmathur.camera.platform.camera2

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraExtensionSession
import android.hardware.camera2.params.ExtensionSessionConfiguration
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.os.Build
import android.os.Handler
import android.view.Surface

/**
 * Camera2 migration Phase 2: the driver seam between [Camera2Session] and the
 * `android.hardware.camera2` API. Robolectric's Camera2 shadows cannot drive
 * a real session, so the session logic (state machine, stream validation,
 * control resolution) is written against these interfaces and unit-tested
 * with a fake driver; [AndroidCamera2Driver] is the only implementation that
 * touches `CameraDevice`/`CameraCaptureSession`.
 *
 * All callbacks fire on the session's [Camera2Executor] thread (the driver is
 * constructed with its [Handler]); implementations must not call back from
 * any other thread, and the session generation-checks every callback anyway.
 */

fun interface SessionErrorListener {
    fun onError(message: String)
}

/** Callbacks for one [Camera2Driver.openCamera] call. */
interface DeviceCallback {
    fun onOpened(device: DeviceHandle)
    fun onClosed()
    fun onDisconnected()
    fun onError(errorCode: Int)
}

/** Callbacks for preview `CaptureResult`s (ring metadata pairing). */
fun interface PreviewResultCallback {
    fun onPreviewResult(metadata: RawStillMetadata)
}

/** Input stream config for a reprocessable session (the ring format). */
data class ReprocessInputConfig(val width: Int, val height: Int, val format: Int)

/** Callbacks for one [DeviceHandle.createCaptureSession] call. */
interface CaptureSessionCallback {
    fun onConfigured(session: CaptureSessionHandle)
    fun onConfigureFailed()
    fun onClosed()
}

/** Testable handle over a `CameraDevice`. */
interface DeviceHandle {
    val cameraId: String
    fun createCaptureSession(surfaces: List<Surface>, callback: CaptureSessionCallback)

    /**
     * Creates a constrained high-speed session (Phase 5) over [surfaces],
     * which must be a combination the HAL advertised for high speed. The
     * default fails the configure: silently substituting a regular session
     * would record realtime video and call it slo-mo.
     */
    fun createConstrainedHighSpeedCaptureSession(
        surfaces: List<Surface>,
        callback: CaptureSessionCallback,
    ) {
        callback.onConfigureFailed()
    }

    /**
     * Creates an extension session (Phase 6) for [extensionMode]
     * (`CameraExtensionCharacteristics.EXTENSION_*`) over [surfaces]
     * (preview first, JPEG still second). The default fails the configure:
     * silently substituting a regular session would take a plain still and
     * call it a vendor night shot.
     */
    fun createExtensionCaptureSession(
        extensionMode: Int,
        surfaces: List<Surface>,
        callback: CaptureSessionCallback,
    ) {
        callback.onConfigureFailed()
    }

    /**
     * Creates a reprocessable session whose input is [input] (the ring
     * reader). The default fails the configure: silently substituting a
     * regular session would let a "reprocess" request consume nothing.
     */
    fun createReprocessableCaptureSession(
        input: ReprocessInputConfig,
        surfaces: List<Surface>,
        callback: CaptureSessionCallback,
    ) {
        callback.onConfigureFailed()
    }

    fun close()
}

/** Callbacks for one [CaptureSessionHandle.captureStill] call. */
interface StillResultCallback {
    fun onCompleted(metadata: RawStillMetadata)
    fun onFailed(message: String)
}

/** Testable handle over a `CameraCaptureSession`. */
interface CaptureSessionHandle {
    /** Issues [params] as the TEMPLATE_PREVIEW repeating request on [target]. */
    fun setRepeatingPreview(params: ResolvedRequestParams, target: Surface)

    /**
     * Issues [params] as one TEMPLATE_STILL_CAPTURE request on [stillTarget]
     * (preview target stays in the session, not in this request). The JPEG
     * bytes arrive separately via the [StillOutput] listener; [callback]
     * carries the `CaptureResult` metadata for the same frame.
     */
    fun captureStill(params: ResolvedStillParams, stillTarget: Surface, callback: StillResultCallback)

    /**
     * Streaming request over [targets] (preview first, ring second when
     * present) with per-frame results for ring metadata pairing. The
     * default keeps the single-target behaviour and drops results.
     */
    fun setRepeatingStreaming(
        params: ResolvedRequestParams,
        targets: List<Surface>,
        callback: PreviewResultCallback?,
    ) {
        if (targets.isNotEmpty()) setRepeatingPreview(params, targets.first())
    }

    /** True when this session was created as reprocessable. */
    val isReprocessable: Boolean get() = false

    /**
     * Reprocesses [frame] (a held ring image) into [stillTarget] with a
     * TEMPLATE_STILL_CAPTURE reprocess request via `ImageWriter`.
     * Returns false when this session cannot reprocess — the caller
     * then falls back to direct encode of the same frame.
     */
    fun captureReprocess(
        params: ResolvedStillParams,
        stillTarget: Surface,
        frame: RingHeldFrame,
        callback: StillResultCallback,
    ): Boolean = false

    /**
     * Issues [params] as the TEMPLATE_RECORD repeating burst on [targets]
     * (encoder surface first, preview second) of a constrained high-speed
     * session. Only valid on a session created via
     * [DeviceHandle.createConstrainedHighSpeedCaptureSession]; the default
     * throws rather than issuing a regular repeating request at HFR.
     */
    fun setRepeatingHighSpeed(params: ResolvedHighSpeedParams, targets: List<Surface>) {
        throw IllegalStateException("not a constrained high-speed session")
    }

    fun close()
}

/** Opens cameras; the session owns the returned handles' lifecycle. */
interface Camera2Driver {
    fun openCamera(cameraId: String, callback: DeviceCallback)

    /** Creates the JPEG still output for [size], or null when unavailable. */
    fun createStillOutput(size: FrameSize): StillOutput?

    /**
     * Creates the JPEG_R (Ultra HDR) still output for [size], or null when
     * unavailable (pre-API 34, unsupported size, reader failure). The
     * default is null: a fake/driver without Ultra HDR degrades still
     * capture to the plain JPEG output, never to a failed capture.
     */
    fun createUltraHdrStillOutput(size: FrameSize): StillOutput? = null

    /** Creates the YUV ring output for [size]; null when unavailable. */
    fun createRingOutput(size: FrameSize): RingOutput? = null

    /** Creates the RAW ring output for [size]/[format]; null when unavailable. */
    fun createRawRingOutput(size: FrameSize, format: RingFormat): RawRingOutput? = null
}

/** Converts a pure [SensorRect] into the HAL's `Rect`. */
internal fun SensorRect.toRect(): Rect = Rect(left, top, right, bottom)

internal fun MeteringRegion.toMeteringRectangle(): MeteringRectangle =
    MeteringRectangle(rect.toRect(), weight)

/** Applies resolved preview controls onto a TEMPLATE_PREVIEW builder. */
internal fun CaptureRequest.Builder.applyPreviewParams(
    params: ResolvedRequestParams,
    target: Surface,
) {
    addTarget(target)
    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
    set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
    if (params.zoomRatio != null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            set(CaptureRequest.CONTROL_ZOOM_RATIO, params.zoomRatio)
        } else if (params.cropRegion != null) {
            set(CaptureRequest.SCALER_CROP_REGION, params.cropRegion.toRect())
        }
    } else if (params.cropRegion != null) {
        set(CaptureRequest.SCALER_CROP_REGION, params.cropRegion.toRect())
    }
    set(CaptureRequest.FLASH_MODE, params.flashMode)
    set(
        CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
        params.aeExposureCompensationSteps,
    )
    if (params.afRegions.isNotEmpty()) {
        set(
            CaptureRequest.CONTROL_AF_REGIONS,
            params.afRegions.map { it.toMeteringRectangle() }.toTypedArray(),
        )
        set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
    }
    if (params.aeRegions.isNotEmpty()) {
        set(
            CaptureRequest.CONTROL_AE_REGIONS,
            params.aeRegions.map { it.toMeteringRectangle() }.toTypedArray(),
        )
        set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
    }
}

/** Applies resolved still controls onto a TEMPLATE_STILL_CAPTURE builder. */
internal fun CaptureRequest.Builder.applyStillParams(
    params: ResolvedStillParams,
    target: Surface,
) {
    addTarget(target)
    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
    set(CaptureRequest.CONTROL_AE_MODE, params.aeMode)
    if (params.zoomRatio != null) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            set(CaptureRequest.CONTROL_ZOOM_RATIO, params.zoomRatio)
        } else if (params.cropRegion != null) {
            set(CaptureRequest.SCALER_CROP_REGION, params.cropRegion.toRect())
        }
    } else if (params.cropRegion != null) {
        set(CaptureRequest.SCALER_CROP_REGION, params.cropRegion.toRect())
    }
    set(CaptureRequest.FLASH_MODE, params.flashMode)
    set(
        CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION,
        params.aeExposureCompensationSteps,
    )
    set(CaptureRequest.JPEG_ORIENTATION, params.jpegOrientationDegrees)
    if (params.afRegions.isNotEmpty()) {
        set(
            CaptureRequest.CONTROL_AF_REGIONS,
            params.afRegions.map { it.toMeteringRectangle() }.toTypedArray(),
        )
    }
    if (params.aeRegions.isNotEmpty()) {
        set(
            CaptureRequest.CONTROL_AE_REGIONS,
            params.aeRegions.map { it.toMeteringRectangle() }.toTypedArray(),
        )
    }
}

/** Applies resolved high-speed fields onto a TEMPLATE_RECORD builder. */
internal fun CaptureRequest.Builder.applyHighSpeedParams(
    params: ResolvedHighSpeedParams,
    targets: List<Surface>,
) {
    targets.forEach { addTarget(it) }
    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
    set(CaptureRequest.CONTROL_AF_MODE, params.afMode)
    set(CaptureRequest.CONTROL_AE_MODE, params.aeMode)
    // Exact fixed range: no AE wandering to a lower rate mid-recording.
    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, params.aeTargetFpsRange)
    set(CaptureRequest.FLASH_MODE, params.flashMode)
}

/** Extracts the pure metadata shape from a completed capture result. */
internal fun TotalCaptureResult.toRawStillMetadata(): RawStillMetadata = RawStillMetadata(
    sensorTimestampNs = get(CaptureResult.SENSOR_TIMESTAMP),
    exposureTimeNs = get(CaptureResult.SENSOR_EXPOSURE_TIME),
    iso = get(CaptureResult.SENSOR_SENSITIVITY),
    focalLengthMm = get(CaptureResult.LENS_FOCAL_LENGTH),
    frameNumber = frameNumber,
    aeState = get(CaptureResult.CONTROL_AE_STATE),
    afState = get(CaptureResult.CONTROL_AF_STATE),
    flashState = get(CaptureResult.FLASH_STATE),
    colorCorrectionGains = runCatching {
        get(CaptureResult.COLOR_CORRECTION_GAINS)?.let { gains ->
            listOf(gains.red, gains.greenEven, gains.greenOdd, gains.blue)
        }
    }.getOrNull(),
)

/**
 * Real driver over [CameraManager]. Constructed with the session executor's
 * handler so every HAL callback lands on the camera thread.
 */
class AndroidCamera2Driver(
    private val manager: CameraManager,
    private val handler: Handler,
) : Camera2Driver {

    constructor(context: Context, handler: Handler) : this(
        context.getSystemService(CameraManager::class.java),
        handler,
    )

    override fun createStillOutput(size: FrameSize): StillOutput? =
        runCatching { JpegStillOutput(size.width, size.height, handler = handler) }.getOrNull()

    override fun createUltraHdrStillOutput(size: FrameSize): StillOutput? {
        // JPEG_R exists only from API 34; older releases keep plain JPEG.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null
        return runCatching {
            JpegStillOutput(
                size.width, size.height, handler = handler,
                format = android.graphics.ImageFormat.JPEG_R,
            )
        }.getOrNull()
    }

    override fun createRingOutput(size: FrameSize): RingOutput? =
        runCatching { YuvRingOutput(size.width, size.height, handler = handler) }.getOrNull()

    override fun createRawRingOutput(size: FrameSize, format: RingFormat): RawRingOutput? =
        runCatching {
            RawImageReaderOutput(size.width, size.height, format, handler = handler)
        }.getOrNull()

    @SuppressLint("MissingPermission")
    override fun openCamera(cameraId: String, callback: DeviceCallback) {
        manager.openCamera(
            cameraId,
            object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) =
                    callback.onOpened(AndroidDeviceHandle(camera, handler))

                override fun onClosed(camera: CameraDevice) = callback.onClosed()

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    callback.onDisconnected()
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    callback.onError(error)
                }
            },
            handler,
        )
    }
}

private class AndroidDeviceHandle(
    private val device: CameraDevice,
    private val handler: Handler,
) : DeviceHandle {

    override val cameraId: String get() = device.id

    override fun createCaptureSession(
        surfaces: List<Surface>,
        callback: CaptureSessionCallback,
    ) {
        val stateCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) =
                callback.onConfigured(AndroidCaptureSessionHandle(device, session, handler))

            override fun onConfigureFailed(session: CameraCaptureSession) =
                callback.onConfigureFailed()

            override fun onClosed(session: CameraCaptureSession) = callback.onClosed()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val outputs = surfaces.map { OutputConfiguration(it) }
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                { command -> handler.post(command) },
                stateCallback,
            )
            device.createCaptureSession(config)
        } else {
            @Suppress("DEPRECATION")
            device.createCaptureSession(surfaces, stateCallback, handler)
        }
    }

    override fun createReprocessableCaptureSession(
        input: ReprocessInputConfig,
        surfaces: List<Surface>,
        callback: CaptureSessionCallback,
    ) {
        val stateCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) =
                callback.onConfigured(
                    AndroidCaptureSessionHandle(
                        device, session, handler,
                        inputSurface = runCatching { session.inputSurface }.getOrNull(),
                    ),
                )

            override fun onConfigureFailed(session: CameraCaptureSession) =
                callback.onConfigureFailed()

            override fun onClosed(session: CameraCaptureSession) = callback.onClosed()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val outputs = surfaces.map { OutputConfiguration(it) }
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                { command -> handler.post(command) },
                stateCallback,
            )
            config.inputConfiguration =
                android.hardware.camera2.params.InputConfiguration(
                    input.width, input.height, input.format,
                )
            runCatching { device.createCaptureSession(config) }
                .onFailure { callback.onConfigureFailed() }
        } else {
            @Suppress("DEPRECATION")
            runCatching {
                device.createReprocessableCaptureSession(
                    android.hardware.camera2.params.InputConfiguration(
                        input.width, input.height, input.format,
                    ),
                    surfaces, stateCallback, handler,
                )
            }.onFailure { callback.onConfigureFailed() }
        }
    }

    override fun createConstrainedHighSpeedCaptureSession(
        surfaces: List<Surface>,
        callback: CaptureSessionCallback,
    ) {
        val stateCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(session: CameraCaptureSession) =
                callback.onConfigured(AndroidCaptureSessionHandle(device, session, handler))

            override fun onConfigureFailed(session: CameraCaptureSession) =
                callback.onConfigureFailed()

            override fun onClosed(session: CameraCaptureSession) = callback.onClosed()
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val outputs = surfaces.map { OutputConfiguration(it) }
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_HIGH_SPEED,
                outputs,
                { command -> handler.post(command) },
                stateCallback,
            )
            device.createCaptureSession(config)
        } else {
            @Suppress("DEPRECATION")
            device.createConstrainedHighSpeedCaptureSession(surfaces, stateCallback, handler)
        }
    }

    override fun createExtensionCaptureSession(
        extensionMode: Int,
        surfaces: List<Surface>,
        callback: CaptureSessionCallback,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            callback.onConfigureFailed()
            return
        }
        val stateCallback = object : CameraExtensionSession.StateCallback() {
            override fun onConfigured(session: CameraExtensionSession) =
                callback.onConfigured(AndroidExtensionSessionHandle(device, session, handler))

            override fun onConfigureFailed(session: CameraExtensionSession) =
                callback.onConfigureFailed()

            override fun onClosed(session: CameraExtensionSession) = callback.onClosed()
        }
        val outputs = surfaces.map { OutputConfiguration(it) }
        val config = ExtensionSessionConfiguration(
            extensionMode,
            outputs,
            { command -> handler.post(command) },
            stateCallback,
        )
        runCatching { device.createExtensionSession(config) }
            .onFailure { callback.onConfigureFailed() }
    }

    override fun close() = device.close()
}

/** Real extension-session handle over a `CameraExtensionSession` (API 31+). */
private class AndroidExtensionSessionHandle(
    private val device: CameraDevice,
    private val session: CameraExtensionSession,
    private val handler: Handler,
) : CaptureSessionHandle {

    private val executor = java.util.concurrent.Executor { command -> handler.post(command) }

    override fun setRepeatingPreview(params: ResolvedRequestParams, target: Surface) {
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        builder.applyPreviewParams(params, target)
        session.setRepeatingRequest(builder.build(), executor, NoOpExtensionCallback)
    }

    override fun captureStill(
        params: ResolvedStillParams,
        stillTarget: Surface,
        callback: StillResultCallback,
    ) {
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        builder.applyStillParams(params, stillTarget)
        session.capture(
            builder.build(),
            executor,
            object : CameraExtensionSession.ExtensionCaptureCallback() {
                override fun onCaptureResultAvailable(
                    session: CameraExtensionSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    callback.onCompleted(result.toRawStillMetadata())
                }

                override fun onCaptureFailed(
                    session: CameraExtensionSession,
                    request: CaptureRequest,
                ) {
                    callback.onFailed("extension capture failed")
                }
            },
        )
    }

    override fun close() {
        runCatching { session.close() }
    }

    private object NoOpExtensionCallback : CameraExtensionSession.ExtensionCaptureCallback()
}

private class AndroidCaptureSessionHandle(
    private val device: CameraDevice,
    private val session: CameraCaptureSession,
    private val handler: Handler,
    private val inputSurface: Surface? = null,
) : CaptureSessionHandle {

    private var imageWriter: android.media.ImageWriter? = null

    // Reprocess requests are built from a frame's TotalCaptureResult
    // (createReprocessCaptureRequest), so preview results are retained
    // by sensor timestamp until their ring image is consumed/evicted.
    private val previewResults = LinkedHashMap<Long, TotalCaptureResult>()

    override val isReprocessable: Boolean get() = inputSurface != null

    override fun setRepeatingPreview(params: ResolvedRequestParams, target: Surface) {
        setRepeatingStreaming(params, listOf(target), null)
    }

    override fun setRepeatingStreaming(
        params: ResolvedRequestParams,
        targets: List<Surface>,
        callback: PreviewResultCallback?,
    ) {
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        builder.applyPreviewParams(params, targets.first())
        targets.drop(1).forEach { builder.addTarget(it) }
        val captureCallback = if (callback == null) {
            null
        } else {
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    result.get(CaptureResult.SENSOR_TIMESTAMP)?.let { ts ->
                        previewResults[ts] = result
                        while (previewResults.size > 16) {
                            previewResults.remove(previewResults.keys.first())
                        }
                    }
                    callback.onPreviewResult(result.toRawStillMetadata())
                }
            }
        }
        session.setRepeatingRequest(builder.build(), captureCallback, handler)
    }

    override fun captureReprocess(
        params: ResolvedStillParams,
        stillTarget: Surface,
        frame: RingHeldFrame,
        callback: StillResultCallback,
    ): Boolean {
        val input = inputSurface ?: return false
        val image = frame.nativeImageForReprocess as? android.media.Image ?: return false
        // queueInputImage transfers ownership of the ring image to the
        // writer; the session must not close it afterwards.
        val writer = imageWriter ?: android.media.ImageWriter.newInstance(input, 2).also {
            imageWriter = it
        }
        // No retained result for this frame (evicted before use): the
        // caller falls back to direct encode of the same image.
        val inputResult = previewResults.remove(frame.meta.sensorTimestampNs) ?: return false
        return runCatching {
            val builder = device.createReprocessCaptureRequest(inputResult)
            builder.applyStillParams(params, stillTarget)
            writer.queueInputImage(image)
            session.capture(
                builder.build(),
                object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult,
                    ) {
                        callback.onCompleted(result.toRawStillMetadata())
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: android.hardware.camera2.CaptureFailure,
                    ) {
                        callback.onFailed("reprocess capture failed (reason ${failure.reason})")
                    }
                },
                handler,
            )
            true
        }.getOrDefault(false)
    }

    override fun setRepeatingHighSpeed(params: ResolvedHighSpeedParams, targets: List<Surface>) {
        val highSpeed = session as? CameraConstrainedHighSpeedCaptureSession
            ?: throw IllegalStateException("not a constrained high-speed session")
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        builder.applyHighSpeedParams(params, targets)
        // High-speed sessions only accept requests batched via the
        // high-speed request list, issued as a repeating burst.
        val requests = highSpeed.createHighSpeedRequestList(builder.build())
        highSpeed.setRepeatingBurst(requests, null, handler)
    }

    override fun captureStill(
        params: ResolvedStillParams,
        stillTarget: Surface,
        callback: StillResultCallback,
    ) {
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        builder.applyStillParams(params, stillTarget)
        session.capture(
            builder.build(),
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    callback.onCompleted(result.toRawStillMetadata())
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: android.hardware.camera2.CaptureFailure,
                ) {
                    callback.onFailed("capture failed (reason ${failure.reason})")
                }
            },
            handler,
        )
    }

    override fun close() {
        runCatching { imageWriter?.close() }
        imageWriter = null
        session.close()
    }
}
