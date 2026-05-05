package com.vayunmathur.findfamily.ui

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.plus
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vayunmathur.findfamily.R
import com.vayunmathur.findfamily.Route
import com.vayunmathur.findfamily.util.LocationMode
import com.vayunmathur.findfamily.util.LocationTrackingService
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.DatabaseViewModel
import com.vayunmathur.library.util.NavBackStack
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(
    backStack: NavBackStack<Route>,
    viewModel: DatabaseViewModel
) {
    val context = LocalContext.current
    val dataStore = remember { DataStoreUtils.getInstance(context) }
    val coroutineScope = rememberCoroutineScope()

    var locationMode by remember { mutableStateOf(LocationMode.fromKey(dataStore.getString("location_mode") ?: "balanced")) }
    var gpsFallbackEnabled by remember { mutableStateOf(dataStore.getBoolean("gps_fallback_enabled", true)) }
    var smartTrackingEnabled by remember { mutableStateOf(dataStore.getBoolean("smart_tracking_enabled", true)) }

    fun setLocationMode(mode: LocationMode) {
        locationMode = mode
        coroutineScope.launch {
            dataStore.setString("location_mode", mode.key)
            restartTrackingService(context)
        }
    }

    fun setGpsFallbackEnabled(enabled: Boolean) {
        gpsFallbackEnabled = enabled
        coroutineScope.launch {
            dataStore.setBoolean("gps_fallback_enabled", enabled)
            restartTrackingService(context)
        }
    }

    fun setSmartTrackingEnabled(enabled: Boolean) {
        smartTrackingEnabled = enabled
        coroutineScope.launch {
            dataStore.setBoolean("smart_tracking_enabled", enabled)
            restartTrackingService(context)
        }
    }

    val batteryEstimate = when (locationMode) {
        LocationMode.BATTERY_SAVER -> "1-2%"
        LocationMode.BALANCED -> "3-5%"
        LocationMode.HIGH_ACCURACY -> "8-12%"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = { IconNavigation(backStack) }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = paddingValues.plus(PaddingValues(16.dp))
        ) {
            item {
                SectionHeader(stringResource(R.string.settings_location_section))
                Spacer(Modifier.height(8.dp))
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.settings_location_mode),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = stringResource(R.string.settings_location_mode_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        Column(Modifier.selectableGroup()) {
                            LocationMode.entries.forEach { mode ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .selectable(
                                            selected = locationMode == mode,
                                            onClick = { setLocationMode(mode) },
                                            role = Role.RadioButton
                                        )
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = locationMode == mode,
                                        onClick = { setLocationMode(mode) }
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = when (mode) {
                                                LocationMode.BATTERY_SAVER -> stringResource(R.string.location_mode_battery_saver)
                                                LocationMode.BALANCED -> stringResource(R.string.location_mode_balanced)
                                                LocationMode.HIGH_ACCURACY -> stringResource(R.string.location_mode_high_accuracy)
                                            },
                                            style = MaterialTheme.typography.bodyLarge,
                                            fontWeight = if (locationMode == mode) FontWeight.SemiBold else FontWeight.Normal
                                        )
                                        Text(
                                            text = when (mode) {
                                                LocationMode.BATTERY_SAVER -> stringResource(R.string.location_mode_battery_saver_desc)
                                                LocationMode.BALANCED -> stringResource(R.string.location_mode_balanced_desc)
                                                LocationMode.HIGH_ACCURACY -> stringResource(R.string.location_mode_high_accuracy_desc)
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
                ) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_gps_fallback)) },
                        supportingContent = {
                            Text(
                                text = if (gpsFallbackEnabled)
                                    stringResource(R.string.settings_gps_fallback_enabled)
                                else
                                    stringResource(R.string.settings_gps_fallback_disabled)
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = gpsFallbackEnabled,
                                onCheckedChange = { setGpsFallbackEnabled(it) }
                            )
                        }
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
                ) {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.settings_smart_tracking)) },
                        supportingContent = {
                            Text(
                                text = if (smartTrackingEnabled)
                                    stringResource(R.string.settings_smart_tracking_enabled)
                                else
                                    stringResource(R.string.settings_smart_tracking_disabled)
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = smartTrackingEnabled,
                                onCheckedChange = { setSmartTrackingEnabled(it) }
                            )
                        }
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = stringResource(R.string.settings_battery_info),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.settings_battery_info_text, batteryEstimate),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(80.dp))
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary
    )
}

private fun restartTrackingService(context: Context) {
    try {
        val intent = Intent(context, LocationTrackingService::class.java)
        context.stopService(intent)
        context.startForegroundService(intent)
    } catch (_: Exception) {
    }
}
