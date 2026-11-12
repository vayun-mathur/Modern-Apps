package com.vayunmathur.camera.util

import android.graphics.Bitmap
import android.net.Uri
import androidx.annotation.OptIn
import com.vayunmathur.library.log.Log
import androidx.camera.core.ExperimentalZeroShutterLag
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlin.math.roundToInt

/** Shutter-timer countdown tick (1s). */
internal const val TIMER_TICK_MS = 1000L

fun CameraViewModel.takePhoto() {
    // Second tap cancels an armed timer, mirroring toggleRecording().
    if (timerCountdownMutable.value > 0) {
        cancelTimerCountdown()
        return
    }
    if (isCapturingMutable.value || burstActiveMutable.value) return
    val timer = timerDurationMutable.value
    if (timer.seconds > 0) {
        timerCountdownJob = viewModelScope.launch {
            for (i in timer.seconds downTo 1) {
                timerCountdownMutable.value = i
                kotlinx.coroutines.delay(TIMER_TICK_MS)
            }
            timerCountdownMutable.value = 0
            timerCountdownJob = null
            capturePhoto()
        }
    } else {
        capturePhoto()
    }
}

internal fun CameraViewModel.capturePhoto() {
    if (imageCapture == null || isCapturingMutable.value || burstActiveMutable.value) return
    // Claim in-flight synchronously so a second shutter tap (or a stacked timer firing)
    // can't start a concurrent capture. Every path clears it once its frame is captured
    // (Motion Photo) or saved (the other paths) — never later.
    isCapturingMutable.value = true
    when (routeStillCapture()) {
        StillRoute.NIGHT -> captureNightPhoto()
        StillRoute.MOTION_PHOTO -> captureMotionPhoto()
        StillRoute.SINGLE -> captureSinglePhoto()
    }
}

/** Which capture path a shutter press takes; chosen by [routeStillCapture]. */
internal enum class StillRoute { SINGLE, NIGHT, MOTION_PHOTO }

/**
 * Picks the capture path for a shutter press. Choosing [StillRoute.MOTION_PHOTO] reserves a
 * [CameraViewModel.motionSaveQueue] slot, which the Motion Photo path releases once its save
 * lands; with every slot taken by earlier shots still encoding, the press takes a plain still
 * instead of waiting (#914).
 */
internal fun CameraViewModel.routeStillCapture(): StillRoute = when {
    // Night Sight mode (or auto-engaged night): the preview is bound with the vendor NIGHT
    // extension, so a plain single capture through that ImageCapture lets the vendor pipeline
    // produce the multi-frame night image.
    nightPreviewActiveMutable.value -> StillRoute.SINGLE
    nightCaptureApplies() -> StillRoute.NIGHT
    !motionPhotoEligible() -> StillRoute.SINGLE
    motionSaveQueue.tryReserve() -> StillRoute.MOTION_PHOTO
    else -> {
        Log.status(
            "CameraViewModel",
            "${motionSaveQueue.pending} Motion Photos still saving; taking a plain still instead"
        )
        StillRoute.SINGLE
    }
}

/**
 * Whether a shot takes the night path (multi-frame burst, or the single-frame long-exposure
 * override): night mode active, exposure fully auto and flash off. The night paths lock AE off
 * (CONTROL_AE_MODE_OFF) with a forced long exposure, which breaks flash metering/timing and
 * double-exposes (issue #721); with flash on the shot stays an instant flash photo with no
 * long-exposure countdown overlay. Also requires a non-ZSL ImageCapture: a ZSL capture returns
 * a frame buffered before the AE lock, so a night shot fired in the moment before the session
 * rebinds without ZSL (see [CameraViewModel.zslCompatible]) takes a plain photo instead (#914).
 */
internal fun CameraViewModel.nightCaptureApplies(): Boolean {
    val nightAuto = nightModeActive.value && isExposureAuto()
    return nightAuto && flashModeMutable.value == FlashMode.OFF && !stillCaptureIsZsl()
}

/** True when the bound ImageCapture was built for zero-shutter-lag capture. */
@OptIn(ExperimentalZeroShutterLag::class)
internal fun CameraViewModel.stillCaptureIsZsl(): Boolean =
    imageCapture?.captureMode == ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG

/**
 * Motion Photo for plain PHOTO captures (no warmth/shadows bake, not capturing for a caller).
 * Only at the native 4:3 ratio: the motion still is saved as raw JPEG bytes (to preserve the
 * Ultra HDR gain map + motion trailer), which can't carry CameraX's crop. For other ratios the
 * single-shot path saves a cropped JPEG.
 */
private fun CameraViewModel.motionPhotoEligible(): Boolean {
    val plainPhoto = cameraModeMutable.value == CameraMode.PHOTO && !captureForResult
    val noBake = warmthMutable.value == 0f && shadowsMutable.value == 0f
    return plainPhoto && noBake && aspectRatioMutable.value == AspectRatioOption.RATIO_4_3
}

/**
 * Starts a press-and-hold burst: standard single-frame captures fired back-to-back (one in
 * flight at a time) with `IMG_<ts>_BURSTn` names, until [stopBurst] or [CameraViewModel.BURST_MAX]
 * is reached. Uses the plain capture path (no night/manual special-casing).
 */
fun CameraViewModel.startBurst() {
    if (burstActiveMutable.value || isCapturingMutable.value) return
    // A burst is immediate: an armed shutter timer no longer applies.
    cancelTimerCountdown()
    val capture = imageCapture ?: return
    // Mark both in-flight: a single capture must not start mid-burst and a burst must not
    // start mid-capture. Releasing the shutter always goes through finishBurst().
    isCapturingMutable.value = true
    burstActiveMutable.value = true
    burstCountMutable.value = 0
    val ts = MediaStoreSaver.timestamp()

    fun finishBurst() {
        burstActiveMutable.value = false
        isCapturingMutable.value = false
    }

    fun shootNext(n: Int) {
        if (!burstActiveMutable.value || n > CameraViewModel.BURST_MAX) {
            finishBurst()
            return
        }
        // prepareStillSave falls back to MediaStore, so null is unexpected — but a null
        // here must terminate, never retry (the old `?: run { shootNext(n + 1) }` looped
        // forever when the save target was unusable).
        val pending = prepareStillSave("IMG_${ts}_BURST${n}.jpg") ?: run {
            finishBurst()
            return
        }
        val outputOptions = pending.outputOptions

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(app),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    burstCountMutable.value = n
                    pending.closeStream()
                    pending.resolveUri(outputFileResults)?.let { setLastCaptureUri(it) }
                    shootNext(n + 1)
                }
                override fun onError(exception: ImageCaptureException) {
                    Log.error("CameraViewModel", "Burst frame $n failed", exception)
                    pending.closeStream()
                    shootNext(n + 1)
                }
            }
        )
    }
    shootNext(1)
}

fun CameraViewModel.stopBurst() {
    // Release path for the press-and-hold gesture: only clears the loop flag. The in-flight
    // frame's save callback finishes the sequence (finishBurst) so a release between frames
    // still lands the shot already exposing.
    burstActiveMutable.value = false
}

/** Appends an analysis frame to the Motion-Photo ring buffer, trimming by age then count. */
fun CameraViewModel.addMotionFrame(bitmap: Bitmap, timestampNanos: Long, rotationDegrees: Int) {
    synchronized(motionLock) {
        motionFrames.addLast(CameraViewModel.MotionFrame(bitmap, timestampNanos, rotationDegrees))
        val windowNanos = motionPhotoDurationMutable.value.seconds * 1_000_000_000L
        val cutoff = timestampNanos - windowNanos
        while (motionFrames.size > 1 && motionFrames.first().timestampNanos < cutoff) {
            motionFrames.removeFirst().bitmap.recycle()
        }
        val maxFrames = (motionPhotoDurationMutable.value.seconds *
            CameraViewModel.MOTION_FRAMES_PER_SECOND)
            .coerceAtMost(CameraViewModel.MOTION_MAX_FRAMES_HARD_CAP)
        while (motionFrames.size > maxFrames) {
            motionFrames.removeFirst().bitmap.recycle()
        }
    }
}

internal fun CameraViewModel.drainMotionFrames(): List<CameraViewModel.MotionFrame> = synchronized(motionLock) {
    val list = motionFrames.toList()
    motionFrames.clear()
    list
}

internal fun CameraViewModel.clearMotionFrames() = synchronized(motionLock) {
    motionFrames.forEach { it.bitmap.recycle() }
    motionFrames.clear()
}

/**
 * A captured Motion Photo still waiting for its clip: the JPEG bytes, the ring-buffer frames
 * drained at capture time (owned by the request and recycled once the save finishes), and the
 * capture-time orientation, mirroring and file name, so a camera flip or a later shot while
 * this one waits in the queue can't change how it is saved.
 */
internal class MotionSaveRequest(
    val jpegBytes: ByteArray,
    val frames: List<CameraViewModel.MotionFrame>,
    val rotationDegrees: Int,
    val mirrored: Boolean,
    val displayName: String
)

/**
 * Captures a Motion Photo: the still (captured in-memory so its bytes can carry the trailer) plus
 * the buffered ring-buffer frames encoded to a short MP4 and appended as a Google Motion Photo
 * trailer. Falls back to saving the plain still if no frames are buffered or encoding fails.
 * The still may be Ultra HDR (gain map preserved); the appended clip is SDR at analysis resolution.
 *
 * The caller ([routeStillCapture]) has already reserved a [CameraViewModel.motionSaveQueue] slot.
 * The shutter is released as soon as the still is in memory; the clip is built and saved in the
 * background by [onMotionStillCaptured] (#914).
 */
internal fun CameraViewModel.captureMotionPhoto() {
    val capture = imageCapture ?: run {
        motionSaveQueue.release()
        isCapturingMutable.value = false
        return
    }
    isCapturingMutable.value = true
    // Name, mirroring and orientation are fixed at the shutter press, not when the save runs.
    val displayName = "IMG_${MediaStoreSaver.timestamp()}.jpg"
    val mirrored = mirrorCaptures
    capture.takePicture(
        ContextCompat.getMainExecutor(app),
        object : ImageCapture.OnImageCapturedCallback() {
            private var feedbackSent = false

            override fun onCaptureStarted() {
                feedbackSent = true
                emitShutterFeedback()
            }

            override fun onCaptureSuccess(image: ImageProxy) {
                if (!feedbackSent) emitShutterFeedback()
                val degrees = image.imageInfo.rotationDegrees
                val jpegBytes = try {
                    image.planes[0].buffer.let { buf ->
                        ByteArray(buf.remaining()).also { buf.get(it) }
                    }
                } finally {
                    image.close()
                }
                onMotionStillCaptured(
                    MotionSaveRequest(jpegBytes, drainMotionFrames(), degrees, mirrored, displayName)
                )
            }

            override fun onError(exception: ImageCaptureException) {
                Log.error("CameraViewModel", "Motion Photo capture failed; falling back to still", exception)
                motionSaveQueue.release()
                isCapturingMutable.value = false
                captureSinglePhoto()
            }
        }
    )
}

/**
 * The Motion Photo still is in memory: release the shutter now and finish the clip in the
 * background. Saves run on [CameraViewModel.motionSaveDispatcher] one at a time, in shot order
 * (the mutex is fair), inside [CameraViewModel.motionSaveScope], which outlives the screen so
 * a shot taken just before leaving still lands. The thumbnail updates when each save lands.
 */
internal fun CameraViewModel.onMotionStillCaptured(request: MotionSaveRequest): Job {
    isCapturingMutable.value = false
    return motionSaveScope.launch(motionSaveDispatcher) {
        val uri = try {
            motionSaveMutex.withLock { motionPhotoSaver(request) }
        } finally {
            request.frames.forEach { it.bitmap.recycle() }
            motionSaveQueue.release()
        }
        if (uri != null) setLastCaptureUri(uri)
    }
}

/** Signals the UI that the sensor started (or finished) exposing a still. */
internal fun CameraViewModel.emitShutterFeedback() {
    shutterFeedbackMutable.tryEmit(Unit)
}

/** Builds the clip for [request], appends it to the still and saves it. Blocking: call off main. */
internal fun CameraViewModel.assembleAndSaveMotionPhoto(request: MotionSaveRequest): Uri? {
    val frames = request.frames
    // In-memory captures carry no ImageCapture.Metadata, so stamp GPS/orientation into the
    // still bytes here (issue #731). Must happen BEFORE the MP4 trailer is appended —
    // ExifInterface only understands a pure JPEG.
    val stillBytes = stampStillBytes(request.jpegBytes, request.rotationDegrees, request.mirrored)
    // Only frames matching the newest frame's dimensions are encoded (a rebind can change size).
    val sized = frames.takeIf { it.isNotEmpty() }?.let { list ->
        val w = list.last().bitmap.width
        val h = list.last().bitmap.height
        list.filter { it.bitmap.width == w && it.bitmap.height == h }
    }.orEmpty()

    val bytes = if (sized.size >= 2) {
        val tmp = java.io.File(app.cacheDir, "motion_${System.currentTimeMillis()}.mp4")
        val spanNs = (sized.last().timestampNanos - sized.first().timestampNanos).coerceAtLeast(1)
        val fps = (sized.size * 1_000_000_000.0 / spanNs).roundToInt().coerceIn(5, 30)
        val ok = MotionPhotoEncoder.encode(
            sized.map { it.bitmap }, tmp, sized.last().rotationDegrees, fps
        )
        if (ok && tmp.exists()) {
            val mp4 = tmp.readBytes()
            tmp.delete()
            MotionPhotoWriter.assemble(stillBytes, mp4)
        } else {
            tmp.delete()
            stillBytes
        }
    } else {
        stillBytes
    }
    return saveStillBytes(request.displayName, bytes)
}

/**
 * Routes the night shutter: prefer CameraX Extensions NIGHT (vendor multi-frame processing)
 * when it's usable on this device, otherwise fall back to the custom burst + Rust merge. Uses
 * nightExtensionUsable (the gated/failure-cached value) so a device where the extension can't
 * bind (GrapheneOS/Pixel) goes straight to the working custom path instead of failing.
 */
internal fun CameraViewModel.captureNightPhoto() {
    viewModelScope.launch {
        if (nightExtensionUsableMutable.value) {
            captureNightPhotoExtension()
        } else {
            captureNightPhotoCustom()
        }
    }
}
