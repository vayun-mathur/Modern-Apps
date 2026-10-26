package com.vayunmathur.library.map

import android.content.Context
import com.vayunmathur.library.log.Log
import android.view.Choreographer
import android.view.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
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
 * thread**, including construction. The renderer is not safe to drive from two threads at
 * once, and the frame loop runs on the main thread's [Choreographer], so a call from
 * anywhere else races the frame currently being recorded. This is a hard requirement, not a
 * convention — there is no lock behind it.
 *
 * That costs less than it sounds like: the expensive work (range fetch, inflate, MVT decode,
 * tessellation) happens on worker threads inside the native side, so a frame on this side
 * only uploads finished meshes and records a command buffer. Android Auto's
 * `SurfaceCallback` is dispatched on the main thread, so the car path satisfies this without
 * doing anything.
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
 * [Surface.release]. Whoever created the `Surface` releases it, and only after
 * [detachSurface] has returned.
 *
 * ## State set before the surface exists
 *
 * [MapNative.create] deliberately takes no layer, puck or connectivity arguments, so
 * everything set through [setPalette], [setLayers], [setOnline], [setUserPuck],
 * [setRoute] and [setRegionMask] is remembered and replayed into the native renderer
 * inside [attachSurface], **before the first frame**. Without that the first resident set
 * is tessellated with the wrong layers and immediately thrown away, the puck is missing
 * until the next fix arrives a second later, the route is missing until the host happens
 * to set it again, and a surface created while offline stays pinned to cache. Setting any
 * of them while a surface is attached applies immediately.
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
 * route/traffic/picking halves live as extensions in SurfaceMapOverlays.kt, so this
 * file holds only frame-loop, surface-lifecycle and attach-replay logic.
 */
@Suppress("TooManyFunctions")
class SurfaceMapRenderer(
    context: Context,
    private val density: Float,
    private val onFrame: () -> Unit = {},
) {

    /**
     * Convenience for a host whose surface already exists at construction time: identical to
     * building the renderer and immediately calling [attachSurface]. Check [renderState]
     * afterwards — creation is synchronous, so it is already final when this returns.
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
    internal var handle = 0L
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
     * its camera out of [composeCamera] inside [renderFrame] and nothing tells us when a
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
     * Compose reads its camera out of a [CameraState] inside the frame callback, so that a
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
     * Backed by Compose state so [VulkanMapSurface] can show a fallback without polling;
     * non-Compose hosts can just read it after [attachSurface] returns, since the native
     * renderer is created synchronously there. Written only on the main thread, so it needs
     * no synchronisation.
     */
    var renderState: MapRenderState by mutableStateOf(MapRenderState.Initialising)
        private set

    /**
     * Whether the host wants frames. Owned here rather than split across a `pause()`/
     * `resume()` pair so there is exactly one writer per input and one reconciler
     * ([syncFrameLoop]) deciding whether the callback should be posted. The orderings that
     * pair gets wrong — stopped before the surface arrives, surface destroyed while stopped,
     * [start] before the handle exists — all collapse into "recompute the predicate" here.
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
    private var userPuck: UserPuck? = null

    /**
     * The app pins to draw, remembered so a set pushed before the surface existed — or one that
     * outlives a surface being recreated — is still there in the first frame, the same way
     * [userPuck] and [routeSegments] are.
     */
    private var markers: List<MapMarker> = emptyList()

    /**
     * The simulated transit vehicles to draw, remembered on the same terms as [markers] so a set
     * pushed before the surface existed — or one that outlives a surface being recreated — is still
     * there in the first frame. Kept apart from [markers] because the host pushes the two on
     * different cadences (vehicles at ~1 Hz, pins on a tap/search).
     */
    private var vehicles: List<MapMarker> = emptyList()

    /**
     * The route to draw, and how, remembered so a route set before the surface existed —
     * or one that outlives a surface being recreated — is still there in the first frame.
     *
     * Kept as the coloured segments rather than as anything native, because the native mesh
     * dies with the renderer and this has to be able to rebuild it.
     */
    internal var routeSegments: List<RouteSegment>? = null
    internal var routeStyle = RouteStyle()

    /**
     * The pack-driven rail-lines network to draw, and how — a second coloured
     * overlay beside the navigation route so the network and a selected route
     * coexist. Remembered on the same terms as [routeSegments] so a push that
     * arrived before the surface existed is re-applied in the first frame.
     */
    internal var railSegments: List<RouteSegment>? = null
    internal var railStyle = RouteStyle()

    /**
     * The Moon raster pair, remembered so a surface attached after the push —
     * or re-attached after a recreate — still starts with the Moon available,
     * the same way [userPuck] and [routeSegments] are.
     */
    private var moonTextures: MoonTextures? = null

    /**
     * The last-pushed live-traffic colour table (`component_id`s and their ARGB), remembered so
     * a push that arrived before the surface existed — or that has to outlive the surface being
     * recreated — is re-applied in the first frame, the same way [route] is. `null` means the
     * overlay has nothing to draw (never pushed, or cleared).
     */
    internal var trafficSpeeds: Pair<LongArray, IntArray>? = null

    /**
     * Whether to draw the orthographic-sphere globe rather than the flat map.
     *
     * Pulled per frame out of the Compose `CameraState` (see [composeCamera]), like
     * the camera itself — a `LaunchedEffect` in `VulkanMapSurface` invalidates on
     * change, so flipping the toggle while idle still redraws. Read alongside the
     * camera in [renderFrame] and handed to the native frame as its `globe` flag.
     * Plain `var` (not Compose state): only ever written from effects, read on the
     * frame callback. Default-off; the car path never sets it.
     */
    var globeEnabled: Boolean = false

    /**
     * Which body the globe draws. Earth (default) is the vector basemap; Moon is
     * the maps-pushed raster pair (see [SurfaceMapRenderer.setMoonTextures]).
     * Plain `var` like [globeEnabled]: written from effects, read on the frame
     * callback. Only read while the globe is active.
     */
    var body: MapBody = MapBody.Earth

    /**
     * Upload the Moon raster pair after attach (and after any re-attach — the
     * native textures die with the renderer like every other GPU state, so this
     * must be replayed; see `attachSurface`). [colorRgba] is `width x height`
     * RGBA8, [demRg] is `demWidth x demHeight` RG8 packed uint16. Remembered so
     * a surface attached after the push still starts with the Moon available.
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
        if (handle != 0L) {
            MapNative.setMoonTextures(handle, colorRgba, width, height, demRg, demWidth, demHeight)
        }
        invalidate()
    }

    /** The baked `regionId` to mask, or `null` for no mask. */
    private var selectedRegionId: Long? = null

    /**
     * Whether [selectedRegionId] has been matched to resident tiles yet.
     *
     * The match needs the region's tiles to be resident, and a selection usually arrives before
     * they are - tapping a city label recentres the map, so the tiles under the new camera are
     * still in flight. So an unresolved id is retried each frame until it lands rather than
     * being dropped, which is the difference between the mask appearing and the mask silently
     * never appearing.
     */
    private var regionResolved = true

    /**
     * Where cached byte ranges live: the app cache dir, so "Clear cache" in
     * system settings reclaims them like any other cache. Previously external
     * files, where only "Clear storage" could reach them.
     *
     * The platform may evict this first under storage pressure, which is fine:
     * every entry re-streams on demand, and offline areas simply re-download.
     */
    private val cacheDir: File by lazy {
        deleteStaleExternalCache()
        File(appContext.cacheDir, CACHE_DIR_NAME).apply { mkdirs() }
    }

    /**
     * One-time reclaim: older builds kept the range cache at
     * externalFilesDir/vectortilecache/, invisible to "Clear cache". The
     * entries are keyed by URL + range, so nothing here can be confused for
     * the new location's — delete the whole stale directory.
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
     * A `.mamaps` archive pushed to the device's external files dir, if present and
     * [tileSource] is [TileSource.LocalFile]. When it exists the renderer opens it
     * directly and does no networking; otherwise this is `null` and the renderer uses
     * the built-in URL and its range cache unchanged (including the LocalFile fallback
     * when no archive was pushed).
     */
    private val localArchivePath: String?
        get() {
            if (tileSource != TileSource.LocalFile) return null
            val root = appContext.getExternalFilesDir(null) ?: appContext.filesDir
            val local = File(root, LOCAL_ARCHIVE_NAME)
            return if (local.exists()) local.absolutePath else null
        }

    /**
     * Bring the native renderer up on [surface] at [widthPx] x [heightPx] pixels, replaying
     * the deferred theme, layers, connectivity, puck and region mask before any frame is
     * drawn. [renderState] is final when this returns.
     *
     * Any previously attached surface is torn down first. The caller keeps ownership of
     * [surface] and must not release it until after [detachSurface] or [destroy].
     */
    fun attachSurface(surface: Surface, widthPx: Int, heightPx: Int) {
        if (handle != 0L) detachSurface()
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
        handle = MapNative.create(surface, cacheDir.absolutePath, localArchivePath, widthPx, heightPx, dark, muted)
        if (handle == 0L) {
            Log.error(TAG, "the Vulkan renderer failed to start; see MapRenderer in logcat")
            renderState = MapRenderState.Unavailable(MapRenderState.Reason.RendererStartFailed)
            return
        }
        MapNative.setOnline(handle, online)
        // Before the first frame, so the very first resident set is tessellated with
        // the layers the host asked for instead of being built and then invalidated.
        MapNative.setLayers(handle, layers.poi, layers.transit, layers.poiKinds.joinToString(","))
        MapNative.setTrafficEnabled(handle, layers.traffic)
        MapNative.setSafetyEnabled(handle, layers.safety)
        applyUserPuck()
        applyRoute()
        applyRailLines()
        applyRegionMask()
        applyTraffic()
        applyMarkers()
        applyVehicles()
        applyMoonTextures()
        renderState = MapRenderState.Rendering
        syncFrameLoop()
        // The whole deferred set was just replayed into a brand-new native renderer, and the
        // resident set is empty, so this surface owes a frame whatever the loop was doing.
        invalidate()
    }

    /** Tell the renderer the attached surface is now [widthPx] x [heightPx] pixels. */
    fun resize(widthPx: Int, heightPx: Int) {
        this.widthPx = widthPx
        this.heightPx = heightPx
        if (handle != 0L) MapNative.resize(handle, widthPx, heightPx)
        invalidate()
    }

    /**
     * Destroy the native renderer and stop drawing, leaving this instance reusable: a later
     * [attachSurface] brings it back up with all the deferred state intact.
     *
     * Does not release the [Surface] — that is the caller's, and releasing it before this
     * returns would pull the window out from under a GPU that has not gone idle yet.
     */
    fun detachSurface() {
        if (handle != 0L) {
            MapNative.destroy(handle)
            handle = 0L
        }
        // Back to Initialising, not sticky-Unavailable: the surface is gone, and a surface
        // that is recreated (navigation, backgrounding) gets a fresh attempt.
        renderState = MapRenderState.Initialising
        // handle is now 0, so this is what removes the callback.
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
     * Idempotent.
     *
     * This stops *rendering* only. In-flight tile fetches on the native side are not paused,
     * and that is where the data cost is, so this does not fully quiesce the map.
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
     * no renderer, no measured viewport, or the swapchain needed rebuilding.
     *
     * Uses `System.nanoTime()` as the animation clock; the Choreographer loop passes its own
     * `frameTimeNanos` through the [renderFrame]`(Long)` overload instead.
     */
    fun renderFrame(): Boolean = renderFrame(System.nanoTime())

    /**
     * As [renderFrame], but with the frame clock supplied by the caller — the Choreographer's
     * `frameTimeNanos`, which the renderer forwards to shaders so animation is tied to the
     * presented frame rather than to wall-clock jitter.
     */
    fun renderFrame(frameTimeNanos: Long): Boolean {
        val snapshot = readFrameSnapshot() ?: return false
        trackFrameChange(snapshot)
        val drawn = renderSnapshot(snapshot, frameTimeNanos)
        if (drawn) onFrame()
        if (!regionResolved) applyRegionMask()
        return drawn
    }

    /** Camera + viewport + globe state for one frame, or null when nothing can draw. */
    private data class FrameSnapshot(
        val position: CameraPosition,
        val widthDp: Float,
        val heightDp: Float,
        val globe: Boolean,
        val moon: Boolean
    ) {
        /** True when this frame would draw anything different from [previous]. */
        fun hasChangedSince(previous: FrameSnapshot?): Boolean = previous == null || this != previous
    }

    private fun readFrameSnapshot(): FrameSnapshot? {
        if (handle == 0L) return null
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
                moon = globe && state.body == MapBody.Moon
            )
        }
        if (widthPx <= 0 || heightPx <= 0 || density <= 0f) return null
        val globe = globeEnabled && camera.zoom < GLOBE_DETAIL_ZOOM
        return FrameSnapshot(
            position = camera,
            widthDp = widthPx / density,
            heightDp = heightPx / density,
            globe = globe,
            moon = globe && body == MapBody.Moon
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

    private fun renderSnapshot(snapshot: FrameSnapshot, frameTimeNanos: Long): Boolean {
        // The Compose path carries the twist-gesture bearing end to end: `CameraState`
        // holds it, `Projection` rotates overlays with it, and the native frame below draws
        // the basemap with it — so the basemap and everything positioned through the
        // projection turn together. Tilt is honoured the same way on both paths.
        val position = snapshot.position
        return MapNative.render(
            handle,
            position.target.longitude.toFloat(),
            position.target.latitude.toFloat(),
            position.zoom.toFloat(),
            position.bearing.toFloat(),
            position.pitch.toFloat(),
            snapshot.widthDp,
            snapshot.heightDp,
            density,
            frameTimeNanos,
            snapshot.globe,
            snapshot.moon,
        )
    }

    private fun syncFrameLoop() {
        if (started && handle != 0L) requestFrame() else cancelFrame()
    }

    /**
     * Ask for a frame because something changed, and re-arm the idle grace.
     *
     * The renderer draws **on demand**, not every vsync: rendering a still map at 60 Hz did
     * full Vulkan work on the main thread for an identical picture, which is what made the
     * device hot. Every setter here calls this, and so must anything outside that mutates what
     * the renderer would draw. It is public and cheap for that reason — a host that is unsure
     * whether a change is already covered should call it, because the cost of a wasted frame
     * is one frame and the cost of a missed one is a map that has silently stopped updating.
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
     * public mutator must wake the loop, and [FrameLoopWakeTest] asserts each one does. A
     * setter that stops calling [invalidate] fails there instead of freezing the map on device.
     */
    internal var wakeCount = 0
        private set

    private fun requestFrame() {
        if (!started || handle == 0L) return
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
     * today, a failed tile waiting out its retry backoff.
     *
     * Posting it immediately would spin through the whole backoff at 60fps, and not posting it
     * at all would leave the tile missing until the user happened to move the camera. Any
     * [invalidate] in the meantime supersedes it.
     */
    private fun requestFrameDelayed(delayMillis: Long) {
        if (posted || !started || handle == 0L) return
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
        if (!started || handle == 0L) return
        val drawn = renderFrame(frameTimeNanos)
        // A frame that did not present has not shown the change that asked for it: the
        // swapchain was rebuilt, or the viewport is not measured yet. Either way, try again.
        if (!drawn) {
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
        val delay = MapNative.nextFrameDelayMillis(handle)
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
        if (handle != 0L) MapNative.setPalette(handle, dark, muted)
        invalidate()
    }

    /**
     * Turn the optional POI, transit and traffic layers on or off. Not free — see
     * [LayerOptions]. A call that changes nothing does nothing.
     */
    fun setLayers(options: LayerOptions) {
        this.layers = options
        if (handle != 0L) {
            MapNative.setLayers(handle, options.poi, options.transit, options.poiKinds.joinToString(","))
            MapNative.setTrafficEnabled(handle, options.traffic)
            MapNative.setSafetyEnabled(handle, options.safety)
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
        if (handle != 0L) MapNative.setOnline(handle, online)
        invalidate()
    }

    /**
     * Tell the renderer a gesture is active. While active the native fetch zoom freezes,
     * so a pinch does not churn the wanted tile set every frame — rendering keeps tracking
     * the live camera. Driven from the gesture channel in `VulkanMapSurface`; safe to call
     * redundantly (the native side ignores no-change sets), and safe to never call (the
     * native settle timer releases the freeze on its own).
     */
    fun setGestureActive(active: Boolean, zoom: Float) {
        if (handle != 0L) {
            MapNative.setGestureActive(handle, active, zoom)
        }
        // No invalidate: the camera motion that accompanies the gesture already wakes the
        // loop through the position push channel; a bare release signal needs no frame.
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
     * pan and tilt in lock-step with the basemap instead of trailing it the way a Compose overlay
     * does. An empty list clears them. Cheap: the geometry is the shared unit quad, so this is a
     * pure state push, no tessellation.
     */
    fun setMarkers(markers: List<MapMarker>) {
        this.markers = markers
        applyMarkers()
        invalidate()
    }

    /**
     * Replace the simulated transit vehicles with [vehicles], drawn by the renderer as billboarded
     * sprites through the same path as [setMarkers]. An empty list clears them. Cheap: the geometry
     * is the shared unit quad, so this is a pure state push, no tessellation.
     *
     * Separate from [setMarkers] so the host's ~1 Hz vehicle recompute replaces only the vehicles
     * and leaves the app pins untouched; drive it from the vehicle ticker rather than merging it
     * into the pin set.
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
        this.regionResolved = false
        applyRegionMask()
        invalidate()
    }

    fun setRoute(points: List<GeoPoint>?, style: RouteStyle = RouteStyle()) =
        setSingleRoute(points, style)

    fun setRoute(overlay: RouteOverlay?) = setOverlayRoute(overlay)

    internal fun pickLabels(box: DpRect, layerIds: Set<String>): List<PlacedLabel> =
        tapLabelsIn(box, layerIds)

    private fun applyRegionMask() {
        if (handle == 0L) return
        val id = selectedRegionId
        if (id == null || id == 0L) {
            MapNative.clearRegionMask(handle)
            regionResolved = true
            return
        }
        // `0` clears above; a nonzero id returns nonzero once a resident tile carries it.
        // `0` back means the tiles have not landed yet — retried next frame, not a miss.
        val resolved = MapNative.setRegionMask(handle, id) != 0L
        // Logged on the transition only: this runs every retry frame while unresolved.
        if (resolved) Log.dev("RegionDbg", "applyRegionMask id=$id resolved")
        regionResolved = resolved
    }

    private fun applyUserPuck() {
        if (handle == 0L) return
        val puck = userPuck
        if (puck == null) {
            MapNative.clearUserPuck(handle)
        } else {
            MapNative.setUserPuck(
                handle,
                puck.position.longitude.toFloat(),
                puck.position.latitude.toFloat(),
                puck.bearing ?: 0f,
                puck.bearing != null,
            )
        }
    }

    private fun applyMarkers() {
        if (handle == 0L) return
        val pins = markers
        if (pins.isEmpty()) {
            MapNative.clearMarkers(handle)
            return
        }
        val packed = packMapMarkers(pins)
        MapNative.setMarkers(handle, packed.ids, packed.lonLat, packed.icons, packed.labels)
    }

    private fun applyVehicles() {
        if (handle == 0L) return
        val vs = vehicles
        if (vs.isEmpty()) {
            MapNative.clearVehicles(handle)
            return
        }
        val packed = packVehicles(vs)
        MapNative.setVehicles(handle, packed.ids, packed.lonLat, packed.icons, packed.colors)
    }

    private fun applyMoonTextures() {
        if (handle == 0L) return
        val moon = moonTextures ?: return
        MapNative.setMoonTextures(
            handle,
            moon.colorRgba, moon.width, moon.height,
            moon.demRg, moon.demWidth, moon.demHeight,
        )
    }

/**
 * The Moon raster pair the host pushes for the lunar globe: converted LROC
 * color (RGBA8) + converted LDEM (RG8 packed uint16). Plain holder so the
 * renderer can remember and replay it across re-attach like every other
 * deferred state.
 */
data class MoonTextures(
    val colorRgba: ByteArray,
    val width: Int,
    val height: Int,
    val demRg: ByteArray,
    val demWidth: Int,
    val demHeight: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MoonTextures) return false
        return width == other.width && height == other.height &&
            demWidth == other.demWidth && demHeight == other.demHeight &&
            colorRgba.contentEquals(other.colorRgba) && demRg.contentEquals(other.demRg)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + demWidth
        result = 31 * result + demHeight
        result = 31 * result + colorRgba.contentHashCode()
        result = 31 * result + demRg.contentHashCode()
        return result
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
         * A quarter of a second is long enough to cover an invalidation that arrives a frame
         * or two late (the `snapshotFlow` push channel is one such) and short enough that an
         * idle map settles to zero frames while the user is still looking at it. It costs
         * nothing during an interaction, where every frame re-arms it.
         *
         * `internal` rather than `private` so [FrameLoopWakeTest] can pin the quarter-second:
         * a grace of zero freezes the map whenever the push channel is one frame late, and an
         * unbounded one re-heats the phone this loop exists to cool.
         */
        internal val IDLE_GRACE_NANOS = java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(250)

        /** Retry cadence for an unresolved region mask while no tile work is pending. */
        const val REGION_RETRY_MILLIS = 500L

        // DEFAULT_ROUTE_COLOR lives in SurfaceMapOverlays.kt.
    }
}

/**
 * The Moon raster pair the host pushes for the lunar globe: converted LROC
 * color (RGBA8) + converted LDEM (RG8 packed uint16). Plain holder so the
 * renderer can remember and replay it across re-attach like every other
 * deferred state.
 */
data class MoonTextures(
    val colorRgba: ByteArray,
    val width: Int,
    val height: Int,
    val demRg: ByteArray,
    val demWidth: Int,
    val demHeight: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MoonTextures) return false
        return width == other.width && height == other.height &&
            demWidth == other.demWidth && demHeight == other.demHeight &&
            colorRgba.contentEquals(other.colorRgba) && demRg.contentEquals(other.demRg)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + demWidth
        result = 31 * result + demHeight
        result = 31 * result + colorRgba.contentHashCode()
        result = 31 * result + demRg.contentHashCode()
        return result
    }
}
