package com.vayunmathur.camera.platform

import android.graphics.ColorMatrix
import androidx.annotation.StringRes
import android.media.MediaRecorder
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.vayunmathur.camera.R
import kotlin.math.roundToInt

/** Capture modes selectable in the camera UI. */
enum class CameraMode { PHOTO, PORTRAIT, PANORAMA, PHOTOSPHERE, VIDEO, SLOW_MO, TIMELAPSE, CINEMATIC }
/** Flash behaviour for still capture. */
enum class FlashMode { ON, OFF, AUTO }

/** Shutter-timer durations (seconds). */
private const val TIMER_ONE_SECOND = 1
private const val TIMER_THREE_SECONDS = 3
private const val TIMER_FIVE_SECONDS = 5
private const val TIMER_TEN_SECONDS = 10
/** Shutter-timer delay options. */
enum class TimerDuration(val seconds: Int) {
    NONE(0),
    ONE(TIMER_ONE_SECOND),
    THREE(TIMER_THREE_SECONDS),
    FIVE(TIMER_FIVE_SECONDS),
    TEN(TIMER_TEN_SECONDS)
}
/** Viewfinder and capture aspect-ratio options. */
enum class AspectRatioOption(val label: String) {
    RATIO_16_9("16:9"),
    RATIO_4_3("4:3"),
    RATIO_3_2("3:2"),
    RATIO_1_1("1:1")
}
/** Video codecs selectable for recording, with display text. */
enum class VideoCodec(@StringRes val labelRes: Int, @StringRes val descriptionRes: Int) {
    AVC(R.string.codec_avc_label, R.string.codec_avc_description),
    HEVC(R.string.codec_hevc_label, R.string.codec_hevc_description),
    AV1(R.string.codec_av1_label, R.string.codec_av1_description),
}

/** Still photo formats selectable in settings. AVIF is encoded natively (ravif). */
enum class PhotoFormat(@StringRes val labelRes: Int, @StringRes val descriptionRes: Int) {
    JPEG(R.string.photo_format_jpeg_label, R.string.photo_format_jpeg_description),
    AVIF(R.string.photo_format_avif_label, R.string.photo_format_avif_description),
}

/** Audio codecs selectable for video recording, with display text and Recorder mime. */
enum class AudioCodec(
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
    val mimeType: String,
) {
    AAC(
        R.string.audio_codec_aac_label,
        R.string.audio_codec_aac_description,
        android.media.MediaFormat.MIMETYPE_AUDIO_AAC,
    ),
    OPUS(
        R.string.audio_codec_opus_label,
        R.string.audio_codec_opus_description,
        android.media.MediaFormat.MIMETYPE_AUDIO_OPUS,
    ),
}
/** Motion-Photo clip durations (seconds). */
private const val MOTION_ONE_SECOND = 1
private const val MOTION_THREE_SECONDS = 3
private const val MOTION_FIVE_SECONDS = 5

/** Motion-Photo clip length (issue #712): how far back the ring buffer reaches. */
enum class MotionPhotoDuration(val seconds: Int) {
    ONE(MOTION_ONE_SECOND),
    THREE(MOTION_THREE_SECONDS),
    FIVE(MOTION_FIVE_SECONDS),
}
/** Audio input sources selectable for video recording. */
enum class AudioInputSource(
    @StringRes val labelRes: Int,
    @StringRes val descriptionRes: Int,
    val specValue: Int,
) {
    CAMCORDER(
        R.string.audio_source_camcorder_label,
        R.string.audio_source_camcorder_description,
        MediaRecorder.AudioSource.CAMCORDER
    ),
    MIC(R.string.audio_source_mic_label, R.string.audio_source_mic_description, MediaRecorder.AudioSource.MIC),
    VOICE_COMMUNICATION(
        R.string.audio_source_voice_communication_label,
        R.string.audio_source_voice_communication_description,
        MediaRecorder.AudioSource.VOICE_COMMUNICATION
    ),
    UNPROCESSED(
        R.string.audio_source_unprocessed_label,
        R.string.audio_source_unprocessed_description,
        MediaRecorder.AudioSource.UNPROCESSED
    ),
}

/**
 * The bind-time recording settings snapshot: video/audio codec, audio source and
 * front mirroring are baked into the Recorder/VideoCapture when the session binds
 * and cannot change mid-recording. The user's chosen (desired) values live in the
 * individual CameraViewModel flows and persist immediately; this snapshot is what
 * a bind actually uses, held unchanged while a recording is in progress and
 * released to the desired values when it stops (see CameraViewModel.bindTimeSettings).
 */
data class BindTimeSettings(
    val videoCodec: VideoCodec,
    val audioCodec: AudioCodec,
    val audioInputSource: AudioInputSource,
    val mirrorFront: Boolean,
)

/** Zoom-label tuning: sub-1x threshold, decimal scaling, near-integer snap tolerance. */
private const val ZOOM_SUB_UNIT_MAX = 1f
private const val ZOOM_DECIMAL_SCALE = 10f
private const val ZOOM_INTEGER_SNAP_TOLERANCE = 0.05f

/** Formats a zoom ratio for the zoom bar: ".5", "1x", or "1.5x". */
fun formatZoomLabel(ratio: Float): String = when {
    ratio < ZOOM_SUB_UNIT_MAX -> ".${(ratio * ZOOM_DECIMAL_SCALE).roundToInt()}"
    else -> {
        val rounded = (ratio * ZOOM_DECIMAL_SCALE).roundToInt() / ZOOM_DECIMAL_SCALE
        if (kotlin.math.abs(rounded - rounded.roundToInt()) < ZOOM_INTEGER_SNAP_TOLERANCE) {
            "${rounded.roundToInt()}x"
        } else {
            "%.1fx".format(rounded)
        }
    }
}

/** One selectable exposure-time stop for manual capture. */
data class ExposureTimeStop(val label: String, val nanos: Long?)

/**
 * Builds the warmth/shadows color matrix shared by the live preview and the
 * saved capture, so a photo looks the same as what the viewfinder showed.
 */
/** Warmth/shadows color-matrix tuning (red/blue channel tilt, shadow lift). */
private const val COLOR_WARMTH_RED_GAIN = 0.15f
private const val COLOR_WARMTH_GREEN_GAIN = 0.05f
private const val COLOR_SHADOW_LIFT = 40f

fun buildColorAdjustmentMatrix(warmth: Float, shadows: Float): ColorMatrix = ColorMatrix(
    floatArrayOf(
        1f + warmth * COLOR_WARMTH_RED_GAIN, 0f, 0f, 0f, shadows * COLOR_SHADOW_LIFT,
        0f, 1f + warmth * COLOR_WARMTH_GREEN_GAIN, 0f, 0f, shadows * COLOR_SHADOW_LIFT,
        0f, 0f, 1f - warmth * COLOR_WARMTH_RED_GAIN, 0f, shadows * COLOR_SHADOW_LIFT,
        0f, 0f, 0f, 1f, 0f,
    )
)

internal class ManualLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    fun start() {
        registry.currentState = Lifecycle.State.RESUMED
    }

    fun destroy() {
        registry.currentState = Lifecycle.State.DESTROYED
    }
}

/** One [CameraViewModel.zslCompatible] input sample: wanted value plus whether a shot is in flight. */
internal class ZslGate(val compatible: Boolean, val capturing: Boolean)

/** One [CameraViewModel.bindTimeSettings] input sample: desired settings plus recording state. */
internal class BindSettingsGate(val settings: BindTimeSettings, val recording: Boolean)
