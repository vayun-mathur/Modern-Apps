package com.vayunmathur.maps.util

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.maps.data.MapPreferences
import com.vayunmathur.maps.data.ThemeMode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Backs both the P6 settings screen (units / theme / voice guidance) and the P6
 * layers sheet (traffic / satellite / safety toggles), reading and writing the
 * shared [MapPreferences] keys through [DataStoreUtils].
 *
 * The layer toggles live here (rather than on the map VM) so the same persisted
 * value drives both the sheet's switches and the layer visibility in
 * `MapLayers`. Reads use the boolean/string flows seeded with the current
 * snapshot so the first frame already reflects the stored value; writes are
 * fire-and-forget on `viewModelScope`.
 */
class MapSettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val ds = DataStoreUtils.getInstance(application)

    // --- Settings screen ---------------------------------------------------

    val themeMode: StateFlow<ThemeMode> =
        ds.stringFlow(MapPreferences.KEY_THEME_MODE)
            .map { ThemeMode.from(it) }
            .stateIn(
                viewModelScope, SharingStarted.Eagerly,
                ThemeMode.from(ds.getString(MapPreferences.KEY_THEME_MODE)),
            )

    val voiceGuidance: StateFlow<Boolean> = boolPref(
        MapPreferences.KEY_VOICE_GUIDANCE, MapPreferences.DEFAULT_VOICE_GUIDANCE,
    )

    /**
     * True-3D-sphere globe (maps-only; no other app reads this). Off by default
     * so the flat Mercator map is unchanged until the user opts in.
     */
    val globeEnabled: StateFlow<Boolean> = boolPref(
        MapPreferences.KEY_GLOBE, MapPreferences.DEFAULT_GLOBE,
    )

    fun setThemeMode(mode: ThemeMode) =
        launch { ds.setString(MapPreferences.KEY_THEME_MODE, mode.pref) }

    fun setVoiceGuidance(enabled: Boolean) =
        launch { ds.setBoolean(MapPreferences.KEY_VOICE_GUIDANCE, enabled) }

    fun setGlobeEnabled(enabled: Boolean) =
        launch { ds.setBoolean(MapPreferences.KEY_GLOBE, enabled) }

    // --- Map updates ---------------------------------------------------------

    /**
     * Auto-update map data on unmetered networks. Drives the periodic
     * [MapOverlayWorker] check; the Settings row checks manually regardless.
     */
    val mapAutoUpdate: StateFlow<Boolean> = boolPref(
        MapPreferences.KEY_MAP_AUTO_UPDATE, MapPreferences.DEFAULT_MAP_AUTO_UPDATE,
    )

    /** Overlay sequence already stored on disk (0 = base only). */
    val overlayAppliedSeq: StateFlow<Long> =
        ds.longFlow(MapOverlayWorker.KEY_APPLIED_SEQ, 0L).stateIn(
            viewModelScope, SharingStarted.Eagerly,
            ds.getLong(MapOverlayWorker.KEY_APPLIED_SEQ) ?: 0L,
        )

    /** Bytes still to fetch for the pending overlay (0 = nothing pending). */
    val overlayPendingBytes: StateFlow<Long> =
        ds.longFlow(MapOverlayWorker.KEY_PENDING_BYTES, 0L).stateIn(
            viewModelScope, SharingStarted.Eagerly,
            ds.getLong(MapOverlayWorker.KEY_PENDING_BYTES) ?: 0L,
        )

    /** Last manifest check time (millis, 0 = never). */
    val overlayLastCheck: StateFlow<Long> =
        ds.longFlow(MapOverlayWorker.KEY_LAST_CHECK, 0L).stateIn(
            viewModelScope, SharingStarted.Eagerly,
            ds.getLong(MapOverlayWorker.KEY_LAST_CHECK) ?: 0L,
        )

    fun setMapAutoUpdate(enabled: Boolean) =
        launch {
            ds.setBoolean(MapPreferences.KEY_MAP_AUTO_UPDATE, enabled)
            val context = getApplication<Application>().applicationContext
            if (enabled) MapOverlayWorker.scheduleAuto(context)
            else MapOverlayWorker.cancelAuto(context)
        }

    /** Ensure the periodic chain matches the persisted toggle (call on start). */
    fun syncAutoUpdateSchedule() {
        val context = getApplication<Application>().applicationContext
        if (ds.getBoolean(MapPreferences.KEY_MAP_AUTO_UPDATE, MapPreferences.DEFAULT_MAP_AUTO_UPDATE)) {
            MapOverlayWorker.scheduleAuto(context)
        }
    }

    // --- Layers sheet ------------------------------------------------------

    val trafficLayer: StateFlow<Boolean> = boolPref(
        MapPreferences.KEY_LAYER_TRAFFIC, MapPreferences.DEFAULT_LAYER_TRAFFIC,
    )
    val satelliteLayer: StateFlow<Boolean> = boolPref(
        MapPreferences.KEY_LAYER_SATELLITE, MapPreferences.DEFAULT_LAYER_SATELLITE,
    )
    val safetyLayer: StateFlow<Boolean> = boolPref(
        MapPreferences.KEY_LAYER_SAFETY, MapPreferences.DEFAULT_LAYER_SAFETY,
    )
    val transitLayer: StateFlow<Boolean> = boolPref(
        MapPreferences.KEY_LAYER_TRANSIT, MapPreferences.DEFAULT_LAYER_TRANSIT,
    )

    /**
     * Google services (place details — photos/hours/phone, never reviews — + Street View). Off by
     * default; the first-launch consent dialog or the settings toggle opts in.
     * Every Google call site gates on this — off means zero Google traffic.
     */
    val googleServices: StateFlow<Boolean> = boolPref(
        MapPreferences.KEY_GOOGLE_SERVICES, MapPreferences.DEFAULT_GOOGLE_SERVICES,
    )

    fun setTrafficLayer(enabled: Boolean) =
        launch { ds.setBoolean(MapPreferences.KEY_LAYER_TRAFFIC, enabled) }

    fun setSatelliteLayer(enabled: Boolean) =
        launch { ds.setBoolean(MapPreferences.KEY_LAYER_SATELLITE, enabled) }

    fun setSafetyLayer(enabled: Boolean) =
        launch { ds.setBoolean(MapPreferences.KEY_LAYER_SAFETY, enabled) }

    fun setTransitLayer(enabled: Boolean) =
        launch { ds.setBoolean(MapPreferences.KEY_LAYER_TRANSIT, enabled) }

    fun setGoogleServices(enabled: Boolean) =
        launch {
            ds.setBoolean(MapPreferences.KEY_GOOGLE_SERVICES, enabled)
            ds.setBoolean(MapPreferences.KEY_GOOGLE_CONSENT_ASKED, true)
        }

    private fun boolPref(key: String, default: Boolean): StateFlow<Boolean> =
        ds.booleanFlow(key).stateIn(
            viewModelScope, SharingStarted.Eagerly, ds.getBoolean(key, default),
        )

    private fun launch(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
