package com.vayunmathur.auto.platform

import android.view.Surface
import com.vayunmathur.library.carhost.HostNavState
import com.vayunmathur.library.carhost.HostTemplate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The car display's UI state, shared between the projection session and the
 * Compose launcher.
 *
 * Mirrors [AutoSessionState] (the phone side): the session runs on its own
 * worker thread and the car UI collects on the presentation's lifecycle, so
 * they share nothing but these flows. Setting a [MutableStateFlow] value is
 * thread-safe, which is what makes worker-thread emission safe here.
 *
 * Session feeds ([nowPlaying], [activeCall], [navSnapshot] ...) are written by
 * [CarDisplay]'s setters (which keep their signatures so [VideoSinkChannel]
 * compiles unchanged) and collected by the launcher composables. The hosted
 * surface hooks ([mapSurfaceListener], [mapTouchForwarder]) stay imperative
 * volatile vars like [CallCardPush]: they are called from `TextureView`
 * callbacks, not from composition.
 */
object CarLauncherState {
    private val _nowPlaying = MutableStateFlow<NowPlayingInfo?>(null)

    /** Latest now-playing snapshot; null until the media session reports. */
    val nowPlaying: StateFlow<NowPlayingInfo?> = _nowPlaying.asStateFlow()

    private val _drivingRestricted = MutableStateFlow(false)

    /** Driving-restriction gate: hides media actions, locks browsing. */
    val drivingRestricted: StateFlow<Boolean> = _drivingRestricted.asStateFlow()

    private val _nightDark = MutableStateFlow(false)

    /** Night palette for the car UI; the hosted map learns it separately. */
    val nightDark: StateFlow<Boolean> = _nightDark.asStateFlow()

    private val _activeCall = MutableStateFlow<ActiveCallInfo?>(null)

    /** Active-call snapshot; null with no live call. */
    val activeCall: StateFlow<ActiveCallInfo?> = _activeCall.asStateFlow()

    private val _phoneStatus = MutableStateFlow<PhoneStatus?>(null)

    /** Latest phone status for the status cluster; null until first pull. */
    val phoneStatus: StateFlow<PhoneStatus?> = _phoneStatus.asStateFlow()

    private val _navSnapshot = MutableStateFlow<com.vayunmathur.auto.protocol.NavSnapshot?>(null)

    /** Latest guidance snapshot for the pre-host nav fallback. */
    val navSnapshot: StateFlow<com.vayunmathur.auto.protocol.NavSnapshot?> = _navSnapshot.asStateFlow()

    private val _actions = MutableStateFlow(CarLauncherActions())

    /** Card taps and call actions, late-wired by the service like before. */
    val actions: StateFlow<CarLauncherActions> = _actions.asStateFlow()

    private val _selectedApp = MutableStateFlow<String?>(null)

    /**
     * Selected app id (`DiscoveredApp.id`); null means the home grid.
     * Written by launcher taps, read by the body + the session focus.
     */
    val selectedApp: StateFlow<String?> = _selectedApp.asStateFlow()

    private val _discovered = MutableStateFlow<List<DiscoveredApp>>(emptyList())

    /** Latest discovery snapshot for the home grid. */
    val discovered: StateFlow<List<DiscoveredApp>> = _discovered.asStateFlow()

    private val _templates = MutableStateFlow<Map<String, HostTemplate>>(emptyMap())

    /** Latest parsed template per component id; absent until first invalidate. */
    val templates: StateFlow<Map<String, HostTemplate>> = _templates.asStateFlow()

    private val _pinnedOrder = MutableStateFlow<List<String>>(emptyList())

    /** Ordered pinned ids for the dock; stale ids filtered by the UI. */
    val pinnedOrder: StateFlow<List<String>> = _pinnedOrder.asStateFlow()

    private val _hostNav = MutableStateFlow<HostNavState?>(null)

    /**
     * Legacy Maps-only template state, read by the pre-Phase-C status path.
     * Superseded by [templates]; retained until the session rewire drops the
     * `setHostNavState` call.
     */
    val hostNav: StateFlow<HostNavState?> = _hostNav.asStateFlow()

    /**
     * Receives the nav renderer's map surface: (surface, w, h), or (null, 0, 0)
     * on destroy. Written by [CarDisplay.setMapSurfaceListener], read by the
     * navigation renderer's `TextureView` island.
     */
    @Volatile
    var mapSurfaceListener: ((Surface?, Int, Int) -> Unit)? = null

    /**
     * Forwards touches on the map surface to the hosted app. Written by
     * [CarDisplay], read by the navigation renderer.
     */
    @Volatile
    var mapTouchForwarder: ((action: Int, x: Float, y: Float) -> Boolean)? = null

    /** Records a media snapshot. Thread-safe like every other flow write here. */
    fun setNowPlaying(info: NowPlayingInfo) {
        _nowPlaying.value = info
    }

    /** Hides the now-playing card without touching the cached snapshot. */
    fun hideNowPlaying() {
        _nowPlaying.value = _nowPlaying.value?.takeUnless { it.shouldShowCard() }
    }

    /** Gates driving-restricted pixels. Thread-safe. */
    fun setDrivingRestricted(restricted: Boolean) {
        _drivingRestricted.value = restricted
    }

    /** Switches the car UI day/night palette. Thread-safe. */
    fun setNight(dark: Boolean) {
        _nightDark.value = dark
    }

    /** Pushes one active-call snapshot; null hides the card. Thread-safe. */
    fun setActiveCall(info: ActiveCallInfo?) {
        _activeCall.value = info
    }

    /** Pushes one phone-status snapshot into the status cluster. Thread-safe. */
    fun setPhoneStatus(status: PhoneStatus) {
        _phoneStatus.value = status
    }

    /** Pushes one guidance snapshot to the nav fallback. Thread-safe. */
    fun setNavSnapshot(snapshot: com.vayunmathur.auto.protocol.NavSnapshot) {
        _navSnapshot.value = snapshot
    }

    /** Replaces the card/call actions wholesale. Thread-safe. */
    fun updateActions(transform: (CarLauncherActions) -> CarLauncherActions) {
        _actions.value = transform(_actions.value)
    }

    /** Selects an app (or null for home). Thread-safe. */
    fun selectApp(id: String?) {
        _selectedApp.value = id
    }

    /** Publishes one discovery snapshot for the home grid. Thread-safe. */
    fun setDiscovered(apps: List<DiscoveredApp>) {
        _discovered.value = apps
    }

    /** Drops one app from the grid and dock for the rest of the session. Thread-safe. */
    fun hideApp(id: String) {
        _discovered.update { apps -> apps.filterNot { it.id == id } }
    }

    /** Publishes one parsed template for [id]. Thread-safe. */
    fun setTemplate(id: String, template: HostTemplate) {
        _templates.value += id to template
    }

    /** Publishes the pinned order for the dock. Thread-safe. */
    fun setPinnedOrder(ids: List<String>) {
        _pinnedOrder.value = ids
    }

    /** Pushes one Maps-only template state. Replaced by Phase B's generic flow. */
    fun setHostNav(state: HostNavState) {
        _hostNav.value = state
    }
}

/**
 * What the car launcher's cards invoke.
 *
 * Late-wired by the service through [CarDisplay]'s setters exactly like the
 * old view callbacks: every field defaults to a no-op so the launcher
 * composes before the session finishes wiring.
 */
data class CarLauncherActions(
    val onMediaTap: () -> Unit = {},
    val onPreviousTap: () -> Unit = {},
    val onNextTap: () -> Unit = {},
    val onAnswerCall: () -> Unit = {},
    val onEndCall: () -> Unit = {},
    val onHoldToggle: () -> Unit = {},
    val onMuteToggle: () -> Unit = {},
    /**
     * Re-tap on the open app's dock icon: resets that app to its home
     * screen. Late-wired by [CarAppHostSession]; no-op until the session
     * starts.
     */
    val onAppHomeTap: (android.content.ComponentName) -> Unit = {},
)
