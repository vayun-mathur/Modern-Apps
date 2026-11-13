package com.vayunmathur.library.map

import android.content.ComponentCallbacks2
import android.content.Context
import com.vayunmathur.library.log.Log
import android.view.Choreographer
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpRect
import java.io.File

/**
 * Draws the vector basemap into an arbitrary [Surface].
 *
 * This is the public, Compose-free boundary of the renderer: [MapNative] stays internal and
 * nothing here knows about `TextureView`, `SurfaceView` or Android Auto. Anything that can
 * produce a [Surface] and a pixel size can host the map — [VulkanMapSurface] wraps this for
 * Compose, and a car app can hand it the `Surface` out of `SurfaceContainer`.
 *
 * ## Threading
 *
 * **Every method on this class, and every property set on it, must be called on the main
 * thread**, including construction. The class itself has no lock: its state, the on-demand
 * frame loop on the main thread's [Choreographer] and the camera read are all main-thread.
 *
 * The *native* renderer, though, never runs on the main thread. Every [MapNative] call — the
 * frame, `create`/`destroy`, every setter, picks — is handed to [MapRenderThread] in call order,
 * so the main thread only decides when to draw and what camera to draw from, and is never
 * blocked by a frame, a GPU wait or a swapchain rebuild. Results that feed main-thread state
 * (the render state, the tilt pivot, "draw again when") come back through a main-thread post.
 * Android Auto's `SurfaceCallback` is dispatched on the main thread, so the car path satisfies
 * this without doing anything.
 *
 * ## Lifecycle
 *
 * ```kotlin
 * val renderer = SurfaceMapRenderer(context, density = dpi / 160f)
 * renderer.setPalette(dark = true, muted = false)   // fine before the surface exists
 * renderer.attachSurface(surface, widthPx, heightPx)
 * renderer.camera = CameraPosition(GeoPoint(lon, lat), zoom = 14.0)
 * renderer.setRoute(routePoints)                    // fine before the surface exists too
 * renderer.start()
 * // …
 * renderer.stop()
 * renderer.detachSurface()   // the caller still owns and releases `surface`
 * renderer.destroy()
 * ```
 *
 * The surface is deliberately not owned here: [detachSurface] destroys the native renderer
 * (which waits for the GPU to go idle and drops the `ANativeWindow`) but never calls
 * [Surface.release]. Whoever created the `Surface` releases it, after [detachSurface] returns.
 *
 * ## State set before the surface exists
 *
 * [MapNative.create] deliberately takes no layer, puck or connectivity arguments, so
 * everything set through [setPalette], [setLayers], [setOnline], [setUserPuck],
 * [setRoute] and [setRegionMask] is remembered and replayed into the native renderer
 * right after it is created, **before the first frame** — the render thread runs them in
 * order behind `create`. Without that the first resident set is tessellated with the wrong
 * layers and immediately thrown away, the puck is missing until the next fix, the route is
 * missing until the host happens to set it again, and a surface created while offline stays
 * pinned to cache. Setting any of them while a surface is attached applies immediately.
 *
 * @param context any [Context]; only the application context is retained.
 * @param density pixels per dp for [surface][attachSurface]'s display — `dpi / 160f` on
 *   Android Auto, `LocalDensity.current.density` in Compose. The renderer scales line
 *   widths, icons and text with it, so a wrong value gives a legible-but-wrong map rather
 *   than a visible failure.
 * @param onFrame called on the main thread after each frame that was actually presented.
 *
 * Public setters mirror the render-state surface one-for-one (palette, layers, puck,
 * markers, vehicles, route, rail, traffic, region, connectivity, Moon textures); the
 * route/traffic/picking halves live as extensions in SurfaceMapOverlays.kt and the
 * render-thread frame in SurfaceMapFrame.kt, so this file holds only frame-loop,
 * surface-lifecycle and attach-replay logic.
 */
@Suppress("TooManyFunctions")
class SurfaceMapRenderer(
    context: Context,
    private val density: Float,
    private val onFrame: () -> Unit = {},
) {

    /**
     * Convenience for a host whose surface already exists at construction time: identical to
     * building the renderer and immediately calling [attachSurface].
     *
     * The frame loop is *not* started; call [start].
     */
    constructor(
        context: Context,
        surface: Surface,
        widthPx: Int,
        heightPx: Int,
        density: Float,
        onFrame: () -> Unit = {},
    ) : this(context, density, onFrame) {
        attachSurface(surface, widthPx, heightPx)
    }

    private val appContext = context.applicationContext

    /**
     * The native handle as last reported to the main thread: nonzero once `create` succeeded,
     * zero before, after a failure and after [detachSurface]. Informational on this side;
     * native calls go through [link], which owns the render thread's own copy.
     */
    internal var handle = 0L
        private set

    /** The native renderer on [MapRenderThread]: every native call goes through it. */
    private val link = MapNativeLink()

    /** Whether a native renderer exists or is being created (see [MapNativeLink.attached]). */
    private val attached: Boolean get() = link.attached

    /** A frame is on the render thread; the loop does not queue a second behind it. */
    private var frameInFlight = false

    /** A vsync arrived while [frameInFlight]: draw again the moment the current frame lands. */
    private var frameOwed = false

    private var widthPx = 0
    private var heightPx = 0

    /**
     * One callback for the life of the renderer, posted and re-posted rather than rebuilt.
     * [posted] carries the "is it installed" state that the identity of a per-run callback
     * object used to.
     */
    private val frameCallback = Choreographer.FrameCallback { frameTimeNanos -> onVsync(frameTimeNanos) }
    private var posted = false

    /** Whether the posted callback is scheduled for later rather than the next vsync. */
    private var postedDelayed = false

    /**
     * [System.nanoTime] when something last changed what the next frame would draw.
     *
     * The loop keeps drawing until [IDLE_GRACE_NANOS] after this, rather than stopping on the
     * frame after the last change — see [stillChanging].
     */
    private var lastChangeNanos = System.nanoTime()

    /**
     * The camera and viewport the last frame was actually drawn from, so a change that reaches
     * the renderer without passing through a setter is still noticed. The Compose path *pulls*
     * its camera out of [composeCamera] when a frame is dispatched and nothing tells us when a
     * gesture or animation moves it, so this is the backstop under the push channel
     * `VulkanMapSurface` installs.
     */
    private var lastFrame: FrameSnapshot? = null

    /**
     * Where the map is looking, and which way is up. Read once per frame on the main
     * thread, so a host may write it as often as it likes — a camera animation running at
     * 60 Hz costs the assignment plus an [invalidate], which is a timestamp and an
     * already-posted check.
     *
     * [CameraPosition.bearing] rotates the map for heading-up navigation. Usable through this
     * property and through a [CameraState] on the Compose path: [Projection] now carries the
     * bearing, so overlays rotate with the basemap on both paths.
     *
     * Ignored while [composeCamera] is set, which is the Compose path only.
     */
    var camera: CameraPosition = CameraPosition()
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Compose reads its camera out of a [CameraState] when each frame is dispatched, so that a
     * gesture or animation lands in the very next frame rather than a snapshot-propagation
     * later. Setting this makes the loop take both the camera and the dp viewport from that
     * state (skipping any frame whose viewport is not measured yet) instead of from [camera]
     * and the attached surface size.
     *
     * Because this is a *pull*, a host that sets it must also push: nothing here observes the
     * [CameraState], so a gesture or animation moving it while the loop is idle would go
     * unnoticed. [VulkanMapSurface] calls [invalidate] from a `snapshotFlow` over the camera
     * for exactly that reason.
     */
    internal var composeCamera: CameraState? = null
        set(value) {
            field = value
            invalidate()
        }

    /**
     * Whether this surface is drawing, or why it is not.
     *
     * Backed by Compose state so [VulkanMapSurface] can show a fallback without polling.
     * Native creation runs on the render thread, so after [attachSurface] this reads
     * [MapRenderState.Initialising] until the create lands a moment later and flips it to
     * [MapRenderState.Rendering] or [MapRenderState.Unavailable]; only a missing renderer
     * library is reported synchronously. Written only on the main thread.
     */
    var renderState: MapRenderState by mutableStateOf(MapRenderState.Initialising)
        private set

    /**
     * Called on the main thread when native creation fails after [attachSurface] returned, so
     * a host holding the [Surface] only for this renderer can release it (nothing will draw
     * into it). Internal: [VulkanMapSurface] is the one host that needs it.
     */
    internal var onStartFailed: (() -> Unit)? = null

    /**
     * Whether the host wants frames. Owned here rather than split across a `pause()`/
     * `resume()` pair so there is exactly one writer per input and one reconciler
     * ([syncFrameLoop]) deciding whether the callback should be posted.
     */
    private var started = false

    /** Remembered so a surface attached after the theme was set still starts in it. */
    private var dark = false
    private var muted = false

    /** Remembered so the first resident set is tessellated with the layers the host asked for. */
    private var layers = LayerOptions()

    /** Remembered so a surface attached while offline is not left retrying, or vice versa. */
    private var online = true

    /** Remembered so a fix taken before the surface existed is drawn in the first frame. */
    internal var userPuck: UserPuck? = null
        private set

    /**
     * The app pins to draw, remembered so a set pushed before the surface existed — or one that
     * outlives a surface being recreated — is still there in the first frame.
     */
    internal var markers: List<MapMarker> = emptyList()
        private set

    /**
     * The simulated transit vehicles to draw, remembered on the same terms as [markers]. Kept
     * apart from [markers] because the host pushes the two on different cadences (vehicles at
     * ~1 Hz, pins on a tap/search).
     */
    internal var vehicles: List<MapMarker> = emptyList()
        private set

    /**
     * The route to draw, and how, remembered so a route set before the surface existed —
     * or one that outlives a surface being recreated — is still there in the first frame.
     */
    internal var routeSegments: List<RouteSegment>? = null
    internal var routeStyle = RouteStyle()

    /**
     * The pack-driven rail-lines network to draw, and how — a second coloured overlay beside
     * the navigation route. Remembered on the same terms as [routeSegments].
     */
    internal var railSegments: List<RouteSegment>? = null
    internal var railStyle = RouteStyle()

    /** The Moon raster pair, remembered so a surface attached after the push still has it. */
    internal var moonTextures: MoonTextures? = null
        private set

    /**
     * The last-pushed live-traffic colour table (`component_id`s and their ARGB), remembered so
     * a push that arrived before the surface existed is re-applied in the first frame. `null`
     * means the overlay has nothing to draw (never pushed, or cleared).
     */
    internal var trafficSpeeds: Pair<LongArray, IntArray>? = null

    /**
     * Whether to draw the orthographic-sphere globe rather than the flat map. Read alongside
     * [camera] when a frame is dispatched (the Compose path reads `CameraState.globeEnabled`
     * instead). Default-off; the car path never sets it.
     */
    var globeEnabled: Boolean = false

    /** Which body the globe draws: Earth (default) or the maps-pushed Moon raster pair. */
    var body: MapBody = MapBody.Earth

    /**
     * Upload the Moon raster pair after attach (and after any re-attach — the native textures
     * die with the renderer like every other GPU state). [colorRgba] is `width x height`
     * RGBA8, [demRg] is `demWidth x demHeight` RG8 packed uint16. The ~36 MB upload runs on
     * the render thread.
     */
    fun setMoonTextures(
        colorRgba: ByteArray,
        width: Int,
        height: Int,
        demRg: ByteArray,
        demWidth: Int,
        demHeight: Int,
    ) {
        moonTextures = MoonTextures(colorRgba, width, height, demRg, demWidth, demHeight)
        applyMoonTextures()
        invalidate()
    }

    /** Forget the Moon raster pair and free its GPU images, e.g. once the host leaves the Moon. */
    fun clearMoonTextures() {
        if (moonTextures == null) return
        moonTextures = null
        onRenderThread { h -> MapNative.clearMoonTextures(h) }
        invalidate()
    }

    /**
     * Release memory for [ComponentCallbacks2.onTrimMemory]. From
     * [ComponentCallbacks2.TRIM_MEMORY_BACKGROUND] up every resident tile goes (the next frame
     * refetches what it needs); below it only off-screen tiles do. The native trim waits for
     * the GPU to go idle, which is why it runs on the render thread.
     */
    fun trimMemory(level: Int) {
        if (!attached) return
        val keepVisible = level < ComponentCallbacks2.TRIM_MEMORY_BACKGROUND
        onRenderThread { h -> MapNative.trimMemory(h, keepVisible) }
        invalidate()
    }

    /** The baked `regionId` to mask, or `null` for no mask. */
    private var selectedRegionId: Long? = null

    /**
     * Whether [selectedRegionId] has been matched to resident tiles yet, as last reported by a
     * frame. Only the loop's retry cadence reads it; the retry itself runs on the render thread
     * right after each frame (see [NativeRegionMask]).
     */
    private var regionResolved = true

    /**
     * Where cached byte ranges live: the app cache dir, so "Clear cache" in system settings
     * reclaims them like any other cache. Resolved on the render thread (first use deletes a
     * stale legacy directory recursively, which is disk work the main thread must not do).
     */
    private val cacheDir: File by lazy {
        deleteStaleExternalCache()
        File(appContext.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }
    }

    /**
     * One-time reclaim: older builds kept the range cache at externalFilesDir/vectortilecache/,
     * invisible to "Clear cache". The entries are keyed by URL + range, so nothing here can be
     * confused for the new location's — delete the whole stale directory.
     */
    private fun deleteStaleExternalCache() {
        val external = appContext.getExternalFilesDir(null) ?: return
        val stale = File(external, CACHE_DIR_NAME)
        if (stale.exists()) stale.deleteRecursively()
    }

    /**
     * Where tiles come from — see [TileSource]. Remembered so a surface attached after a
     * host sets it still starts on the right source; changing it after attach takes
     * effect on the next attach (detach + attach), since workers open their archive once
     * at creation. Defaults to [TileSource.Server].
     */
    var tileSource: TileSource = TileSource.Server

    /**
     * A `.mamaps` archive pushed to the device's external files dir, if present and [source]
     * is [TileSource.LocalFile]; otherwise `null` and the renderer uses the built-in URL and
     * its range cache. Disk work, so called on the render thread.
     */
    private fun localArchivePath(source: TileSource): String? {
        if (source != TileSource.LocalFile) return null
        val root = appContext.getExternalFilesDir(null) ?: appContext.filesDir
        val local = File(root, LOCAL_ARCHIVE_NAME)
        return if (local.exists()) local.absolutePath else null
    }

    /**
     * Run [block] with the live native handle on the render thread, after every native call
     * issued before it. Dropped when no surface is attached (the state it would apply is
     * remembered and replayed on the next attach) and skipped on the render thread when the
     * create failed. The single funnel for native calls from this class and its extensions.
     */
    internal fun onRenderThread(block: (Long) -> Unit) = link.post(block)

    /**
     * Run [block] on the render thread and wait for its answer, at most [timeoutMillis]; [fallback]
     * with no surface, after a failed create, or past the bound. For the synchronous queries
     * (picks) only — everything else posts.
     */
    internal fun <T> queryRenderThread(timeoutMillis: Long, fallback: T, block: (Long) -> T): T =
        link.query(timeoutMillis, fallback, block)

    /**
     * Bring the native renderer up on [surface] at [widthPx] x [heightPx] pixels, replaying
     * the deferred theme, layers, connectivity, puck and region mask before any frame is
     * drawn. Creation runs on the render thread: [renderState] reads
     * [MapRenderState.Initialising] until it lands (see [renderState], [onStartFailed]).
     *
     * Any previously attached surface is torn down first. The caller keeps ownership of
     * [surface] and must not release it until after [detachSurface] or [destroy].
     */
    fun attachSurface(surface: Surface, widthPx: Int, heightPx: Int) {
        if (attached) detachSurface()
        this.widthPx = widthPx
        this.heightPx = heightPx
        // A new native renderer has drawn nothing, so the previous surface's snapshot must not
        // let the camera diff conclude the first frame changed nothing.
        lastFrame = null
        if (!MapNative.isAvailable) {
            Log.error(TAG, "libmap_renderer.so did not load; the map will not draw")
            renderState = MapRenderState.Unavailable(MapRenderState.Reason.RendererLibraryMissing)
            return
        }
        val source = tileSource
        link.create(
            surface, { cacheDir.absolutePath }, { localArchivePath(source) }, widthPx, heightPx, dark, muted,
            ::onCreated,
        )
        // Queued behind `create`, so the very first resident set is tessellated with the
        // layers the host asked for instead of being built and then invalidated.
        replayDeferredState()
        syncFrameLoop()
        // The whole deferred set was just replayed into a brand-new native renderer, and the
        // resident set is empty, so this surface owes a frame whatever the loop was doing.
        invalidate()
    }

    private fun replayDeferredState() {
        val online = online
        val layers = layers
        onRenderThread { h ->
            MapNative.setOnline(h, online)
            MapNative.setLayers(h, layers.poi, layers.transit, layers.poiKinds.joinToString(","))
            MapNative.setTrafficEnabled(h, layers.traffic)
            MapNative.setSafetyEnabled(h, layers.safety)
        }
        applyUserPuck()
        applyRoute()
        applyRailLines()
        applyRegionMask()
        applyTraffic()
        applyMarkers()
        applyVehicles()
        applyMoonTextures()
    }

    /** The create result, back on the main thread. Ignored when the surface already moved on. */
    private fun onCreated(nativeHandle: Long) {
        if (nativeHandle == 0L) {
            Log.error(TAG, "the Vulkan renderer failed to start; see MapRenderer in logcat")
            link.createFailed()
            renderState = MapRenderState.Unavailable(MapRenderState.Reason.RendererStartFailed)
            syncFrameLoop()
            onStartFailed?.invoke()
            return
        }
        handle = nativeHandle
        renderState = MapRenderState.Rendering
        invalidate()
    }

    /** Tell the renderer the attached surface is now [widthPx] x [heightPx] pixels. */
    fun resize(widthPx: Int, heightPx: Int) {
        this.widthPx = widthPx
        this.heightPx = heightPx
        onRenderThread { h -> MapNative.resize(h, widthPx, heightPx) }
        invalidate()
    }

    /**
     * Destroy the native renderer and stop drawing, leaving this instance reusable: a later
     * [attachSurface] brings it back up with all the deferred state intact.
     *
     * Waits (bounded, [DETACH_WAIT_MILLIS]) for the render thread to destroy the native side,
     * which waits for the GPU and drops the `ANativeWindow` (see [MapNativeLink.destroy]).
     */
    fun detachSurface() {
        link.destroy(DETACH_WAIT_MILLIS)
        handle = 0L
        frameInFlight = false
        frameOwed = false
        // Back to Initialising, not sticky-Unavailable: the surface is gone, and a surface
        // that is recreated (navigation, backgrounding) gets a fresh attempt.
        renderState = MapRenderState.Initialising
        // Not attached any more, so this is what removes the callback.
        syncFrameLoop()
    }

    /** Start driving frames, once there is a surface to draw into. Idempotent. */
    fun start() {
        started = true
        syncFrameLoop()
        // A host coming back on screen owes a frame: the surface may have been recreated, and
        // anything pushed while stopped never reached a frame.
        invalidate()
    }

    /**
     * Stop driving frames without tearing the renderer down, for a host that is off screen.
     * Idempotent. In-flight tile fetches on the native side are not paused.
     */
    fun stop() {
        started = false
        syncFrameLoop()
    }

    /** [stop] plus [detachSurface]: this host is done with the map. */
    fun destroy() {
        started = false
        detachSurface()
    }

    /**
     * Draw exactly one frame, for a host driving its own loop instead of using
     * [start]/[stop]. Returns whether a frame was actually presented — false when there is
     * no renderer, no measured viewport, the swapchain needed rebuilding, or the render thread
     * did not answer within [FRAME_CALL_TIMEOUT_MILLIS].
     *
     * Uses `System.nanoTime()` as the animation clock; see the overload for a supplied clock.
     */
    fun renderFrame(): Boolean = renderFrame(System.nanoTime())

    /**
     * As [renderFrame], but with the frame clock supplied by the caller (a Choreographer
     * `frameTimeNanos`), which the renderer forwards to shaders so animation is tied to the
     * presented frame rather than to wall-clock jitter.
     */
    fun renderFrame(frameTimeNanos: Long): Boolean {
        val snapshot = readFrameSnapshot() ?: return false
        trackFrameChange(snapshot)
        val result = link.frameNow(snapshot, density, frameTimeNanos, wantsPivot(snapshot), FRAME_CALL_TIMEOUT_MILLIS)
        applyFrameResult(result, snapshot)
        if (result.drawn) onFrame()
        return result.drawn
    }

    private fun wantsPivot(snapshot: FrameSnapshot): Boolean = composeCamera != null && snapshot.wantsPivot

    private fun readFrameSnapshot(): FrameSnapshot? {
        if (!attached) return null
        val state = composeCamera
        if (state != null) {
            val viewport = state.viewportDp ?: return null
            val position = state.position
            val globe = state.globeEnabled && position.zoom < GLOBE_DETAIL_ZOOM
            return FrameSnapshot(
                position = position,
                widthDp = viewport.width,
                heightDp = viewport.height,
                globe = globe,
                moon = globe && state.body == MapBody.Moon,
            )
        }
        if (widthPx <= 0 || heightPx <= 0 || density <= 0f) return null
        val globe = globeEnabled && camera.zoom < GLOBE_DETAIL_ZOOM
        return FrameSnapshot(
            position = camera,
            widthDp = widthPx / density,
            heightDp = heightPx / density,
            globe = globe,
            moon = globe && body == MapBody.Moon,
        )
    }

    // The backstop for a camera that moved without telling us: the Compose path pulls its
    // camera out of a CameraState here, and a gesture that somehow reached it without
    // waking the loop would otherwise draw one frame and settle mid-movement.
    private fun trackFrameChange(snapshot: FrameSnapshot) {
        if (!snapshot.hasChangedSince(lastFrame)) return
        lastFrame = snapshot
        lastChangeNanos = System.nanoTime()
    }

    /**
     * Fold a frame's main-thread-visible results back in: the region retry state and the
     * Compose camera's tilt pivot, so overlays positioned through [Projection] pivot on the
     * same terrain the basemap draws with. Writes the pivot only on change, so a settled pivot
     * costs no recomposition; off the tilted flat path it resets to the sea-level 0.0.
     */
    private fun applyFrameResult(result: FrameResult, snapshot: FrameSnapshot) {
        regionResolved = !result.regionPending
        val state = composeCamera ?: return
        val pivot = if (snapshot.wantsPivot) result.pivotDp else 0.0
        if (!pivot.isNaN() && pivot != state.pivotDp) state.pivotDp = pivot
    }

    private fun syncFrameLoop() {
        if (started && attached) requestFrame() else cancelFrame()
    }

    /**
     * Ask for a frame because something changed, and re-arm the idle grace.
     *
     * The renderer draws **on demand**, not every vsync: rendering a still map at 60 Hz did
     * full Vulkan work for an identical picture, which is what made the device hot. Every
     * setter here calls this, and so must anything outside that mutates what the renderer
     * would draw. It is public and cheap for that reason — the cost of a wasted frame is one
     * frame and the cost of a missed one is a map that has silently stopped updating.
     *
     * Main thread, like everything else here. Idempotent within a frame.
     *
     * Increments [wakeCount], which is what [FrameLoopWakeTest] counts: the failure mode of
     * this loop is a change nobody woke it for, so every mutator's wake is enumerated there.
     */
    fun invalidate() {
        wakeCount++
        lastChangeNanos = System.nanoTime()
        requestFrame()
    }

    /**
     * How many times the loop has been woken. Test seam for the on-demand contract: every
     * public mutator must wake the loop, and [FrameLoopWakeTest] asserts each one does.
     */
    internal var wakeCount = 0
        private set

    private fun requestFrame() {
        if (!started || !attached) return
        if (posted) {
            // Already scheduled for the next vsync — nothing to do.
            if (!postedDelayed) return
            // Scheduled for *later*, for work that was not urgent when it was posted. Something
            // has changed since, so pull it forward rather than making a camera move wait out a
            // retry backoff that can be seconds long.
            Choreographer.getInstance().removeFrameCallback(frameCallback)
        }
        posted = true
        postedDelayed = false
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    /**
     * Ask for one frame [delayMillis] from now, for work that is pending but not yet due —
     * today, a failed tile waiting out its retry backoff. Any [invalidate] supersedes it.
     */
    private fun requestFrameDelayed(delayMillis: Long) {
        if (posted || !started || !attached) return
        posted = true
        postedDelayed = true
        Choreographer.getInstance().postFrameCallbackDelayed(frameCallback, delayMillis)
    }

    private fun cancelFrame() {
        if (!posted) return
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        posted = false
        postedDelayed = false
    }

    private fun onVsync(frameTimeNanos: Long) {
        // Cleared first, so a re-entrant invalidate() from a setter called inside onFrame()
        // schedules the next frame rather than being swallowed as already-posted.
        posted = false
        postedDelayed = false
        // stop() and detachSurface() cannot un-post a callback Choreographer has already
        // dispatched for this frame, so the predicate is re-checked here rather than trusted.
        if (!started || !attached) return
        // The render thread is still on the previous frame. Queueing a second behind it would
        // draw a stale camera late; instead the current frame's completion draws the newest
        // camera immediately (see onFrameDone), so a slow frame costs latency, never a backlog.
        if (frameInFlight) {
            frameOwed = true
            return
        }
        dispatchFrame(frameTimeNanos)
    }

    /**
     * Read this frame's camera on the main thread and hand the frame to the render thread.
     * The main thread's share of a frame is this read plus one queue post.
     */
    private fun dispatchFrame(frameTimeNanos: Long) {
        val snapshot = readFrameSnapshot()
        if (snapshot == null) {
            // No measured viewport yet: try again next vsync, as a frame that did not draw.
            requestFrame()
            return
        }
        trackFrameChange(snapshot)
        frameInFlight = true
        link.frame(snapshot, density, frameTimeNanos, wantsPivot(snapshot)) { result -> onFrameDone(snapshot, result) }
    }

    /** A render-thread frame landed: apply its results and decide when the next one runs. */
    private fun onFrameDone(snapshot: FrameSnapshot, result: FrameResult) {
        frameInFlight = false
        applyFrameResult(result, snapshot)
        if (result.drawn) onFrame()
        if (!started || !attached) return
        // A vsync came and went while this frame was drawing: the camera has likely moved
        // again, so draw the newest one now rather than waiting out another vsync.
        if (frameOwed) {
            frameOwed = false
            dispatchFrame(System.nanoTime())
            return
        }
        // A frame that did not present has not shown the change that asked for it: the
        // swapchain was rebuilt, no image was free, or the viewport is not measured yet.
        if (!result.drawn) {
            requestFrame()
            return
        }
        if (System.nanoTime() - lastChangeNanos < IDLE_GRACE_NANOS) {
            requestFrame()
            return
        }
        // Nothing local is changing, so the native side decides: draw now, draw later, or stop.
        // An unresolved region mask retries once its tiles land, which is a frame the native
        // side already asks for; with nothing pending (offline, say) it polls on a slow cadence
        // rather than spinning the loop at 60 fps.
        val delay = result.nextDelayMillis
        when {
            delay == 0L -> requestFrame()
            delay > 0L -> requestFrameDelayed(if (regionResolved) delay else minOf(delay, REGION_RETRY_MILLIS))
            !regionResolved -> requestFrameDelayed(REGION_RETRY_MILLIS)
        }
    }

    /** Switch palette: light or dark, muted or not. Free — only a push constant changes. */
    fun setPalette(dark: Boolean, muted: Boolean) {
        this.dark = dark
        this.muted = muted
        onRenderThread { h -> MapNative.setPalette(h, dark, muted) }
        invalidate()
    }

    /**
     * Turn the optional POI, transit and traffic layers on or off. Not free — see
     * [LayerOptions]. A call that changes nothing does nothing.
     */
    fun setLayers(options: LayerOptions) {
        this.layers = options
        val kinds = options.poiKinds.joinToString(",")
        onRenderThread { h ->
            MapNative.setLayers(h, options.poi, options.transit, kinds)
            MapNative.setTrafficEnabled(h, options.traffic)
            MapNative.setSafetyEnabled(h, options.safety)
        }
        invalidate()
    }

    // Route, traffic and picking implementations live in SurfaceMapOverlays.kt; the public
    // setters below delegate so existing call sites are unchanged.
    fun setTrafficSpeeds(ids: LongArray, argbColors: IntArray) = pushTrafficSpeeds(ids, argbColors)

    /** Clear the live-traffic overlay so it draws nothing until the next [setTrafficSpeeds]. */
    fun clearTraffic() = dropTraffic()

    /**
     * Tell the renderer whether the device is online. Offline it serves stale cached ranges
     * instead of attempting a request, so a previously-viewed area keeps drawing.
     */
    fun setOnline(online: Boolean) {
        this.online = online
        onRenderThread { h -> MapNative.setOnline(h, online) }
        invalidate()
    }

    /**
     * Show the user's location, drawn inside the renderer's own frame so it stays glued to
     * the ground while the map moves. `null` draws nothing.
     */
    fun setUserPuck(puck: UserPuck?) {
        this.userPuck = puck
        applyUserPuck()
        invalidate()
    }

    /**
     * Replace the app's pins with [markers], drawn by the renderer as billboarded sprites so they
     * pan and tilt in lock-step with the basemap. An empty list clears them. Cheap: the geometry
     * is the shared unit quad, so this is a pure state push, no tessellation.
     */
    fun setMarkers(markers: List<MapMarker>) {
        this.markers = markers
        applyMarkers()
        invalidate()
    }

    /**
     * Replace the simulated transit vehicles with [vehicles], drawn through the same path as
     * [setMarkers]. An empty list clears them. Separate from [setMarkers] so the host's ~1 Hz
     * vehicle recompute replaces only the vehicles and leaves the app pins untouched.
     */
    fun setVehicles(vehicles: List<MapMarker>) {
        this.vehicles = vehicles
        applyVehicles()
        invalidate()
    }

    internal fun pickAt(xDp: Float, yDp: Float): Long = tapMarkerAt(xDp, yDp)

    /** Dim everything outside the region [mask] names, or clear the mask with `null`. */
    fun setRegionMask(mask: Long?) {
        Log.dev("RegionDbg", "setRegionMask mask=$mask")
        this.selectedRegionId = mask
        this.regionResolved = mask == null || mask == 0L
        applyRegionMask()
        invalidate()
    }

    fun setRoute(points: List<GeoPoint>?, style: RouteStyle = RouteStyle()) =
        setSingleRoute(points, style)

    fun setRoute(overlay: RouteOverlay?) = setOverlayRoute(overlay)

    internal fun pickLabels(box: DpRect, layerIds: Set<String>): List<PlacedLabel> =
        tapLabelsIn(box, layerIds)

    /**
     * The native per-step frame times (see [MapNative.lastFrameStepTimesNanos]), delivered to
     * [onResult] on the main thread; empty with no live renderer. For the debug overlay, which
     * must not read native state from the main thread.
     */
    internal fun queryStepTimes(onResult: (LongArray) -> Unit) = link.stepTimes(onResult)

    private fun applyRegionMask() = link.selectRegion(selectedRegionId)

    private fun applyMoonTextures() {
        val moon = moonTextures ?: return
        onRenderThread { h ->
            MapNative.setMoonTextures(
                h,
                moon.colorRgba, moon.width, moon.height,
                moon.demRg, moon.demWidth, moon.demHeight,
            )
        }
    }

    // `internal` (not `private`) so the frame-loop contract is testable: `IDLE_GRACE_NANOS`
    // is pinned by `FrameLoopWakeTest`.
    internal companion object {
        const val TAG = "SurfaceMapRenderer"
        const val CACHE_DIR_NAME = "vectortilecache"
        // Matches the maps app's MapTileCache.BASEMAP_ARCHIVE_FILE and the sideload
        // gate in MainActivity: one pushed basemap.mamaps satisfies the download gate,
        // the offline router, POI, and this renderer's local read.
        const val LOCAL_ARCHIVE_NAME = "basemap.mamaps"

        /**
         * How long the frame loop keeps running after the last change it noticed.
         *
         * The renderer stops drawing when nothing is changing, so the cost of failing to
         * notice a change is a map that has frozen — far worse than the frames this wastes.
         * A quarter of a second covers an invalidation that arrives a frame or two late (the
         * `snapshotFlow` push channel is one such) and is short enough that an idle map
         * settles to zero frames while the user is still looking at it.
         */
        internal val IDLE_GRACE_NANOS = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(250)

        /** Retry cadence for an unresolved region mask while no tile work is pending. */
        const val REGION_RETRY_MILLIS = 500L

        /**
         * Upper bound on [detachSurface]'s wait for the native teardown. A frame on the render
         * thread is bounded by the 100 ms swapchain-acquire cap plus its own few ms, and the
         * teardown by one GPU idle; a second is far past both and still well inside an ANR.
         */
        const val DETACH_WAIT_MILLIS = 1_000L

        /** Upper bound on [renderFrame]'s synchronous wait for one render-thread frame. */
        const val FRAME_CALL_TIMEOUT_MILLIS = 500L

        // DEFAULT_ROUTE_COLOR and the pick timeout live in SurfaceMapOverlays.kt.
    }
}
