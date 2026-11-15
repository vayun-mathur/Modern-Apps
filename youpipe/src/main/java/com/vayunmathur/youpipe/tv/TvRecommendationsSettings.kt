package com.vayunmathur.youpipe.tv

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.vayunmathur.library.tv.TvButton
import com.vayunmathur.library.tv.TvIconButton
import com.vayunmathur.library.tv.TvInitialFocus
import com.vayunmathur.library.tv.TvListRow
import com.vayunmathur.library.tv.TvScreenHeader
import com.vayunmathur.library.tv.TvSwitchRow
import com.vayunmathur.library.ui.IconAdd
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.IconRemove
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.youpipe.R
import com.vayunmathur.youpipe.data.RecommendationPreferences
import com.vayunmathur.youpipe.ui.presetLabel
import com.vayunmathur.youpipe.util.RecSource
import com.vayunmathur.youpipe.util.RecommendationPreset
import com.vayunmathur.youpipe.util.YouPipeViewModel
import com.vayunmathur.youpipe.util.resetAlgorithm
import com.vayunmathur.youpipe.util.setDiscoveryFamiliar
import com.vayunmathur.youpipe.util.setFocusedDiverse
import com.vayunmathur.youpipe.util.setFreshEvergreen
import com.vayunmathur.youpipe.util.setHideLive
import com.vayunmathur.youpipe.util.setHidePaid
import com.vayunmathur.youpipe.util.setHideShorts
import com.vayunmathur.youpipe.util.setMaxDuration
import com.vayunmathur.youpipe.util.setMinDuration
import com.vayunmathur.youpipe.util.setPreset
import com.vayunmathur.youpipe.util.toggleSource

private val TV_SOURCE_TOGGLES = listOf(
    RecSource.RELATED to R.string.label_source_related,
    RecSource.TRENDING to R.string.label_source_trending,
    RecSource.SUBSCRIPTION to R.string.label_source_subscription,
    RecSource.TOP_CHANNEL to R.string.label_source_top_channel,
    RecSource.SEARCH to R.string.label_source_search,
)

/**
 * TV recommendations settings: presets + dials as steppers + source/filter switches.
 *
 * The phone uses touch sliders and number fields; a TV gets stepper rows (LEFT/RIGHT
 * nudges the value while the row holds focus, CENTER opens nothing) plus the same
 * switches. Dial values persist through the same
 * `setDiscoveryFamiliar`/`setFreshEvergreen`/`setFocusedDiverse` setters — no TV-specific
 * prefs path.
 */
@Composable
fun TvRecommendationsSettingsPage(
    backStack: NavBackStack<TvRoute>,
    ypvm: YouPipeViewModel,
) {
    val recPrefs by ypvm.recommendationPreferences.collectAsState()
    val firstFocus = remember { FocusRequester() }
    TvInitialFocus(firstFocus)
    TvSettingsBody {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            TvScreenHeader(title = stringResource(R.string.label_recommendations))
            Column(
                Modifier.padding(start = 48.dp, end = 48.dp, bottom = 44.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TvRecommendationPresets(ypvm, recPrefs, firstFocus)
                TvRecommendationDials(ypvm, recPrefs)
                TvRecommendationSources(ypvm, recPrefs)
                TvRecommendationFilters(ypvm, recPrefs)
                TvRecommendationActions(backStack, ypvm)
            }
        }
    }
}

@Composable
private fun TvRecommendationPresets(
    ypvm: YouPipeViewModel,
    recPrefs: RecommendationPreferences,
    firstFocus: FocusRequester,
) {
    RecommendationPreset.entries.forEachIndexed { index, preset ->
        TvListRow(
            headline = presetLabel(preset),
            trailing = if (recPrefs.preset == preset.name) {
                { IconCheck() }
            } else {
                null
            },
            onClick = { ypvm.setPreset(preset) },
            modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier,
        )
    }
}

@Composable
private fun TvRecommendationDials(
    ypvm: YouPipeViewModel,
    recPrefs: RecommendationPreferences,
) {
    TvStepperRow(
        label = stringResource(R.string.label_dial_discovery),
        value = recPrefs.discoveryFamiliar,
        onChange = { ypvm.setDiscoveryFamiliar(it) },
    )
    TvStepperRow(
        label = stringResource(R.string.label_dial_fresh),
        value = recPrefs.freshEvergreen,
        onChange = { ypvm.setFreshEvergreen(it) },
    )
    TvStepperRow(
        label = stringResource(R.string.label_dial_diverse),
        value = recPrefs.focusedDiverse,
        onChange = { ypvm.setFocusedDiverse(it) },
    )
}

@Composable
private fun TvRecommendationSources(
    ypvm: YouPipeViewModel,
    recPrefs: RecommendationPreferences,
) {
    Text(
        text = stringResource(R.string.label_rec_sources),
        style = MaterialTheme.typography.titleMedium,
    )
    TV_SOURCE_TOGGLES.forEach { (source, labelRes) ->
        val enabled = when (source) {
            RecSource.RELATED -> recPrefs.sourceRelated
            RecSource.TRENDING -> recPrefs.sourceTrending
            RecSource.SUBSCRIPTION -> recPrefs.sourceSubscription
            RecSource.TOP_CHANNEL -> recPrefs.sourceTopChannel
            RecSource.SEARCH -> recPrefs.sourceSearch
        }
        TvSwitchRow(
            headline = stringResource(labelRes),
            checked = enabled,
            onCheckedChange = { ypvm.toggleSource(source) },
        )
    }
}

@Composable
private fun TvRecommendationFilters(
    ypvm: YouPipeViewModel,
    recPrefs: RecommendationPreferences,
) {
    Text(
        text = stringResource(R.string.label_rec_content_filters),
        style = MaterialTheme.typography.titleMedium,
    )
    TvSwitchRow(
        headline = stringResource(R.string.label_hide_shorts),
        checked = recPrefs.hideShorts,
        onCheckedChange = { ypvm.setHideShorts(it) },
    )
    TvSwitchRow(
        headline = stringResource(R.string.label_hide_live),
        checked = recPrefs.hideLive,
        onCheckedChange = { ypvm.setHideLive(it) },
    )
    TvSwitchRow(
        headline = stringResource(R.string.label_hide_paid),
        checked = recPrefs.hidePaid,
        onCheckedChange = { ypvm.setHidePaid(it) },
    )
    TvStepperRow(
        label = stringResource(R.string.label_min_duration),
        value = recPrefs.minDurationSec.toFloat(),
        step = 30f,
        range = 0f..3600f,
        onChange = { ypvm.setMinDuration(it.toLong()) },
    )
    TvStepperRow(
        label = stringResource(R.string.label_max_duration),
        value = recPrefs.maxDurationSec.toFloat(),
        step = 60f,
        range = 0f..7200f,
        onChange = { ypvm.setMaxDuration(it.toLong()) },
    )
}

@Composable
private fun TvRecommendationActions(
    backStack: NavBackStack<TvRoute>,
    ypvm: YouPipeViewModel,
) {
    TvListRow(
        headline = stringResource(R.string.title_manage_interests),
        onClick = { backStack.add(TvRoute.ManageInterests) },
    )
    TvButton(
        text = stringResource(R.string.action_reset_algorithm),
        onClick = { ypvm.resetAlgorithm() },
    )
}

/**
 * A 0..1 (or ranged) stepper: a label plus focusable minus/plus [TvIconButton]s around the
 * value. The row itself is not focusable — LEFT/RIGHT moves between the two buttons.
 * Replaces the phone's touch `Slider` and number fields with D-pad steps.
 */
@Composable
private fun TvStepperRow(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    step: Float = 0.1f,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
) {
    val scheme = MaterialTheme.colorScheme
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface,
            )
            Text(
                text = if (range.endInclusive <= 1f) {
                    "${(value.coerceIn(range) * 100).toInt()}%"
                } else {
                    "${value.toInt()}s"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
        }
        TvIconButton(
            onClick = { onChange((value - step).coerceIn(range.start, range.endInclusive)) },
            size = 44.dp,
            icon = { IconRemove() },
        )
        TvIconButton(
            onClick = { onChange((value + step).coerceIn(range.start, range.endInclusive)) },
            size = 44.dp,
            icon = { IconAdd() },
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}
