package com.vayunmathur.launcher.platform

import android.app.Application
import android.graphics.drawable.Drawable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Per-process banner/icon cache: package-change rebuilds re-query the package list but must
 * not re-rasterise art the grid already holds. Drawables are immutable snapshots, so sharing
 * them across rebuilds is safe.
 */
class TvArtCache(private val app: Application) {
    private val banners = mutableMapOf<String, Drawable?>()
    private val icons = mutableMapOf<String, Drawable?>()

    suspend fun banner(pkg: String): Drawable? = withContext(Dispatchers.IO) {
        synchronized(banners) { banners[pkg] } ?: loadBanner(pkg)
    }

    suspend fun icon(pkg: String): Drawable? = withContext(Dispatchers.IO) {
        synchronized(icons) { icons[pkg] } ?: loadIcon(pkg)
    }

    private fun loadBanner(pkg: String): Drawable? {
        val banner = TvApps.banner(app.packageManager, pkg)
        synchronized(banners) { banners[pkg] = banner }
        return banner
    }

    private fun loadIcon(pkg: String): Drawable? {
        val icon = TvApps.appIcon(app.packageManager, pkg)
        synchronized(icons) { icons[pkg] = icon }
        return icon
    }

    fun drop(pkg: String) {
        synchronized(banners) { banners.remove(pkg) }
        synchronized(icons) { icons.remove(pkg) }
    }
}

