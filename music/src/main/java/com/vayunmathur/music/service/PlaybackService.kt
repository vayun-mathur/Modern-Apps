package com.vayunmathur.music.service
import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.vayunmathur.music.MainActivity
import com.vayunmathur.music.platform.nextRepeatMode

/**
 * Playback + media-browse service.
 *
 * Extends [MediaLibraryService] so it serves two clients from one place:
 *  - the phone app, which connects a plain `MediaController` for transport, and
 *  - Android Auto (and any `MediaBrowser`), which browses the car tree built by
 *    [MusicLibraryTree] and plays through the very same [ExoPlayer]/session.
 *
 * The player and the custom shuffle/repeat notification buttons are unchanged;
 * we only widened the session type (MediaLibrarySession is a MediaSession) and
 * added the library-browsing callbacks, so phone playback is unaffected.
 *
 * **The player handed to the session is a [CastingPlayer], and that is where casting lives.** Every
 * transport this app has - its own UI, the mini-player, the notification, the lockscreen, a headset,
 * Android Auto - reaches playback through this one object, so wrapping it here is what makes them all
 * describe and drive the television while a cast is running. [CastQueue] holds the raw player for the
 * things that must *not* go to the television: muting the phone, and putting it back where the
 * television got to afterwards.
 */
class PlaybackService : MediaLibraryService() {

    private var mediaSession: MediaLibrarySession? = null
    private lateinit var libraryTree: MusicLibraryTree
    private var castQueue: CastQueue? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Custom Command Constants
    companion object {
        const val ACTION_SHUFFLE = "ACTION_SHUFFLE"
        const val ACTION_REPEAT = "ACTION_REPEAT"
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        libraryTree = MusicLibraryTree(this)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val local = ExoPlayer.Builder(this)
            .setDeviceVolumeControlEnabled(true)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        val player = CastingPlayer(local)
        castQueue = CastQueue(this, local)

        // Create the session with our custom callback
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaLibrarySession.Builder(this, player, MediaSessionCallback())
            .setSessionActivity(pendingIntent)
            .build()

        // Push updated browse content to the car whenever the library changes.
        libraryTree.onLibraryChanged = {
            mainHandler.post { notifyBrowseChildrenChanged() }
        }

        // Initialize the notification buttons for the first time
        updateNotificationButtons()
    }

    @OptIn(UnstableApi::class)
    private fun notifyBrowseChildrenChanged() {
        val session = mediaSession ?: return
        libraryTree.refreshableParents().forEach { parentId ->
            session.notifyChildrenChanged(parentId, libraryTree.childCount(parentId), null)
        }
    }

    /**
     * This function builds the buttons and pushes them to the MediaSession.
     * The notification provider reads these 'preferences' to decide what to show.
     */
    @OptIn(UnstableApi::class)
    private fun updateNotificationButtons() {
        val session = mediaSession ?: return
        val player = session.player

        // 1. Shuffle Button
        val shuffleIcon = if (player.shuffleModeEnabled)
            CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF

        val shuffleBtn = CommandButton.Builder(shuffleIcon)
            .setDisplayName("Shuffle")
            .setSessionCommand(SessionCommand(ACTION_SHUFFLE, Bundle.EMPTY))
            .build()

        // 2. Repeat Button
        val repeatIcon = when (player.repeatMode) {
            Player.REPEAT_MODE_ONE -> CommandButton.ICON_REPEAT_ONE
            Player.REPEAT_MODE_ALL -> CommandButton.ICON_REPEAT_ALL
            else -> CommandButton.ICON_REPEAT_OFF
        }

        val repeatBtn = CommandButton.Builder(repeatIcon)
            .setDisplayName("Repeat")
            .setSessionCommand(SessionCommand(ACTION_REPEAT, Bundle.EMPTY))
            .build()

        // Set the buttons. This list defines the order in the notification.
        session.setMediaButtonPreferences(ImmutableList.of(shuffleBtn, repeatBtn))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaSession
    }

    @OptIn(UnstableApi::class)
    private inner class MediaSessionCallback : MediaLibrarySession.Callback {
        // Handle the button clicks
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {

            when (customCommand.customAction) {
                ACTION_SHUFFLE -> {
                    session.player.shuffleModeEnabled = !session.player.shuffleModeEnabled
                    updateNotificationButtons() // Redraw notification with new icon
                }
                ACTION_REPEAT -> {
                    session.player.repeatMode = nextRepeatMode(session.player.repeatMode)
                    updateNotificationButtons() // Redraw notification with new icon
                }
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        // ── Android Auto browse tree ──────────────────────────────────────────

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(libraryTree.rootItem(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(libraryTree.children(parentId)), params)
            )

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val item = libraryTree.item(mediaId)
                ?: return Futures.immediateFuture(LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE))
            return Futures.immediateFuture(LibraryResult.ofItem(item, null))
        }

        override fun onSearch(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<Void>> {
            val count = libraryTree.searchResults(query).size
            session.notifySearchResultChanged(browser, query, count, params)
            return Futures.immediateFuture(LibraryResult.ofVoid())
        }

        override fun onGetSearchResult(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            query: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> =
            Futures.immediateFuture(
                LibraryResult.ofItemList(ImmutableList.copyOf(libraryTree.searchResults(query)), params)
            )

        // ── Playback resolution (browse taps + voice "play X") ────────────────

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mutableListOf<MediaItem>()
            for (item in mediaItems) {
                val query = item.requestMetadata.searchQuery
                when {
                    !query.isNullOrBlank() -> resolved.addAll(libraryTree.searchPlayableSongs(query))
                    item.localConfiguration != null -> resolved.add(item) // already playable
                    else -> libraryTree.resolveForPlayback(item.mediaId)?.let { resolved.add(it) }
                }
            }
            libraryTree.markPlayed(resolved.map { it.mediaId })
            return Futures.immediateFuture(resolved)
        }
    }

    override fun onDestroy() {
        libraryTree.release()
        castQueue?.release()
        castQueue = null
        mediaSession?.run {
            // Releasing the wrapper releases what it wraps, so the local player goes with it.
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
