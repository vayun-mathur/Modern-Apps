package com.vayunmathur.camera.wear.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Phone <-> watch protocol for the Wear OS Camera companion.
 *
 * SINGLE SOURCE OF TRUTH, DELIBERATELY DUPLICATED: Gradle cannot let one
 * application module depend on another, so the wear module (`:camera-wear`,
 * applicationId `com.vayunmathur.camera`) carries a copy of this file under
 * `com.vayunmathur.camera.wear.domain`. Change both copies together; each
 * side's unit tests pin the same golden JSON/frame bytes so a one-sided
 * edit fails loudly.
 *
 * Transport (WearLink, :library:wearlink — RFCOMM, no Play services):
 * - Presence is a completed handshake for [CAPABILITY] (the appId).
 * - Data items: [STATE_PATH] phone -> watch (mirrored camera state) and
 *   [THUMBNAIL_PATH] phone -> watch (last-capture JPEG bytes; the media
 *   itself stays on the phone).
 * - Messages: [OP_PATH] watch -> phone (one control op), and
 *   [PREVIEW_FRAME_PATH] phone -> watch (one live viewfinder frame).
 *   Delivery needs a live session; nothing wakes a dead phone process.
 *
 * Live viewfinder transport choice — stated, not implied: WearLink's
 * STREAM primitive is a file transfer (the header commits total size +
 * SHA-256 up front, the receiver verifies the whole stream at close, and
 * STREAM_ACK resume replays a durable file offset). A live preview is an
 * unbounded sequence of ephemeral frames whose total size is unknowable
 * and whose stale frames are worthless, so STREAM cannot carry it without
 * breaking WearLink's contract. The extension therefore lives behind this
 * camera protocol seam, leaving :library:wearlink untouched: each frame
 * is one fire-and-forget MESSAGE (JPEG well under the 256 KiB message
 * cap), the phone keeps only the latest frame, and the watch keeps only
 * the newest sequence. Frames are never queued, resumed or verified.
 */
object WearCameraProtocol {
    /** WearLink appId the phone requires in the peer handshake. */
    const val CAPABILITY = "camera_wear_companion"

    /** Data-item path (phone -> watch): the [WearCameraState] JSON. */
    const val STATE_PATH = "/camera/state"

    /** Data-item path (phone -> watch): last-capture thumbnail JPEG bytes. */
    const val THUMBNAIL_PATH = "/camera/thumbnail"

    /** Message path (watch -> phone): one [WearCameraOp] JSON. */
    const val OP_PATH = "/camera/op"

    /** Message path (phone -> watch): one binary preview frame (see [PreviewFrame]). */
    const val PREVIEW_FRAME_PATH = "/camera/preview-frame"

    /** Widest preview/thumbnail frame the watch is ever sent, in pixels. */
    const val PREVIEW_MAX_WIDTH = 320

    /** Nominal preview rate; the phone adapts inside [MIN_FPS]..[MAX_FPS]. */
    const val PREVIEW_NOMINAL_FPS = 5
    const val PREVIEW_MIN_FPS = 2
    const val PREVIEW_MAX_FPS = 8

    /** JPEG quality bounds for the adaptive preview/thumbnail encoder. */
    const val PREVIEW_JPEG_QUALITY = 60
    const val PREVIEW_MIN_JPEG_QUALITY = 40
    const val PREVIEW_MAX_JPEG_QUALITY = 75

    /** Encoded preview frames larger than this lower the next quality step. */
    const val PREVIEW_LARGE_FRAME_BYTES = 24_000

    /**
     * Modes the watch may switch to remotely. PANORAMA/PHOTOSPHERE are
     * excluded: both need an on-phone guided sweep. SLOW_MO is included —
     * the phone's mode switch itself enforces its back-lens rule.
     */
    val REMOTE_MODES = listOf("PHOTO", "VIDEO", "PORTRAIT", "TIMELAPSE", "CINEMATIC", "SLOW_MO")

    /** Modes whose shutter action records video rather than a still. */
    val VIDEO_MODES = setOf("VIDEO", "TIMELAPSE", "CINEMATIC", "SLOW_MO")

    /** Shutter-timer options (seconds) the watch may set; 0 is off. */
    val TIMER_OPTIONS_SECONDS = listOf(0, 1, 3, 5, 10)

    /** Wire JSON: unknown keys ignored so a newer side never breaks an older one. */
    val json: Json = Json { ignoreUnknownKeys = true }
}

/** Mirrored phone-camera state, published at [WearCameraProtocol.STATE_PATH]. */
@Serializable
data class WearCameraState(
    /** Phone mode name (CameraMode.name), including non-remote modes. */
    val mode: String = "PHOTO",
    /** Modes the watch may switch to (see [WearCameraProtocol.REMOTE_MODES]). */
    val remoteModes: List<String> = WearCameraProtocol.REMOTE_MODES,
    val frontLens: Boolean = false,
    val zoomRatio: Float = 1f,
    /** Selectable zoom preset ratios, ascending (the phone's zoom-bar levels). */
    val zoomPresets: List<Float> = listOf(1f),
    val zoomMin: Float = 1f,
    val zoomMax: Float = 1f,
    /** FlashMode.name: ON, OFF or AUTO. */
    val flash: String = "OFF",
    val hasFlashUnit: Boolean = true,
    val torch: Boolean = false,
    val timerSeconds: Int = 0,
    val timerCountdown: Int = 0,
    val recording: Boolean = false,
    val recordingSeconds: Long = 0L,
    val capturing: Boolean = false,
    /** True while the phone is streaming preview frames to the watch. */
    val previewStreaming: Boolean = false,
    /** Epoch millis of the last landed capture, 0 when none this session. */
    val lastCaptureAtMillis: Long = 0L,
    /** "PHOTO" or "VIDEO" for the last capture, empty when none. */
    val lastCaptureKind: String = "",
)

/** Kind of a watch-originated camera control. */
@Serializable
enum class WearOpKind {
    SHUTTER,
    RECORD_TOGGLE,
    SET_MODE,
    SET_ZOOM,
    ZOOM_PRESET,
    SET_FLASH,
    SET_TORCH,
    FLIP_LENS,
    SET_TIMER,
    PREVIEW_START,
    PREVIEW_STOP,
}

/** One watch-originated control, as [WearCameraProtocol.OP_PATH]. Unused fields keep defaults. */
@Serializable
data class WearCameraOp(
    val kind: WearOpKind,
    val mode: String = "",
    val zoomRatio: Float = 1f,
    val zoomPresetIndex: Int = -1,
    val flash: String = "",
    val torch: Boolean = false,
    val timerSeconds: Int = 0,
)

/** Whether a planned op may run against the mirrored state, and why not when it may not. */
sealed interface WearOpDecision {
    data object Apply : WearOpDecision
    data class Reject(val reason: String) : WearOpDecision
}

/**
 * Pure op gate, shared by the phone (authoritative, against live state)
 * and testable without a camera: mode/lens/zoom changes rebind the phone
 * session, so they are refused while recording (the phone's own mode/lens
 * actions gate the same way); unknown modes/flash values and out-of-set
 * timers are refused rather than guessed at. The phone re-checks against
 * its live ViewModel after planning, so a stale watch state never forces
 * an unsafe op — the mirrored state corrects the watch on the next publish.
 */
fun planWearCameraOp(op: WearCameraOp, state: WearCameraState): WearOpDecision = when (op.kind) {
    WearOpKind.SHUTTER, WearOpKind.RECORD_TOGGLE, WearOpKind.PREVIEW_START, WearOpKind.PREVIEW_STOP ->
        WearOpDecision.Apply
    WearOpKind.SET_TORCH -> WearOpDecision.Apply
    WearOpKind.SET_MODE -> when {
        op.mode !in WearCameraProtocol.REMOTE_MODES -> WearOpDecision.Reject("mode not remote-switchable")
        state.recording && op.mode != state.mode -> WearOpDecision.Reject("recording in progress")
        else -> WearOpDecision.Apply
    }
    WearOpKind.FLIP_LENS -> when {
        state.recording -> WearOpDecision.Reject("recording in progress")
        state.mode == "SLOW_MO" -> WearOpDecision.Reject("slo-mo is back-lens only")
        else -> WearOpDecision.Apply
    }
    WearOpKind.SET_ZOOM, WearOpKind.ZOOM_PRESET ->
        if (state.mode == "PANORAMA" || state.mode == "PHOTOSPHERE") {
            WearOpDecision.Reject("zoom locked in this mode")
        } else {
            WearOpDecision.Apply
        }
    WearOpKind.SET_FLASH ->
        if (op.flash in setOf("ON", "OFF", "AUTO")) WearOpDecision.Apply
        else WearOpDecision.Reject("unknown flash mode")
    WearOpKind.SET_TIMER ->
        if (op.timerSeconds in WearCameraProtocol.TIMER_OPTIONS_SECONDS) WearOpDecision.Apply
        else WearOpDecision.Reject("unsupported timer")
}

/** Clamps a requested zoom ratio into the mirrored range; non-finite requests become the current ratio. */
fun clampWearZoom(requested: Float, state: WearCameraState): Float =
    if (!requested.isFinite()) state.zoomRatio
    else requested.coerceIn(state.zoomMin, state.zoomMax)

/** The preset ratio for [index], or null when the index is stale (presets changed under it). */
fun wearZoomPreset(state: WearCameraState, index: Int): Float? = state.zoomPresets.getOrNull(index)

/**
 * One live viewfinder frame on [WearCameraProtocol.PREVIEW_FRAME_PATH]:
 * a fixed 20-byte header (`WCF1`, sequence, timestamp, size, rotation)
 * followed by the JPEG bytes. The sequence lets the watch drop stale
 * frames; nothing is ever retransmitted.
 */
object PreviewFrame {
    const val HEADER_BYTES = 20
    private val MAGIC = byteArrayOf('W'.code.toByte(), 'C'.code.toByte(), 'F'.code.toByte(), '1'.code.toByte())

    fun encode(sequence: Long, timestampMillis: Long, width: Int, height: Int, rotationDegrees: Int, jpeg: ByteArray): ByteArray {
        val out = ByteArray(HEADER_BYTES + jpeg.size)
        MAGIC.copyInto(out)
        writeInt(out, 4, (sequence and 0xFFFF_FFFFL).toInt())
        writeInt(out, 8, (timestampMillis and 0xFFFF_FFFFL).toInt())
        writeShort(out, 12, width)
        writeShort(out, 14, height)
        writeShort(out, 16, rotationDegrees)
        writeShort(out, 18, 0)
        jpeg.copyInto(out, HEADER_BYTES)
        return out
    }

    /** Decoded header plus the JPEG slice; null on any malformed input. */
    fun decode(body: ByteArray): Decoded? {
        if (body.size <= HEADER_BYTES) return null
        if (!MAGIC.indices.all { body[it] == MAGIC[it] }) return null
        val width = readShort(body, 12)
        val height = readShort(body, 14)
        if (width <= 0 || height <= 0) return null
        return Decoded(
            sequence = readInt(body, 4).toLong() and 0xFFFF_FFFFL,
            timestampMillis = readInt(body, 8).toLong() and 0xFFFF_FFFFL,
            width = width,
            height = height,
            rotationDegrees = readShort(body, 16),
            jpeg = body.copyOfRange(HEADER_BYTES, body.size),
        )
    }

    data class Decoded(
        val sequence: Long,
        val timestampMillis: Long,
        val width: Int,
        val height: Int,
        val rotationDegrees: Int,
        val jpeg: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Decoded) return false
            return sequence == other.sequence && timestampMillis == other.timestampMillis &&
                width == other.width && height == other.height &&
                rotationDegrees == other.rotationDegrees && jpeg.contentEquals(other.jpeg)
        }

        override fun hashCode(): Int {
            var r = sequence.hashCode()
            r = 31 * r + timestampMillis.hashCode()
            r = 31 * r + width
            r = 31 * r + height
            r = 31 * r + rotationDegrees
            r = 31 * r + jpeg.contentHashCode()
            return r
        }
    }

    private fun writeInt(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value ushr 24).toByte()
        out[offset + 1] = (value ushr 16).toByte()
        out[offset + 2] = (value ushr 8).toByte()
        out[offset + 3] = value.toByte()
    }

    private fun readInt(body: ByteArray, offset: Int): Int =
        ((body[offset].toInt() and 0xFF) shl 24) or ((body[offset + 1].toInt() and 0xFF) shl 16) or
            ((body[offset + 2].toInt() and 0xFF) shl 8) or (body[offset + 3].toInt() and 0xFF)

    private fun writeShort(out: ByteArray, offset: Int, value: Int) {
        out[offset] = (value ushr 8).toByte()
        out[offset + 1] = value.toByte()
    }

    private fun readShort(body: ByteArray, offset: Int): Int =
        ((body[offset].toInt() and 0xFF) shl 8) or (body[offset + 1].toInt() and 0xFF)
}

/** Serialises [state] for the state data item (the golden-pinned wire form). */
fun encodeWearCameraState(state: WearCameraState): ByteArray =
    WearCameraProtocol.json.encodeToString(state).encodeToByteArray()

/** Parses a state data item; null when malformed (the watch keeps its last state). */
fun decodeWearCameraState(body: ByteArray): WearCameraState? = runCatching {
    WearCameraProtocol.json.decodeFromString<WearCameraState>(body.decodeToString())
}.getOrNull()

/** Serialises [op] for the op message (the golden-pinned wire form). */
fun encodeWearCameraOp(op: WearCameraOp): ByteArray =
    WearCameraProtocol.json.encodeToString(op).encodeToByteArray()

/** Parses an op message; null when malformed (the phone ignores it). */
fun decodeWearCameraOp(body: ByteArray): WearCameraOp? = runCatching {
    WearCameraProtocol.json.decodeFromString<WearCameraOp>(body.decodeToString())
}.getOrNull()
