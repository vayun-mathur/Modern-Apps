package com.vayunmathur.findfamily.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.ExposedDropdownMenuDefaults
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.IconCheck
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.SelectableDropdownMenuItem
import com.vayunmathur.library.ui.Text
import com.vayunmathur.findfamily.ui.dialogs.interactionSourceClickable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.findfamily.R
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.platform.PersonActions
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Clock

@Composable
fun AutoToggleRow(user: User, waypoints: List<Waypoint>, actions: PersonActions) {
    val neverLabel = stringResource(R.string.auto_toggle_never)
    val opts = remember {
        listOf(
            Pair("15 min", 15.minutes),
            Pair("30 min", 30.minutes),
            Pair("1 hour", 1.hours),
            Pair("2 hours", 2.hours),
            Pair("4 hours", 4.hours),
            Pair("6 hours", 6.hours),
            Pair("12 hours", 12.hours),
            Pair("1 day", 1.days),
            Pair("2 days", 2.days),
            Pair("1 week", 7.days),
        )
    }

    val resolvedLabels = mapOf(
        "15 min" to stringResource(R.string.expiry_15_minutes),
        "30 min" to stringResource(R.string.expiry_30_minutes),
        "1 hour" to stringResource(R.string.expiry_1_hour),
        "2 hours" to stringResource(R.string.expiry_2_hours),
        "4 hours" to stringResource(R.string.expiry_4_hours),
        "6 hours" to stringResource(R.string.expiry_6_hours),
        "12 hours" to stringResource(R.string.expiry_12_hours),
        "1 day" to stringResource(R.string.expiry_1_day),
        "2 days" to stringResource(R.string.expiry_2_days),
        "1 week" to stringResource(R.string.expiry_1_week),
    )

    val labelToDuration: Map<String, Duration> = opts.associate { (k, v) -> (resolvedLabels[k] ?: k) to v }

    // Arrival triggers: one "Arrival at <name>" option per named saved place.
    val arrivalWaypoints = waypoints.filter { it.name.isNotBlank() }
    val arrivalWaypointName = user.sharingAutoToggleWaypointId
        ?.let { id -> arrivalWaypoints.firstOrNull { it.id == id }?.name }

    // Live ticker for countdown — ticks every second while a timeout is active
    var now by remember { mutableStateOf(Clock.System.now()) }
    val endAt = user.sharingAutoToggleAt
    LaunchedEffect(endAt) {
        if (endAt == null) return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(1000)
            val current = Clock.System.now()
            now = current
            if (current >= endAt) break
        }
    }

    // Arrival trigger wins if set; otherwise show live [hh:]mm:ss countdown; otherwise Never.
    // Auto-toggle modes are mutually exclusive, so at most one of these is active.
    val currentLabel = when {
        arrivalWaypointName != null -> stringResource(R.string.arrival_at, arrivalWaypointName)
        endAt == null -> neverLabel
        else -> {
            val remaining = endAt - now
            if (remaining.inWholeSeconds <= 0) neverLabel else formatAutoToggleCountdown(remaining)
        }
    }

    val dropdownLabel = if (user.sendingEnabled) stringResource(R.string.disable_after) else stringResource(R.string.enable_after)

    var expanded by remember { mutableStateOf(false) }

    Row(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            dropdownLabel,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        Box {
            OutlinedTextField(
                currentLabel, {},
                interactionSource = interactionSourceClickable { expanded = true },
                readOnly = true,
                modifier = Modifier.width(180.dp),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) }
            )
            DropdownMenu(expanded, { expanded = false }) {
                // Never option first — disables auto-toggle; others reschedule countdown.
                // Auto-toggle modes are mutually exclusive, so exactly one option is current:
                // "Never" while nothing is armed, or the arrival waypoint while an arrival
                // trigger is armed. An armed countdown only stores its fire time, not the
                // nominal duration, so the smallest option covering the remaining time is
                // marked (exact while the head of the countdown, a shrinking bucket after).
                val remaining = endAt?.let { it - now }
                SelectableDropdownMenuItem(
                    selected = endAt == null && user.sharingAutoToggleWaypointId == null,
                    onClick = {
                        expanded = false
                        actions.setUserAutoToggle(user, null)
                    },
                    text = { Text(neverLabel) },
                    selectedLeadingIcon = { IconCheck() },
                )
                labelToDuration.forEach { (label, dur) ->
                    SelectableDropdownMenuItem(
                        selected = user.sharingAutoToggleWaypointId == null && remaining != null &&
                            remaining.inWholeSeconds > 0 && dur >= remaining &&
                            labelToDuration.values.none { other -> other < dur && other >= remaining },
                        onClick = {
                            expanded = false
                            actions.setUserAutoToggle(user, dur)
                        },
                        text = { Text(label) },
                        selectedLeadingIcon = { IconCheck() },
                    )
                }
                // Arrival triggers — flip sharing when "Me" arrives at a saved place (GitHub #406).
                arrivalWaypoints.forEach { waypoint ->
                    SelectableDropdownMenuItem(
                        selected = user.sharingAutoToggleWaypointId == waypoint.id,
                        onClick = {
                            expanded = false
                            actions.setUserArrivalToggle(user, waypoint.id)
                        },
                        text = { Text(stringResource(R.string.arrival_at, waypoint.name)) },
                        selectedLeadingIcon = { IconCheck() },
                    )
                }
            }
        }
    }
}
