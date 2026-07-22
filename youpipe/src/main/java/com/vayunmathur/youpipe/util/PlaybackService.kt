package com.vayunmathur.youpipe.util
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.SingleSampleMediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isAndroidStreamingUrl
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isIosStreamingUrl
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isVisionOsStreamingUrl
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getAndroidUserAgent
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getIosUserAgent
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getVisionOsUserAgent

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    companion object {
        const val EXTRA_AUDIO_URI = "extra_audio_uri"
    }

    override fun onCreate() {
        super.onCreate()

        val defaultUserAgent = "Mozilla/5.0 (Android 14; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0"

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                val userAgent = when {
                    isAndroidStreamingUrl(url) -> getAndroidUserAgent(null)
                    isIosStreamingUrl(url) -> getIosUserAgent(null)
                    isVisionOsStreamingUrl(url) -> getVisionOsUserAgent(null)
                    else -> defaultUserAgent
                }
                chain.proceed(
                    request.newBuilder()
                        .header("User-Agent", userAgent)
                        .build()
                )
            }
            .build()

        val okHttpDataSourceFactory = OkHttpDataSource.Factory(okHttpClient)

        val dataSourceFactory = DefaultDataSource.Factory(this, okHttpDataSourceFactory)

        // 1. Use delegation instead of inheritance
        val defaultMediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)

        val customMediaSourceFactory = object : MediaSource.Factory {
            @Suppress("DEPRECATION")
            override fun createMediaSource(mediaItem: MediaItem): MediaSource {
                // Check if we passed an audio URI in the tag (via onAddMediaItems)
                // or in the extras (fallback)
                val audioUriString = mediaItem.localConfiguration?.tag as? String
                    ?: mediaItem.mediaMetadata.extras?.getString(EXTRA_AUDIO_URI)

                val subtitleSources = mediaItem.localConfiguration?.subtitleConfigurations
                    ?.map { cfg ->
                        SingleSampleMediaSource.Factory(dataSourceFactory)
                            .setTreatLoadErrorsAsEndOfStream(true)
                            .createMediaSource(cfg, C.TIME_UNSET)
                    } ?: emptyList()

                return if (audioUriString != null) {
                    val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(mediaItem)
                    val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(audioUriString))

                    MergingMediaSource(videoSource, audioSource, *subtitleSources.toTypedArray())
                } else {
                    // Even without separate audio, we need to merge subtitles explicitly
                    // because default factory may not handle them reliably through MediaSession
                    val videoSource = defaultMediaSourceFactory.createMediaSource(mediaItem)
                    if (subtitleSources.isNotEmpty()) {
                        MergingMediaSource(videoSource, *subtitleSources.toTypedArray())
                    } else {
                        videoSource
                    }
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

        // Enable legacy text decoding for TTML/VTT/SRT subtitles via reflection
        // Media3 1.11+ disables legacy text decoding by default, but SingleSampleMediaSource requires it
        try {
            val textRendererClass = Class.forName("androidx.media3.exoplayer.text.TextRenderer")
            // Try to find and set any static flag that enables legacy decoding
            // If no public API, we'll rely on DefaultRenderersFactory properly configuring TextOutput
            android.util.Log.d("YouPipeSubs", "Attempting to enable legacy text decoding")
        } catch (e: Exception) {
            android.util.Log.w("YouPipeSubs", "Could not configure text renderer via reflection", e)
        }

        val renderersFactory = object : androidx.media3.exoplayer.DefaultRenderersFactory(this) {
            override fun buildTextRenderers(
                context: android.content.Context,
                output: androidx.media3.exoplayer.text.TextOutput,
                outputLooper: android.os.Looper,
                extensionRendererMode: Int,
                out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>
            ) {
                // In Media3 1.11+, TextRenderer requires explicit legacy decoding enabled for TTML/VTT/SRT
                super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
                // Enable legacy decoding on all text renderers via experimental API
                out.filterIsInstance<androidx.media3.exoplayer.text.TextRenderer>().forEach { textRenderer ->
                    try {
                        //noinspection ExperimentalApiUsageError
                        textRenderer.experimentalSetLegacyDecodingEnabled(true)
                        android.util.Log.d("YouPipeSubs", "Enabled legacy decoding on TextRenderer")
                    } catch (e: Exception) {
                        android.util.Log.w("YouPipeSubs", "Failed to enable legacy decoding", e)
                    }
                }
                // Fallback: ensure text renderer exists
                if (out.none { it.trackType == androidx.media3.common.C.TRACK_TYPE_TEXT }) {
                    val tr = androidx.media3.exoplayer.text.TextRenderer(output, outputLooper)
                    try {
                        //noinspection ExperimentalApiUsageError
                        tr.experimentalSetLegacyDecodingEnabled(true)
                    } catch (_: Exception) {}
                    out.add(tr)
                }
            }
        }.setEnableDecoderFallback(true)
            .setExtensionRendererMode(androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)

        val player = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
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
            player.stop() // Stop playback first
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        // Stop the player and release everything
        player?.let {
            it.pause()
            it.stop()
        }
        stopSelf() // This will trigger onDestroy() in the service
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        // If the player is stopped or the activity has disconnected,
        // we stop the service from being a foreground service.
        if (session.player.playbackState == Player.STATE_IDLE ||
            session.player.playbackState == Player.STATE_ENDED) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }
        super.onUpdateNotification(session, startInForegroundRequired)
    }
}