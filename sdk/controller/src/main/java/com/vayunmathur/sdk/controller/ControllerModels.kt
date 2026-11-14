package com.vayunmathur.sdk.controller

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Generic controller input, from any phone or gamepad, for any TV game.
 *
 * [buttons] is a bitmask of [ControllerButton] bits; [axes] holds the normalized sticks in slot
 * order ([AXIS_LEFT_X], [AXIS_LEFT_Y], [AXIS_RIGHT_X], [AXIS_RIGHT_Y], [AXIS_THROTTLE],
 * [AXIS_BRAKE]); [hatX]/[hatY] is the D-pad as -1/0/1. [touchX]/[touchY] carry an optional second
 * pointer (a look stick on phones that have one), normalized -1..1, or null when unused.
 *
 * [seq] is per-player and strictly increasing; the host keeps a replay window and ignores anything
 * at or below the high-water mark. [clientTimestampMs] is the phone's `uptimeMillis` at sample
 * time, so the host can measure skew and jitter without trusting the clock.
 */
@Serializable
data class ControllerInput(
    val playerId: String,
    val seq: Long,
    val buttons: Long = 0L,
    val axes: List<Float> = List(AXIS_COUNT) { 0f },
    val hatX: Int = 0,
    val hatY: Int = 0,
    val touchX: Float? = null,
    val touchY: Float? = null,
    val clientTimestampMs: Long = 0L,
) {
    /** True when [button] is held in this sample. */
    fun isPressed(button: ControllerButton): Boolean = buttons and button.bit != 0L
}

/**
 * Button bit positions in [ControllerInput.buttons]. A `Long` so games can add their own.
 *
 * Declaration order is the wire format: each button owns bit `1L shl ordinal`.
 */
enum class ControllerButton {
    A, B, X, Y,
    LEFT_SHOULDER, RIGHT_SHOULDER, LEFT_TRIGGER, RIGHT_TRIGGER,
    START, SELECT, LEFT_STICK, RIGHT_STICK,
    DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT;

    /** This button's bit in [ControllerInput.buttons]. */
    val bit: Long get() = 1L shl ordinal
}

/** Axis slots in [ControllerInput.axes], in fixed order. */
const val AXIS_LEFT_X = 0
const val AXIS_LEFT_Y = 1
const val AXIS_RIGHT_X = 2
const val AXIS_RIGHT_Y = 3
const val AXIS_THROTTLE = 4
const val AXIS_BRAKE = 5
const val AXIS_COUNT = 6

/**
 * Rumble the host asks a phone to play: [durationMs] at [lowFreq]/[highFreq] amplitude 0..1.
 *
 * Phones render it with the vibrator; gamepads pass it to `VibrationEffect`. Fire and forget -
 * there is no acknowledgement, because a missed buzz is not worth a round trip.
 */
@Serializable
data class RumbleFeedback(
    val durationMs: Int,
    val lowFreq: Float = 0f,
    val highFreq: Float = 1f,
)

/** One seat at the table, as the lobby shows it. */
@Serializable
data class PlayerSlot(
    val playerId: String,
    val displayName: String,
    /** True for a phone over the network; false for a gamepad plugged into the TV. */
    val remote: Boolean,
    val connected: Boolean = true,
    val lastSeq: Long = 0L,
)

/** The whole lobby, broadcast whenever it changes. Replaces wholesale; no deltas. */
@Serializable
data class LobbyState(
    val slots: List<PlayerSlot> = emptyList(),
    /** The game this lobby is for, e.g. "kart" - informational, not a protocol branch. */
    val gameId: String = "",
    /** True once the countdown has started and joins are locked. */
    val racing: Boolean = false,
)

/** Who is calling, and whether it expects to be remembered. */
@Serializable
@SerialName("HELLO")
data class ControllerHello(
    val version: Int = ControllerContract.CONTROLLER_PROTOCOL_VERSION,
    val senderName: String,
    /**
     * A stable id for this phone, so the TV can look up a remembered device key before it knows
     * anything else. Not a secret: it only decides whether a code will be asked for.
     */
    val senderId: String,
    /**
     * True when the phone holds a device key for this TV.
     *
     * The TV still decides - it may have forgotten the phone, or been factory reset - but this is
     * what lets it avoid putting a pair code on screen for a phone that does not need one.
     */
    val paired: Boolean = false,
) : ControllerMessage

/** What the TV is, and the key to seal a secret to. */
@Serializable
@SerialName("HOST_HELLO")
data class ControllerHostHello(
    val version: Int = ControllerContract.CONTROLLER_PROTOCOL_VERSION,
    val hostName: String,
    val hostId: String,
    /** Base64 ML-KEM + ML-DSA public bundle, from `PqcIdentity.publicBundle`. */
    val publicBundle: String,
    val gameId: String = "",
) : ControllerMessage

/** The session secret, sealed to [ControllerHostHello.publicBundle]. Base64 of the sealed bytes. */
@Serializable
@SerialName("SEALED_SECRET")
data class ControllerSealedSecret(val sealed: String) : ControllerMessage

/**
 * The TV's answer to `SEALED_SECRET`, and always exactly one message so the sequence has no branch
 * the phone has to guess at.
 *
 * [code] false means the TV remembers this phone and wants only a proof of the device key it holds;
 * true means it does not, and six digits are on screen.
 */
@Serializable
@SerialName("PAIR_REQUIRED")
data class ControllerPairRequired(
    val code: Boolean,
) : ControllerMessage

/**
 * Either the code proof or the remembered-device proof - the TV knows which it asked for, so one
 * message carries both cases. Base64 of the HMAC bytes.
 */
@Serializable
@SerialName("PAIR_PROOF")
data class ControllerPairProof(val proof: String) : ControllerMessage

/**
 * Paired.
 *
 * [deviceKey] is a fresh random key, present only when this was a code pairing; both ends persist it
 * and later sessions authenticate with it instead of a code. Absent when the phone had already
 * proved possession of one. Base64.
 */
@Serializable
@SerialName("PAIR_OK")
data class ControllerPairOk(val deviceKey: String? = null) : ControllerMessage

/** Wrong proof. [attemptsLeft] is how many tries remain against the code currently on screen. */
@Serializable
@SerialName("PAIR_FAILED")
data class ControllerPairFailed(val attemptsLeft: Int) : ControllerMessage

/** A phone announcing which seat it wants, after pairing. */
@Serializable
@SerialName("JOIN")
data class ControllerJoin(val displayName: String) : ControllerMessage

/** A phone leaving, or the host removing it. */
@Serializable
@SerialName("LEAVE")
data class ControllerLeave(val reason: String = "") : ControllerMessage

/** One input sample, phone to TV, at [ControllerContract.INPUT_HZ]. */
@Serializable
@SerialName("INPUT")
data class ControllerInputFrame(val input: ControllerInput) : ControllerMessage

/** The current lobby, TV to every phone, whenever it changes. */
@Serializable
@SerialName("LOBBY")
data class ControllerLobby(val lobby: LobbyState) : ControllerMessage

/** Rumble, TV to one phone. */
@Serializable
@SerialName("RUMBLE")
data class ControllerRumble(val rumble: RumbleFeedback) : ControllerMessage

/**
 * Nothing to say, said out loud.
 *
 * A read timeout is the only liveness check either end has, and it cannot tell a quiet lobby from
 * a dead one. Sent every 20 s while connected, and echoed straight back. Carries no payload:
 * anything worth knowing is already in [ControllerLobby].
 */
@Serializable
@SerialName("PING")
data object ControllerPing : ControllerMessage

/**
 * Either end, at any point after the secret is established.
 *
 * Sent rather than just closing the socket, so the lobby can free the seat immediately instead of
 * holding it while it waits for a read to time out.
 */
@Serializable
@SerialName("BYE")
data class ControllerBye(val reason: String = "") : ControllerMessage

/** The control-channel message, discriminated by `type`. */
@Serializable
sealed interface ControllerMessage

/**
 * Strict on the way in, complete on the way out.
 *
 * `ignoreUnknownKeys` because a field added by a newer build must not kill a session outright, and
 * `encodeDefaults` because the discriminator and the version are defaulted constants that the peer
 * genuinely needs. `explicitNulls` off so an absent `deviceKey` stays absent.
 */
val ControllerJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    classDiscriminator = "type"
}
