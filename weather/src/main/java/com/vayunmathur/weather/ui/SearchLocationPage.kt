package com.vayunmathur.weather.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.ListItemDefaults
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.weather.R
import com.vayunmathur.weather.Route
import com.vayunmathur.weather.domain.formatTemperatureCompact
import com.vayunmathur.weather.network.GeocodingResult
import com.vayunmathur.weather.network.WeatherApi
import com.vayunmathur.weather.platform.WeatherViewModel
import com.vayunmathur.weather.platform.rememberTempUnit
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow

/**
 * Search-location screen registered as a `DialogPage()` route entry.
 * Hosted by Navigation3's `DialogSceneStrategy` so it renders as a system
 * dialog instead of a full page. Picking a result inserts the location
 * and resets the back stack to `Route.Home(newId)` so the new location is
 * the selected one.
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
    var temps by remember { mutableStateOf<Map<Long, Double?>>(emptyMap()) }
    val tempUnit = rememberTempUnit()

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

    LaunchedEffect(results) {
        temps = emptyMap()
        if (results.isEmpty()) return@LaunchedEffect
        // One batch call for all rows (P4) instead of N parallel
        // currentTemperature calls. Render path untouched.
        val batch = runCatching {
            WeatherApi.currentTemperatures(results.map { it.latitude to it.longitude })
        }.getOrDefault(emptyMap())
        temps = results.associate { r ->
            // Match by coordinate (batch keys are rounded server-side; fall
            // back to per-point lookup for any row the batch omitted).
            val t = batch.entries.firstOrNull { (key, _) ->
                kotlin.math.abs(key.first - r.latitude) < 0.005 &&
                    kotlin.math.abs(key.second - r.longitude) < 0.005
            }?.value ?: runCatching {
                WeatherApi.currentTemperature(r.latitude, r.longitude)
            }.getOrNull()
            r.id to t
        }
    }

    com.vayunmathur.library.ui.Surface(
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
            com.vayunmathur.library.ui.OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(stringResource(R.string.city_name_hint)) },
                leadingIcon = {
                    com.vayunmathur.library.ui.IconSearch()
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 320.dp)) {
                when {
                    searching && results.isEmpty() -> {
                        Text("Loading\u2026", modifier = Modifier.align(Alignment.Center))
                    }
                    query.isNotBlank() && results.isEmpty() && !searching -> {
                        Text(
                            stringResource(R.string.no_matches),
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
                                    trailingContent = {
                                        if (temps.containsKey(r.id)) {
                                            temps[r.id]?.let { temp ->
                                                Text(
                                                    formatTemperatureCompact(temp, tempUnit),
                                                    style = MaterialTheme.typography.titleMedium,
                                                    color = MaterialTheme.colorScheme.onSurface,
                                                )
                                            }
                                        } else {
                                            Text("Loading\u2026")
                                        }
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
                                            ) { newId ->
                                                // Select the added location in the
                                                // stack itself (replaces this dialog
                                                // entry and any default Home beneath).
                                                backStack.reset(Route.Home(newId))
                                            }
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

/**
 * Opens the OS regional-units settings so the user can pick temperature/wind/etc. units.
 * The app itself stores no units config; it reads the system regional preferences (see
 * [com.vayunmathur.weather.platform.rememberTempUnit]).
 * on devices without a dedicated regional-preferences screen (pre-Android 14).
 */
internal fun openRegionalUnitsSettings(context: Context) {
    // Value of Settings.ACTION_REGIONAL_PREFERENCES_SETTINGS (API 34); used as a literal so the
    // deep link still compiles/works when built against lower compile SDKs.
    val actions = listOf(
        "android.settings.REGIONAL_PREFERENCES_SETTINGS",
        Settings.ACTION_LOCALE_SETTINGS,
        Settings.ACTION_SETTINGS,
    )
    for (action in actions) {
        try {
            context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) {
            // Try the next, broader settings target.
        }
    }
}

/** Format a "X ago" delta from [nowMs] to the given epoch ms. */
internal fun formatAgo(resources: Resources, epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
    val deltaSec = ((nowMs - epochMs) / 1000L).coerceAtLeast(0L)
    return when {
        deltaSec < 60 -> resources.getString(R.string.just_now)
        deltaSec < 3600 -> resources.getString(R.string.minutes_ago, (deltaSec / 60).toInt())
        deltaSec < 86_400 -> resources.getString(R.string.hours_ago, (deltaSec / 3600).toInt())
        else -> resources.getString(R.string.days_ago, (deltaSec / 86_400).toInt())
    }
}
