package com.vayunmathur.web.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.vayunmathur.library.log.Log
import androidx.compose.runtime.mutableStateMapOf
import com.vayunmathur.web.platform.BrowserUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

private const val TAG = "FaviconStore"
private const val DIR_NAME = "favicons"
private const val EXT = ".webp"
private const val QUALITY = 90

/** Long edge of a stored icon, in pixels. Sites serve up to 256px; nothing here renders that big. */
private const val ICON_MAX_DIM = 64

/**
 * Site icons, keyed by host.
 *
 * Host rather than tab id so the tab grid and the new-tab recents list read the same store —
 * a recents entry is usually not an open tab and has no tab id to look up.
 *
 * Icons come only from `WebChromeClient.onReceivedIcon` on pages the user actually visited.
 * Deliberately no `/favicon.ico` fetch and no third-party favicon service: either would turn
 * rendering a list of hosts into a network request per host, and the recents list would then
 * announce browsing history the user never re-opened.
 *
 * Private tabs and incognito windows are memory-only, mirroring `persistTabsSync`. Persisted
 * icons live in `filesDir`, not `cacheDir`, so they survive a cache eviction.
 */
object FaviconStore {

    /** Snapshot state so an icon arriving mid-session recomposes whoever asked for it. */
    private val cache = mutableStateMapOf<String, Bitmap?>()

    private val loading = ConcurrentHashMap.newKeySet<String>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * The icon for [host] ("news.ycombinator.com" — no scheme, no port), or null if the user
     * has never loaded a page there. Compose-observable; a miss starts a disk read that
     * recomposes the caller if it finds something. Main thread only.
     */
    fun forHost(host: String): Bitmap? {
        val key = host.lowercase()
        if (key.isBlank()) return null
        if (cache.containsKey(key)) return cache[key]
        loadFromDisk(key)
        return null
    }

    /** [forHost] for callers holding a full URL. */
    fun forUrl(url: String): Bitmap? = forHost(BrowserUtils.hostFromUrl(url))

    /**
     * Records the icon a page reported. The bitmap is published to memory immediately so the
     * current session sees it, then downscaled and written off the main thread.
     */
    fun put(url: String, icon: Bitmap, isPrivate: Boolean) {
        if (!BrowserUtils.isWebUrl(url)) return
        val host = BrowserUtils.hostFromUrl(url).lowercase()
        if (host.isBlank()) return
        cache[host] = icon
        scope.launch {
            val scaled = runCatching { downscale(icon) }.getOrDefault(icon)
            if (scaled !== icon) withContext(Dispatchers.Main) { cache[host] = scaled }
            if (isPrivate) return@launch
            val file = fileFor(host) ?: return@launch
            runCatching {
                file.parentFile?.mkdirs()
                file.outputStream().use { scaled.compress(Bitmap.CompressFormat.WEBP_LOSSY, QUALITY, it) }
            }.onFailure { Log.status(TAG, "favicon write failed", it) }
        }
    }

    private fun loadFromDisk(host: String) {
        // Bail before recording anything if there is no usable file path: caching a negative
        // here would make the entry permanently absent, even after [init] later supplies a
        // context.
        val file = fileFor(host) ?: return
        if (!loading.add(host)) return
        scope.launch {
            val bitmap = runCatching {
                file.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.absolutePath) }
            }.getOrNull()
            withContext(Dispatchers.Main) {
                // A page may have reported its icon while the read was in flight; that is
                // newer than anything on disk, so it wins.
                if (!cache.containsKey(host)) cache[host] = bitmap
                loading.remove(host)
            }
        }
    }

    private fun downscale(icon: Bitmap): Bitmap {
        val longEdge = max(icon.width, icon.height)
        if (longEdge <= ICON_MAX_DIM || longEdge == 0) return icon
        val scale = ICON_MAX_DIM.toFloat() / longEdge
        return Bitmap.createScaledBitmap(
            icon,
            max(1, (icon.width * scale).toInt()),
            max(1, (icon.height * scale).toInt()),
            true,
        )
    }

    /** Hosts can be punycode, very long, or contain characters a filename cannot. */
    private fun fileFor(host: String): File? {
        val context = appContext ?: return null
        val digest = MessageDigest.getInstance("SHA-256").digest(host.toByteArray())
        val name = digest.joinToString("") { "%02x".format(it) }
        return File(File(context.filesDir, DIR_NAME), name + EXT)
    }
}
