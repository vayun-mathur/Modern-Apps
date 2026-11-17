package com.vayunmathur.cast.tv.platform

import android.content.Context
import com.vayunmathur.library.log.Log
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.vayunmathur.cast.protocol.ContentSession
import com.vayunmathur.cast.protocol.EphemeralTls
import com.vayunmathur.cast.protocol.MediaProxy
import com.vayunmathur.cast.protocol.PlayMedia
import com.vayunmathur.cast.protocol.PlaybackAction
import com.vayunmathur.cast.protocol.PlaybackCommand
import com.vayunmathur.cast.protocol.PlaybackState
import com.vayunmathur.cast.protocol.ProtocolBase64
import javax.net.ssl.HttpsURLConnection

private const val TAG = "ContentPlayer"

/**
 * Plays app content the phone is serving, rather than decoding pixels it encoded.
 *
 * This is the other half of the change that removes the encoder from the phone. The TV fetches byte
 * ranges of the original media over HTTPS and hands them to its own decoder, so it owns the clock,
 * the buffer and the seeking - and every pathology of the RTP path stops applying rather than being
 * fixed. A pause is a pause. A seek is a byte offset. There is no picture-loss indicator because
 * there are no lost pictures to indicate.
 *
 * **Trust is the session's, not a PKI's.** The certificate fingerprint arrived over the control
 * channel, which the pairing already authenticated, so the player's data source trusts exactly that
 * one certificate. Nothing here is told to skip verification - the point of pinning is that it
 * verifies something stronger than a name.
 *
 * **This player is the truth about a served session**, which is what [snapshot] and [apply] are for.
 * The phone has no player running - it deliberately paused its own before the cast started - so it
 * neither knows where playback is nor could usefully be asked to move it. Everything the phone shows
 * and everything it commands passes through here.
 */
@OptIn(UnstableApi::class)
class ContentPlayer(
    private val session: ContentSession,
) {

    private var player: ExoPlayer? = null

    /**
     * Called whenever something about playback changed, so a snapshot can go out at once.
     *
     * A pause that took half a second to reach the phone's notification reads as a dropped button
     * press. The heartbeat behind this exists for position, which changes constantly and would make
     * "changed" mean "always".
     *
     * Runs on the player's own thread, which is the main thread.
     */
    var onChanged: (() -> Unit)? = null

    /**
     * What the phone said the item runs for, used until the player works it out for itself.
     *
     * This is the whole reason `PLAY_MEDIA` carries a duration. A resource still being encoded has no
     * length in its container either, so ExoPlayer reports none - and a seek bar that appears only once
     * a track has finished encoding is a seek bar that never appears on a first play.
     */
    private var statedDurationMs: Long = 0

    /** Where playback is, for the phone. Null until the player has produced anything. */
    val position: Long get() = player?.currentPosition ?: 0L
    val duration: Long get() = player?.duration?.takeIf { it > 0 } ?: statedDurationMs
    val isPlaying: Boolean get() = player?.isPlaying == true
    val isBuffering: Boolean get() = player?.playbackState == Player.STATE_BUFFERING

    /**
     * The item played to its end on its own, which is what lets the phone advance its queue.
     *
     * A flag rather than something inferred from position against duration: those are
     * indistinguishable from a pause at the end of a track, and a growing transcode has no duration to
     * compare against at all.
     */
    val isEnded: Boolean get() = player?.playbackState == Player.STATE_ENDED

    val speed: Float get() = player?.playbackParameters?.speed ?: 1f

    val volume: Float get() = player?.volume ?: 1f

    /**
     * Everything the phone needs to draw this player, as an absolute snapshot.
     *
     * `hasNext` and `hasPrevious` are left at their defaults deliberately: the queue is a list only
     * the phone can see, so it owns them in both directions and ignores whatever arrives from here.
     */
    fun snapshot(): PlaybackState = PlaybackState(
        positionMs = position,
        durationMs = duration,
        playing = isPlaying,
        buffering = isBuffering,
        speed = speed,
        volume = volume,
        ended = isEnded,
    )

    /**
     * Do what a transport asked, wherever it was pressed.
     *
     * [PlaybackAction.Next] and [PlaybackAction.Previous] are refused rather than handled, and must
     * be: there is no queue here to advance, and the phone answers them by sending a fresh
     * `PLAY_MEDIA`. Returns whether this player owned the command, so a caller can pass the rest on.
     */
    fun apply(command: PlaybackCommand): Boolean {
        when (command.action) {
            PlaybackAction.Play -> play()
            PlaybackAction.Pause -> pause()
            PlaybackAction.Toggle -> if (isPlaying) pause() else play()
            PlaybackAction.SeekTo -> command.value?.let { seekTo(it.toLong()) }
            PlaybackAction.SkipForward -> seekTo(position + SKIP_MS)
            PlaybackAction.SkipBack -> seekTo((position - SKIP_MS).coerceAtLeast(0))
            PlaybackAction.SetSpeed -> command.value?.let { setSpeed(it.toFloat()) }
            PlaybackAction.SetVolume -> command.value?.let { setVolume(it.toFloat()) }
            PlaybackAction.Next, PlaybackAction.Previous -> return false
        }
        return true
    }

    /**
     * Builds the player, or returns false if the fingerprint was unusable.
     *
     * [onError] is reported rather than swallowed: a failed load on this path used to be invisible,
     * and an expired resource that produces no error and no picture is the hardest kind of fault to
     * attribute.
     */
    fun start(context: Context, onError: (String) -> Unit): Boolean {
        val fingerprint = ProtocolBase64.decode(session.certificateFingerprint)
        if (fingerprint == null || fingerprint.size != FINGERPRINT_BYTES) {
            Log.status(TAG, "the phone sent an unusable certificate fingerprint")
            return false
        }

        // Process-wide because `DefaultHttpDataSource` is `HttpURLConnection`-backed and takes its
        // socket factory from the default. Acceptable because every HTTPS request this app makes goes
        // to the same pinned proxy - but not relied upon: `ArtworkFetcher` sets the factory on its
        // own connection, because a snapshot can arrive before this has run.
        HttpsURLConnection.setDefaultSSLSocketFactory(EphemeralTls.client(fingerprint).socketFactory)

        val http = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
            // The proxy answers from a file descriptor the phone's app supplied, and can be slow to
            // get one. A redirect is never legitimate here.
            .setAllowCrossProtocolRedirects(false)

        val built = ExoPlayer.Builder(context.applicationContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build()
        built.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                // Deliberately loud. The old path reported load errors as end-of-stream, so an
                // expired URL produced silence and no explanation at either end.
                Log.status(TAG, "playback failed: ${error.errorCodeName}", error)
                onError(error.errorCodeName)
            }

            // Everything at once rather than a callback per field: the reporter's job is to notice
            // that *something* changed, and it compares snapshots itself to decide whether to send.
            override fun onEvents(player: Player, events: Player.Events) {
                onChanged?.invoke()
            }
        })
        built.playWhenReady = true
        player = built
        Log.status(
            TAG,
            "content player up for ${session.host}:${session.port}, " +
                if (session.video) "audio and video" else "audio only",
        )
        return true
    }

    /** Points the player at a resource on the proxy and starts it. */
    fun play(media: PlayMedia) {
        val active = player ?: return
        val url = MediaProxy.url(session.host, session.port, session.token, media.resourceId)
        val item = MediaItem.Builder()
            .setUri(url)
            // Stated rather than sniffed: the phone knows what it is serving, and sniffing costs a
            // round trip before anything can start.
            .setMimeType(media.mimeType.ifBlank { MimeTypes.BASE_TYPE_AUDIO })
            .build()
        Log.status(TAG, "playing ${media.resourceId} (${media.mimeType}) from ${media.startPositionMs}ms")
        statedDurationMs = media.durationMs
        // A position rather than the start, so handing playback over from the phone keeps it. A
        // resource still being written cannot honour it - there is no length to seek against - and
        // ExoPlayer then begins at the beginning, which is the honest outcome rather than a failure.
        active.setMediaItem(item, media.startPositionMs)
        active.prepare()
        active.play()
    }

    /**
     * Hands the player a surface, or takes it away.
     *
     * Null is the audio-only case and the case where the TV's surface has gone - handing the player
     * a surface that has been destroyed is what makes the *next* session fail rather than this one.
     */
    fun setSurface(surface: Surface?) {
        player?.setVideoSurface(surface)
    }

    fun play() {
        player?.play()
    }

    fun pause() {
        player?.pause()
    }

    fun seekTo(positionMs: Long) {
        // A byte offset on this path, not a key-frame renegotiation. This is the line that used to
        // be a round trip to the phone and a wait for a fresh key frame.
        player?.seekTo(positionMs)
    }

    fun setSpeed(speed: Float) {
        player?.setPlaybackSpeed(speed)
    }

    fun setVolume(volume: Float) {
        player?.volume = volume.coerceIn(0f, 1f)
    }

    fun release() {
        onChanged = null
        runCatching { player?.release() }
        player = null
    }

    private companion object {
        const val FINGERPRINT_BYTES = 32

        /**
         * How far a discrete skip moves, now that this end applies one.
         *
         * Its own constant rather than a number the phone supplies: the press is applied here so that
         * it acts at once, and a round trip to ask how far ten seconds is would defeat that.
         */
        const val SKIP_MS = 10_000L

        /** The phone is on the same LAN, so a slow connect means something is wrong rather than far. */
        const val CONNECT_TIMEOUT_MS = 8_000

        /**
         * Longer than the connect timeout, because the proxy may be waiting on the app for a
         * descriptor - but bounded, so a phone that has stopped answering surfaces as a load error
         * rather than as a picture that never arrives.
         */
        const val READ_TIMEOUT_MS = 15_000
    }
}
