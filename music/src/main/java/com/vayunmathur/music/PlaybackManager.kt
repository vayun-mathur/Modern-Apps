package com.vayunmathur.music

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.vayunmathur.music.database.Music
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

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

    init {
        val appContext = context.applicationContext
        val sessionToken = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val controllerFuture = MediaController.Builder(appContext, sessionToken).buildAsync()

        controllerFuture.addListener({
            controller = controllerFuture.get().apply {
                addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(playing: Boolean) {
                        _isPlaying.value = playing
                    }

//                    override fun onPlaybackStateChanged(state: Int) {
//                        if (state == Player.STATE_READY) {
//                            _duration.value = duration.coerceAtLeast(0L)
//                        }
//                    }

                    override fun onShuffleModeEnabledChanged(enabled: Boolean) {
                        _shuffleMode.value = enabled
                    }

                    override fun onRepeatModeChanged(mode: Int) {
                        _repeatMode.value = mode
                    }
                })
            }
            startProgressUpdateLoop()
        }, MoreExecutors.directExecutor())
    }

    private fun startProgressUpdateLoop() {
        scope.launch {
            while (true) {
                controller?.let {
                    _currentPosition.value = it.currentPosition
                    _duration.value = it.duration.coerceAtLeast(0L)
                }
                delay(1000)
            }
        }
    }

    fun playSong(song: Music) {
        val player = controller ?: return
        if (player.currentMediaItem?.mediaId == song.id.toString()) return

        val mediaItem = MediaItem.Builder()
            .setMediaId(song.id.toString())
            .setUri(song.uri.toUri())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .build()
            )
            .build()

        player.stop()
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
    }

    fun togglePlayPause() = controller?.let { if (it.isPlaying) it.pause() else it.play() }
    fun seekTo(pos: Long) = controller?.seekTo(pos)
    fun skipNext() = controller?.seekToNext()
    fun skipPrevious() = controller?.seekToPrevious()

    fun toggleShuffle() {
        controller?.let { it.shuffleModeEnabled = !it.shuffleModeEnabled }
    }

    fun toggleRepeat() {
        controller?.let {
            it.repeatMode = when (it.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ONE
                Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_ALL
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile private var INSTANCE: PlaybackManager? = null
        fun getInstance(context: Context): PlaybackManager =
            INSTANCE ?: synchronized(this) { INSTANCE ?: PlaybackManager(context).also { INSTANCE = it } }
    }
}