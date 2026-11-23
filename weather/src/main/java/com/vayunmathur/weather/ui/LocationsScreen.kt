package com.vayunmathur.weather.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconDragHandle
import com.vayunmathur.library.ui.IconSearch
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.LoadingState
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ModalBottomSheet
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.TopAppBarDefaults
import com.vayunmathur.library.ui.SheetValue
import com.vayunmathur.library.ui.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.weather.R
import com.vayunmathur.weather.Route
import com.vayunmathur.weather.data.SavedLocation
import com.vayunmathur.weather.ui.components.LocationItem
import com.vayunmathur.weather.ui.components.UseDeviceLocationCard
import com.vayunmathur.weather.platform.LocationRow
import com.vayunmathur.weather.platform.LocationsUiState
import com.vayunmathur.weather.platform.WeatherActions
import com.vayunmathur.weather.platform.WeatherViewModel
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import com.vayunmathur.library.ui.ReorderableItem
import com.vayunmathur.library.ui.draggableHandle
import com.vayunmathur.library.ui.rememberReorderableLazyListState
import com.vayunmathur.library.ui.animatedDp

/**
 * Binds [WeatherViewModel] and the back stack to the stateless [LocationsScreen].
 *
 * The per-row "Last updated 4m ago" line is built here because it needs both a [Resources]
 * for the string and a clock; the screen only ever sees the finished text.
 */
/**
 * Locations drawer content, with no dependency on the ViewModel or the back stack so it can
 * be rendered from a `@Preview` — see `src/screenshotTest`, which is where the store listing
 * images come from. Renders inside [HomeScreen]'s `ModalNavigationDrawer`: a Scaffold with a
 * back/close top bar, a scrollable list of [LocationItem]s, and a full-width "Search
 * location" Button pinned to the bottom (in the Scaffold's `bottomBar` slot).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationsScreen(
    state: LocationsUiState,
    actions: WeatherActions,
    onLocationSelect: (SavedLocation) -> Unit = {},
    onClose: () -> Unit = {},
    onSearchLocation: () -> Unit = {},
    onUseDeviceLocation: () -> Unit = {},
) {
    var longPressedLocation: SavedLocation? by remember { mutableStateOf(null) }
    val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden, enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded))

    val haptics = LocalHapticFeedback.current
    val listState = rememberLazyListState()
    var localData by remember { mutableStateOf(state.rows) }
    var hasDragged by remember { mutableStateOf(false) }

    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        localData = localData.toMutableList().apply { add(to.index, removeAt(from.index)) }
        hasDragged = true
        haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
    }

    LaunchedEffect(state.rows) {
        if (!reorderState.isAnyItemDragging) localData = state.rows
    }
    LaunchedEffect(reorderState.isAnyItemDragging) {
        if (!reorderState.isAnyItemDragging && hasDragged) {
            actions.reorderLocations(localData.map { it.location })
            hasDragged = false
        }
    }

    LazyListScaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        state = listState,
        horizontalPadding = 16.dp,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                title = {
                    Text(
                        stringResource(R.string.locations),
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        IconBack(tint = MaterialTheme.colorScheme.onSurface)
                    }
                },
            )
        },
        bottomBar = {
            val context = LocalContext.current
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onSearchLocation,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    IconSearch()
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.search_location))
                }
                Button(
                    onClick = { openRegionalUnitsSettings(context) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.set_units))
                }
            }
        },
    ) {
        if (!state.hasLoaded && localData.isEmpty()) {
            item(key = "loading") {
                LoadingState(Modifier.fillParentMaxSize())
            }
        }
        val showDeviceLocationCard = localData.none { it.location.isCurrent }
        if (showDeviceLocationCard) {
            item {
                UseDeviceLocationCard(
                    onClick = { if (!state.deviceLocationLoading) onUseDeviceLocation() },
                    isLoading = state.deviceLocationLoading,
                )
                Spacer(Modifier.height(8.dp))
            }
        }
        itemsIndexed(localData, key = { _, item -> item.location.id }) { idx, row ->
            val loc = row.location
            ReorderableItem(reorderState, key = loc.id) { isDragging ->
                val elevation = animatedDp(if (isDragging) 6.dp else 0.dp)
                LocationItem(
                    location = loc,
                    description = row.description,
                    currentWeatherCode = row.weatherCode,
                    isDay = row.isDay,
                    isSelected = loc.id == state.activeLocationId,
                    onClick = { onLocationSelect(loc) },
                    onLongClick = { longPressedLocation = loc },
                    modifier = Modifier.shadow(elevation, MaterialTheme.shapes.extraLarge),
                    dragHandle = if (localData.size > 1) {
                        {
                            IconButton(
                                onClick = {},
                                modifier = Modifier.draggableHandle(
                                    reorderState,
                                    key = loc.id,
                                    index = idx,
                                    onDragStarted = {
                                        haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate)
                                    },
                                    onDragStopped = {
                                        haptics.performHapticFeedback(HapticFeedbackType.GestureEnd)
                                    },
                                ),
                            ) {
                                IconDragHandle(tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    } else {
                        null
                    },
                )
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
                        IconDelete(tint = MaterialTheme.colorScheme.onSurface)
                    },
                    content = { Text(stringResource(UiR.string.delete), color = MaterialTheme.colorScheme.onSurface) },
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
                Spacer(Modifier.height(4.dp))
                com.vayunmathur.library.ui.TextButton(
                    onClick = {
                        actions.deleteLocation(sheetLocation)
                        longPressedLocation = null
                    },
                    modifier = Modifier.padding(start = 16.dp),
                ) { Text(stringResource(R.string.confirm_delete)) }
            }
        }
    }
}
