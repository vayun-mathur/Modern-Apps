package com.vayunmathur.weather.ui

import android.Manifest
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.R as LibraryR
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.weather.R
import com.vayunmathur.weather.Route
import com.vayunmathur.weather.data.SavedLocation
import com.vayunmathur.weather.network.GeocodingResult
import com.vayunmathur.weather.network.WeatherApi
import com.vayunmathur.weather.ui.components.LocationItem
import com.vayunmathur.weather.ui.components.UseDeviceLocationCard
import com.vayunmathur.weather.util.LocationProvider
import com.vayunmathur.weather.util.WeatherViewModel
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/**
 * Composable helper that provides a device-location request action with
 * permission handling. Returns the onClick lambda and a loading flag.
 * Used by both [LocationsScreen] and [EmptyHome][com.vayunmathur.weather.ui.EmptyHome].
 */
@Composable
internal fun rememberRequestDeviceLocation(
    viewModel: WeatherViewModel,
): Pair<() -> Unit, Boolean> {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }

    val fetchLocation: () -> Unit = {
        loading = true
        scope.launch {
            val loc = LocationProvider.currentLocation(context)
            if (loc != null) {
                viewModel.setCurrentLocation("Current location", loc.latitude, loc.longitude)
            } else {
                Toast.makeText(context, "Couldn't determine location", Toast.LENGTH_SHORT).show()
            }
            loading = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted.values.any { it }) {
            fetchLocation()
        } else {
            Toast.makeText(context, "Location permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    val onClick = {
        if (LocationProvider.hasPermission(context)) {
            fetchLocation()
        } else {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
        }
    }

    return onClick to loading
}

/**
 * Locations drawer content. Renders inside [HomePage]'s
 * `DismissibleNavigationDrawer`. Scaffold with a back/close top bar,
 * a scrollable list of [LocationItem]s, and a full-width "Search location"
 * Button pinned to the bottom (in the Scaffold's `bottomBar` slot). The
 * search button opens a [SearchLocationDialog] for picking a city by name.
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.coroutines.FlowPreview::class,
)
@Composable
fun LocationsScreen(
    backStack: NavBackStack<Route>,
    viewModel: WeatherViewModel,
    activeLocation: SavedLocation?,
    onLocationSelect: (SavedLocation) -> Unit,
    onClose: () -> Unit,
) {
    val locations by viewModel.savedLocations.collectAsState()
    val forecasts by viewModel.forecasts.collectAsState()

    // Ticks every 30s so the "Last updated Xm ago" labels advance over time
    // rather than being frozen at whatever they read when the drawer opened.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            nowMs = System.currentTimeMillis()
        }
    }

    var longPressedLocation: SavedLocation? by remember { mutableStateOf(null) }
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))

    val (onAddCurrentLocation, deviceLocationLoading) = rememberRequestDeviceLocation(viewModel)

    val haptics = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    var localData by remember { mutableStateOf(locations) }
    var hasDragged by remember { mutableStateOf(false) }

    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        localData = localData.toMutableList().apply { add(to.index, removeAt(from.index)) }
        hasDragged = true
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }

    LaunchedEffect(locations) {
        if (!reorderState.isAnyItemDragging) localData = locations
    }
    LaunchedEffect(reorderState.isAnyItemDragging) {
        if (!reorderState.isAnyItemDragging && hasDragged) {
            viewModel.reorderLocations(localData)
            hasDragged = false
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                title = {
                    Text(
                        "Locations",
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(
                            painter = painterResource(LibraryR.drawable.arrow_back_24px),
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                },
            )
        },
        bottomBar = {
            Button(
                onClick = { backStack.add(Route.SearchLocation) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Icon(
                    painter = painterResource(LibraryR.drawable.outline_search_24),
                    contentDescription = null,
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.search_location))
            }
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding(), bottom = paddingValues.calculateBottomPadding()),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
            ) {
                val showDeviceLocationCard = localData.none { it.isCurrent }
                if (showDeviceLocationCard) {
                    item {
                        UseDeviceLocationCard(
                            onClick = { if (!deviceLocationLoading) onAddCurrentLocation() },
                            isLoading = deviceLocationLoading,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
                items(localData, key = { it.id }) { loc ->
                    ReorderableItem(reorderState, key = loc.id) { isDragging ->
                        val elevation by animateDpAsState(if (isDragging) 6.dp else 0.dp)
                        val state = forecasts[loc.id]
                        val description = state?.fetchedAtEpochMs
                            ?.takeIf { it > 0L }
                            ?.let { "Last updated ${formatAgo(it, nowMs)}" }
                            ?: "No data yet"
                        LocationItem(
                            location = loc,
                            description = description,
                            currentWeatherCode = state?.forecast?.current?.weatherCode,
                            isDay = (state?.forecast?.current?.isDay ?: 1) == 1,
                            isSelected = loc.id == activeLocation?.id,
                            onClick = { onLocationSelect(loc) },
                            onLongClick = { longPressedLocation = loc },
                            modifier = Modifier.shadow(elevation, MaterialTheme.shapes.extraLarge),
                            dragHandle = if (localData.size > 1) {
                                {
                                    IconButton(
                                        onClick = {},
                                        modifier = Modifier.draggableHandle(
                                            onDragStarted = {
                                                haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                            },
                                            onDragStopped = {
                                                haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                            },
                                        ),
                                    ) {
                                        Icon(
                                            painter = painterResource(LibraryR.drawable.drag_handle_24px),
                                            contentDescription = "Reorder",
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            } else {
                                null
                            },
                        )
                    }
                }
            }
        }
    }

    val sheetLocation = longPressedLocation
    if (sheetLocation != null) {
        ModalBottomSheet(
            onDismissRequest = { longPressedLocation = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(modifier = Modifier.padding(bottom = 16.dp)) {
                Text(
                    text = sheetLocation.name,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                    leadingContent = {
                        Icon(
                            painter = painterResource(LibraryR.drawable.delete_24px),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                        )
                    },
                    content = { Text("Delete", color = MaterialTheme.colorScheme.onSurface) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                Spacer(Modifier.height(4.dp))
                androidx.compose.material3.TextButton(
                    onClick = {
                        viewModel.deleteLocation(sheetLocation)
                        longPressedLocation = null
                    },
                    modifier = Modifier.padding(start = 16.dp),
                ) { Text(stringResource(R.string.confirm_delete)) }
            }
        }
    }
}

/** Format a "X ago" delta from [nowMs] to the given epoch ms. */
private fun formatAgo(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val deltaSec = ((nowMs - epochMs) / 1000L).coerceAtLeast(0L)
    return when {
        deltaSec < 60 -> "just now"
        deltaSec < 3600 -> "${deltaSec / 60}m ago"
        deltaSec < 86_400 -> "${deltaSec / 3600}h ago"
        else -> "${deltaSec / 86_400}d ago"
    }
}

/**
 * Search-location screen registered as a `DialogPage()` route entry.
 * Hosted by Navigation3's `DialogSceneStrategy` so it renders as a system
 * dialog instead of a full page. Picking a result inserts the location
 * and pops the back stack.
 */
@OptIn(
    ExperimentalMaterial3Api::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class,
    kotlinx.coroutines.FlowPreview::class,
)
@Composable
fun SearchLocationPage(backStack: NavBackStack<Route>, viewModel: WeatherViewModel) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<GeocodingResult>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        snapshotFlow { query }
            .debounce(300)
            .distinctUntilChanged()
            .flatMapLatest { q ->
                flow {
                    if (q.isBlank()) {
                        emit(emptyList<GeocodingResult>())
                    } else {
                        searching = true
                        val res = runCatching { WeatherApi.geocode(q).results }.getOrDefault(emptyList())
                        searching = false
                        emit(res)
                    }
                }
            }
            .collect { results = it }
    }

    androidx.compose.material3.Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .heightIn(min = 220.dp, max = 480.dp),
        ) {
            Text(
                stringResource(R.string.search_location),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.height(12.dp))
            androidx.compose.material3.OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.city_name_hint)) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(LibraryR.drawable.outline_search_24),
                        contentDescription = null,
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 320.dp)) {
                when {
                    searching && results.isEmpty() -> {
                        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                    }
                    query.isNotBlank() && results.isEmpty() && !searching -> {
                        Text(
                            "No matches",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.align(Alignment.Center),
                        )
                    }
                    else -> {
                        LazyColumn(modifier = Modifier.fillMaxSize()) {
                            items(results, key = { it.id }) { r ->
                                ListItem(
                                    colors = ListItemDefaults.colors(
                                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                                    ),
                                    content = { Text(r.name) },
                                    supportingContent = {
                                        val parts = listOfNotNull(r.admin1, r.country).filter { it.isNotBlank() }
                                        if (parts.isNotEmpty()) Text(parts.joinToString(", "))
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 2.dp)
                                        .clickable {
                                            viewModel.addLocation(
                                                name = r.name,
                                                country = r.country.orEmpty(),
                                                latitude = r.latitude,
                                                longitude = r.longitude,
                                            )
                                            backStack.pop()
                                        },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
