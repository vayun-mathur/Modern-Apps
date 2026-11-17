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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.DrawerState
import com.vayunmathur.library.ui.DrawerValue
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.isExpandedWidth
import com.vayunmathur.library.ui.LoadingIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ModalDrawerSheet
import com.vayunmathur.library.ui.ModalNavigationDrawer
import com.vayunmathur.library.ui.OutlinedButton
import com.vayunmathur.library.ui.PullToRefreshBox
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.rememberDrawerState
import com.vayunmathur.library.ui.appBarScrollBehavior
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
import com.vayunmathur.weather.platform.precipitationNowcast
import com.vayunmathur.weather.platform.rememberDisplayUnits
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Binds [WeatherViewModel] and the back stack to the stateless [HomeScreen].
 * [locationId] is the selected location carried by `Route.Home`; null (or a
 * since-deleted id) resolves to the first saved location.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomePage(
    backStack: NavBackStack<Route>,
    viewModel: WeatherViewModel,
    locationId: Long? = null,
) {
    val locations = viewModel.savedLocations.collectAsState().value

    if (locations == null) {
        // Room hasn't answered yet. Hold a plain surface rather than falling
        // through to EmptyHome, which would flash the location chooser on
        // every cold start before the saved rows arrive.
        // RAW SCAFFOLD EXCEPTION: cold-start placeholder — a bare, bar-less,
        // content-less surface. No shared scaffold applies (they all draw chrome).
        Scaffold(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {}
        return
    }

    if (locations.isEmpty()) {
        EmptyHome(
            viewModel = viewModel,
            onAddLocation = { backStack.add(Route.SearchLocation) },
        )
        return
    }

    val activeLocation = locations.firstOrNull { it.id == locationId } ?: locations.first()

    val forecasts by viewModel.forecasts.collectAsState()
    val selected by viewModel.selectedDateOrTime.collectAsState()
    val forecastState = forecasts[activeLocation.id]
    val forecast = forecastState?.forecast

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(activeLocation.id, lifecycleOwner) {
        // Only poll while the app is actually in the foreground; repeatOnLifecycle
        // cancels the loop when the app is backgrounded and restarts it on resume,
        // so we don't hit the network every 60s behind the user's back (the hourly
        // WorkManager job covers background refreshes).
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                viewModel.refreshAll()
                delay(60_000)
            }
        }
    }

    // "Rain in ~30 min" needs both the wall clock and the string table, so it is
    // resolved here rather than inside the screen — see WeatherUiContract.
    val context = LocalContext.current
    val nowcast = if (selected == null && forecast != null) {
        precipitationNowcast(context, forecast.minutely15, forecast.utcOffsetSeconds)
    } else {
        null
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val closeDrawer = { scope.launch { drawerState.close() } }

    HomeScreen(
        state = LocationUiState(
            location = activeLocation,
            forecast = forecast,
            airQuality = forecastState?.airQuality?.current,
            refreshing = forecastState?.refreshing == true,
            error = forecastState?.error,
            selected = selected,
        ),
        units = rememberDisplayUnits(),
        actions = viewModel,
        drawerState = drawerState,
        precipitationNowcast = nowcast,
        onOpenMap = { metric, isoTime ->
            backStack.add(
                Route.WeatherMap(
                    latitude = activeLocation.latitude,
                    longitude = activeLocation.longitude,
                    name = activeLocation.name,
                    isoTime = isoTime,
                    metric = metric.name,
                )
            )
        },
        drawerContent = {
            LocationsPage(
                backStack = backStack,
                viewModel = viewModel,
                activeLocation = activeLocation,
                onLocationSelect = { picked ->
                    // The selection lives exactly in the back stack: rewrite
                    // the current Home entry (never push a second Home).
                    if (backStack.backStack.lastOrNull() is Route.Home) {
                        backStack.setLast(Route.Home(picked.id))
                    } else {
                        backStack.reset(Route.Home(picked.id))
                    }
                    viewModel.clearSelection()
                    closeDrawer()
                },
                onClose = { closeDrawer() },
            )
        },
    )
}


/**
 * The forecast body under its floating in-content search bar. Shared by the
 * modal-drawer layout (compact) and the permanent side-panel layout (expanded)
 * above, so the two widths cannot drift apart.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ForecastScaffold(
    state: LocationUiState,
    units: DisplayUnits,
    actions: WeatherActions,
    drawerState: DrawerState,
    precipitationNowcast: String?,
    nowEpochSec: Long,
    onOpenMap: (WeatherMetric, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        // RAW SCAFFOLD EXCEPTION: bespoke full-bleed home. The forecast
        // scrolls under a floating in-content MainSearchBar (there is no top
        // app bar), inside a ModalNavigationDrawer + PullToRefreshBox, and the
        // body consumes the scaffold insets itself. No shared scaffold fits.
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) { paddingValues ->
        ForecastColumn(
            state = state,
            units = units,
            actions = actions,
            drawerState = drawerState,
            paddingValues = paddingValues,
            precipitationNowcast = precipitationNowcast,
            nowEpochSec = nowEpochSec,
            onOpenMap = onOpenMap,
        )
    }
}

/**
 * Shown in place of the forecast when nothing is pinned yet. Stays on the ViewModel: it is
 * an empty state, not a listing shot.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmptyHome(viewModel: WeatherViewModel, onAddLocation: () -> Unit) {
    val (onUseCurrent, requesting) = rememberRequestDeviceLocation(viewModel)

    AppScaffold(title = stringResource(R.string.app_name), scrollBehavior = appBarScrollBehavior()) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.no_locations_yet), style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.add_a_city_or_use_your_current_location),
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
                    Text(if (requesting) stringResource(R.string.locating) else stringResource(R.string.use_current_location))
                }
            }
        }
    }
}
