package com.vayunmathur.auto.service

import android.content.ComponentName
import android.content.Context
import androidx.car.app.CarAppService
import com.vayunmathur.auto.platform.CarAppDiscovery
import com.vayunmathur.auto.platform.CarLauncherState
import com.vayunmathur.auto.platform.DiscoveredApp
import com.vayunmathur.auto.platform.HostManager
import com.vayunmathur.auto.platform.HostTemplateBus
import com.vayunmathur.auto.platform.VideoSinkChannel
import com.vayunmathur.library.carhost.HostNavState
import com.vayunmathur.library.carhost.HostTemplate

/**
 * Session-scoped owner for the multi-app host ([HostManager]).
 *
 * Binds every discovered `CarAppService` (navigation, POI, IoT, weather)
 * plus first-party services (music now, communicate next — see
 * [discover]), and wires every video-sink hookup the sessions need
 * (legacy template states, map surface, night, map touches). Session
 * thread owns start/stop; the video sink is touched from `openNext` on the
 * pump thread like every other `sink.set*` call.
 */
class CarAppHostSession(
    private val context: Context,
    private val video: () -> VideoSinkChannel?,
) {
    private var manager: HostManager? = null

    /**
     * Binds every car-app service; template states push into the car card
     * through the legacy path until Phase C replaces the nav renderer.
     */
    fun start() {
        if (manager != null) return
        val host = HostManager(context)
        manager = host
        val apps = discover()
        // Publish before binding: a refusing app's hideApp must land after this snapshot.
        CarLauncherState.setDiscovered(apps)
        host.bindAll(apps)
        CarLauncherState.updateActions {
            it.copy(onAppHomeTap = { component -> resetApp(component) })
        }
        // Default focus: Maps when present, else the first discovered app, so
        // the map surface attaches without waiting for a launcher tap.
        val maps = apps.firstOrNull { it.component.packageName == MAPS_PACKAGE }
        host.setFocused(maps?.component ?: apps.firstOrNull()?.component)
    }

    /**
     * Wires the host into a video sink: night as a configuration change, the
     * renderer surface as the focused app's `SurfaceContainer`, and map
     * touches into the focused app's own `SurfaceCallback`. Call from
     * `openNext` with the sink under construction, like every other `sink.set*`
     * call.
     */
    fun wireInto(
        sink: VideoSinkChannel,
        snapshots: () -> com.vayunmathur.auto.protocol.NavSnapshot?,
    ) {
        val sessionHost = manager ?: return
        sink.wiring.setMapDarkApplier { dark -> sessionHost.setNight(dark) }
        sink.wiring.setNavSource(
            get = snapshots,
            onMapSurface = { surface, w, h ->
                sessionHost.forwardSurface(surface, w, h)
            },
        )
        sink.wiring.setMapTouchForwarder { action, x, y ->
            sessionHost.injectMapTouch(action, x, y)
        }
        // Legacy template path: every generic template that parses to a
        // Navigation maps back onto the old card state until Phase C.
        sink.wiring.setHostNavState(HostNavState(mapsPresent = true, connected = false))
    }

    /** Tears every session down; the service still owns the `Surface`. */
    fun stop() {
        manager?.unbindAll()
        manager = null
    }

    /** Selects the focused app; its surface attaches, others detach. */
    fun setFocused(component: ComponentName?) {
        manager?.setFocused(component)
    }

    /**
     * Resets one app to its home screen (see [HostManager.resetApp]):
     * re-tapping the open app's dock icon rebuilds its screen stack from
     * the root. Selection stays put.
     */
    fun resetApp(component: ComponentName?) {
        if (component == null) return
        manager?.resetApp(component)
    }

    /** Maps one parsed template onto the legacy card state. */
    fun onTemplate(template: HostTemplate) {
        HostTemplateBus.toLegacy(template)?.let { video()?.wiring?.setHostNavState(it) }
    }

    private fun discover(): List<DiscoveredApp> {
        val merged = runCatching { CarAppDiscovery.queryAll(context) }.getOrDefault(emptyList())
        if (merged.isNotEmpty()) return merged
        // Fallback: the bundled Maps service directly, so the loopback path
        // hosts Maps even when discovery reports nothing.
        return listOf(
            DiscoveredApp(
                component = ComponentName(MAPS_PACKAGE, MAPS_CAR_SERVICE_CLASS),
                label = MAPS_LABEL,
                icon = null,
                categories = setOf(CarAppService.CATEGORY_NAVIGATION_APP),
            ),
        )
    }

    private companion object {
        const val MAPS_PACKAGE = "com.vayunmathur.maps"
        const val MAPS_CAR_SERVICE_CLASS = "com.vayunmathur.maps.car.MapsCarAppService"
        const val MAPS_LABEL = "Maps"
    }
}
