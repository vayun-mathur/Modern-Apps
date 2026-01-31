package com.vayunmathur.youpipe.ui

import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import okhttp3.OkHttpClient

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    companion object {
        const val EXTRA_AUDIO_URI = "extra_audio_uri"
    }

    override fun onCreate() {
        super.onCreate()

        val okHttpClient = OkHttpClient()
        val dataSourceFactory = OkHttpDataSource.Factory(okHttpClient)
            .setUserAgent("Mozilla/5.0 (Android 14; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0")

        // 1. Use delegation instead of inheritance
        val defaultMediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)

        val customMediaSourceFactory = object : MediaSource.Factory {
            override fun createMediaSource(mediaItem: MediaItem): MediaSource {
                // Check if we passed an audio URI in the tag (via onAddMediaItems)
                // or in the extras (fallback)
                val audioUriString = mediaItem.localConfiguration?.tag as? String
                    ?: mediaItem.mediaMetadata.extras?.getString(EXTRA_AUDIO_URI)

                return if (audioUriString != null) {
                    val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(mediaItem)
                    val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(audioUriString))

                    MergingMediaSource(videoSource, audioSource)
                } else {
                    // Delegate to the standard factory for normal items
                    defaultMediaSourceFactory.createMediaSource(mediaItem)
                }
            }

            // Delegate required interface methods to the default factory
            override fun setDrmSessionManagerProvider(drmSessionManagerProvider: androidx.media3.exoplayer.drm.DrmSessionManagerProvider) =
                apply { defaultMediaSourceFactory.setDrmSessionManagerProvider(drmSessionManagerProvider) }

            override fun setLoadErrorHandlingPolicy(loadErrorHandlingPolicy: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy) =
                apply { defaultMediaSourceFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy) }

            override fun getSupportedTypes(): IntArray = defaultMediaSourceFactory.supportedTypes
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(customMediaSourceFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        // 2. Set up the MediaSession Callback to ensure the Audio URI reaches the Factory
        val callback = object : MediaSession.Callback {
            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>
            ): ListenableFuture<MutableList<MediaItem>> {
                val updatedItems = mediaItems.map { item ->
                    val audioUri = item.mediaMetadata.extras?.getString(EXTRA_AUDIO_URI)
                    item.buildUpon()
                        .setTag(audioUri) // The 'tag' is preserved and readable by the Factory
                        .build()
                }.toMutableList()
                return Futures.immediateFuture(updatedItems)
            }
        }

        mediaSession = MediaSession.Builder(this, player)
            .setCallback(callback)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player?.playWhenReady == false || player?.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        // If the player is stopped or the activity has disconnected,
        // we stop the service from being a foreground service.
        if (session.player.playbackState == Player.STATE_IDLE ||
            session.player.playbackState == Player.STATE_ENDED) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        super.onUpdateNotification(session, startInForegroundRequired)
    }
}