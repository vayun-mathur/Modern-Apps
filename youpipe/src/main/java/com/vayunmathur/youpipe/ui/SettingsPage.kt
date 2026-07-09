package com.vayunmathur.youpipe.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.util.BottomNavBar
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.MAIN_BOTTOM_BAR_ITEMS
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.Route
import com.vayunmathur.youpipe.util.RecSource
import com.vayunmathur.youpipe.util.RecommendationPreset
import com.vayunmathur.youpipe.util.YouPipeViewModel
import com.vayunmathur.youpipe.util.YouPipeViewModel.Companion.ALL_SPONSOR_CATEGORIES
import com.vayunmathur.youpipe.util.YouPipeViewModel.Companion.SPONSOR_CATEGORY_LABELS

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(
    backStack: NavBackStack<Route>,
    ypvm: YouPipeViewModel,
) {
    val sponsorBlockCategories by ypvm.sponsorBlockCategories.collectAsState()
    val deArrowEnabled by ypvm.deArrowEnabled.collectAsState()
    val recPrefs by ypvm.recommendationPreferences.collectAsState()
    val isLoading by ypvm.isImporting.collectAsState()
    val progress by ypvm.importProgress.collectAsState()

    val youtubeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) ypvm.importYouTubeTakeout(uri)
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) ypvm.exportSubscriptions(uri)
    }

    val restoreLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) ypvm.restoreSubscriptions(uri)
    }

    val newPipeLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) ypvm.importNewPipe(uri)
    }

    Scaffold(
        topBar = { TopAppBar({ Text(stringResource(R.string.title_settings)) }) },
        bottomBar = { BottomNavBar(backStack, MAIN_BOTTOM_BAR_ITEMS, Route.Settings) }
    ) { paddingValues ->
        if (!isLoading) {
            LazyColumn(Modifier.padding(paddingValues)) {
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.label_sponsorblock)) }
                    )
                }
                ALL_SPONSOR_CATEGORIES.forEach { category ->
                    item(key = "sb_$category") {
                        val label = SPONSOR_CATEGORY_LABELS[category] ?: category
                        val checked = category in sponsorBlockCategories
                        ListItem(
                            headlineContent = { Text(label) },
                            trailingContent = {
                                Switch(
                                    checked = checked,
                                    onCheckedChange = { ypvm.toggleSponsorBlockCategory(category) }
                                )
                            },
                            modifier = Modifier.padding(start = 16.dp)
                        )
                    }
                }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.label_dearrow)) },
                        supportingContent = { Text(stringResource(R.string.label_dearrow_description)) },
                        trailingContent = {
                            Switch(
                                checked = deArrowEnabled,
                                onCheckedChange = { ypvm.setDeArrowEnabled(it) }
                            )
                        }
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.label_recommendations)) }
                    )
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                        RecommendationPreset.entries.forEach { preset ->
                            FilterChip(
                                selected = recPrefs.preset == preset.name,
                                onClick = { ypvm.setPreset(preset) },
                                label = { Text(presetLabel(preset)) },
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                    }
                }
                item {
                    RecommendationSlider(
                        label = stringResource(R.string.label_dial_discovery),
                        value = recPrefs.discoveryFamiliar,
                        onChange = { ypvm.setDiscoveryFamiliar(it) },
                    )
                }
                item {
                    RecommendationSlider(
                        label = stringResource(R.string.label_dial_fresh),
                        value = recPrefs.freshEvergreen,
                        onChange = { ypvm.setFreshEvergreen(it) },
                    )
                }
                item {
                    RecommendationSlider(
                        label = stringResource(R.string.label_dial_diverse),
                        value = recPrefs.focusedDiverse,
                        onChange = { ypvm.setFocusedDiverse(it) },
                    )
                }
                item {
                    ListItem(headlineContent = { Text(stringResource(R.string.label_rec_sources)) })
                }
                SOURCE_TOGGLES.forEach { (source, labelRes) ->
                    item(key = "src_${source.name}") {
                        val enabled = when (source) {
                            RecSource.RELATED -> recPrefs.sourceRelated
                            RecSource.TRENDING -> recPrefs.sourceTrending
                            RecSource.SUBSCRIPTION -> recPrefs.sourceSubscription
                            RecSource.TOP_CHANNEL -> recPrefs.sourceTopChannel
                            RecSource.SEARCH -> recPrefs.sourceSearch
                        }
                        ListItem(
                            headlineContent = { Text(stringResource(labelRes)) },
                            trailingContent = {
                                Switch(checked = enabled, onCheckedChange = { ypvm.toggleSource(source) })
                            },
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }
                item {
                    ListItem(headlineContent = { Text(stringResource(R.string.label_rec_content_filters)) })
                }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.label_hide_shorts)) },
                        trailingContent = {
                            Switch(checked = recPrefs.hideShorts, onCheckedChange = { ypvm.setHideShorts(it) })
                        },
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.label_hide_live)) },
                        trailingContent = {
                            Switch(checked = recPrefs.hideLive, onCheckedChange = { ypvm.setHideLive(it) })
                        },
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
                item {
                    DurationField(
                        label = stringResource(R.string.label_min_duration),
                        seconds = recPrefs.minDurationSec,
                        onChange = { ypvm.setMinDuration(it) },
                    )
                }
                item {
                    DurationField(
                        label = stringResource(R.string.label_max_duration),
                        seconds = recPrefs.maxDurationSec,
                        onChange = { ypvm.setMaxDuration(it) },
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.label_manage_interests)) },
                        modifier = Modifier.padding(start = 16.dp).clickable {
                            backStack.add(Route.RecommendationSettings)
                        },
                    )
                }
                item {
                    ListItem(
                        headlineContent = {
                            Button(onClick = { ypvm.resetAlgorithm() }) {
                                Text(stringResource(R.string.action_reset_algorithm))
                            }
                        },
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
                item { HorizontalDivider() }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.label_backup_restore)) }
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.label_export_youpipe)) },
                        modifier = Modifier.padding(start = 16.dp).clickable {
                            exportLauncher.launch("youpipe_subscriptions.json")
                        }
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.label_restore_youpipe)) },
                        modifier = Modifier.padding(start = 16.dp).clickable {
                            restoreLauncher.launch("application/json")
                        }
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.label_import_newpipe)) },
                        modifier = Modifier.padding(start = 16.dp).clickable {
                            newPipeLauncher.launch("application/json")
                        }
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text(stringResource(R.string.label_import_youtube)) },
                        modifier = Modifier.padding(start = 16.dp).clickable {
                            youtubeLauncher.launch("application/zip")
                        }
                    )
                }
            }
        } else {
            Box(Modifier.fillMaxSize()) {
                CircularProgressIndicator({ progress }, modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

private val SOURCE_TOGGLES = listOf(
    RecSource.RELATED to R.string.label_source_related,
    RecSource.TRENDING to R.string.label_source_trending,
    RecSource.SUBSCRIPTION to R.string.label_source_subscription,
    RecSource.TOP_CHANNEL to R.string.label_source_top_channel,
    RecSource.SEARCH to R.string.label_source_search,
)

@Composable
private fun presetLabel(preset: RecommendationPreset): String = when (preset) {
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
