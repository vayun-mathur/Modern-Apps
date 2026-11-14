package com.vayunmathur.cast.platform

import android.os.Build
import com.vayunmathur.library.log.Log
import com.vayunmathur.cast.domain.ClientFailure
import com.vayunmathur.cast.network.ControlSocket
import com.vayunmathur.cast.protocol.Bye
import com.vayunmathur.cast.protocol.ByeReason
import com.vayunmathur.cast.protocol.ContentEnded
import com.vayunmathur.cast.protocol.ContentReady
import com.vayunmathur.cast.protocol.ContentSession
import com.vayunmathur.cast.protocol.DecoderLimits
import com.vayunmathur.cast.protocol.DisplayMode
import com.vayunmathur.cast.protocol.Hello
import com.vayunmathur.cast.protocol.Negotiation
import com.vayunmathur.cast.protocol.NowPlaying
import com.vayunmathur.cast.protocol.PROTOCOL_VERSION
import com.vayunmathur.cast.protocol.PairCode
import com.vayunmathur.cast.protocol.PairFailed
import com.vayunmathur.cast.protocol.PairOk
import com.vayunmathur.cast.protocol.PairProof
import com.vayunmathur.cast.protocol.PairRequired
import com.vayunmathur.cast.protocol.Ping
import com.vayunmathur.cast.protocol.PlayMedia
import com.vayunmathur.cast.protocol.PlaybackCommand
import com.vayunmathur.cast.protocol.PlaybackState
import com.vayunmathur.cast.protocol.ProtocolBase64
import com.vayunmathur.cast.protocol.SealedSecret
import com.vayunmathur.cast.protocol.SecretSealing
import com.vayunmathur.cast.protocol.SessionKeys
import com.vayunmathur.cast.protocol.StreamConfig
import com.vayunmathur.cast.protocol.StreamConstants
import com.vayunmathur.cast.protocol.StreamReady
import com.vayunmathur.cast.protocol.Transcript
import com.vayunmathur.cast.protocol.TvIdentity
import com.vayunmathur.cast.protocol.VideoCodec
import com.vayunmathur.cast.protocol.VideoCodecConfig
import java.security.SecureRandom
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "MirrorClient"

/**
 * How long a mid-session `STREAM_CONFIG` waits for its answer.
 *
 * Far shorter than the socket's own read deadline on purpose. This is a resolution change with a
 * user watching a blank screen, so failing fast and saying so beats holding the session open for a
 * minute on the chance that a television is merely slow.
 */
private const val RECONFIGURE_TIMEOUT_MS = 5_000L

/** The UDP port range a TV may name: 0 means "none", and anything past 65535 is not a port. */
private const val MIN_UDP_PORT = 1
private const val MAX_UDP_PORT = 65535

/** Bits per megabit, for turning a decoder bitrate into a log line. */
private const val BITS_PER_MEGABIT = 1_000_000.0

/** Whether the TV took a content session, and why not if it did not. */
sealed interface ContentOutcome {

        /** The TV accepted the content session. */
    data object Accepted : ContentOutcome

    /**
     * [detail] is the TV's own words, so the phone can say something true rather than "failed".
     *
     * The refusal that matters is a TV with no Opus decoder on an audio-only session: before it was
     * negotiated, that TV accepted the session and played silence, which is the failure this whole
     * reply exists to make visible.
     */
    data class Refused(val detail: String) : ContentOutcome
}

/** What a handshake step produced, or why it stopped. */
sealed interface HandshakeOutcome {

    /** Trusted. [deviceKey] is set only when this was a code pairing, and is what to persist. */
    data class Paired(val deviceKey: ByteArray?) : HandshakeOutcome {
        override fun equals(other: Any?): Boolean =
            other is Paired && deviceKey.contentEquals(other.deviceKey)

        override fun hashCode(): Int = deviceKey?.contentHashCode() ?: 0
    }

    /**
     * The TV is showing six digits.
     *
     * [codeChanged] means the previous three attempts were used up and the code on screen is a new
     * one, so the user has to look again rather than retype what they had.
     */
    data class NeedsCode(val attemptsLeft: Int, val codeChanged: Boolean) : HandshakeOutcome

    /** Configured. The media pipeline is built from [negotiation]. */
    data class Ready(val negotiation: Negotiation) : HandshakeOutcome

        /** The handshake failed for the given [reason]. */
    data class Failed(val reason: ClientFailure) : HandshakeOutcome
}

/**
 * The phone's half of the MA Cast handshake: what `CastSession` used to be, minus a control plane that
 * was never ours to define.
 *
 * Driven step by step by [CastController] rather than running itself, for one reason: pairing needs six
 * digits from a human, so the sequence genuinely has to stop and wait for the UI. Everything either
 * side of that is a straight-line exchange.
 *
 * Every method blocks on the socket, so all of them must be called from an IO context. That is a
 * deliberate trade against `CastSession`'s purity: the *rules* worth unit-testing - the key schedule,
 * the proofs, the attempt limit, the route construction - all live in `:cast:protocol` and are tested
 * there. What is left here is sequencing, which is only meaningfully tested against a real socket.
 */
class MirrorClient(
    private val socket: ControlSocket,
    private val senderId: String,
    private val storedDeviceKey: ByteArray?,
    private val senderName: String = deviceName(),
) {

    private val transcript = Transcript()

    private var keys: SessionKeys? = null

    /**
     * A re-negotiation waiting for its `STREAM_READY`, or null when none is.
     *
     * Written from the coroutine calling [reconfigureStream] and read from the thread parked in
     * [awaitEnd], hence `@Volatile`. It is the hand-off between the two: the reader cannot know a
     * reply is wanted, and the requester cannot read it.
     */
    @Volatile
    private var pendingStreamReady: CompletableDeferred<StreamReady>? = null

    /**
     * The transcript, frozen after `SEALED_SECRET`.
     *
     * Frozen at exactly that point because it has to cover the TV's public bundle: that is what makes
     * a pair proof unforgeable by a man in the middle, which substituted a bundle of its own.
     */
    private var transcriptValue: ByteArray? = null

    var receiverName: String? = null
        private set

    var receiverId: String? = null
        private set

    /** The TV's real decoder limits, one envelope per codec, which the frame size is chosen against. */
    var limits: DecoderLimits? = null
        private set

    /**
     * The TV panel's own modes, which a desktop is composed for.
     *
     * Empty from a receiver that enumerated none, in which case the sender keeps using the phone's
     * geometry - see [com.vayunmathur.cast.platform.mirror.MirrorGeometry.forDesktop].
     */
    var displayModes: List<DisplayMode> = emptyList()
        private set

    /**
     * `HELLO` → `TV_IDENTITY` → `SEALED_SECRET` → `PAIR_REQUIRED`, then the device proof if we hold a
     * key the TV accepts.
     *
     * Returns [HandshakeOutcome.NeedsCode] when the user has to type something, and
     * [HandshakeOutcome.Paired] when a remembered device key was enough.
     */
    fun begin(): HandshakeOutcome {
        val hello = socket.send(
            Hello(
                senderName = senderName,
                senderId = senderId,
                paired = storedDeviceKey != null,
            ),
        ) ?: return HandshakeOutcome.Failed(ClientFailure.Unreachable)
        transcript.add(hello)

        val identityFrame = socket.receive()
            ?: return HandshakeOutcome.Failed(ClientFailure.Unreachable)
        val identity = identityFrame.message as? TvIdentity
            ?: return HandshakeOutcome.Failed(ClientFailure.Protocol)
        if (identity.version != PROTOCOL_VERSION) {
            Log.status(TAG, "the TV speaks version ${identity.version}; we speak $PROTOCOL_VERSION")
            return HandshakeOutcome.Failed(ClientFailure.VersionMismatch)
        }
        rememberIdentity(identityFrame.body, identity)

        return exchangeSecret(identity)
    }

    private fun rememberIdentity(body: ByteArray, identity: TvIdentity) {
        transcript.add(body)
        receiverName = identity.receiverName
        receiverId = identity.receiverId
        limits = identity.limits
        displayModes = identity.displayModes
        logReceiverCapabilities(identity)
    }

    private fun logReceiverCapabilities(identity: TvIdentity) {
        if (identity.displayModes.isEmpty()) {
            Log.status(TAG, "'${identity.receiverName}' reported no panel modes; a desktop will use phone geometry")
        } else {
            Log.status(
                TAG,
                "'${identity.receiverName}' panel offers " +
                    identity.displayModes.joinToString { "${it.width}x${it.height}" },
            )
        }
        if (identity.limits.videoCodecs.isEmpty()) {
            // Not a failure here - CastController names the codecs it checked - but the log is where
            // it would be diagnosed, and "the TV advertised nothing" is invisible otherwise.
            Log.status(TAG, "'${identity.receiverName}' advertised no hardware video decoder at all")
        }
        for (codec in identity.limits.videoCodecs) {
            Log.status(
                TAG,
                "'${identity.receiverName}' decodes ${codec.codec.label} up to " +
                    "${codec.maxWidth}x${codec.maxHeight} @ ${codec.maxFrameRate}fps, " +
                    "${codec.maxBitRate / BITS_PER_MEGABIT} Mbit/s",
            )
        }
    }

    private fun exchangeSecret(identity: TvIdentity): HandshakeOutcome {
        val bundle = ProtocolBase64.decode(identity.publicBundle)
            ?: return HandshakeOutcome.Failed(ClientFailure.Protocol)
        val secret = SessionKeys.newSecret()
        val sealed = runCatching { SecretSealing.seal(bundle, secret) }.getOrNull()
            ?: return HandshakeOutcome.Failed(ClientFailure.Protocol)
        val sealedFrame = socket.send(SealedSecret(sealed = ProtocolBase64.encode(sealed)))
            ?: return HandshakeOutcome.Failed(ClientFailure.Unreachable)
        transcript.add(sealedFrame)

        val sessionKeys = SessionKeys.of(secret)
        keys = sessionKeys
        // From here on the control channel is AES-256-GCM. Both ends install the cipher at exactly this
        // point, which is what keeps them in step without a per-frame flag an attacker could clear.
        socket.codec.useSessionKey(sessionKeys.control)
        val frozen = transcript.value()
        transcriptValue = frozen

        val required = socket.receive()?.message as? PairRequired
            ?: return HandshakeOutcome.Failed(ClientFailure.Unreachable)
        return answerPairRequired(required, sessionKeys, frozen)
    }

    private fun answerPairRequired(
        required: PairRequired,
        sessionKeys: SessionKeys,
        frozen: ByteArray,
    ): HandshakeOutcome {
        if (required.code) {
            // The TV does not know us - either we have never paired, or it was reset since. Either way
            // the key we may be holding is worthless and the user has to read the screen.
            return HandshakeOutcome.NeedsCode(required.attemptsLeft, codeChanged = false)
        }
        val deviceKey = storedDeviceKey ?: return protocolFailure()
        return proof(sessionKeys.deviceProof(deviceKey, frozen))
    }

    /**
     * Prove the six digits the user read off the TV.
     *
     * A wrong code is a routine outcome rather than an error: the session stays open and
     * [HandshakeOutcome.NeedsCode] comes back with one fewer attempt, so the user simply types it
     * again.
     */
    fun enterCode(code: String): HandshakeOutcome {
        if (!PairCode.isWellFormed(code)) {
            // Refused here rather than on the wire: a typo must not spend one of three attempts.
            return HandshakeOutcome.NeedsCode(attemptsLeft = -1, codeChanged = false)
        }
        val sessionKeys = keys ?: return protocolFailure()
        val frozen = transcriptValue ?: return protocolFailure()
        return proof(sessionKeys.pairProof(code, frozen))
    }

    private fun proof(bytes: ByteArray): HandshakeOutcome {
        socket.send(PairProof(proof = ProtocolBase64.encode(bytes)))
            ?: return HandshakeOutcome.Failed(ClientFailure.Unreachable)
        val reply = socket.receive() ?: return HandshakeOutcome.Failed(ClientFailure.Unreachable)
        return when (val message = reply.message) {
            is PairOk -> {
                Log.status(TAG, "paired with '$receiverName'")
                HandshakeOutcome.Paired(message.deviceKey?.let { ProtocolBase64.decode(it) })
            }
            is PairFailed -> HandshakeOutcome.NeedsCode(
                attemptsLeft = message.attemptsLeft,
                codeChanged = message.codeChanged,
            )
            // The answer to a *device* proof the TV would not accept: it has forgotten us - a reinstall
            // or a reset - and has put a code on screen instead. Not a failure; the user just has to
            // read it.
            is PairRequired -> {
                Log.status(TAG, "'$receiverName' no longer remembers this phone; a code is on screen")
                HandshakeOutcome.NeedsCode(message.attemptsLeft, codeChanged = false)
            }
            else -> HandshakeOutcome.Failed(ClientFailure.Protocol)
        }
    }

    /**
     * `STREAM_CONFIG` → `STREAM_READY`.
     *
     * [width] and [height] are the phone's own choice within the TV's reported limits: it sends its
     * native aspect ratio and the TV letterboxes, which is the whole reason for owning the receiver.
     *
     * [appLabel] is empty for screen mirroring and the streaming app's name for an SDK session; the TV
     * shows it instead of the phone's name.
     */
    fun configureStream(
        width: Int,
        height: Int,
        frameRate: Float,
        bitRate: Int,
        videoCodec: VideoCodec,
        audio: Boolean,
        video: Boolean,
        appLabel: String = "",
    ): HandshakeOutcome {
        val sessionKeys = keys ?: return protocolFailure()
        val random = SecureRandom()
        val config = StreamConfig(
            width = width,
            height = height,
            frameRate = frameRate,
            bitRate = bitRate,
            audio = audio,
            video = video,
            audioSsrc = random.ssrc(StreamConstants.AUDIO_SSRC_MIN, StreamConstants.AUDIO_SSRC_MAX),
            videoSsrc = random.ssrc(StreamConstants.VIDEO_SSRC_MIN, StreamConstants.VIDEO_SSRC_MAX),
            videoCodec = videoCodec,
            appLabel = appLabel,
        )
        // Null means the channel went away between choosing a codec and configuring it, which is
        // exactly what a teardown racing a fresh session does. Refused rather than thrown: the caller
        // has a failure branch for this and had nothing to catch an exception with.
        socket.send(config) ?: return HandshakeOutcome.Failed(ClientFailure.StreamRefused)
        val ready = socket.receive()?.message as? StreamReady
            ?: return HandshakeOutcome.Failed(ClientFailure.StreamRefused)
        if (ready.udpPort !in MIN_UDP_PORT..MAX_UDP_PORT) {
            Log.status(TAG, "the TV named udp port ${ready.udpPort}")
            return HandshakeOutcome.Failed(ClientFailure.StreamRefused)
        }
        Log.status(TAG, "streaming ${videoCodec.label} ${width}x$height to udp ${ready.udpPort}")
        return HandshakeOutcome.Ready(Negotiation.of(config, ready, sessionKeys))
    }

    /**
     * `STREAM_CONFIG` → `STREAM_READY` **while the session is already running**.
     *
     * Identical on the wire to [configureStream] and deliberately not folded into it, because the
     * two differ in who reads the reply. Before a session starts, this object owns the socket and
     * [configureStream] reads `STREAM_READY` itself. Once it is running, [awaitEnd] is the socket's
     * single reader - a blocking read that a coroutine cancellation cannot interrupt - so a second
     * `configureStream` would have its reply swallowed by the watcher's `else -> {}` and then block
     * until its own read deadline. That is exactly what a resolution change looked like: the old
     * stream stopped, the new one never started, and the television sat on its last frame.
     *
     * So the reply is *handed over* instead: [pendingStreamReady] is armed before the request goes
     * out, and [awaitEnd] completes it on the way past.
     */
    suspend fun reconfigureStream(
        width: Int,
        height: Int,
        frameRate: Float,
        bitRate: Int,
        videoCodec: VideoCodec,
        audio: Boolean,
        video: Boolean,
    ): HandshakeOutcome {
        val sessionKeys = keys ?: return protocolFailure()
        val random = SecureRandom()
        val config = StreamConfig(
            width = width,
            height = height,
            frameRate = frameRate,
            bitRate = bitRate,
            audio = audio,
            video = video,
            audioSsrc = random.ssrc(StreamConstants.AUDIO_SSRC_MIN, StreamConstants.AUDIO_SSRC_MAX),
            videoSsrc = random.ssrc(StreamConstants.VIDEO_SSRC_MIN, StreamConstants.VIDEO_SSRC_MAX),
            videoCodec = videoCodec,
        )
        val waiter = CompletableDeferred<StreamReady>()
        // Armed before the send, or a television that answers immediately would arrive at an
        // unarmed watcher and be dropped by the very `else` branch this exists to route around.
        pendingStreamReady = waiter
        val sent = socket.send(config)
        if (sent == null) {
            pendingStreamReady = null
            return HandshakeOutcome.Failed(ClientFailure.StreamRefused)
        }
        val ready = withTimeoutOrNull(RECONFIGURE_TIMEOUT_MS) { waiter.await() }
        pendingStreamReady = null
        if (ready == null) {
            Log.status(TAG, "'$receiverName' did not answer a mid-session STREAM_CONFIG")
            return HandshakeOutcome.Failed(ClientFailure.StreamRefused)
        }
        if (ready.udpPort !in MIN_UDP_PORT..MAX_UDP_PORT) {
            Log.status(TAG, "the TV named udp port ${ready.udpPort}")
            return HandshakeOutcome.Failed(ClientFailure.StreamRefused)
        }
        Log.status(TAG, "restreaming ${videoCodec.label} ${width}x$height to udp ${ready.udpPort}")
        return HandshakeOutcome.Ready(Negotiation.of(config, ready, sessionKeys))
    }

    /**
     * `CONTENT_SESSION` → `CONTENT_READY`, for app content that has a file behind it.
     *
     * The replacement for [configureStream] on that path, and there is no `STREAM_READY` and no UDP
     * port because there is no RTP: the TV fetches byte ranges from the proxy instead. Nothing here
     * negotiates a codec, because nothing is being encoded - the TV decodes whatever the file already
     * is, which is the entire point.
     *
     * [certificateFingerprint] travels on this channel precisely because it is already encrypted and
     * bound to the pairing transcript. That is what lets the TV pin one certificate instead of
     * trusting an authority to vouch for an address on a LAN.
     */
    fun openContentSession(
        host: String,
        port: Int,
        certificateFingerprint: ByteArray,
        token: String,
        video: Boolean,
        appLabel: String,
    ): ContentOutcome {
        val offered = socket.send(
            ContentSession(
                host = host,
                port = port,
                certificateFingerprint = ProtocolBase64.encode(certificateFingerprint),
                token = token,
                video = video,
                appLabel = appLabel,
            ),
        )
        if (offered == null) return ContentOutcome.Refused("the control channel had already closed")
        val ready = socket.receive()?.message as? ContentReady
            ?: return ContentOutcome.Refused("the TV did not answer")
        if (!ready.accepted) {
            Log.status(TAG, "the TV refused a content session: ${ready.detail}")
            return ContentOutcome.Refused(ready.detail)
        }
        Log.status(TAG, "serving ${if (video) "audio and video" else "audio"} from $host:$port")
        return ContentOutcome.Accepted
    }

    /**
     * Tell the TV what to play next from the proxy.
     *
     * Sent once per item rather than as a playlist, because the queue stays on the phone: it owns the
     * ordering, the artwork and the metadata, so "next" is a decision this end makes and then reports.
     */
    fun playMedia(media: PlayMedia) {
        Log.status(TAG, "asking the TV to play ${media.resourceId}")
        runCatching { socket.send(media) }
            .onFailure { Log.status(TAG, "could not send the play request", it) }
    }

    /**
     * Tell the TV what the item it was asked to play actually is.
     *
     * Separate from [playMedia] because it is state rather than a command: the cover and the lyrics can
     * arrive after the audio has already started, and folding them into the play request would mean
     * restarting the track to change them. Non-throwing for [sendPlaybackState]'s reasons, and logged
     * without its payload - a lyric sheet in the log would bury everything else.
     */
    fun sendNowPlaying(nowPlaying: NowPlaying) {
        Log.status(
            TAG,
            "describing ${nowPlaying.resourceId} to the TV: '${nowPlaying.title}'" +
                if (nowPlaying.artworkResourceId.isEmpty()) ", no cover" else ", with a cover",
        )
        runCatching { socket.send(nowPlaying) }
            .onFailure { Log.status(TAG, "could not send the now-playing metadata", it) }
    }

    /**
     * Hand the TV the video codec configuration, for a codec that cannot carry it in-band.
     *
     * Sent on the already-open control channel rather than in a media packet, because the receiver
     * needs it *before* it configures its decoder - there is no frame yet to attach it to. Called
     * repeatedly and idempotent: every key-frame request re-sends it, which is what gives it a repair
     * path at all.
     */
    fun sendCodecConfig(csd: ByteArray) {
        Log.status(TAG, "sending codec config: ${csd.size} bytes")
        runCatching { socket.send(VideoCodecConfig(csd = ProtocolBase64.encode(csd))) }
            .onFailure { Log.status(TAG, "could not send the codec config", it) }
    }

    /**
     * Put a playback snapshot on the control channel, for the TV's seek bar.
     *
     * Follows [sendCodecConfig] in being non-throwing, and for the same kind of reason: this is called
     * from a poll loop, so a failure would recur every tick and there is nothing useful to do about
     * one. A socket that has actually died is [awaitEnd]'s business and it will say so.
     *
     * Not logged per send. Two of these a second would bury everything else in the log, which is the
     * opposite of what the logging in this class is for.
     */
    fun sendPlaybackState(state: PlaybackState) {
        runCatching { socket.send(state) }
            .onFailure { Log.status(TAG, "could not send the playback state", it) }
    }

    /**
     * Ask the TV to do something, in a served session.
     *
     * The reverse of the direction this message runs for screen mirroring, and the honest one for a
     * file: the television has the player, so this is what a pause pressed anywhere on the phone turns
     * into. Non-throwing for [sendPlaybackState]'s reasons - a dead socket is [awaitEnd]'s to report -
     * but logged, because unlike a snapshot each of these is a user action rather than a tick.
     */
    fun sendPlaybackCommand(command: PlaybackCommand) {
        Log.status(TAG, "asking the TV for ${command.action}${command.value?.let { " $it" }.orEmpty()}")
        runCatching { socket.send(command) }
            .onFailure { Log.status(TAG, "could not send ${command.action}", it) }
    }

    /**
     * Tell the TV the content session is over, and that the connection is not.
     *
     * **Nothing else tells it.** The TV owns its own clock and buffer, so a session that simply stops
     * being served carries on playing whatever it had already fetched and then fails its next request
     * against a proxy that has gone - an error card where the idle screen belongs. Which is also why
     * this must be sent *before* the proxy stops.
     *
     * Deliberately not [sayGoodbye]: that ends the connection, and with it the paired-and-connected
     * state the user just used. Closing one cast should leave the TV ready for the next.
     */
    fun sendContentEnded() {
        Log.status(TAG, "telling '$receiverName' the content session is over")
        runCatching { socket.send(ContentEnded) }
            .onFailure { Log.status(TAG, "could not end the content session", it) }
    }

    /**
     * Put a keep-alive on the control channel, so the TV's read deadline does not expire.
     *
     * Non-throwing and unlogged for exactly [sendPlaybackState]'s reasons - it is a timer loop, and
     * a socket that has really died is [awaitEnd]'s to report. The reply is absorbed by
     * [awaitEnd]'s `else` branch, which is where it wants to be handled: the point of the echo is
     * that a read happened at all, not what was in it.
     */
    fun sendPing() {
        runCatching { socket.send(Ping) }
            .onFailure { Log.status(TAG, "could not send a keep-alive", it) }
    }

    /**
     * Wait for the TV to say goodbye, or for the socket to die, acting on anything else it sends.
     *
     * Held open for the whole session so that a TV going away is noticed at once, rather than only
     * when UDP starts failing - a control channel nobody reads is a control channel that cannot report
     * anything.
     *
     * Returns the `BYE` reason, or null when the socket died without one. The reason is carried back
     * rather than only logged because one of them - [ByeReason.MISSING_CODEC_CONFIG] - is a decision
     * the caller has to act on, not a note for a human.
     *
     * **A dispatch loop rather than a drain.** This used to discard everything that was not a `Bye`,
     * which was right while the TV had nothing else to say. It has a transport now, and both halves of
     * it arrive on this same socket - and cannot arrive anywhere else, because there is one socket with
     * one read position and this is already the only reader.
     *
     * [onCommand] is null for screen mirroring, which has no transport to control. [onState] is null
     * for anything that is not a served session, where the TV has no player to describe. Either
     * message turning up anyway is logged and dropped rather than acted on.
     */
    fun awaitEnd(
        onCommand: ((PlaybackCommand) -> Unit)? = null,
        onState: ((PlaybackState) -> Unit)? = null,
    ): String? {
        while (true) {
            val next = socket.receive() ?: return null
            when (val message = next.message) {
                is Bye -> {
                    Log.status(TAG, "'$receiverName' said goodbye: ${message.reason.ifBlank { "no reason" }}")
                    return message.reason
                }
                is PlaybackCommand -> {
                    val argument = message.value?.let { " $it" }.orEmpty()
                    if (onCommand == null) {
                        Log.status(TAG, "'$receiverName' asked for ${message.action}$argument, " +
                            "but there is no transport to control")
                    } else {
                        Log.status(TAG, "'$receiverName' asked for ${message.action}$argument")
                        onCommand(message)
                    }
                }
                // The television's own player, in a served session. Not logged: this arrives twice a
                // second, and a line per snapshot would bury everything else about the session.
                is PlaybackState -> onState?.invoke(message)
                // The echo of our own keep-alive. Nothing to do with it: having read it is the
                // whole effect, because that is what pushed this socket's read deadline out.
                is Ping -> {}
                // Handed to whoever is re-negotiating rather than dropped. This branch is the
                // whole reason `reconfigureStream` can exist: this loop owns the socket for the
                // life of the session, so a reply read here is a reply nobody else can read.
                is StreamReady -> {
                    val waiter = pendingStreamReady
                    if (waiter == null) {
                        Log.status(TAG, "'$receiverName' sent STREAM_READY with nothing waiting for it")
                    } else {
                        pendingStreamReady = null
                        waiter.complete(message)
                    }
                }
                else -> {}
            }
        }
    }

    /**
     * Say goodbye.
     *
     * Sent rather than just closing the socket, so the TV returns to its idle screen at once instead of
     * holding the last frame until a read times out.
     */
    fun sayGoodbye(reason: String) {
        runCatching { socket.send(Bye(reason = reason)) }
    }

    private fun protocolFailure() = HandshakeOutcome.Failed(ClientFailure.Protocol)

    private fun SecureRandom.ssrc(min: Int, max: Int): Long = (min + nextInt(max - min + 1)).toLong()

        /** Device naming used when presenting this phone to the TV. */
    companion object {
        /** What the TV shows while it waits for a code, and what its logs name us as. */
        fun deviceName(): String =
            listOf(Build.MODEL, Build.DEVICE).firstOrNull { !it.isNullOrBlank() } ?: "Phone"
    }
}
