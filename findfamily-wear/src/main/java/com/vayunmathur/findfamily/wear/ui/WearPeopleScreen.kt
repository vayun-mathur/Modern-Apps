package com.vayunmathur.findfamily.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.vayunmathur.findfamily.wear.R
import com.vayunmathur.findfamily.wear.data.WearFindFamilyStore
import com.vayunmathur.findfamily.wear.domain.WearAlertKind
import com.vayunmathur.findfamily.wear.domain.WearMotion
import com.vayunmathur.findfamily.wear.domain.wearAgeLabel
import com.vayunmathur.findfamily.wear.domain.wearDistanceSummary
import com.vayunmathur.findfamily.wear.domain.wearSnapshotIsStale
import com.vayunmathur.findfamily.wear.domain.wearStaleLabel
import com.vayunmathur.findfamily.wear.sync.WearLinkBridge
import kotlinx.coroutines.launch

/**
 * People home: EVERY person in the phone circle, most recently updated
 * first, with place, distance/direction from you, last-update age,
 * battery and motion — from the local snapshot (offline-safe, stale
 * labelled). Actions ask the phone; nothing here claims a phone change.
 */
@Composable
fun WearPeopleScreen(
    store: WearFindFamilyStore,
    onOpenPerson: (Long) -> Unit,
    onOpenMap: () -> Unit,
) {
    val snapshot by store.snapshot.collectAsState()
    val connected by store.connected.collectAsState()
    val context = LocalContext.current
    WearPeopleContent(snapshot = snapshot, connected = connected, nowMillis = System.currentTimeMillis(), onOpenPerson = onOpenPerson, onOpenMap = onOpenMap, onRequestRefresh = { WearLinkBridge.requestRefresh(context) }, onStopSharing = { WearLinkBridge.stopSharingNow(context) })
}

/** Stateless people home behind [WearPeopleScreen]: snapshot/connection/instant are parameters for previews. */
@Composable
internal fun WearPeopleContent(
    snapshot: com.vayunmathur.findfamily.wear.domain.WearFindFamilySnapshot,
    connected: Boolean,
    nowMillis: Long,
    onOpenPerson: (Long) -> Unit,
    onOpenMap: () -> Unit,
    onRequestRefresh: suspend () -> Boolean,
    onStopSharing: suspend () -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    val requestedText = stringResource(R.string.wear_requested)
    val noPhoneText = stringResource(R.string.wear_no_phone)
    val now = nowMillis
    val self = snapshot.people.firstOrNull { it.isSelf }
    val listState = rememberScalingLazyListState()

    ScalingLazyColumn(state = listState) {
        item {
            Text(
                stringResource(R.string.app_name),
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
        item {
            Text(
                if (snapshot.sharingEnabled) stringResource(R.string.wear_sharing_on)
                else stringResource(R.string.wear_sharing_off),
                style = MaterialTheme.typography.body2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }
        if (!connected) {
            item {
                Text(
                    stringResource(R.string.wear_offline),
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
        } else if (wearSnapshotIsStale(snapshot, now)) {
            item {
                Text(
                    stringResource(R.string.wear_stale_snapshot),
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
        }
        if (snapshot.truncated) {
            item {
                Text(
                    stringResource(R.string.wear_truncated, snapshot.people.size, snapshot.totalPeople),
                    style = MaterialTheme.typography.caption2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }
        }
        note?.let { text ->
            item {
                Text(
                    text,
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }
        }
        if (snapshot.people.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.wear_empty),
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
        } else {
            items(snapshot.people, key = { it.id }) { person ->
                val place = person.placeLabel.ifBlank { stringResource(R.string.wear_no_location) }
                val distance = wearDistanceSummary(self, person)
                val age = wearAgeLabel(person.updatedAtMillis, now)
                val battery = if (person.batteryPercent >= 0) " · ${person.batteryPercent}%" else ""
                val motion = when (person.motion) {
                    WearMotion.MOVING -> " · Moving"
                    WearMotion.STATIONARY -> " · Still"
                    WearMotion.UNKNOWN -> ""
                }
                val stale = wearStaleLabel(person, now)
                Chip(
                    onClick = { onOpenPerson(person.id) },
                    label = {
                        Text(
                            if (person.isSelf) "${person.name} (${stringResource(R.string.wear_you)})" else person.name,
                            maxLines = 1,
                        )
                    },
                    secondaryLabel = {
                        Text(
                            listOf(place, distance, age, stale).filter { it.isNotBlank() }
                                .joinToString(" · ") + battery + motion,
                            maxLines = 2,
                        )
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
        }
        // Alerts: current saved-place state + no-show rows from the phone.
        if (snapshot.alerts.isNotEmpty()) {
            item {
                Text(
                    stringResource(R.string.wear_alerts),
                    style = MaterialTheme.typography.title3,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
            items(snapshot.alerts.take(10), key = { "${it.kind}-${it.personId}-${it.atMillis}" }) { alert ->
                val personName = snapshot.people.firstOrNull { it.id == alert.personId }?.name ?: "?"
                Text(
                    when (alert.kind) {
                        WearAlertKind.AT_PLACE -> "$personName at ${alert.placeLabel}"
                        WearAlertKind.NOSHOW ->
                            if (alert.fired) "$personName did not arrive${if (alert.placeLabel.isNotBlank()) " at ${alert.placeLabel}" else ""}"
                            else "$personName expected${if (alert.placeLabel.isNotBlank()) " at ${alert.placeLabel}" else ""}"
                    },
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }
        }
        item {
            Chip(
                onClick = onOpenMap,
                label = { Text(stringResource(R.string.wear_map), maxLines = 1) },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        item {
            Chip(
                onClick = {
                    scope.launch {
                        note = if (onRequestRefresh()) requestedText else noPhoneText
                    }
                },
                label = { Text(stringResource(R.string.wear_refresh), maxLines = 1) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        item {
            Chip(
                onClick = {
                    scope.launch {
                        note = if (onStopSharing()) requestedText else noPhoneText
                    }
                },
                label = { Text(stringResource(R.string.wear_stop_sharing), maxLines = 1) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
    }
}
