package com.vayunmathur.music.platform
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.MoreExecutors
import com.vayunmathur.library.log.Log
import com.vayunmathur.music.data.Music
import com.vayunmathur.music.data.MusicRepository
import com.vayunmathur.music.service.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlin.random.Random

/** Typed model of a playback queue's origin, encoded as the string id stored on each queue. */
sealed interface PlaybackSource {
    val id: String

    data object AllSongs : PlaybackSource {
        override val id = "all_songs"
    }

    data class Album(val albumId: Long) : PlaybackSource {
        override val id = "album_$albumId"
    }

    data class Playlist(val playlistId: Long) : PlaybackSource {
        override val id = "playlist_$playlistId"
    }

    data class Artist(val artistId: Long) : PlaybackSource {
        override val id = "artist_$artistId"
    }

    companion object {
        fun parse(id: String?): PlaybackSource? = when {
            id == null -> null
            id == AllSongs.id -> AllSongs
            id.startsWith("album_") -> id.removePrefix("album_").toLongOrNull()?.let(::Album)
            id.startsWith("playlist_") -> id.removePrefix("playlist_").toLongOrNull()?.let(::Playlist)
            id.startsWith("artist_") -> id.removePrefix("artist_").toLongOrNull()?.let(::Artist)
            else -> null
        }
    }
}

/**
 * The one repeat cycle, shared by the app transport and the notification
 * buttons in `PlaybackService`: off -> all -> one -> off. Unknown modes fall
 * back to off. The two surfaces previously cycled in different orders, so the
 * same press meant different things depending on where it was pressed.
 */
fun nextRepeatMode(mode: Int): Int = when (mode) {
    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
    else -> Player.REPEAT_MODE_OFF
}

class PlaybackManager private constructor(context: Context) {

    private var controller: MediaController? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // Observables for the UI
    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition = _currentPosition.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration = _duration.asStateFlow()

    private val _shuffleMode = MutableStateFlow(false)
    val shuffleMode = _shuffleMode.asStateFlow()

    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode = _repeatMode.asStateFlow()

    private val _currentMediaItem = MutableStateFlow<MediaItem?>(null)
    val currentMediaItem = _currentMediaItem.asStateFlow()

    private val _currentSource = MutableStateFlow<String?>(null)
    val currentSource = _currentSource.asStateFlow()

    private val _currentSourceName = MutableStateFlow<String?>(null)
    val currentSourceName = _currentSourceName.asStateFlow()

    private val _player = MutableStateFlow<Player?>(null)
    /** The session player, once the controller has connected. */
    val player = _player.asStateFlow()

    /** Album id -> art uri, mirrored from the library so queue items carry the cover. */
    @Volatile private var albumArt: Map<Long, String> = emptyMap()

    init {
        val appContext = context.applicationContext
        val sessionToken = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(appContext, sessionToken).buildAsync()

        controllerFuture.addListener({
            try {
                controller = controllerFuture.get().apply {
                    // Seed from the player's current state: the listener
                    // below only fires on changes, so connecting while the
                    // service was already playing (app restart, process
                    // death) otherwise left every observable at its default
                    // - paused, no track, repeat off - until the next
                    // transition, and the mini-player offered to play a
                    // track that was already playing.
                    _isPlaying.value = isPlaying
                    _currentMediaItem.value = currentMediaItem
                    _shuffleMode.value = shuffleModeEnabled
                    _repeatMode.value = repeatMode
                    _currentPosition.value = currentPosition.coerceAtLeast(0L)
                    _duration.value = duration.coerceAtLeast(0L)
                    addListener(object : Player.Listener {
                        override fun onIsPlayingChanged(playing: Boolean) {
                            _isPlaying.value = playing
                        }
                        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                            _currentMediaItem.value = mediaItem
                        }

                        override fun onShuffleModeEnabledChanged(enabled: Boolean) {
                            _shuffleMode.value = enabled
                        }

                        override fun onRepeatModeChanged(mode: Int) {
                            _repeatMode.value = mode
                        }
                    })
                }
                _player.value = controller
                startProgressUpdateLoop()
            } catch (e: IllegalStateException) {
                Log.error("PlaybackManager", "Error initializing MediaController", e)
            } catch (e: SecurityException) {
                Log.error("PlaybackManager", "Error initializing MediaController", e)
            } catch (e: java.util.concurrent.ExecutionException) {
                // A refused session connection surfaces here, not as one of
                // the types above: without this catch the failure escaped
                // the listener as an uncaught exception on the main thread.
                Log.error("PlaybackManager", "Error initializing MediaController", e)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                Log.error("PlaybackManager", "Error initializing MediaController", e)
            }
        }, MoreExecutors.directExecutor())

        scope.launch {
            MusicRepository.get(appContext).albums.collect { albums ->
                albumArt = albums.associate { it.id to it.uri }
            }
        }
    }

    private fun artworkFor(song: Music): Uri =
        (albumArt[song.albumId]?.takeIf { it.isNotBlank() } ?: song.uri).toUri()

    private fun startProgressUpdateLoop() {
        scope.launch {
            _isPlaying.collectLatest { playing ->
                if (!playing) return@collectLatest
                while (true) {
                    controller?.let {
                        _currentPosition.value = it.currentPosition
                        _duration.value = it.duration.coerceAtLeast(0L)
                    }
                    delay(PROGRESS_UPDATE_MS)
                }
            }
        }
    }

    fun playSong(
        songs: List<Music>,
        startWithIndex: Int,
        sourceId: String? = null,
        sourceName: String? = null,
    ) {
        if (songs.isEmpty() || startWithIndex !in songs.indices) return
        _currentSource.value = sourceId
        _currentSourceName.value = sourceName
        playSongsInternal(songs, startWithIndex, shuffle = false)
    }

    fun playShuffled(
        songs: List<Music>,
        sourceId: String? = null,
        sourceName: String? = null,
    ) {
        if (songs.isEmpty()) return
        _currentSource.value = sourceId
        _currentSourceName.value = sourceName
        val startWithIndex = Random.nextInt(songs.size)
        playSongsInternal(songs, startWithIndex, shuffle = true)
    }

    private fun playSongsInternal(songs: List<Music>, startWithIndex: Int, shuffle: Boolean) {
        if (songs.isEmpty() || startWithIndex !in songs.indices) return
        val player = controller ?: return

        // Repeat is the user's setting, not a property of the new queue:
        // forcing it here silently undid an explicit off/one chosen on the
        // now-playing screen every time another song or album was tapped.
        player.shuffleModeEnabled = shuffle

        val mediaItems = songs.map { song ->
            MediaItem.Builder()
                .setMediaId(song.id.toString())
                .setUri(song.uri.toUri())
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(song.title)
                        .setArtist(song.artist)
                        .setArtworkUri(artworkFor(song))
                        .build()
                )
                .build()
        }

        player.stop()
        player.setMediaItems(mediaItems, startWithIndex, 0L)
        player.prepare()
        player.play()
    }

    fun togglePlayPause() = controller?.let { if (it.isPlaying) it.pause() else it.play() }

    fun seekTo(pos: Long) {
        controller?.seekTo(pos)
        _currentPosition.value = pos
    }
    fun skipNext() = controller?.seekToNext()
    fun skipPrevious() = controller?.seekToPrevious()

    fun toggleShuffle() {
        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    fun toggleRepeat() {
        controller?.let { it.repeatMode = nextRepeatMode(it.repeatMode) }
    }

    companion object {
        private const val PROGRESS_UPDATE_MS = 1000L
        @SuppressLint("StaticFieldLeak")
        @Volatile private var INSTANCE: PlaybackManager? = null
        fun getInstance(context: Context): PlaybackManager =
            INSTANCE ?: synchronized(this) { INSTANCE ?: PlaybackManager(context).also { INSTANCE = it } }
    }
}
