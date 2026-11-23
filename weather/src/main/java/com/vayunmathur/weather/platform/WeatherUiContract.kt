package com.vayunmathur.weather.platform

import androidx.compose.runtime.Composable
import com.vayunmathur.weather.data.SavedLocation
import com.vayunmathur.weather.domain.PressureUnit
import com.vayunmathur.weather.domain.SelectedDateOrTime
import com.vayunmathur.weather.domain.TemperatureUnit
import com.vayunmathur.weather.domain.WindUnit
import com.vayunmathur.weather.network.AirQualityCurrent
import com.vayunmathur.weather.network.ForecastResponse

/**
 * The UI contract between [WeatherViewModel] and the screens.
 *
 * Screens take a state value plus an actions interface rather than the ViewModel itself,
 * so they can be rendered by a `@Preview` — which is what the store listing images are
 * generated from. It lives in `util` rather than `ui` so the dependency runs one way:
 * `ui` depends on `util`, and the ViewModel implements the actions interface.
 */

/**
 * Display units and the 12/24-hour choice, resolved from the device's regional
 * preferences by the binder rather than inside the screens. Hoisting them is what keeps a
 * preview reproducible: the render machine's locale and clock setting no longer leak into
 * the image.
 */
data class DisplayUnits(
    val temperature: TemperatureUnit = TemperatureUnit.Celsius,
    val wind: WindUnit = WindUnit.KmH,
    val pressure: PressureUnit = PressureUnit.Hpa,
    val use24Hour: Boolean = false,
)

/** Read [DisplayUnits] from the device's regional preferences. */
@Composable
fun rememberDisplayUnits(): DisplayUnits = DisplayUnits(
    temperature = rememberTempUnit(),
    wind = rememberWindUnit(),
    pressure = rememberPressureUnit(),
    use24Hour = rememberUse24Hour(),
)

/** Everything the forecast page draws for one saved location. */
data class LocationUiState(
    val location: SavedLocation,
    val forecast: ForecastResponse? = null,
    val airQuality: AirQualityCurrent? = null,
    val refreshing: Boolean = false,
    val error: String? = null,
    /** The hour/day the user is inspecting, or null for "now / today". */
    val selected: SelectedDateOrTime? = null,
)

/**
 * One row of the locations drawer. [description] ("Last updated 4m ago" / "No data yet")
 * is resolved by the binder because it needs both a [android.content.Context] for the
 * string and a clock — neither of which belongs in a screen that has to render
 * identically every time.
 */
data class LocationRow(
    val location: SavedLocation,
    val description: String,
    val weatherCode: Int? = null,
    val isDay: Boolean = true,
)

/** Everything the locations drawer draws. */
data class LocationsUiState(
    val rows: List<LocationRow> = emptyList(),
    /** False until the saved-locations DAO emits at least once (the VM flow starts null). */
    val hasLoaded: Boolean = true,
    val activeLocationId: Long? = null,
    /** True while a device-location fix is being requested. */
    val deviceLocationLoading: Boolean = false,
)

/**
 * Callbacks the screens fire. Every method has a no-op default so a preview can render a
 * screen without supplying behaviour — [Noop] is the whole implementation a preview needs.
 * The signatures match [WeatherViewModel]'s existing methods, which is why the ViewModel
 * implements this directly rather than through an adapter.
 */
interface WeatherActions {
    fun refreshAll(force: Boolean = false) {}
    fun toggleTime(isoTime: String) {}
    fun toggleDay(isoDate: String) {}
    fun clearSelection() {}
    fun deleteLocation(location: SavedLocation) {}
    fun reorderLocations(ordered: List<SavedLocation>) {}

    /** Shared constants and helpers for WeatherActions. */
    companion object {
        val Noop: WeatherActions = object : WeatherActions {}
    }
}
