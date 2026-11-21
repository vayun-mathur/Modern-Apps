package com.vayunmathur.findfamily.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vayunmathur.findfamily.R
import com.vayunmathur.library.ui.IconWifiFull
import com.vayunmathur.library.ui.IconWifiOff
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import kotlin.time.Instant
import kotlinx.coroutines.delay

/**
 * Relay connectivity for the map top bar.
 *
 * A relay outage and every family member being offline used to look the
 * same: pins simply stopped moving. This chip makes the relay itself
 * visible — connected quietly, disconnected with the age of the newest
 * location on screen so stale positions are never read as current.
 */
@Composable
fun RelayStatusChip(relayConnected: Boolean, lastLocationMillis: Long?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Re-render the "last updated" age while disconnected; it is the whole
    // point of the disconnected state and must not freeze at disconnect time.
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(relayConnected) {
        while (!relayConnected) {
            nowMillis = System.currentTimeMillis()
            delay(30_000L)
        }
    }
    val container = if (relayConnected) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val content = if (relayConnected) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    val label = if (relayConnected) {
        stringResource(R.string.relay_connected)
    } else if (lastLocationMillis != null) {
        stringResource(
            R.string.relay_disconnected_last_updated,
            timestring(Instant.fromEpochMilliseconds(lastLocationMillis), context),
        )
    } else {
        stringResource(R.string.relay_disconnected_no_locations)
    }
    Row(
        modifier = modifier
            .background(container, RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (relayConnected) {
            IconWifiFull(Modifier.size(16.dp), tint = content)
        } else {
            IconWifiOff(Modifier.size(16.dp), tint = content)
        }
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = content,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
