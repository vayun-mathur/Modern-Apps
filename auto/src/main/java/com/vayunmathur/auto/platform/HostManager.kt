package com.vayunmathur.auto.platform

import android.content.ComponentName
import android.content.Context
import com.vayunmathur.library.log.Log
import android.view.Surface
import com.vayunmathur.library.carhost.HostAction
import com.vayunmathur.library.carhost.HostNavState
import com.vayunmathur.library.carhost.HostTemplate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns one [HostSession] per bound car-app component.
 *
 * Surface policy: a single focused session holds a `Surface` at a time. The
 * launcher selects an app ([setFocused]); the previously focused session's
 * surface detaches (`clearSurface`) and the newly focused one attaches on the
 * next `TextureView` callback through [forwardSurface]. Template states stream
 * per component through [templates]; the launcher renders the focused one.
 *
 * Session thread owns start/stop; surface calls hop like the old
 * `CarAppHostSession` (see `CarAppHostSession.wireInto`).
 */
class HostManager(private val context: Context) {
    private val sessions = linkedMapOf<ComponentName, HostSession>()

    private val _templates = MutableStateFlow<Map<String, HostTemplate>>(emptyMap())

    /** Latest parsed template per component id; absent until first invalidate. */
    val templates: StateFlow<Map<String, HostTemplate>> = _templates.asStateFlow()

    private val _focused = MutableStateFlow<ComponentName?>(null)

    /** The selected app; its surface attaches, all others detach. */
    val focused: StateFlow<ComponentName?> = _focused.asStateFlow()

    /** Binds every discovered app that is not already bound. */
    fun bindAll(apps: List<DiscoveredApp>) {
        for (app in apps) bindMissing(app)
        dropGone(apps)
    }

    /** Binds one app unless it is already bound or has no category. */
    private fun bindMissing(app: DiscoveredApp) {
        val key = app.component
        if (sessions.containsKey(key)) return
        val category = app.categories.firstOrNull() ?: return
        val session = HostSession(
            context = context,
            component = key,
            category = category,
            label = app.label,
            onRefused = {
                RefusedCarApps.markRefused(context, key)
                CarLauncherState.hideApp(key.flattenToString())
            },
        ) { template ->
            val id = key.flattenToString()
            _templates.value += id to template
            CarLauncherState.setTemplate(id, template)
            HostTemplateBus.publish(template)
        }
        sessions[key] = session
        session.bind()
    }

    /** Drops sessions whose app disappeared. */
    private fun dropGone(apps: List<DiscoveredApp>) {
        val alive = apps.map { it.component }.toSet()
        val gone = sessions.keys - alive
        for (key in gone) {
            sessions.remove(key)?.unbind()
            _templates.value -= key.flattenToString()
        }
    }

    /**
     * Selects the focused app: detaches the old session's surface, records
     * the new one. The next `TextureView` callback attaches through
     * [forwardSurface].
     */
    fun setFocused(component: ComponentName?) {
        val previous = _focused.value
        if (previous == component) return
        if (previous != null) {
            runCatching { sessions[previous]?.clearSurface() }
                .onFailure { Log.status(TAG, "clearSurface failed for $previous", it) }
        }
        _focused.value = component
        pendingSurface?.let { (surface, w, h) ->
            if (component != null) forwardSurface(surface, w, h)
        }
    }

    private var pendingSurface: Triple<Surface?, Int, Int>? = null

    /**
     * Forwards the renderer's `TextureView` surface to the focused session.
     * Null (destroyed) clears it. Called from the `TextureView` callbacks;
     * cached so a focus switch with a live surface re-attaches immediately.
     */
    fun forwardSurface(surface: Surface?, width: Int, height: Int) {
        pendingSurface = Triple(surface, width, height)
        val focused = _focused.value ?: return
        val session = sessions[focused] ?: return
        if (surface != null) session.setSurface(surface, width, height)
        else session.clearSurface()
    }

    /** Forwards one map touch to the focused session. Main thread only. */
    fun injectMapTouch(action: Int, xPx: Float, yPx: Float): Boolean {
        val focused = _focused.value ?: return false
        return sessions[focused]?.injectMapTouch(action, xPx, yPx) ?: false
    }

    /** Pushes night to every bound session; each app restyles itself. */
    fun setNight(dark: Boolean) {
        for (session in sessions.values) session.setNight(dark)
    }

    /** Template for [component], or null until its first invalidate. */
    fun templateFor(component: ComponentName): HostTemplate? =
        _templates.value[component.flattenToString()]

    /** Tears every session down. */
    fun unbindAll() {
        for (session in sessions.values) runCatching { session.unbind() }
        sessions.clear()
        _templates.value = emptyMap()
        _focused.value = null
        pendingSurface = null
    }

    /**
     * Resets one app to its home screen: unbinds and rebinds its session, so
     * the app builds a fresh screen stack from its root. Playback and other
     * app-owned state live outside the car session (music's player, maps'
     * nav singleton) and survive; the re-handshake takes ~300ms. Runs the
     * blocking unbind off the caller.
     */
    fun resetApp(component: ComponentName) {
        val session = sessions[component] ?: return
        kotlin.concurrent.thread(name = "ma-auto-carhost-reset", isDaemon = true) {
            runCatching { session.unbind() }
                .onFailure { Log.status(TAG, "reset unbind failed for $component", it) }
            session.bind()
            if (component == _focused.value) {
                pendingSurface?.let { (surface, w, h) ->
                    if (surface != null) session.setSurface(surface, w, h)
                }
            }
        }
    }

    private companion object {
        const val TAG = "MaAuto.HostManager"
    }
}

/**
 * Main-thread fan-out for hosted templates.
 *
 * Sessions publish parsed templates here (in addition to the `HostManager`
 * flow) so the legacy `HostNavState` path in `CarDisplay.setHostNavState`
 * keeps working until Phase C replaces the nav renderer: [publish] maps a
 * parsed [HostTemplate.Navigation] back to the legacy state the old card
 * reads.
 */
object HostTemplateBus {
    private val _legacyNav = MutableStateFlow<HostNavState?>(null)

    /** Legacy nav state for the pre-Phase-C card; null until first parse. */
    val legacyNav: StateFlow<HostNavState?> = _legacyNav.asStateFlow()

    /** Publishes one parsed template. */
    fun publish(template: HostTemplate) {
        toLegacy(template)?.let { _legacyNav.value = it }
    }

    /** Maps a parsed [HostTemplate.Navigation] back to the legacy state. */
    fun toLegacy(template: HostTemplate): HostNavState? {
        if (template !is HostTemplate.Navigation) return null
        return HostNavState(
            mapsPresent = true,
            connected = true,
            navigating = template.navigating,
            loading = template.loading,
            cue = template.cue,
            road = template.road,
            distanceText = template.distanceText,
            etaText = template.etaText,
            lanesText = template.lanesText,
            actions = template.actions.map { uiAction ->
                HostAction(uiAction.title, uiAction.onClick)
            },
        )
    }
}
