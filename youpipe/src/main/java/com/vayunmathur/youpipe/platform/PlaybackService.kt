package com.vayunmathur.youpipe.platform
import android.content.Intent
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
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
import com.vayunmathur.library.log.Log
import com.vayunmathur.youpipe.platform.CastAudioTap
import com.vayunmathur.youpipe.network.sabr.LocalDomPoTokenProvider
import com.vayunmathur.youpipe.network.sabr.SabrNgDashMediaSource
import com.vayunmathur.youpipe.network.sabr.SabrNgSessionStore
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isAndroidStreamingUrl
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isIosStreamingUrl
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.isVisionOsStreamingUrl
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getAndroidUserAgent
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getIosUserAgent
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper.getVisionOsUserAgent

/** Playback Service: a media session service implementation for playback service. */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    // The live playhead for SABR session pacing, read by each SABR source's pump thread.
    // @Volatile: written on main, read off-thread by every SABR pump. A plain var lets a
    // pump thread cache a stale null forever, which used to surface as playhead=0 and meter
    // the session to death.
    @Volatile
    private var servicePlayer: ExoPlayer? = null

    private fun buildDataSourceFactory(
        defaultUserAgent: String,
    ): DefaultDataSource.Factory {
        // Prefer HttpURLConnection-backed default datasource; SABR path uses library:network.
        // Inject per-request User-Agent via ResolvingDataSource so range/redirect code still inherits it.
        val httpFactory = DefaultHttpDataSource.Factory().apply {
            setUserAgent(defaultUserAgent)
            setConnectTimeoutMs(HTTP_TIMEOUT_MS)
            setReadTimeoutMs(HTTP_TIMEOUT_MS)
            setAllowCrossProtocolRedirects(true)
        }

        val resolvingFactory = androidx.media3.datasource.ResolvingDataSource.Factory(httpFactory) { dataSpec ->
            val url = dataSpec.uri.toString()
            val userAgent = when {
                isAndroidStreamingUrl(url) -> getAndroidUserAgent(null)
                isIosStreamingUrl(url) -> getIosUserAgent(null)
                isVisionOsStreamingUrl(url) -> getVisionOsUserAgent(null)
                else -> defaultUserAgent
            }
            val headers = buildMap {
                put("User-Agent", userAgent)
                dataSpec.httpRequestHeaders.forEach { (k, v) ->
                    if (!k.equals("User-Agent", ignoreCase = true)) put(k, v)
                }
            }
            dataSpec.withRequestHeaders(headers)
        }

        return DefaultDataSource.Factory(this, resolvingFactory)
    }

    private fun buildCustomMediaSourceFactory(
        defaultMediaSourceFactory: DefaultMediaSourceFactory,
        dataSourceFactory: DefaultDataSource.Factory,
        sabrPlayheadMsProvider: () -> Long?,
    ): MediaSource.Factory = object : MediaSource.Factory {
        @Suppress("DEPRECATION")
        override fun createMediaSource(mediaItem: MediaItem): MediaSource {
            val subtitleSources = mediaItem.localConfiguration?.subtitleConfigurations
                ?.map { cfg ->
                    SingleSampleMediaSource.Factory(dataSourceFactory)
                        .setTreatLoadErrorsAsEndOfStream(true)
                        .createMediaSource(cfg, C.TIME_UNSET)
                } ?: emptyList()

            // SABR: the sabr:// video URI carries both audio and video via one MediaSource.
            val vUri = mediaItem.localConfiguration?.uri
            if (vUri != null && vUri.scheme == "sabr") {
                return createSabrSource(mediaItem, vUri.toString(), subtitleSources, sabrPlayheadMsProvider)
            }

            val audioUriString = mediaItem.localConfiguration?.tag as? String
                ?: mediaItem.mediaMetadata.extras?.getString(EXTRA_AUDIO_URI)

            return if (audioUriString != null) {
                val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(mediaItem)
                val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(audioUriString))

                MergingMediaSource(videoSource, audioSource, *subtitleSources.toTypedArray())
            } else {
                val videoSource = defaultMediaSourceFactory.createMediaSource(mediaItem)
                if (subtitleSources.isNotEmpty()) {
                    MergingMediaSource(videoSource, *subtitleSources.toTypedArray())
                } else {
                    videoSource
                }
            }
        }

        override fun setDrmSessionManagerProvider(
            drmSessionManagerProvider: androidx.media3.exoplayer.drm.DrmSessionManagerProvider,
        ) = apply { defaultMediaSourceFactory.setDrmSessionManagerProvider(drmSessionManagerProvider) }

        override fun setLoadErrorHandlingPolicy(
            loadErrorHandlingPolicy: androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy,
        ) = apply { defaultMediaSourceFactory.setLoadErrorHandlingPolicy(loadErrorHandlingPolicy) }

        override fun getSupportedTypes(): IntArray = defaultMediaSourceFactory.supportedTypes
    }

    private fun createSabrSource(
        mediaItem: MediaItem,
        videoUri: String,
        subtitleSources: List<MediaSource>,
        sabrPlayheadMsProvider: () -> Long?,
    ): MediaSource {
        val vUri = videoUri.toUri()
        val videoId = vUri.host
            ?: throw IllegalArgumentException("SABR media item is missing a video id: $videoUri")
        val vitag = vUri.getQueryParameter("v")?.toIntOrNull() ?: 0
        val extras = mediaItem.mediaMetadata.extras
        val rawAudioUri = mediaItem.localConfiguration?.tag as? String
            ?: extras?.getString(EXTRA_AUDIO_URI)
        var audioItag = extras?.getInt(EXTRA_AUDIO_ITAG, 0) ?: 0
        val audioTrackIdOverride: String? = extras?.getString(EXTRA_AUDIO_TRACK_ID)
        if (audioItag == 0 && rawAudioUri != null) {
            try {
                val aUri = rawAudioUri.toUri()
                if (aUri.scheme == "sabr") {
                    audioItag = aUri.getQueryParameter("a")?.toIntOrNull() ?: 0
                }
            } catch (_: Exception) {}
        }
        val info = SabrNgSessionStore.getExtractorInfo(videoId)
            ?: throw IllegalStateException("No SABR info available for $videoId")
        val provider = LocalDomPoTokenProvider.shared(applicationContext)
        val localization = Localization("en", "US")
        // Defer PO-token minting + init-segment fetch to the SABR source's background
        // open path. Doing this on the main thread (via createSourceSpec here) blocked
        // setMediaItems for several seconds and triggered an ANR. The token is still
        // minted lazily at play time (first source open), preserving the known-working
        // ordering that avoids the SABR bootstrap 403 regression.
        val vAudioItag = audioItag
        val vAudioTrackId = audioTrackIdOverride
        val tokenMinter: (Boolean) -> ByteArray? = { force ->
            provider.getPoTokenBytes(videoId, force)
        }
        val specSupplier: () -> com.vayunmathur.youpipe.network.sabr.SabrNgSourceSpec = {
            val poToken = tokenMinter(false)
            SabrNgSessionStore.createSourceSpec(
                videoId, vitag, vAudioItag, vAudioTrackId, info, poToken,
                localization, tokenMinter
            )
        }
        val sabrSource = SabrNgDashMediaSource(
            this, mediaItem, videoId, specSupplier,
            tokenMinter, sabrPlayheadMsProvider
        )
        return if (subtitleSources.isNotEmpty()) {
            MergingMediaSource(sabrSource, *subtitleSources.toTypedArray())
        } else {
            sabrSource
        }
    }

    private fun buildRenderersFactory(): androidx.media3.exoplayer.DefaultRenderersFactory =
        object : androidx.media3.exoplayer.DefaultRenderersFactory(this) {
            /**
             * Insert the cast PCM tap into the audio sink's processor chain.
             *
             * This is the hook the cast path needs: `CastAudioTap` sees the decoded samples on their
             * way to the speaker, so casting does not have to re-decode anything. It costs nothing
             * when no cast session is open - the tap has no output stream and drops the buffer - and
             * local muting still works, because `volume` is applied in the sink *after* the chain, so
             * the tap sees full-scale PCM either way.
             */
            @Suppress("DEPRECATION")
            override fun buildAudioSink(
                context: android.content.Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): androidx.media3.exoplayer.audio.AudioSink =
                androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(
                        arrayOf(
                            androidx.media3.exoplayer.audio.TeeAudioProcessor(CastAudioTap),
                        )
                    )
                    .build()

            @OptIn(ExperimentalApi::class)
            @Suppress("DEPRECATION")
            override fun buildTextRenderers(
                context: android.content.Context,
                output: androidx.media3.exoplayer.text.TextOutput,
                outputLooper: android.os.Looper,
                extensionRendererMode: Int,
                out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>
            ) {
                super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
                out.filterIsInstance<androidx.media3.exoplayer.text.TextRenderer>().forEach { textRenderer ->
                    try {
                        textRenderer.experimentalSetLegacyDecodingEnabled(true)
                    } catch (_: Exception) {}
                }
                if (out.none { it.trackType == androidx.media3.common.C.TRACK_TYPE_TEXT }) {
                    val tr = androidx.media3.exoplayer.text.TextRenderer(output, outputLooper)
                    try {
                        tr.experimentalSetLegacyDecodingEnabled(true)
                    } catch (_: Exception) {}
                    out.add(tr)
                }
            }
        }.setEnableDecoderFallback(true)
            .setExtensionRendererMode(
                androidx.media3.exoplayer.DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
            )

    /** Shared constants and helpers for PlaybackService. */
    companion object {
        const val EXTRA_AUDIO_URI = "extra_audio_uri"
        const val EXTRA_AUDIO_TRACK_ID = "extra_audio_track_id"
        const val EXTRA_AUDIO_ITAG = "extra_audio_itag"
        private const val HTTP_TIMEOUT_MS = 30_000
    }

    override fun onCreate() {
        super.onCreate()

        val defaultUserAgent = "Mozilla/5.0 (Android 14; Mobile; rv:128.0) Gecko/128.0 Firefox/128.0"
        val dataSourceFactory = buildDataSourceFactory(defaultUserAgent)

        // Kept nullable (never coalesced to 0) so a missing player falls back to
        // segment-tracked progress in SabrNgSession instead of shadowing it with 0.
        val sabrPlayheadMsProvider: () -> Long? = {
            try {
                val position = servicePlayer?.currentPosition
                if (position == null || position == C.TIME_UNSET) null
                else position.coerceAtLeast(0L)
            } catch (_: Exception) {
                null
            }
        }

        // 1. Use delegation instead of inheritance
        val defaultMediaSourceFactory = DefaultMediaSourceFactory(this)
            .setDataSourceFactory(dataSourceFactory)

        val customMediaSourceFactory =
            buildCustomMediaSourceFactory(defaultMediaSourceFactory, dataSourceFactory, sabrPlayheadMsProvider)

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        try {
            Log.dev("YouPipeSubs", "Attempting to enable legacy text decoding")
        } catch (_: Exception) {}

        val renderersFactory = buildRenderersFactory()

        val player = ExoPlayer.Builder(this)
            .setRenderersFactory(renderersFactory)
            .setMediaSourceFactory(customMediaSourceFactory)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
        servicePlayer = player

        val callback = object : MediaSession.Callback {
            override fun onAddMediaItems(
                mediaSession: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: MutableList<MediaItem>
            ): ListenableFuture<MutableList<MediaItem>> {
                val updatedItems = mediaItems.map { item ->
                    val extras = item.mediaMetadata.extras
                    val audioUri = extras?.getString(EXTRA_AUDIO_URI)
                    item.buildUpon()
                        .setTag(audioUri)
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

    // #810: crash after exiting fullscreen.
    // Exiting fullscreen (orientation restore, PiP auto-enter, or leaving the video) can
    // leave a MediaSessionService start intent in flight while the activity is already
    // backgrounded. On Android 12+ the startForeground() inside Media3's onStartCommand ->
    // stopSelfSafely then throws ForegroundServiceStartNotAllowedException (an
    // IllegalStateException) and ActivityThread takes down the whole process. Drop the
    // start instead of crashing; the session keeps its state for the next foreground start.
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return try {
            super.onStartCommand(intent, flags, startId)
        } catch (e: IllegalStateException) {
            Log.error("YouPipePlayer", "PlaybackService start suppressed in background", e)
            try {
                stopSelf(startId)
            } catch (_: Exception) {
            }
            START_NOT_STICKY
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.stop()
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player?.isPlaying == true) {
            try {
                player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                    .build()
            } catch (_: Exception) {}
            return
        }
        player?.let {
            it.pause()
            it.stop()
        }
        stopSelf()
    }

    override fun onUpdateNotification(session: MediaSession, startInForegroundRequired: Boolean) {
        if (session.player.playbackState == Player.STATE_IDLE ||
            session.player.playbackState == Player.STATE_ENDED) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            return
        }
        super.onUpdateNotification(session, startInForegroundRequired)
    }
}
