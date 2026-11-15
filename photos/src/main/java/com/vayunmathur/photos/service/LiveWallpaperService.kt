package com.vayunmathur.photos.service

import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.service.wallpaper.WallpaperService
import android.view.Choreographer
import android.view.SurfaceHolder
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Live wallpaper that renders either a muted, looping video (via ExoPlayer) or an
 * animated GIF (via [AnimatedImageDrawable] drawn to the engine surface), always
 * center-cropped to fill the screen. The media URI and its type are chosen in the
 * photos viewer and persisted through [DataStoreUtils]; the engine reads them when
 * its surface is created.
 *
 * Runs in the app's own process, so it inherits the app's READ_MEDIA_* access and
 * can read the MediaStore content URI directly — no persistable URI grant needed.
 */
@UnstableApi
class LiveWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = LiveWallpaperEngine()

    private inner class LiveWallpaperEngine : Engine() {
        // Engine callbacks run on the main thread; keep our coroutines there too so
        // ExoPlayer and the Choreographer draw loop stay on the thread they require.
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        private var exoPlayer: ExoPlayer? = null

        private var gifDrawable: AnimatedImageDrawable? = null
        private var surfaceWidth = 0
        private var surfaceHeight = 0
        private var choreographerCallback: Choreographer.FrameCallback? = null
        private var drawing = false

        private var uri: Uri? = null
        private var isVideo = false

        // AnimatedImageDrawable needs a callback attached to run; we drive redraws
        // ourselves via Choreographer, so these are intentionally no-ops.
        private val drawableCallback = object : Drawable.Callback {
            override fun invalidateDrawable(who: Drawable) {}
            override fun scheduleDrawable(who: Drawable, what: Runnable, `when`: Long) {}
            override fun unscheduleDrawable(who: Drawable, what: Runnable) {}
        }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            scope.launch {
                val ds = DataStoreUtils.getInstance(applicationContext)
                val uriStr = ds.getStringAwait(KEY_WALLPAPER_URI) ?: return@launch
                isVideo = ds.getBooleanAwait(KEY_WALLPAPER_IS_VIDEO)
                uri = uriStr.toUri()
                if (isVideo) startVideo(holder) else startGif()
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            surfaceWidth = width
            surfaceHeight = height
            gifDrawable?.let { updateGifBounds(it) }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (isVideo) {
                exoPlayer?.playWhenReady = visible
            } else {
                if (visible) startDrawLoop() else stopDrawLoop()
            }
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            super.onSurfaceDestroyed(holder)
            releaseVideo()
            stopDrawLoop()
            gifDrawable = null
        }

        override fun onDestroy() {
            super.onDestroy()
            scope.cancel()
            releaseVideo()
            stopDrawLoop()
            gifDrawable = null
        }

        // --- Video ------------------------------------------------------------

        private fun startVideo(holder: SurfaceHolder) {
            val u = uri ?: return
            exoPlayer = ExoPlayer.Builder(applicationContext).build().apply {
                setMediaItem(MediaItem.fromUri(u))
                repeatMode = Player.REPEAT_MODE_ONE
                volume = 0f
                setVideoScalingMode(C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                setVideoSurface(holder.surface)
                prepare()
                playWhenReady = isVisible
            }
        }

        private fun releaseVideo() {
            exoPlayer?.release()
            exoPlayer = null
        }

        // --- GIF --------------------------------------------------------------

        private fun startGif() {
            val u = uri ?: return
            scope.launch {
                val d = withContext(Dispatchers.IO) {
                    runCatching {
                        ImageDecoder.decodeDrawable(
                            ImageDecoder.createSource(contentResolver, u)
                        ) as? AnimatedImageDrawable
                    }.getOrNull()
                } ?: return@launch
                d.repeatCount = AnimatedImageDrawable.REPEAT_INFINITE
                d.callback = drawableCallback
                gifDrawable = d
                updateGifBounds(d)
                if (isVisible) startDrawLoop()
            }
        }

        /** Center-crop the drawable to fill the surface (scale = max(sw/iw, sh/ih)). */
        private fun updateGifBounds(d: AnimatedImageDrawable) {
            val iw = d.intrinsicWidth
            val ih = d.intrinsicHeight
            val hasImage = iw > 0 && ih > 0
            val hasSurface = surfaceWidth > 0 && surfaceHeight > 0
            if (!hasImage || !hasSurface) return
            val scale = max(surfaceWidth.toFloat() / iw, surfaceHeight.toFloat() / ih)
            val dw = (iw * scale).toInt()
            val dh = (ih * scale).toInt()
            val left = (surfaceWidth - dw) / 2
            val top = (surfaceHeight - dh) / 2
            d.setBounds(left, top, left + dw, top + dh)
        }

        private fun startDrawLoop() {
            val d = gifDrawable ?: return
            if (drawing) return
            drawing = true
            if (!d.isRunning) d.start()
            val cb = choreographerCallback ?: object : Choreographer.FrameCallback {
                override fun doFrame(frameTimeNanos: Long) {
                    if (!drawing) return
                    drawGifFrame()
                    Choreographer.getInstance().postFrameCallback(this)
                }
            }.also { choreographerCallback = it }
            Choreographer.getInstance().postFrameCallback(cb)
        }

        private fun stopDrawLoop() {
            drawing = false
            choreographerCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
            gifDrawable?.takeIf { it.isRunning }?.stop()
        }

        private fun drawGifFrame() {
            val d = gifDrawable ?: return
            val canvas = surfaceHolder.lockCanvas() ?: return
            try {
                canvas.drawColor(Color.BLACK)
                d.draw(canvas)
            } finally {
                surfaceHolder.unlockCanvasAndPost(canvas)
            }
        }
    }

    /** Shared constants and helpers for LiveWallpaperService. */
    companion object {
        const val KEY_WALLPAPER_URI = "wallpaper_uri"
        const val KEY_WALLPAPER_IS_VIDEO = "wallpaper_is_video"
    }
}
