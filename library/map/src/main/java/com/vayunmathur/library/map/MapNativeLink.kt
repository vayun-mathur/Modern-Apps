package com.vayunmathur.library.map

import android.view.Surface
import com.vayunmathur.library.log.Log

/**
 * The link from one [SurfaceMapRenderer] to its native renderer on [MapRenderThread].
 *
 * Owns the two halves of "is there a native renderer": the main thread's view ([attached],
 * [generation]) and the render thread's ([nativeHandle], [region]), and is the only code that
 * crosses between them. Every native call goes through [post], [query] or one of the
 * lifecycle tasks here, so all of them run on the render thread in submission order — the
 * native side's single-threaded contract — and every result that comes back is checked
 * against [generation] so a frame or create that outlived its surface is dropped.
 *
 * Split from SurfaceMapRenderer.kt (FileLength) along the thread boundary.
 */
internal class MapNativeLink {

    /**
     * The native handle, **render thread only**: written by the create/destroy tasks and read
     * by every task after them. Never read on the main thread, which is what keeps the native
     * single-threaded contract without a lock.
     */
    private var nativeHandle = 0L

    /** The selected region mask as the render thread tracks it (render thread only). */
    private val region = NativeRegionMask()

    /**
     * Whether a native renderer exists or is being created, on the main thread's view: set by
     * [create], cleared by [destroy] and by a failed create ([createFailed]). Native calls are
     * gated on this rather than on a handle, so a setter issued between [create] and the
     * create finishing is queued behind it, not dropped.
     */
    var attached = false
        private set

    /**
     * Bumped on every create and destroy (main thread), so a result that crosses back after
     * the surface it belonged to is gone is recognised and ignored.
     */
    var generation = 0
        private set

    /**
     * Run [block] with the live native handle on the render thread, after every call issued
     * before it. Dropped when not [attached]; skipped on the render thread after a failed create.
     */
    fun post(block: (Long) -> Unit) {
        if (!attached) return
        MapRenderThread.post {
            val h = nativeHandle
            if (h != 0L) block(h)
        }
    }

    /** As [post], but waits for the answer, at most [timeoutMillis]; [fallback] otherwise. */
    fun <T> query(timeoutMillis: Long, fallback: T, block: (Long) -> T): T {
        if (!attached) return fallback
        return MapRenderThread.call(timeoutMillis, fallback) {
            val h = nativeHandle
            if (h != 0L) block(h) else fallback
        }
    }

    /**
     * Queue `MapNative.create` on [surface]; [onCreated] gets the handle (0 on failure) back on
     * the main thread, unless a [destroy] overtook it. [cacheDir] and [localPath] are resolved
     * on the render thread because both touch the disk.
     */
    @Suppress("LongParameterList")
    fun create(
        surface: Surface,
        cacheDir: () -> String,
        localPath: () -> String?,
        widthPx: Int,
        heightPx: Int,
        dark: Boolean,
        muted: Boolean,
        onCreated: (Long) -> Unit,
    ) {
        attached = true
        val created = ++generation
        MapRenderThread.post {
            // A throw (an unlinked symbol, a disk error resolving the paths) is a failed start,
            // not a lost answer: without a reply the loop would keep dispatching empty frames.
            val h = try {
                MapNative.create(surface, cacheDir(), localPath(), widthPx, heightPx, dark, muted)
            } catch (t: Throwable) {
                Log.error("MapNativeLink", "native create threw", t)
                0L
            }
            nativeHandle = h
            MapRenderThread.toMain { if (created == generation) onCreated(h) }
        }
    }

    /** The create answered 0: nothing will draw, so stop queueing native calls. */
    fun createFailed() {
        attached = false
    }

    /**
     * Destroy the native renderer on the render thread and wait for it, at most [waitMillis].
     * Past the bound it returns anyway: the native side holds its own reference on the window,
     * so a caller releasing its `Surface` then can only make the late teardown's last frame
     * fail, never crash — while an unbounded wait could hold the main thread hostage.
     */
    fun destroy(waitMillis: Long) {
        if (!attached) return
        attached = false
        generation++
        MapRenderThread.call(waitMillis, Unit) {
            val h = nativeHandle
            nativeHandle = 0L
            region.reset()
            if (h != 0L) MapNative.destroy(h)
        }
    }

    /** Select the region mask on the render thread (see [NativeRegionMask]). */
    fun selectRegion(regionId: Long?) = post { h -> region.select(h, regionId) }

    /**
     * Draw one frame on the render thread from a snapshot read on the main thread; [onDone]
     * gets the result back on the main thread, unless a [destroy] overtook it.
     */
    fun frame(
        snapshot: FrameSnapshot,
        density: Float,
        frameTimeNanos: Long,
        pivotWanted: Boolean,
        onDone: (FrameResult) -> Unit,
    ) {
        val issued = generation
        MapRenderThread.post {
            val h = nativeHandle
            val result = if (h == 0L) FrameResult.NONE else drawFrame(h, snapshot, density, frameTimeNanos, pivotWanted, region)
            MapRenderThread.toMain { if (issued == generation) onDone(result) }
        }
    }

    /** One frame drawn synchronously, for a host driving its own loop; bounded by [timeoutMillis]. */
    fun frameNow(
        snapshot: FrameSnapshot,
        density: Float,
        frameTimeNanos: Long,
        pivotWanted: Boolean,
        timeoutMillis: Long,
    ): FrameResult = query(timeoutMillis, FrameResult.NONE) { h ->
        drawFrame(h, snapshot, density, frameTimeNanos, pivotWanted, region)
    }

    /** The native per-step frame times, delivered on the main thread; empty with no renderer. */
    fun stepTimes(onResult: (LongArray) -> Unit) {
        if (!attached) {
            onResult(LongArray(0))
            return
        }
        MapRenderThread.post {
            val h = nativeHandle
            val times = if (h != 0L) MapNative.lastFrameStepTimesNanos(h) else LongArray(0)
            MapRenderThread.toMain { onResult(times) }
        }
    }
}

/**
 * The selected region mask as the render thread sees it: the id to mask and whether it has
 * been matched to resident tiles yet.
 *
 * The match needs the region's tiles to be resident, and a selection usually arrives before
 * they are — tapping a city label recentres the map, so the tiles under the new camera are
 * still in flight. So an unresolved id is retried after every frame until it lands rather than
 * being dropped. Owned by the render thread, so the retry runs right after the frame that may
 * have landed the tiles, with no main-thread hop.
 */
internal class NativeRegionMask {
    private var id: Long? = null

    /** True while a nonzero id is selected and no resident tile carries it yet. */
    var pending = false
        private set

    /** Select [regionId] (`null`/`0` clears) and try to apply it at once. */
    fun select(handle: Long, regionId: Long?) {
        id = regionId
        pending = true
        apply(handle)
    }

    /** Apply the selected mask; leaves [pending] set while its tiles have not landed. */
    fun apply(handle: Long) {
        val selected = id
        if (selected == null || selected == 0L) {
            MapNative.clearRegionMask(handle)
            pending = false
            return
        }
        // A nonzero answer once a resident tile carries the id; `0` means not yet.
        pending = MapNative.setRegionMask(handle, selected) == 0L
    }

    /** Forget the selection, for a renderer that has been destroyed. */
    fun reset() {
        id = null
        pending = false
    }
}
