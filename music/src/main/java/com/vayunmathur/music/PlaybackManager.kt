package com.vayunmathur.music

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import androidx.core.net.toUri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.vayunmathur.music.database.Music

class PlaybackManager private constructor(context: Context) {

    private val controllerFuture: ListenableFuture<MediaController>
    private var controller: MediaController? = null

    init {
        // Use ApplicationContext to prevent memory leaks from Activities
        val appContext = context.applicationContext
        val sessionToken = SessionToken(
            appContext, 
            ComponentName(appContext, PlaybackService::class.java)
        )
        
        controllerFuture = MediaController.Builder(appContext, sessionToken).buildAsync()
        controllerFuture.addListener({
            controller = controllerFuture.get()
        }, MoreExecutors.directExecutor())
    }

    companion object {
        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var INSTANCE: PlaybackManager? = null

        fun getInstance(context: Context): PlaybackManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PlaybackManager(context).also { INSTANCE = it }
            }
        }
    }

    /**
     * Plays a new song. If the song is already playing, it does nothing.
     */
    fun playSong(song: Music) {
        val player = controller ?: return

        val mediaItem = MediaItem.Builder()
            .setMediaId(song.id.toString())
            .setRequestMetadata(
                MediaItem.RequestMetadata.Builder()
                    .setMediaUri(song.uri.toUri())
                    .build()
            )
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .build()
            )
            .build()

        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
    }

    fun pause() = controller?.pause()
    
    fun togglePlayPause() {
        controller?.let {
            if (it.isPlaying) it.pause() else it.play()
        }
    }

    // Call this only when the app is fully closing
    fun release() {
        MediaController.releaseFuture(controllerFuture)
        INSTANCE = null
    }
}