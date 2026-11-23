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
import androidx.compose.runtime.mutableLongStateOf
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
@Composable
fun LocationsPage(
    backStack: NavBackStack<Route>,
    viewModel: WeatherViewModel,
    activeLocation: SavedLocation?,
    onLocationSelect: (SavedLocation) -> Unit,
    onClose: () -> Unit,
) {
    val savedLocations by viewModel.savedLocations.collectAsState()
    val locations = savedLocations.orEmpty()
    val forecasts by viewModel.forecasts.collectAsState()
    val resources = LocalResources.current

    // Ticks every 30s so the "Last updated Xm ago" labels advance over time
    // rather than being frozen at whatever they read when the drawer opened.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(30_000)
            nowMs = System.currentTimeMillis()
        }
    }

    val (onAddCurrentLocation, deviceLocationLoading) = rememberRequestDeviceLocation(viewModel)

    val rows = locations.map { loc ->
        val state = forecasts[loc.id]
        LocationRow(
            location = loc,
            description = state?.fetchedAtEpochMs
                ?.takeIf { it > 0L }
                ?.let { resources.getString(R.string.last_updated, formatAgo(resources, it, nowMs)) }
                ?: resources.getString(R.string.no_data_yet),
            weatherCode = state?.forecast?.current?.weatherCode,
            isDay = (state?.forecast?.current?.isDay ?: 1) == 1,
        )
    }

    LocationsScreen(
        state = LocationsUiState(
            hasLoaded = savedLocations != null,
            rows = rows,
            activeLocationId = activeLocation?.id,
            deviceLocationLoading = deviceLocationLoading,
        ),
        actions = viewModel,
        onLocationSelect = onLocationSelect,
        onClose = onClose,
        onSearchLocation = { backStack.add(Route.SearchLocation) },
        onUseDeviceLocation = onAddCurrentLocation,
    )
}

