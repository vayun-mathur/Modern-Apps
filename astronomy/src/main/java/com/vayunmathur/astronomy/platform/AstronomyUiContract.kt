package com.vayunmathur.astronomy.platform

import android.hardware.SensorManager
import kotlinx.datetime.TimeZone
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The UI contract between [AstronomyViewModel] and the pages in `ui.pages`.
 *
 * Pages take a state value plus an actions interface rather than the ViewModel itself, so
 * they can be rendered by a `@Preview` — which is what the store listing images are
 * generated from (see `src/screenshotTest`). It lives beside the ViewModel rather than in
 * `ui.pages` so the dependency runs one way: `ui.pages` depends on `ui`, and the ViewModel
 * implements these interfaces.
 *
 * Every actions method has a no-op default body, so [SkyMapActions.Noop] and friends are
 * the whole implementation a preview needs.
 */

/**
 * Everything the sky map draws. The projection inputs ([centerAzRad], [centerAltRad],
 * [rotationRad]) are already resolved from the device sensors by the binder, and
 * [visibleSky] is already computed for [simTime] — so nothing here reads the clock or the
 * compass, which is what lets a preview pin the whole screen to one instant.
 */
@OptIn(ExperimentalTime::class)
data class SkyMapUiState(
    val visibleSky: VisibleSky,
    val simTime: Instant,
    /** Zone the time scrubber labels itself in. */
    val timeZone: TimeZone,
    val isLive: Boolean = true,
    val fovDeg: Float = 70f,
    val centerAzRad: Double = 0.0,
    val centerAltRad: Double = 0.0,
    val rotationRad: Double = 0.0,
    val constellationMode: ConstellationMode = ConstellationMode.LINES,
    val showGrid: Boolean = true,
    val showDeepSky: Boolean = true,
    val showPlanets: Boolean = true,
    val nightMode: Boolean = false,
    val trajectory: List<TrajectoryPoint> = emptyList(),
    val selectedObjectId: String? = null,
    /** Magnetometer accuracy from the device sensors; drives the calibration banner. */
    val accuracy: Int = SensorManager.SENSOR_STATUS_ACCURACY_HIGH,
)

/** Sky map callbacks. */
@OptIn(ExperimentalTime::class)
interface SkyMapActions {
    fun setFov(v: Float) {}
    fun selectObject(id: String) {}
    fun setTime(instant: Instant, live: Boolean = false) {}

    /** Holds the shared no-op [SkyMapActions] instance for previews. */
    companion object {
        val Noop: SkyMapActions = object : SkyMapActions {}
    }
}

/**
 * Search callbacks. [search] is a query rather than a state field because the results are
 * recomputed on every keystroke and never outlive the screen.
 */
interface SearchActions {
    fun search(query: String): List<SearchResult> = emptyList()
    fun selectObject(id: String) {}

    /** Holds the shared no-op [SearchActions] instance for previews. */
    companion object {
        val Noop: SearchActions = object : SearchActions {}
    }
}

/** Everything the settings screen draws. */
data class SettingsUiState(
    val constellationMode: ConstellationMode = ConstellationMode.LINES,
    val showGrid: Boolean = true,
    val showDeepSky: Boolean = true,
    val showPlanets: Boolean = true,
    val showBelowHorizon: Boolean = true,
    val nightMode: Boolean = false,
    val magLimit: Float = 6f,
    val fovDeg: Float = 70f,
    val latDeg: Double? = null,
    val lonDeg: Double? = null,
    val starCount: Int = 0,
    val constellationCount: Int = 0,
    val deepSkyCount: Int = 0,
)

/** Settings callbacks. Parameter names match the ViewModel setters they bind to. */
interface SettingsActions {
    fun setShowConstellations(mode: ConstellationMode) {}
    fun setShowGrid(v: Boolean) {}
    fun setShowDeepSky(v: Boolean) {}
    fun setShowPlanets(v: Boolean) {}
    fun setShowBelowHorizon(v: Boolean) {}
    fun setNightMode(v: Boolean) {}
    fun setMagLimit(v: Float) {}
    fun setFov(v: Float) {}
    fun setManualLocation(latDeg: Double, lonDeg: Double) {}
    fun refreshLocation() {}

    /** Holds the shared no-op [SettingsActions] instance for previews. */
    companion object {
        val Noop: SettingsActions = object : SettingsActions {}
    }
}
