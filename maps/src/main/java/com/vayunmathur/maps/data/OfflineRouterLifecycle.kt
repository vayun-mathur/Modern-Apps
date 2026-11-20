package com.vayunmathur.maps.data
import com.vayunmathur.maps.util.OfflineRouter

import android.content.Context
import com.vayunmathur.library.log.Log

/**
 * Lifecycle (init state, base path, reload) for [OfflineRouter].
 *
 * Split out so `OfflineRouter` stays under the function cap. Owns the
 * initialized flag and base path; the JNI `init` itself stays on
 * [OfflineRouter] (native code resolves it by class and member name) and is
 * reached through its `internal` invoker. Same package, so no API changes
 * for any other caller.
 */
internal object OfflineRouterLifecycle {
    internal var isInitialized = false

    /**
     * Initialized base path for the transit helpers in [OfflineRouterTransit].
     * Null when [initialize] has not run yet or found no external files dir.
     */
    fun transitBase(context: Context): String? {
        if (!isInitialized) initialize(context)
        return OfflineRouter.basePath
    }

    @Synchronized
    fun initialize(context: Context) {
        if (isInitialized) return
        if (!com.vayunmathur.maps.platform.OfflineRouterNative.available) {
            isInitialized = false
            return
        }
        val path = context.getExternalFilesDir(null)?.absolutePath ?: return
        OfflineRouter.basePath = path
        Log.debug("OfflineRouter", "Initializing with path: $path")

        isInitialized = OfflineRouter.initGraph(path)
        Log.debug("OfflineRouter", "Initialization result: $isInitialized")
        if (isInitialized) {
            // Explicit, lazy tile-server start: only once the graph is usable.
            OfflineRouterTraffic.ensureTileServerStarted()
        }
    }

    /**
     * Force a re-load of the routing graph from disk. Call after the single
     * global routing graph (P16) finishes downloading so the freshly downloaded
     * nodes.bin/edges.bin/… replace whatever was (or wasn't) loaded at startup.
     * Re-init is safe: the Rust side atomically swaps the graph behind its lock.
     */
    @Synchronized
    fun reload(context: Context) {
        isInitialized = false
        OfflineRouterTransit.onReload()
        OfflineRouterTraffic.onReload()
        initialize(context)
    }
}
