package com.vayunmathur.weather.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.weather.R
import com.vayunmathur.weather.Route
import com.vayunmathur.weather.data.SavedLocation
import com.vayunmathur.weather.intents.parseLocalIsoToEpochSec
import com.vayunmathur.weather.ui.components.CurrentWeatherCard
import com.vayunmathur.weather.ui.components.DailyCard
import com.vayunmathur.weather.ui.components.HourlyCard
import com.vayunmathur.weather.ui.components.MainSearchBar
import com.vayunmathur.weather.ui.components.MetricGraphSheet
import com.vayunmathur.weather.ui.components.SelectedDateTimeHeader
import com.vayunmathur.weather.ui.components.SummaryCard
import com.vayunmathur.weather.ui.components.WeatherBlocks
import com.vayunmathur.weather.util.PressureUnit
import com.vayunmathur.weather.util.TemperatureUnit
import com.vayunmathur.weather.util.WeatherMetric
import com.vayunmathur.weather.util.WeatherViewModel
import com.vayunmathur.weather.util.WindUnit
import com.vayunmathur.weather.util.formatPressure
import com.vayunmathur.weather.util.formatTemperatureCompact
import com.vayunmathur.weather.util.formatWind
import com.vayunmathur.weather.util.resolveConditions
import kotlin.math.roundToInt
import kotlinx.datetime.toLocalDateTime
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomePage(backStack: NavBackStack<Route>, viewModel: WeatherViewModel) {
    val locations by viewModel.savedLocations.collectAsState()

    if (locations.isEmpty()) {
        EmptyHome(
            viewModel = viewModel,
            onAddLocation = { backStack.add(Route.SearchLocation) },
        )
        return
    }

    var activeLocationId by remember { mutableStateOf(locations.first().id) }
    val activeLocation = locations.firstOrNull { it.id == activeLocationId } ?: locations.first()

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val closeDrawer = { scope.launch { drawerState.close() } }

    BackHandler(enabled = drawerState.isOpen) { closeDrawer() }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surfaceContainer,
            ) {
                LocationsScreen(
                    backStack = backStack,
                    viewModel = viewModel,
                    activeLocation = activeLocation,
                    onLocationSelect = { picked ->
                        activeLocationId = picked.id
                        viewModel.clearSelection()
                        closeDrawer()
                    },
                    onClose = { closeDrawer() },
                )
            }
        },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) { paddingValues ->
            LocationPage(
                backStack = backStack,
                viewModel = viewModel,
                location = activeLocation,
                drawerState = drawerState,
                paddingValues = paddingValues,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationPage(
    backStack: NavBackStack<Route>,
    viewModel: WeatherViewModel,
    location: SavedLocation,
    drawerState: androidx.compose.material3.DrawerState,
    paddingValues: androidx.compose.foundation.layout.PaddingValues,
) {
    val forecasts by viewModel.forecasts.collectAsState()
    val selected by viewModel.selectedDateOrTime.collectAsState()
    val tempUnit = com.vayunmathur.weather.util.rememberTempUnit()
    val windUnit = com.vayunmathur.weather.util.rememberWindUnit()
    val pressureUnit = com.vayunmathur.weather.util.rememberPressureUnit()
    val use24Hour = com.vayunmathur.weather.util.rememberUse24Hour()
    var graphMetric by remember { mutableStateOf<com.vayunmathur.weather.util.WeatherMetric?>(null) }

    LaunchedEffect(location.id) {
        while (true) {
            viewModel.ensureForecast(location)
            kotlinx.coroutines.delay(60_000)
        }
    }

    val state = forecasts[location.id]
    val forecast = state?.forecast
    val scrollState = rememberScrollState()

    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = state?.refreshing == true,
        onRefresh = { viewModel.ensureForecast(location, force = true) },
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
            MainSearchBar(
                paddingValues = paddingValues,
                drawerState = drawerState,
                activeLocation = location,
            )

            if (forecast == null) {
                Box(modifier = Modifier.fillMaxSize().padding(top = 64.dp), contentAlignment = Alignment.TopCenter) {
                    if (state?.error != null) {
                        Text(state.error, color = MaterialTheme.colorScheme.error)
                    } else {
                        CircularProgressIndicator()
                    }
                }
                return@Column
            }

            val current = forecast.current
            val daily = forecast.daily
            val resolved = resolveConditions(forecast, selected)

            var lastSelection by remember { mutableStateOf(selected) }
            LaunchedEffect(selected) { if (selected != null) lastSelection = selected }
            androidx.compose.animation.AnimatedVisibility(
                visible = selected != null,
                enter = androidx.compose.animation.expandVertically() + androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.shrinkVertically() + androidx.compose.animation.fadeOut(),
            ) {
                (selected ?: lastSelection)?.let { sel ->
                    SelectedDateTimeHeader(
                        selection = sel,
                        forecast = forecast,
                        use24Hour = use24Hour,
                        onClear = { viewModel.clearSelection() },
                    )
                }
            }

            if (current != null && resolved != null) {
                CurrentWeatherCard(
                    weatherCode = resolved.weatherCode,
                    isDay = resolved.isDay,
                    temperature = resolved.temperature,
                    apparentTemperature = resolved.apparentTemperature,
                    high = resolved.high,
                    low = resolved.low,
                    tempUnit = tempUnit,
                )
            }
            Column(
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (selected == null) {
                    SummaryCard(forecast = forecast, tempUnit = tempUnit)
                }
                if (forecast.hourly != null) {
                    HourlyCard(
                        hourly = forecast.hourly,
                        tempUnit = tempUnit,
                        utcOffsetSeconds = forecast.utcOffsetSeconds,
                        use24Hour = use24Hour,
                        selectedIsoTime = (selected as? com.vayunmathur.weather.util.SelectedDateOrTime.Time)?.isoTime,
                        onHourSelected = { viewModel.toggleTime(it) },
                        scrollToIsoDate = (selected as? com.vayunmathur.weather.util.SelectedDateOrTime.Day)?.isoDate,
                    )
                }
                if (daily != null) {
                    DailyCard(
                        daily = daily,
                        tempUnit = tempUnit,
                        selectedIsoDate = (selected as? com.vayunmathur.weather.util.SelectedDateOrTime.Day)?.isoDate,
                        onDaySelected = { viewModel.toggleDay(it) },
                    )
                }
                if (current != null && resolved != null) {
                    val sunriseEpoch = resolved.sunriseIso?.let { parseLocalIsoToEpochSec(it, forecast.utcOffsetSeconds) }
                    val sunsetEpoch = resolved.sunsetIso?.let { parseLocalIsoToEpochSec(it, forecast.utcOffsetSeconds) }
                    val nowcast = if (selected == null) {
                        com.vayunmathur.weather.util.precipitationNowcast(forecast.minutely15, forecast.utcOffsetSeconds)
                    } else {
                        null
                    }
                    WeatherBlocks(
                        current = resolved.blockCurrent,
                        uvIndex = resolved.uvIndexMax,
                        air = state.airQuality?.current,
                        sunriseEpochSec = sunriseEpoch,
                        sunsetEpochSec = sunsetEpoch,
                        precipitationMm = resolved.precipitationSum,
                        precipitationNowcast = nowcast,
                        daylightDurationSec = resolved.daylightDurationSec,
                        onMetricSelected = { graphMetric = it },
                        tempUnit = tempUnit,
                        windUnit = windUnit,
                        pressureUnit = pressureUnit,
                        use24Hour = use24Hour,
                    )
                }

            }
        }

        val gm = graphMetric
        if (gm != null && forecast != null) {
            MetricGraphSheet(
                title = gm.title,
                points = com.vayunmathur.weather.util.metricSeries(forecast, gm, selected),
                valueLabel = metricValueFormatter(gm, tempUnit, windUnit, pressureUnit),
                timeLabel = { epoch -> formatGraphTime(epoch, use24Hour) },
                onDismiss = { graphMetric = null },
            )
        }
    }
}

/** Per-metric display formatter for graph value labels. */
private fun metricValueFormatter(
    metric: WeatherMetric,
    tempUnit: TemperatureUnit,
    windUnit: WindUnit,
    pressureUnit: PressureUnit,
): (Double) -> String = when (metric) {
    WeatherMetric.Temperature, WeatherMetric.FeelsLike, WeatherMetric.DewPoint ->
        { v -> formatTemperatureCompact(v, tempUnit) }
    WeatherMetric.Humidity, WeatherMetric.CloudCover ->
        { v -> "${v.roundToInt()}%" }
    WeatherMetric.Precipitation ->
        { v -> if (windUnit == WindUnit.Mph) String.format("%.2f in", v / 25.4) else String.format("%.1f mm", v) }
    WeatherMetric.WindSpeed, WeatherMetric.WindGusts ->
        { v -> formatWind(v, windUnit) }
    WeatherMetric.Pressure ->
        { v -> formatPressure(v, pressureUnit) }
    WeatherMetric.Visibility ->
        { v -> if (windUnit == WindUnit.Mph) "${(v / 1609.34).roundToInt()} mi" else "${(v / 1000).roundToInt()} km" }
    WeatherMetric.UvIndex ->
        { v -> v.roundToInt().toString() }
}

/** Format an epoch second as a local hour label for the graph axis. */
private fun formatGraphTime(epochSec: Long, use24Hour: Boolean): String {
    val ldt = kotlin.time.Instant.fromEpochSeconds(epochSec)
        .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
    val h = ldt.hour
    return if (use24Hour) {
        "%02d:00".format(h)
    } else {
        val display = if (h % 12 == 0) 12 else h % 12
        val ampm = if (h < 12) "AM" else "PM"
        "$display $ampm"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmptyHome(viewModel: WeatherViewModel, onAddLocation: () -> Unit) {
    val (onUseCurrent, requesting) = rememberRequestDeviceLocation(viewModel)

    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.weather_title)) }) }) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No locations yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Add a city or use your current location to see the forecast.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                )
                Button(onClick = onAddLocation) { Text(stringResource(R.string.add_location)) }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { onUseCurrent() },
                    enabled = !requesting,
                ) {
                    if (requesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(if (requesting) "Locating…" else "Use current location")
                }
            }
        }
    }
}
