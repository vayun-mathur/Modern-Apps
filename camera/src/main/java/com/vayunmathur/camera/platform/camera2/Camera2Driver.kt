package com.vayunmathur.camera.platform.camera2

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
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
    fun close()
}

/** Testable handle over a `CameraCaptureSession`. */
interface CaptureSessionHandle {
    /** Issues [params] as the TEMPLATE_PREVIEW repeating request on [target]. */
    fun setRepeatingPreview(params: ResolvedRequestParams, target: Surface)
    fun close()
}

/** Opens cameras; the session owns the returned handles' lifecycle. */
interface Camera2Driver {
    fun openCamera(cameraId: String, callback: DeviceCallback)
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
                callback.onConfigured(AndroidCaptureSessionHandle(device, session))

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

    override fun close() = device.close()
}

private class AndroidCaptureSessionHandle(
    private val device: CameraDevice,
    private val session: CameraCaptureSession,
) : CaptureSessionHandle {

    override fun setRepeatingPreview(params: ResolvedRequestParams, target: Surface) {
        val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        builder.applyPreviewParams(params, target)
        session.setRepeatingRequest(builder.build(), null, null)
    }

    override fun close() = session.close()
}
