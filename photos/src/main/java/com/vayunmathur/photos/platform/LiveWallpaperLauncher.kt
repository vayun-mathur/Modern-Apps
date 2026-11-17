package com.vayunmathur.photos.platform
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi

import android.app.WallpaperManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.photos.R
import com.vayunmathur.photos.service.LiveWallpaperService

/**
 * Persists the chosen media as the live wallpaper source and hands off to the system
 * live-wallpaper preview, where the user taps the final "Set wallpaper" button.
 * Android does not allow a third-party app to silently apply an animated wallpaper.
 */
object LiveWallpaperLauncher {

    @OptIn(UnstableApi::class)
    suspend fun apply(context: Context, uri: String, isVideo: Boolean) {
        val ds = DataStoreUtils.getInstance(context.applicationContext)
        ds.setString(LiveWallpaperService.KEY_WALLPAPER_URI, uri)
        ds.setBoolean(LiveWallpaperService.KEY_WALLPAPER_IS_VIDEO, isVideo)

        val component = ComponentName(context, LiveWallpaperService::class.java)
        val direct = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
            putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Attempt to launch directly rather than pre-checking with resolveActivity:
        // package-visibility rules make resolveActivity return null for the system
        // wallpaper picker even when it exists, so a pre-check falsely reports failure.
        try {
            context.startActivity(direct)
            return
        } catch (_: ActivityNotFoundException) {
        }

        // Some devices don't honour the direct component intent; fall back to the
        // generic live-wallpaper chooser.
        val chooser = Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            context.startActivity(chooser)
            return
        } catch (_: ActivityNotFoundException) {
        }

        AppMessages.show(context.getString(R.string.live_wallpaper_unavailable))
    }
}
