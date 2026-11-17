package com.vayunmathur.cast.tv.platform

import android.content.Context
import com.vayunmathur.library.log.Log
import com.vayunmathur.cast.protocol.Bye
import com.vayunmathur.cast.protocol.ContentEnded
import com.vayunmathur.cast.protocol.ContentReady
import com.vayunmathur.cast.protocol.ContentSession
import com.vayunmathur.cast.protocol.NowPlaying
import com.vayunmathur.cast.protocol.Ping
import com.vayunmathur.cast.protocol.PlayMedia
import com.vayunmathur.cast.protocol.PlaybackCommand
import com.vayunmathur.cast.protocol.PlaybackState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ReceiverController"

/**
 * How often a served session's playback goes to the phone even when nothing changed.
 *
 * Matches the cadence the phone used to report at, and for the same reason: every message is an
 * absolute snapshot, so this is the longest anything can be stale for, and the receiving end
 * interpolates position between them precisely so it does not need them faster.
 */
internal const val REPORT_HEARTBEAT_MS = 500L

/**
 * Serve a content session: the phone has the bytes, this end has the player.
 *
 * The whole of the new arrangement on this side. Nothing is decoded from RTP, nothing waits for a
 * key frame, and there is no picture-loss indicator to send because there are no lost pictures.
 * What is left is a URL, a pinned certificate and a player that owns its own clock.
 *
 * **And because it owns the clock, it owns the truth.** A reporting coroutine puts this player's
 * state on the channel - immediately when something changes, and otherwise at
 * [REPORT_HEARTBEAT_MS] - which is what lets every surface on the phone show what is actually
 * playing. `PLAYBACK_COMMAND` arrives here rather than leaving, for the same reason.
 *
 * ExoPlayer must be built and driven from the thread whose looper it took, so every call into it
 * hops to the main thread. The control channel stays on this coroutine, because it is the same
 * blocking read it always was.
 *
 * Returns true when the phone ended the *session* and wants to keep the connection, so the caller
 * can go back to waiting for the next configuration.
 */
internal suspend fun ReceiverController.serveContent(
    context: Context,
    channel: ControlChannel,
    session: ContentSession,
    senderName: String,
): Boolean {
    if (!session.video && AudioPlayer.limits().isEmpty()) {
        // The failure that used to be silence. A TV with no Opus decoder and no picture to fall
        // back on has to say so, and the phone has to be told rather than left streaming into it.
        Log.status(TAG, "refusing an audio-only session: this TV has no Opus decoder")
        channel.send(ContentReady(accepted = false, detail = "this TV has no Opus decoder"))
        mutableState.update { it.copy(phase = ReceiverPhase.Failed(ReceiverFailure.NoAudioDecoder)) }
        return false
    }

    val player = prepareContentPlayer(context, channel, session) ?: return false

    contentPlayer = player
    // A surface may already exist from a previous session's Activity; an audio-only session wants
    // none, and handing it one would put a black rectangle over the now-playing screen.
    if (session.video) surface?.let { withContext(Dispatchers.Main) { player.setSurface(it) } }
    channel.send(ContentReady(accepted = true))
    // Built outside the update, because `update` may retry its lambda under contention and
    // allocating a fetcher per attempt would be one object per lost race.
    val artworkFetcher = ArtworkFetcher(session)
    mutableState.update {
        it.copy(
            phase = ReceiverPhase.Mirroring(
                senderName = senderName,
                // No frame size to letterbox to: the TV plays the media at its own size, which is
                // the point of not squeezing it through an encoder first.
                width = 0,
                height = 0,
                appLabel = session.appLabel,
                hasVideo = session.video,
            ),
            // In the state rather than a field of this object, because the now-playing screen
            // reads it during composition and a plain field is not something composition
            // observes - the same trap the `overlayPinned` comment in `MirrorActivity` names.
            artwork = artworkFetcher,
        )
    }
    Log.status(TAG, "serving content from ${session.host}:${session.port} for '${session.appLabel}'")

    // A third writer on this channel, alongside the ping echo and this coroutine's own sends.
    // Nothing guards it here because nothing needs to: `ControlChannel.send` encodes and writes
    // under one lock, so a frame cannot interleave with another and the cipher's nonce cannot
    // advance twice for one message. What a mutex would add is ordering between *sequences*, and
    // there are none here - every send is a single message.
    val reporting = scope.launch { report(player, channel) }
    try {
        return serveLoop(channel, player, senderName)
    } finally {
        // Cancelled **and joined**, under NonCancellable because this runs on the teardown path a
        // cancelled session takes. A publish already past its last suspension point would
        // otherwise land after the caller has cleared the overlay, putting a stale 0:00 snapshot
        // back on the idle screen and making `nudgeVolume` answer for a session that has gone.
        withContext(NonCancellable) { reporting.cancelAndJoin() }
        contentPlayer = null
        // NonCancellable because this is the teardown path a cancelled session takes, and an
        // ExoPlayer left unreleased holds a codec the next session will ask for.
        withContext(NonCancellable + Dispatchers.Main) { player.release() }
    }
}

/**
 * Build the player for a served session, or refuse it when it cannot be built.
 *
 * Null carries the refusal with it: the phone has been told with a reason and the failure is on
 * screen, so the caller just ends the session.
 */
private suspend fun ReceiverController.prepareContentPlayer(
    context: Context,
    channel: ControlChannel,
    session: ContentSession,
): ContentPlayer? {
    val player = withContext(Dispatchers.Main) { ContentPlayer(session) }
    val started = withContext(Dispatchers.Main) {
        player.start(context) { detail -> Log.status(TAG, "the served stream failed: $detail") }
    }
    if (started) return player
    channel.send(ContentReady(accepted = false, detail = "the player could not be built"))
    mutableState.update { it.copy(phase = ReceiverPhase.Failed(ReceiverFailure.Handshake)) }
    withContext(NonCancellable + Dispatchers.Main) { player.release() }
    return null
}

/**
 * The served session's control loop: one message at a time until the phone leaves.
 *
 * Returns true when the phone ended the *session* and wants to keep the connection, so the caller
 * can go back to waiting for the next configuration.
 */
private suspend fun ReceiverController.serveLoop(
    channel: ControlChannel,
    player: ContentPlayer,
    senderName: String,
): Boolean {
    while (true) {
        val next = channel.receive() ?: return false
        when (val message = next.message) {
            is Bye -> {
                Log.status(TAG, "'$senderName' said goodbye")
                return false
            }
            // The phone is done casting but not done with us. Distinct from a `Bye`, and the
            // difference is the pairing: this leaves the TV connected and ready for the next
            // cast rather than back at its idle screen waiting to be picked again.
            is ContentEnded -> {
                Log.status(TAG, "'$senderName' ended the content session")
                return true
            }
            is PlayMedia -> {
                withContext(Dispatchers.Main) { player.play(message) }
                // Published rather than only held on the player, because it is half of the
                // comparison `nowPlayingForCurrentItem` makes and the UI has to recompose on it.
                mutableState.update { it.copy(playingResourceId = message.resourceId) }
            }
            // What the item *is*, as opposed to which bytes it is. Stored whatever it names:
            // the gate is at read time, so a snapshot arriving before or after the play it
            // describes both work, and one for a track already skipped past is simply never
            // shown. See `ReceiverUiState.nowPlayingForCurrentItem`.
            is NowPlaying -> mutableState.update { it.copy(nowPlaying = message) }
            // The phone's transport, wherever it was pressed - its own screen, a notification,
            // a headset button, a car. Applied to the player that is actually making the
            // sound. `Next` and `Previous` are refused by the player and arrive as a fresh
            // `PLAY_MEDIA` instead, because only the phone can see the queue.
            is PlaybackCommand -> withContext(Dispatchers.Main) { player.apply(message) }
            // Echoed straight back, which is the whole of the keep-alive. Reading it has
            // already pushed this end's deadline out; replying is what pushes the phone's,
            // since a read timeout is not reset by anything that end sends.
            is Ping -> runCatching { channel.send(Ping) }
            else -> Unit
        }
    }
}

/**
 * Keep the phone's copy of this player current, for as long as the session lasts.
 *
 * Two cadences, because they answer different needs. Anything the phone *renders* differently goes
 * out the instant it changes - a pause that took half a second to reach a notification reads as a
 * dropped button press. Position is deliberately excluded from that test: it moves constantly, so
 * "changed" would mean "always", and the phone interpolates between snapshots precisely so it does
 * not need them faster.
 *
 * The heartbeat underneath is what makes the whole thing self-repairing: every message is an
 * absolute snapshot, so a lost one costs at most one interval of staleness and needs no
 * acknowledgement, no sequence number and no retry.
 *
 * Each snapshot is also published locally, because this box draws its own overlay from the same
 * numbers - it is the source of them now, so there is nothing else to draw from.
 */
internal suspend fun ReceiverController.report(player: ContentPlayer, channel: ControlChannel) {
    // Built on the main thread, because every getter behind `snapshot` asserts the player's own
    // looper - reading them from this coroutine would throw rather than report a stale number.
    val latest = withContext(Dispatchers.Main) {
        MutableStateFlow(player.snapshot()).also { flow ->
            player.onChanged = { flow.value = player.snapshot() }
        }
    }
    try {
        coroutineScope {
            launch {
                latest
                    .map { it.copy(positionMs = 0) }
                    .distinctUntilChanged()
                    .collect { publish(latest.value, channel) }
            }
            while (isActive) {
                delay(REPORT_HEARTBEAT_MS)
                latest.value = withContext(Dispatchers.Main) { player.snapshot() }
                publish(latest.value, channel)
            }
        }
    } finally {
        withContext(NonCancellable + Dispatchers.Main) { player.onChanged = null }
    }
}

/** One snapshot, to this box's own overlay and to the phone. */
internal fun ReceiverController.publish(snapshot: PlaybackState, channel: ControlChannel) {
    onPlaybackState(snapshot)
    runCatching { channel.send(snapshot) }
        .onFailure { Log.status(TAG, "could not report playback", it) }
}
