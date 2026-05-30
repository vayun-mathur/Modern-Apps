package com.vayunmathur.weather.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.R as LibraryR
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.weather.Route
import com.vayunmathur.weather.data.SavedLocation
import com.vayunmathur.weather.ui.components.CreditsBottomSection
import com.vayunmathur.weather.ui.components.CurrentWeatherCard
import com.vayunmathur.weather.ui.components.DailyCard
import com.vayunmathur.weather.ui.components.HourlyCard
import com.vayunmathur.weather.ui.components.MainSearchBar
import com.vayunmathur.weather.ui.components.SummaryCard
import com.vayunmathur.weather.ui.components.WeatherBlocks
import com.vayunmathur.weather.util.WeatherViewModel
import kotlinx.coroutines.launch
import kotlin.time.Instant

/**
 * Port of WeatherMaster's `MainScreen` + `MainScreenScaffold`:
 *
 * - Wraps the main content in a `DismissibleNavigationDrawer` whose
 *   `drawerContent` is [LocationsScreen].
 * - The drawer is opened by the hamburger in [MainSearchBar].
 * - Below the search bar, the vertical scroll renders, in order:
 *   `CurrentWeatherCard` → `SummaryCard` → `HourlyCard` → `DailyCard` →
 *   `WeatherBlocks` → `CreditsBottomSection`. 16 dp horizontal padding,
 *   24 dp top, 14 dp spacing between cards.
 */
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

    // Track which location is "active" — we don't have a flag on the
    // entity itself, so default to the first row and let the user pick
    // others from the drawer.
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
    val tempUnit = com.vayunmathur.weather.util.rememberTempUnit()
    val windUnit = com.vayunmathur.weather.util.rememberWindUnit()

    LaunchedEffect(location.id) { viewModel.ensureForecast(location) }

    val state = forecasts[location.id]
    val forecast = state?.forecast
    val scrollState = rememberScrollState()

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
            return
        }

        val current = forecast.current
        val daily = forecast.daily
        val sunriseEpoch = daily?.sunrise?.firstOrNull()?.let { parseLocalIsoToEpochSec(it, forecast.utcOffsetSeconds) }
        val sunsetEpoch = daily?.sunset?.firstOrNull()?.let { parseLocalIsoToEpochSec(it, forecast.utcOffsetSeconds) }

        if (current != null) {
            CurrentWeatherCard(current = current, today = daily, tempUnit = tempUnit)
        }
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            SummaryCard(forecast = forecast, tempUnit = tempUnit)
            if (forecast.hourly != null) {
                HourlyCard(hourly = forecast.hourly, tempUnit = tempUnit)
            }
            if (daily != null) {
                DailyCard(daily = daily, tempUnit = tempUnit)
            }
            if (current != null) {
                WeatherBlocks(
                    current = current,
                    today = daily,
                    air = state.airQuality?.current,
                    sunriseEpochSec = sunriseEpoch,
                    sunsetEpochSec = sunsetEpoch,
                    tempUnit = tempUnit,
                    windUnit = windUnit,
                )
            }
            CreditsBottomSection()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EmptyHome(viewModel: WeatherViewModel, onAddLocation: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var requesting by remember { mutableStateOf(false) }

    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.any { it }) {
            requesting = true
            scope.launch {
                val loc = com.vayunmathur.weather.util.LocationProvider.currentLocation(context)
                if (loc != null) {
                    viewModel.setCurrentLocation(
                        name = "Current location",
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                    )
                } else {
                    android.widget.Toast.makeText(context, "Couldn't determine location", android.widget.Toast.LENGTH_SHORT).show()
                }
                requesting = false
            }
        } else {
            android.widget.Toast.makeText(context, "Location permission denied", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    val onUseCurrent = {
        if (com.vayunmathur.weather.util.LocationProvider.hasPermission(context)) {
            requesting = true
            scope.launch {
                val loc = com.vayunmathur.weather.util.LocationProvider.currentLocation(context)
                if (loc != null) {
                    viewModel.setCurrentLocation(
                        name = "Current location",
                        latitude = loc.latitude,
                        longitude = loc.longitude,
                    )
                }
                requesting = false
            }
            Unit
        } else {
            permissionLauncher.launch(
                arrayOf(
                    android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Weather") }) }) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No locations yet", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Add a city or use your current location to see the forecast.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                )
                Button(onClick = onAddLocation) { Text("Add a location") }
                androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 8.dp))
                androidx.compose.material3.OutlinedButton(
                    onClick = { onUseCurrent() },
                    enabled = !requesting,
                ) {
                    if (requesting) {
                        CircularProgressIndicator(
                            modifier = Modifier.padding(end = 8.dp).then(Modifier),
                            strokeWidth = 2.dp,
                        )
                    }
                    Text(if (requesting) "Locating…" else "Use current location")
                }
            }
        }
    }
}

/**
 * Open-Meteo returns ISO local-time strings (no offset) when timezone=auto
 * is requested. Combine with the response's `utc_offset_seconds` to
 * produce an actual epoch.
 */
private fun parseLocalIsoToEpochSec(iso: String, utcOffsetSec: Int): Long? {
    val padded = if (iso.length == 16) "$iso:00" else iso
    return runCatching {
        Instant.parse("${padded}Z").epochSeconds - utcOffsetSec
    }.getOrNull()
}
