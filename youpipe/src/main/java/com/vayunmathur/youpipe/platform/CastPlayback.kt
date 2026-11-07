package com.vayunmathur.youpipe.platform

import android.content.Context
import android.media.AudioManager
import android.os.ParcelFileDescriptor
import com.vayunmathur.library.log.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.core.content.getSystemService
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import com.vayunmathur.sdk.cast.CastClient
import com.vayunmathur.sdk.cast.CastContract
import com.vayunmathur.sdk.cast.CastException
import com.vayunmathur.sdk.cast.PlaybackAction
import com.vayunmathur.sdk.cast.PlaybackCommand
import com.vayunmathur.sdk.cast.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

private const val TAG = "CastPlayback"

/**
 * YouPipe's cast session: where the video goes, and where the audio goes.
 *
 * An object because the two halves live in different places and both have to reach it. The `Surface`
 * is consumed by `VideoPlayer`, which hands it to the `MediaController`; the PCM is produced inside
 * [PlaybackService]'s audio sink, which has no reference to any of the UI. They are in the same process
 * - `PlaybackService` is a `MediaSessionService` in this app - so a singleton is enough and no second
 * IPC hop is needed.
 *
 * **The reason a URL is not handed over instead.** YouPipe's stream URLs are usually synthetic
 * (`sabr://<videoId>?v=<itag>`), resolvable only in-process by `SabrNgDashMediaSource` with a PO token
 * minted through a WebView, and the receiver is deliberately on a LAN with no internet. So what crosses
 * to the TV is pixels and PCM, which is also why ExoPlayer keeps decoding and keeps its transport
 * controls: only the render target changes.
 */
object CastPlayback {

    /** Where the cast is. */
    sealed interface State {

        /** Playing locally. */
        data object Idle : State

        /** The picker is up, or the session is being negotiated with the TV. */
        data object Connecting : State

        /**
         * On the TV. [surface] is what the player must render into, and it belongs to Cast - it must
         * not be released here, which is what would break the *next* cast rather than this one.
         *
         * [width], [height] and [frameRate] are what Cast **granted**, not what was asked for. They
         * are carried rather than dropped so that a clamp shows up in the log next to the request
         * that provoked it: a session silently downscaled is indistinguishable from one that was
         * never asked for the resolution in the first place, which is how the 1080p cap survived as
         * long as it did.
         */
        data class Casting(
            val surface: Surface,
            val receiverName: String,
            val width: Int,
            val height: Int,
            val frameRate: Int,
        ) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /**
     * Where playback is, and everything a remote needs to know about it.
     *
     * **Hoisted out of `VideoPlayer` because a television outlives a composable.** All of this used to
     * be `remember`ed locals, which was correct while the only thing that read them was the UI drawn
     * beside them - the seek bar and the transport buttons are two feet from the player. A TV asking
     * "what is playing, and how far in" is a second reader with a different lifetime, and a
     * `remember` dies on navigation while the cast session does not.
     *
     * The 300 ms poll loop in `VideoPlayer` remains the **single writer** of [positionMs] and
     * [bufferedMs]; the player listener writes the rest. Nothing else may write them, which is what
     * makes [dragging] a sufficient guard.
     */
    data class Transport(
        val positionMs: Long = 0,
        val bufferedMs: Long = 0,
        val durationMs: Long = 0,
        val playing: Boolean = false,
        val buffering: Boolean = false,
        /**
         * The user has the phone's own seek bar under their thumb.
         *
         * Freezes the poll loop's position write so the bar does not fight the finger, and - now that
         * a television can seek too - refuses a remote seek for the same reason. Two seeks resolving
         * against each other would leave the position wherever the loser landed.
         */
        val dragging: Boolean = false,
        val speed: Float = 1f,
        val volume: Float = 1f,
        val hasNext: Boolean = false,
        val hasPrevious: Boolean = false,
    )

    private val _transport = MutableStateFlow(Transport())
    val transport: StateFlow<Transport> = _transport.asStateFlow()

    /** Read-modify-write, so the poll loop and the player listener cannot drop each other's change. */
    fun update(block: (Transport) -> Transport) {
        _transport.update(block)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var reportJob: Job? = null

    /** Counting down to closing a session nothing is drawing into. See [attachPlayer]. */
    private var orphanJob: Job? = null

    /**
     * The player a remote command is applied to.
     *
     * Held here rather than passed with each command because the commands arrive from Cast, which has
     * no idea what a `MediaController` is. Cleared when the player screen goes away, so a command that
     * outlives it is dropped rather than driving a released controller.
     */
    private var player: Player? = null

    /**
     * The currently attached player, for the TV remote map.
     *
     * Read-only: attach/detach stay the only writers, with their identity guard intact.
     * The phone never reads this — only `tv/TvDpadInput` drives the local controller.
     */
    val attachedPlayer: Player? get() = player

    /**
     * The phone's own media volume, which is the level both ends share.
     *
     * Held from [open]'s context rather than asked for per call: the reporting loop reads it twice a
     * second, and a `getSystemService` per tick would be work for nothing.
     */
    private var audio: AudioManager? = null

    /**
     * Called by the player screen once its `MediaController` has connected, and with null as it goes.
     *
     * **Detach is identity-guarded, and has to be.** A navigation composes the incoming screen before
     * disposing the outgoing one, so if the new screen's controller connects first an unguarded
     * `attachPlayer(null)` would clear the player that had just arrived *and* start the orphan timer -
     * ending the cast in exactly the "next from the television" case this whole mechanism exists for.
     */
    fun attachPlayer(newPlayer: Player?) {
        if (newPlayer == null) {
            detachPlayer(null)
            return
        }
        player = newPlayer
        orphanJob?.cancel()
        orphanJob = null
    }

    /**
     * Let go of [outgoing], if it is still the attached player.
     *
     * Passing the player being disposed is what makes the guard possible; null means "whatever is
     * attached", which only the session teardown wants.
     */
    fun detachPlayer(outgoing: Player?) {
        if (outgoing != null && player !== outgoing) return
        player = null
        if (_state.value !is State.Casting) return
        // **The grace period is what lets a cast survive navigation.** Leaving a video used to end the
        // session outright, on the reasoning that there is one video output and it cannot follow the
        // user to another screen. True as far as it went - but it also meant a "next" from the
        // television dropped the cast, which is the one thing next is for. What is actually wanted is
        // to distinguish "the user has gone somewhere else" from "one player is being replaced by
        // another", and the only honest difference between them is whether something draws into the
        // surface again shortly. So the session is held briefly rather than closed, and a replacement
        // player cancels the timer.
        orphanJob?.cancel()
        orphanJob = scope.launch {
            delay(ORPHAN_GRACE_MS)
            Log.status(TAG, "nothing drew into the cast for ${ORPHAN_GRACE_MS}ms; ending it")
            close()
        }
    }

    /**
     * How the player screen answers a `Next` or `Previous` from the television.
     *
     * A callback into navigation rather than something this object can do itself, because there is no
     * player queue to advance and there cannot easily be one: SABR needs per-video extractor state that
     * only `loadVideo` establishes, so "next" is the same act as tapping a related video. Whether there
     * is anything to go to is reported separately, in [Transport.hasNext] and [Transport.hasPrevious] -
     * a remote must not offer a button that does nothing.
     */
    var onNavigate: ((next: Boolean) -> Unit)? = null

    private var client: CastClient? = null

    fun support(context: Context): CastClient.Support = CastClient(context).support()

    /** True while the picker or the negotiation is in flight, so a second tap does nothing. */
    fun markConnecting() {
        _state.value = State.Connecting
    }

    /**
     * Open a session for a [width] x [height] frame and start the audio tap.
     *
     * The geometry is a request: Cast clamps it to the TV's decoder limits and to this phone's own
     * encoder, and [State.Casting] carries what was actually granted.
     *
     * Returns the failure rather than throwing, because every caller here is a button and every
     * failure is a message.
     */
    suspend fun open(context: Context, width: Int, height: Int): CastException? {
        close()
        _state.value = State.Connecting
        val newClient = CastClient(context)
        // **The shared volume level is the phone's own media volume, and that is the whole trick.** A
        // level invented for casting would have to be persisted, reconciled with the device volume, and
        // explained to a user who found their phone quiet afterwards. `STREAM_MUSIC` is already the
        // thing the user reaches for, already survives the session, and already applies to local
        // playback when the cast ends - so it is reported to the TV as a gain and moved by the TV's
        // remote through `SET_VOLUME`.
        audio = context.applicationContext.getSystemService<AudioManager>()
        audio?.let { manager -> update { it.copy(volume = manager.mediaLevel()) } }
        return try {
            val session = newClient.openSession(width, height, wantAudio = true)
            client = newClient
            newClient.onEnded = { reason ->
                Log.status(TAG, "the cast session ended (reason $reason)")
                close()
            }
            CastAudioTap.attach(session.audio)
            newClient.onCommand = { command -> apply(command) }
            startReporting(newClient)
            if (session.width != width || session.height != height) {
                Log.status(
                    TAG,
                    "asked ${width}x$height, granted ${session.width}x${session.height} " +
                        "@ ${session.frameRate}fps on '${session.receiverName}'",
                )
            } else {
                Log.status(TAG, "casting ${width}x$height @ ${session.frameRate}fps as asked")
            }
            _state.value = State.Casting(
                surface = session.surface,
                receiverName = session.receiverName,
                width = session.width,
                height = session.height,
                frameRate = session.frameRate,
            )
            null
        } catch (e: CastException) {
            newClient.close()
            _state.value = State.Idle
            e
        }
    }

    /**
     * Tell the television what video this is.
     *
     * **Sent even though this is a `Surface` session**, where the receiver already has the picture: a
     * picture is not a name, and until this existed the television could say nothing about an encoded
     * video beyond which app had sent it. It names no resource - nothing is being served here - which
     * is what tells the receiver to render it at once rather than correlate it against a play request
     * that this kind of session never issues.
     *
     * Called again whenever the video changes, which for this app includes a `Next` from the remote:
     * that reloads the player in place rather than advancing a queue, so nothing else would say so.
     *
     * Silent with no session, like everything else here.
     */
    fun setNowPlaying(name: String, author: String) {
        client?.setNowPlaying(title = name, author = author)
    }

    /** Back to playing locally. Idempotent. */
    fun close() {
        orphanJob?.cancel()
        orphanJob = null
        reportJob?.cancel()
        reportJob = null
        CastAudioTap.detach()
        client?.close()
        client = null
        audio = null
        _transport.update { it.copy(hasNext = false, hasPrevious = false) }
        _state.value = State.Idle
    }

    /**
     * Keep the TV's copy of [transport] current, for as long as this session lasts.
     *
     * Two cadences, because they answer different needs. Anything the TV *renders* differently goes out
     * the instant it changes - a pause that took half a second to reach the screen reads as a dropped
     * button press. Position is deliberately excluded from that: it moves on every poll, so "changed"
     * would mean "always", and the TV interpolates between snapshots anyway precisely so it does not
     * need them faster.
     *
     * The heartbeat underneath is what makes the whole thing self-repairing: every message is an
     * absolute snapshot, so a lost one costs at most one interval of staleness and needs no
     * acknowledgement, no sequence number and no retry.
     */
    private fun startReporting(activeClient: CastClient) {
        reportJob?.cancel()
        reportJob = scope.launch {
            launch {
                _transport
                    .map { it.copy(positionMs = 0, bufferedMs = 0) }
                    .distinctUntilChanged()
                    .collect { activeClient.reportPlaybackState(_transport.value.asPlaybackState()) }
            }
            while (isActive) {
                delay(HEARTBEAT_MS)
                // Re-read rather than trusting the last write. The level is the device's, so the
                // hardware keys and every other app can move it - which is exactly why intercepting
                // the volume keys here is unnecessary: the system already applies them to the shared
                // level, and this is what carries the result to the television.
                audio?.let { manager -> update { it.copy(volume = manager.mediaLevel()) } }
                activeClient.reportPlaybackState(_transport.value.asPlaybackState())
            }
        }
    }

    private fun Transport.asPlaybackState() = PlaybackState(
        positionMs = positionMs,
        durationMs = durationMs,
        playing = playing,
        buffering = buffering,
        speed = speed,
        volume = volume,
        hasNext = hasNext,
        hasPrevious = hasPrevious,
    )

    /**
     * Do what the television's remote asked.
     *
     * Runs on the main thread - `CastClient` guarantees it - because a `MediaController` may only be
     * touched there. Every case drives the same player the phone's own buttons drive, so there is one
     * transport rather than two that could disagree.
     *
     * With no player attached this does nothing at all. That is the correct answer rather than a
     * failure: the user has navigated away from the video, and until the session learns to survive
     * that there is nothing to control.
     */
    // internal (was private) so unit tests can drive remote commands without a live cast session.
    internal fun apply(command: PlaybackCommand) {
        val target = player ?: return
        when (command.action) {
            PlaybackAction.Play -> target.play()
            PlaybackAction.Pause -> target.pause()
            PlaybackAction.Toggle -> if (target.isPlaying) target.pause() else target.play()
            PlaybackAction.SeekTo,
            PlaybackAction.SkipForward,
            PlaybackAction.SkipBack,
            -> applySeekCommand(target, command)
            PlaybackAction.SetSpeed,
            PlaybackAction.SetVolume,
            -> applyLevelCommand(command)
            PlaybackAction.Next -> navigateIfAvailable(forward = true)
            PlaybackAction.Previous -> navigateIfAvailable(forward = false)
        }
    }

    private fun applySeekCommand(target: Player, command: PlaybackCommand) {
        when (command.action) {
            PlaybackAction.SeekTo -> command.value?.let { seek(target, it.toLong()) }
            PlaybackAction.SkipForward -> seek(target, target.currentPosition + SKIP_MS)
            PlaybackAction.SkipBack -> seek(target, target.currentPosition - SKIP_MS)
            else -> Unit
        }
    }

    private fun applyLevelCommand(command: PlaybackCommand) {
        when (command.action) {
            PlaybackAction.SetSpeed -> command.value?.let { applySpeed(it.toFloat()) }
            // The television asked for a level. Applied to the device's media volume, so it is still
            // there when playback comes back to the phone - which is the point of sharing one level
            // rather than inventing a cast-only one.
            PlaybackAction.SetVolume -> command.value?.let { setVolume(it.toFloat()) }
            else -> Unit
        }
    }

    private fun applySpeed(speed: Float) {
        // Written into [transport] rather than onto the player: `VideoPlayer` already has an
        // effect that applies the speed together with the pitch, and setting
        // `playbackParameters` here would drop whatever pitch the user had chosen.
        update { it.copy(speed = speed.coerceIn(MIN_SPEED, MAX_SPEED)) }
    }

    private fun navigateIfAvailable(forward: Boolean) {
        val transport = _transport.value
        if (forward && transport.hasNext) onNavigate?.invoke(true)
        if (!forward && transport.hasPrevious) onNavigate?.invoke(false)
    }

    /**
     * Seek, unless the phone's own seek bar is currently under a thumb.
     *
     * The position is written here rather than left to the next poll: the TV re-anchors its estimate on
     * every snapshot, so one still carrying the pre-seek position would visibly pull its bar back
     * before the following tick pushed it forward again.
     */
    private fun seek(target: Player, positionMs: Long) {
        val current = _transport.value
        if (current.dragging) return
        val end = current.durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE
        val clamped = positionMs.coerceIn(0, end)
        target.seekTo(clamped)
        update { it.copy(positionMs = clamped) }
    }

    /**
     * Move the shared level, from a television that asked.
     *
     * Written to the device volume *and* to [transport], rather than only to the device: the next
     * snapshot is up to half a second away, and a seek bar's worth of latency on a volume press is the
     * difference between a remote that feels connected and one that does not.
     */
    private fun setVolume(level: Float) {
        val clamped = level.coerceIn(0f, 1f)
        audio?.setMediaLevel(clamped)
        update { it.copy(volume = clamped) }
    }

    /**
     * `STREAM_MUSIC` as a 0..1 fraction.
     *
     * A fraction rather than an index because the two devices do not have the same number of steps - a
     * phone commonly has 15 or 25, a television 16 or 100 - and sending an index would mean the TV's
     * idea of "half" depended on the phone's hardware.
     */
    private fun AudioManager.mediaLevel(): Float {
        val max = getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return 1f
        return getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    private fun AudioManager.setMediaLevel(level: Float) {
        val max = getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (max <= 0) return
        runCatching {
            setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(level * max), 0)
        }.onFailure {
            // A device with a volume policy that refuses the write - a work profile, a restriction.
            // Nothing to report: the level the TV asked for simply does not take.
            Log.status(TAG, "could not set the media volume", it)
        }
    }

    /** Matches the phone's own skip buttons, so the two remotes move by the same amount. */
    private const val SKIP_MS = 10_000L

    /** The range the phone's own speed menu offers; a TV must not be able to ask for more. */
    private const val MIN_SPEED = 0.25f
    private const val MAX_SPEED = 2f

    /**
     * Slow enough not to compete with the encoder for the control socket, fast enough that a seek bar
     * re-anchors before its interpolation has drifted anywhere visible.
     */
    private const val HEARTBEAT_MS = 500L

    /**
     * How long a session is kept with nothing rendering into it.
     *
     * Long enough for a `MediaController` to reconnect on the next screen - it is built asynchronously
     * - and short enough that walking away from the video leaves a frozen frame on the television for a
     * moment rather than for the evening.
     */
    private const val ORPHAN_GRACE_MS = 5_000L
}

/**
 * The PCM tap, as a `TeeAudioProcessor.AudioBufferSink`.
 *
 * Sits in `PlaybackService`'s audio-sink processor chain, so it sees the decoded samples on their way
 * to the speaker. Local output is muted with `volume = 0f` rather than by stopping the renderer,
 * because volume is applied in the sink *after* the processor chain - the tap still sees full-scale
 * PCM.
 *
 * **Never blocks the audio thread.** `handleBuffer` runs on the playback thread, and a direct write
 * into a full pipe would stall decoding - which is exactly what happens if the reader on the Cast side
 * stops. So buffers go into a bounded queue and a writer thread drains it; when the queue is full the
 * oldest buffer is dropped, which costs a few milliseconds of audio rather than the whole playback.
 */
@OptIn(UnstableApi::class)
object CastAudioTap : TeeAudioProcessor.AudioBufferSink {

    /** ~0.2 s of 20 ms buffers: long enough to ride out a scheduling hiccup, short enough not to lag. */
    private const val QUEUE_CAPACITY = 16

    private const val ENCODING_PCM_16BIT = android.media.AudioFormat.ENCODING_PCM_16BIT

    @Volatile
    private var out: FileOutputStream? = null

    @Volatile
    private var pipe: ParcelFileDescriptor? = null

    private var writer: Thread? = null

    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)

    /** The format the processor chain is handing us, from the last [flush]. */
    @Volatile
    private var sampleRate = CastContract.AUDIO_SAMPLE_RATE

    @Volatile
    private var channelCount = CastContract.AUDIO_CHANNELS

    @Volatile
    private var supported = false

    /** Start writing into [audio]'s pipe. Null means the session has no audio; the tap stays off. */
    fun attach(audio: ParcelFileDescriptor?) {
        detach()
        if (audio == null) return
        pipe = audio
        out = FileOutputStream(audio.fileDescriptor)
        writer = Thread({ drain() }, "cast-pcm-writer").apply {
            isDaemon = true
            start()
        }
    }

    fun detach() {
        val thread = writer
        writer = null
        out = null
        thread?.interrupt()
        queue.clear()
        runCatching { pipe?.close() }
        pipe = null
    }

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        sampleRate = sampleRateHz
        this.channelCount = channelCount
        // Float output would need a different conversion and is off by default; anything other than
        // interleaved 16-bit is refused rather than sent as noise.
        supported = encoding == ENCODING_PCM_16BIT && sampleRateHz > 0 && channelCount > 0
        if (!supported) {
            Log.status(TAG, "cannot tap audio at ${sampleRateHz}Hz, $channelCount ch, encoding $encoding")
        }
        queue.clear()
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (out == null || !supported) return
        val converted = toContractFormat(buffer) ?: return
        // Drop the oldest rather than block: this is the audio thread.
        if (!queue.offer(converted)) {
            queue.poll()
            queue.offer(converted)
        }
    }

    private fun drain() {
        while (!Thread.currentThread().isInterrupted) {
            val chunk = try {
                queue.poll(100, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                return
            } ?: continue
            val stream = out ?: return
            try {
                stream.write(chunk)
            } catch (e: java.io.IOException) {
                // The Cast side closed the pipe; there is nothing to recover and nothing to say to
                // the user, because the session teardown already will.
                Log.status(TAG, "the PCM pipe closed", e)
                return
            }
        }
    }

    /**
     * Whatever the decoder produced, as 48 kHz stereo 16-bit little-endian.
     *
     * Opus - which is what YouPipe plays almost always - is already 48 kHz stereo, so the common path
     * is a straight copy. The conversions exist for the AAC fallback, which is often 44.1 kHz: without
     * them that video would cast with silent audio and nothing to indicate why.
     */
    private fun toContractFormat(buffer: ByteBuffer): ByteArray? {
        val remaining = buffer.remaining()
        if (remaining <= 0) return null
        val bytes = ByteArray(remaining)
        // Read a duplicate: TeeAudioProcessor passes this same buffer on to the sink afterwards, so
        // consuming it here would silence local playback and, worse, the rest of the chain.
        buffer.duplicate().get(bytes)
        if (sampleRate == CastContract.AUDIO_SAMPLE_RATE &&
            channelCount == CastContract.AUDIO_CHANNELS
        ) {
            return bytes
        }
        val frames = remaining / (BYTES_PER_SAMPLE * channelCount)
        if (frames == 0) return null
        val outFrames = (frames.toLong() * CastContract.AUDIO_SAMPLE_RATE / sampleRate).toInt()
        if (outFrames == 0) return null
        val result = ByteArray(outFrames * BYTES_PER_SAMPLE * CastContract.AUDIO_CHANNELS)
        for (frame in 0 until outFrames) {
            // Nearest source frame. Resampling per buffer rather than continuously leaves a sub-sample
            // discontinuity at each boundary, which is inaudible next to the alternative of no audio.
            val source = (frame.toLong() * frames / outFrames).toInt().coerceAtMost(frames - 1)
            val base = source * BYTES_PER_SAMPLE * channelCount
            val left = sampleAt(bytes, base)
            val right = if (channelCount >= STEREO_CHANNEL_COUNT) {
                sampleAt(bytes, base + BYTES_PER_SAMPLE)
            } else {
                left
            }
            val target = frame * BYTES_PER_FRAME
            result[target] = (left and BYTE_MASK).toByte()
            result[target + FIRST_BYTE_OFFSET] = (left shr BYTE_SHIFT and BYTE_MASK).toByte()
            result[target + SECOND_BYTE_OFFSET] = (right and BYTE_MASK).toByte()
            result[target + THIRD_BYTE_OFFSET] = (right shr BYTE_SHIFT and BYTE_MASK).toByte()
        }
        return result
    }

    /** One little-endian 16-bit sample, as its raw bits. */
    private fun sampleAt(bytes: ByteArray, offset: Int): Int {
        if (offset + 1 >= bytes.size) return 0
        return (bytes[offset].toInt() and BYTE_MASK) or (bytes[offset + 1].toInt() shl BYTE_SHIFT)
    }

    private const val BYTES_PER_SAMPLE = 2
    private const val BYTES_PER_FRAME = 4
    private const val STEREO_CHANNEL_COUNT = 2
    private const val BYTE_MASK = 0xFF
    private const val BYTE_SHIFT = 8
    private const val FIRST_BYTE_OFFSET = 1
    private const val SECOND_BYTE_OFFSET = 2
    private const val THIRD_BYTE_OFFSET = 3
}
