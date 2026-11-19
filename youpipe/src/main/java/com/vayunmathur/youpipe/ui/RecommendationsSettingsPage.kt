package com.vayunmathur.youpipe.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.FilterChip
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.SettingsRow
import com.vayunmathur.library.ui.SettingsSection
import com.vayunmathur.library.ui.SettingsSwitchRow
import com.vayunmathur.library.ui.Slider
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.domain.RecSource
import com.vayunmathur.youpipe.domain.RecommendationPreset
import com.vayunmathur.youpipe.platform.YouPipeViewModel
import com.vayunmathur.youpipe.platform.resetAlgorithm
import com.vayunmathur.youpipe.platform.setDiscoveryFamiliar
import com.vayunmathur.youpipe.platform.setFocusedDiverse
import com.vayunmathur.youpipe.platform.setFreshEvergreen
import com.vayunmathur.youpipe.platform.setHideLive
import com.vayunmathur.youpipe.platform.setHidePaid
import com.vayunmathur.youpipe.platform.setHideShorts
import com.vayunmathur.youpipe.platform.setMaxDuration
import com.vayunmathur.youpipe.platform.setMinDuration
import com.vayunmathur.youpipe.platform.setPreset
import com.vayunmathur.youpipe.platform.toggleSource

private val SOURCE_TOGGLES = listOf(
    RecSource.RELATED to R.string.label_source_related,
    RecSource.TRENDING to R.string.label_source_trending,
    RecSource.SUBSCRIPTION to R.string.label_source_subscription,
    RecSource.TOP_CHANNEL to R.string.label_source_top_channel,
    RecSource.SEARCH to R.string.label_source_search,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecommendationsSettingsPage(
    backStack: NavBackStack<Route>,
    ypvm: YouPipeViewModel,
) {
    val recPrefs by ypvm.recommendationPreferences.collectAsState()

    AppScaffold(
        title = stringResource(R.string.label_recommendations),
        backStack = backStack,
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                RecommendationPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = recPrefs.preset == preset.name,
                        onClick = { ypvm.setPreset(preset) },
                        label = { Text(presetLabel(preset)) },
                        modifier = Modifier.padding(end = 8.dp),
                    )
                }
            }

            RecommendationSlider(
                label = stringResource(R.string.label_dial_discovery),
                value = recPrefs.discoveryFamiliar,
                onChange = { ypvm.setDiscoveryFamiliar(it) },
            )
            RecommendationSlider(
                label = stringResource(R.string.label_dial_fresh),
                value = recPrefs.freshEvergreen,
                onChange = { ypvm.setFreshEvergreen(it) },
            )
            RecommendationSlider(
                label = stringResource(R.string.label_dial_diverse),
                value = recPrefs.focusedDiverse,
                onChange = { ypvm.setFocusedDiverse(it) },
            )

            SettingsSection(title = stringResource(R.string.label_rec_sources)) {
                SOURCE_TOGGLES.forEach { (source, labelRes) ->
                    val enabled = when (source) {
                        RecSource.RELATED -> recPrefs.sourceRelated
                        RecSource.TRENDING -> recPrefs.sourceTrending
                        RecSource.SUBSCRIPTION -> recPrefs.sourceSubscription
                        RecSource.TOP_CHANNEL -> recPrefs.sourceTopChannel
                        RecSource.SEARCH -> recPrefs.sourceSearch
                    }
                    SettingsSwitchRow(
                        title = stringResource(labelRes),
                        checked = enabled,
                        onCheckedChange = { ypvm.toggleSource(source) },
                    )
                }
            }

            SettingsSection(title = stringResource(R.string.label_rec_content_filters)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.label_hide_shorts),
                    checked = recPrefs.hideShorts,
                    onCheckedChange = { ypvm.setHideShorts(it) },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.label_hide_live),
                    checked = recPrefs.hideLive,
                    onCheckedChange = { ypvm.setHideLive(it) },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.label_hide_paid),
                    checked = recPrefs.hidePaid,
                    onCheckedChange = { ypvm.setHidePaid(it) },
                )
                DurationField(
                    label = stringResource(R.string.label_min_duration),
                    seconds = recPrefs.minDurationSec,
                    onChange = { ypvm.setMinDuration(it) },
                )
                DurationField(
                    label = stringResource(R.string.label_max_duration),
                    seconds = recPrefs.maxDurationSec,
                    onChange = { ypvm.setMaxDuration(it) },
                )
            }

            SettingsRow(
                title = stringResource(R.string.title_manage_interests),
                onClick = { backStack.add(Route.ManageInterests) },
            )
            Button(
                onClick = { ypvm.resetAlgorithm() },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(stringResource(R.string.action_reset_algorithm))
            }
        }
    }
}

@Composable
internal fun presetLabel(preset: RecommendationPreset): String = when (preset) {
    RecommendationPreset.DISCOVER_MORE -> stringResource(R.string.preset_discover_more)
    RecommendationPreset.BALANCED -> stringResource(R.string.preset_balanced)
    RecommendationPreset.MOSTLY_SUBSCRIPTIONS -> stringResource(R.string.preset_mostly_subscriptions)
    RecommendationPreset.DEEP_DIVES -> stringResource(R.string.preset_deep_dives)
}

@Composable
private fun RecommendationSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Slider(value = value.coerceIn(0f, 1f), onValueChange = onChange, valueRange = 0f..1f)
    }
}

@Composable
private fun DurationField(label: String, seconds: Long, onChange: (Long) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        OutlinedTextField(
            value = if (seconds > 0) seconds.toString() else "",
            onValueChange = { text -> onChange(text.filter { it.isDigit() }.toLongOrNull() ?: 0L) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(120.dp),
        )
    }
}
