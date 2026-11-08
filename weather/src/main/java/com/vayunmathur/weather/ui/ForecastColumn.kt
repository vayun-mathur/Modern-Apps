package com.vayunmathur.weather.ui

import com.vayunmathur.library.ui.ExpandVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.LoadingIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.AppPullToRefreshBox
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.weather.platform.DisplayUnits
import com.vayunmathur.weather.platform.LocationUiState
import com.vayunmathur.weather.domain.SelectedDateOrTime
import com.vayunmathur.weather.domain.WeatherMetric
import com.vayunmathur.weather.domain.formatHourAxisLabel
import com.vayunmathur.weather.domain.metricSeries
import com.vayunmathur.weather.domain.metricValueFormatter
import com.vayunmathur.weather.domain.parseLocalIsoToEpochSec
import com.vayunmathur.weather.domain.resolveConditions
import com.vayunmathur.weather.platform.WeatherActions
import com.vayunmathur.weather.ui.components.CurrentWeatherCard
import com.vayunmathur.weather.ui.components.DailyCard
import com.vayunmathur.weather.ui.components.HourlyCard
import com.vayunmathur.weather.ui.components.MainSearchBar
import com.vayunmathur.weather.ui.components.MetricGraphSheet
import com.vayunmathur.weather.ui.components.SelectedDateTimeHeader
import com.vayunmathur.weather.ui.components.SummaryCard
import com.vayunmathur.weather.ui.components.WeatherBlocks
import com.vayunmathur.library.ui.DrawerState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ForecastColumn(
    state: LocationUiState,
    units: DisplayUnits,
    actions: WeatherActions,
    drawerState: DrawerState,
    paddingValues: PaddingValues,
    precipitationNowcast: String?,
    nowEpochSec: Long,
    onOpenMap: (WeatherMetric, String?) -> Unit,
) {
    var graphMetric by remember { mutableStateOf<WeatherMetric?>(null) }

    val forecast = state.forecast
    val selected = state.selected
    val scrollState = rememberScrollState()

    AppPullToRefreshBox(
        // No pull indicator on first load: the centered LoadingIndicator below
        // already covers it, and the two spinners stack. Refreshes still show it.
        isRefreshing = state.refreshing && forecast != null,
        onRefresh = { actions.refreshAll(force = true) },
        modifier = Modifier.fillMaxSize(),
        // Same spot as the first-load LoadingIndicator below (64.dp clears the
        // floating search bar); the indicator slot itself is always centered.
        topClearance = 64.dp,
    ) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
            MainSearchBar(
                paddingValues = paddingValues,
                drawerState = drawerState,
                activeLocation = state.location,
            )

            if (forecast == null) {
                Box(modifier = Modifier.fillMaxSize().padding(top = 64.dp), contentAlignment = Alignment.TopCenter) {
                    val error = state.error
                    if (error != null) {
                        Text(error, color = MaterialTheme.colorScheme.error)
                    } else {
                        LoadingIndicator()
                    }
                }
                return@Column
            }

            val current = forecast.current
            val daily = forecast.daily
            val resolved = resolveConditions(forecast, selected)

            var lastSelection by remember { mutableStateOf(selected) }
            LaunchedEffect(selected) { if (selected != null) lastSelection = selected }
            ExpandVisibility(visible = selected != null) {
                (selected ?: lastSelection)?.let { sel ->
                    SelectedDateTimeHeader(
                        selection = sel,
                        use24Hour = units.use24Hour,
                        onClear = { actions.clearSelection() },
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
                    tempUnit = units.temperature,
                )
            }
            Column(
                // Include the navigation-bar inset so the last cards (Air quality /
                // Pollen) clear the system nav bar and can scroll fully into view.
                modifier = Modifier.padding(
                    start = 16.dp,
                    end = 16.dp,
                    top = 24.dp,
                    bottom = paddingValues.calculateBottomPadding(),
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (selected == null) {
                    SummaryCard(forecast = forecast, tempUnit = units.temperature)
                }
                if (forecast.hourly != null) {
                    HourlyCard(
                        hourly = forecast.hourly,
                        tempUnit = units.temperature,
                        utcOffsetSeconds = forecast.utcOffsetSeconds,
                        use24Hour = units.use24Hour,
                        selectedIsoTime = (selected as? SelectedDateOrTime.Time)?.isoTime,
                        onHourSelected = { actions.toggleTime(it) },
                        scrollToIsoDate = (selected as? SelectedDateOrTime.Day)?.isoDate,
                        nowIsoHour = forecast.current?.time?.take(13)?.let { "$it:00" },
                        nowEpochSec = nowEpochSec,
                    )
                }
                if (daily != null) {
                    DailyCard(
                        daily = daily,
                        tempUnit = units.temperature,
                        selectedIsoDate = (selected as? SelectedDateOrTime.Day)?.isoDate,
                        onDaySelected = { actions.toggleDay(it) },
                        todayIsoDate = forecast.current?.time?.substringBefore('T'),
                    )
                }
                if (current != null && resolved != null) {
                    val sunriseEpoch = resolved.sunriseIso?.let { parseLocalIsoToEpochSec(it, forecast.utcOffsetSeconds) }
                    val sunsetEpoch = resolved.sunsetIso?.let { parseLocalIsoToEpochSec(it, forecast.utcOffsetSeconds) }
                    val moonriseEpoch = resolved.moonriseIso?.let { parseLocalIsoToEpochSec(it, forecast.utcOffsetSeconds) }
                    val moonsetEpoch = resolved.moonsetIso?.let { parseLocalIsoToEpochSec(it, forecast.utcOffsetSeconds) }
                    WeatherBlocks(
                        current = resolved.blockCurrent,
                        uvIndex = resolved.uvIndexMax,
                        air = state.airQuality,
                        sunriseEpochSec = sunriseEpoch,
                        sunsetEpochSec = sunsetEpoch,
                        precipitationMm = resolved.precipitationSum,
                        precipitationNowcast = precipitationNowcast,
                        daylightDurationSec = resolved.daylightDurationSec,
                        moonPhase = resolved.moonPhase,
                        moonriseEpochSec = moonriseEpoch,
                        moonsetEpochSec = moonsetEpoch,
                        onMetricSelected = { graphMetric = it },
                        tempUnit = units.temperature,
                        windUnit = units.wind,
                        pressureUnit = units.pressure,
                        use24Hour = units.use24Hour,
                        nowEpochSec = nowEpochSec,
                        utcOffsetSeconds = forecast.utcOffsetSeconds,
                    )
                }
            }
        }

        val gm = graphMetric
        if (gm != null && forecast != null) {
            MetricGraphSheet(
                title = stringResource(gm.title),
                points = metricSeries(forecast, gm, selected),
                valueLabel = metricValueFormatter(gm, units.temperature, units.wind, units.pressure),
                timeLabel = { epoch -> formatHourAxisLabel(epoch, units.use24Hour, forecast.utcOffsetSeconds) },
                onOpenMap = {
                    val iso = when (val s = selected) {
                        is SelectedDateOrTime.Time -> s.isoTime
                        is SelectedDateOrTime.Day -> "${s.isoDate}T00:00"
                        null -> null
                    }
                    onOpenMap(gm, iso)
                    graphMetric = null
                },
                onDismiss = { graphMetric = null },
            )
        }
    }
}
