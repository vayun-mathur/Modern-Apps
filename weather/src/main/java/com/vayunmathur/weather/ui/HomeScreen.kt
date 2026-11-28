package com.vayunmathur.weather.ui

import androidx.activity.compose.BackHandler
import com.vayunmathur.library.ui.ExpandVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.DrawerState
import com.vayunmathur.library.ui.DrawerValue
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.isExpandedWidth
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ModalDrawerSheet
import com.vayunmathur.library.ui.ModalNavigationDrawer
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.rememberDrawerState
import com.vayunmathur.library.ui.isExpandedWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.weather.R
import com.vayunmathur.weather.Route
import com.vayunmathur.weather.ui.components.CurrentWeatherCard
import com.vayunmathur.weather.ui.components.DailyCard
import com.vayunmathur.weather.ui.components.HourlyCard
import com.vayunmathur.weather.ui.components.MainSearchBar
import com.vayunmathur.weather.ui.components.MetricGraphSheet
import com.vayunmathur.weather.ui.components.SelectedDateTimeHeader
import com.vayunmathur.weather.ui.components.SummaryCard
import com.vayunmathur.weather.ui.components.WeatherBlocks
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
import com.vayunmathur.weather.platform.WeatherViewModel
import com.vayunmathur.weather.domain.forecastColumnCount
import com.vayunmathur.weather.domain.homeLayoutForWidthDp
import com.vayunmathur.weather.domain.homeUsesPermanentLocationsPanel
import com.vayunmathur.weather.domain.locationsPanelWidthDp
import com.vayunmathur.weather.platform.precipitationNowcast
import com.vayunmathur.weather.platform.rememberDisplayUnits
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The forecast page, with no dependency on the ViewModel, the back stack or the clock so it
 * can be rendered from a `@Preview` — see `src/screenshotTest`, which is where the store
 * listing images come from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: LocationUiState,
    units: DisplayUnits,
    actions: WeatherActions,
    drawerState: DrawerState = rememberDrawerState(DrawerValue.Closed),
    /** Short-range rain outlook; reads a clock, so the binder resolves it. */
    precipitationNowcast: String? = null,
    /**
     * "Now" for the hourly strip and the sun arc. A parameter rather than a clock read so a
     * preview's fixed sample data can't age out from under it.
     */
    nowEpochSec: Long = System.currentTimeMillis() / 1000,
    onOpenMap: (WeatherMetric, String?) -> Unit = { _, _ -> },
    /** The locations drawer. Empty in a preview, which has no saved-location list. */
    drawerContent: @Composable () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }

    // Lay out for the width actually available here, not the activity
    // window: hosted in a pane, this screen must size itself to the pane.
    // (The released squeeze came from the opposite assumption upstream —
    // the navigation host confined this screen to a list pane while it
    // still laid out as if it owned the whole window.)
    androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val availableWidthDp = maxWidth.value.toInt()
        val layout = homeLayoutForWidthDp(availableWidthDp)
        val wide = forecastColumnCount(layout) > 1

        // Expanded windows (large tablets, desktop) pin the locations list
        // as a permanent side panel next to the forecast instead of hiding
        // it in a modal drawer — there is room for both, and picking another
        // location without losing the current forecast is the point of the
        // wide layout. The panel is a bounded list width (280–360dp), so
        // the forecast keeps the majority of the window at any size.
        if (homeUsesPermanentLocationsPanel(layout)) {
            Row(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .width(locationsPanelWidthDp(availableWidthDp).dp)
                        .fillMaxHeight(),
                ) {
                    drawerContent()
                }
                ForecastScaffold(
                    state = state,
                    units = units,
                    actions = actions,
                    drawerState = drawerState,
                    precipitationNowcast = precipitationNowcast,
                    nowEpochSec = nowEpochSec,
                    onOpenMap = onOpenMap,
                    wide = wide,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
            return@BoxWithConstraints
        }

        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                ModalDrawerSheet(
                    drawerContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ) {
                    drawerContent()
                }
            },
        ) {
            ForecastScaffold(
                state = state,
                units = units,
                actions = actions,
                drawerState = drawerState,
                precipitationNowcast = precipitationNowcast,
                nowEpochSec = nowEpochSec,
                onOpenMap = onOpenMap,
                wide = wide,
            )
        }
    }
}
