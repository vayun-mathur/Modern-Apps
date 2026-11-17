package com.vayunmathur.maps.ui

import com.vayunmathur.maps.data.string
import com.vayunmathur.maps.domain.AlternativeRoutes
import com.vayunmathur.maps.platform.formatDistance
import com.vayunmathur.maps.platform.formatDuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.maps.R
import com.vayunmathur.maps.domain.RouteService
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable

/**
 * "Towards X · 4 stops" for a transit step, dropping whichever half the feed
 * doesn't provide (headsign is optional in GTFS; a single-hop ride has no
 * intermediate stops).
 */
@Composable
internal fun transitSupportingText(details: RouteService.API.TransitDetails): String {
    val headsign = details.headsign.takeIf { it.isNotBlank() }
        ?.let { stringResource(R.string.transit_towards, it) }
    val stops = details.stopCount.takeIf { it > 0 }?.let {
        pluralStringResource(R.plurals.transit_stop_count, it, it)
    }
    return when {
        headsign != null && stops != null ->
            stringResource(R.string.transit_detail_separator, headsign, stops)
        else -> headsign ?: stops ?: ""
    }
}

/**
 * Genuinely different alternatives for the shown mode (already gated by
 * [com.vayunmathur.maps.domain.AlternativeRoutes]): duration/distance, and
 * for transit the boarded lines, so the difference is visible rather than
 * asserted. Tapping one swaps it in as the shown route; tapping the
 * selected one again returns to the primary.
 */
@Composable
internal fun AlternativesSection(
    alternatives: List<RouteService.Route>,
    selected: RouteService.Route?,
    onSelect: (RouteService.Route?) -> Unit,
) {
    val context = LocalContext.current
    Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
        Text(
            stringResource(R.string.alternative_routes_title),
            style = MaterialTheme.typography.titleSmall,
        )
        alternatives.forEach { alternative ->
            val lines = alternative.step.mapNotNull {
                it.transitDetails?.transitLine?.let { line -> line.nameShort ?: line.name }
            }.distinct()
            RouteOptionCard(
                route = alternative,
                selected = selected == alternative,
                onClick = { onSelect(if (selected == alternative) null else alternative) },
                modifier = Modifier.fillMaxWidth().padding(top = Spacing.sm),
            )
        }
    }
}

/**
 * One selectable route option: big duration, then distance/arrival and —
 * for transit — the boarded line chips in their line colours. The shown
 * route's card is highlighted with the primary outline.
 */
@Composable
internal fun RouteOptionCard(
    route: RouteService.Route,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lines = route.step.mapNotNull { it.transitDetails?.transitLine }.distinctBy { it.name }
    Card(
        modifier = modifier
            .clickable(onClick = onClick)
            .then(
                if (selected) {
                    Modifier.border(
                        2.dp, MaterialTheme.colorScheme.primary,
                        androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        Column(Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.md)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    formatDuration(context, route.duration),
                    style = MaterialTheme.typography.headlineSmall,
                )
                route.arrivalTime?.let { arrival ->
                    Text(
                        " · $arrival",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = Spacing.sm),
                    )
                }
            }
            Text(
                formatDistance(route.distanceMeters),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (lines.isNotEmpty()) {
                Row(
                    Modifier.padding(top = Spacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    lines.forEach { line -> LineBadge(line.nameShort ?: line.name, line.color) }
                }
            }
        }
    }
}
