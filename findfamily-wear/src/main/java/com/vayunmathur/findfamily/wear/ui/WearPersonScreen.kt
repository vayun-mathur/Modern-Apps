package com.vayunmathur.findfamily.wear.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.vayunmathur.findfamily.wear.R
import com.vayunmathur.findfamily.wear.data.WearFindFamilyStore
import com.vayunmathur.findfamily.wear.domain.WearMotion
import com.vayunmathur.findfamily.wear.domain.wearAgeLabel
import com.vayunmathur.findfamily.wear.domain.wearDistanceSummary
import com.vayunmathur.findfamily.wear.domain.wearRegionFor
import com.vayunmathur.findfamily.wear.domain.wearStaleLabel

/**
 * Per-person detail (Route carries [personId]): precise fix age,
 * accuracy, place label, battery/motion, and a relative view centred
 * on you (region centre) with this person highlighted. Stale fixes are
 * labelled with their age — precision is never faked.
 */
@Composable
fun WearPersonScreen(
    store: WearFindFamilyStore,
    personId: Long,
    onBack: () -> Unit,
) {
    val snapshot by store.snapshot.collectAsState()
    val person = snapshot.people.firstOrNull { it.id == personId }
    val self = snapshot.people.firstOrNull { it.isSelf }
    val now = System.currentTimeMillis()
    val listState = rememberScalingLazyListState()
    val noLocationText = stringResource(R.string.wear_no_location)
    val lines: List<String> = if (person == null) {
        emptyList()
    } else {
        buildList {
            add(person.placeLabel.ifBlank { noLocationText })
            wearDistanceSummary(self, person).takeIf { it.isNotBlank() }?.let { add(it) }
            val age = wearAgeLabel(person.updatedAtMillis, now)
            if (age.isNotBlank()) add("Updated $age")
            wearStaleLabel(person, now).takeIf { it.isNotBlank() }?.let { add(it) }
            if (person.accuracyMeters >= 0) add("Accuracy ±${person.accuracyMeters} m")
            if (person.source == "NETWORK_SIGHTING") add("Seen nearby by another phone — approximate")
            if (person.source == "SHUTDOWN") add("Last fix before that phone switched off")
            if (person.source == "BATTERY_LOW") add("Last fix before that phone ran low")
            if (person.batteryPercent >= 0) add("Battery ${person.batteryPercent}%")
            add(
                when (person.motion) {
                    WearMotion.MOVING -> "Moving"
                    WearMotion.STATIONARY -> "Stationary"
                    WearMotion.UNKNOWN -> "Motion unknown"
                },
            )
            if (person.placeSinceMillis > 0L && person.placeLabel.isNotBlank()) {
                val since = wearAgeLabel(person.placeSinceMillis, now)
                if (since.isNotBlank()) add("At this place since $since")
            }
        }
    }

    ScalingLazyColumn(state = listState) {
        item {
            Text(
                person?.name ?: "?",
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
        if (person == null) {
            item {
                Text(
                    stringResource(R.string.wear_no_location),
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
        } else {
            val region = wearRegionFor(listOfNotNull(self, person))
            if (region.hasCenter && person.latE7 != null) {
                item {
                    Box(Modifier.fillMaxWidth().height(150.dp).padding(horizontal = 8.dp)) {
                        WearRelativeCanvas(
                            region = region,
                            people = listOfNotNull(self, person),
                            nowMillis = now,
                            highlightId = person.id,
                        )
                    }
                }
            }
            items(lines.size) { index ->
                Text(
                    lines[index],
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }
        }
        item {
            Chip(
                onClick = onBack,
                label = { Text(stringResource(R.string.wear_back), maxLines = 1) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
    }
}
