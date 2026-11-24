package com.vayunmathur.appstore.wear.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import com.vayunmathur.appstore.wear.R
import com.vayunmathur.appstore.wear.data.WearStore
import com.vayunmathur.appstore.wear.domain.WatchAppPhase
import com.vayunmathur.appstore.wear.platform.WearInstaller

/**
 * The whole watch store: what the phone offered, what is already downloaded
 * here, and an Install action for anything verified and waiting.
 *
 * Deliberately tiny — one scrolling column, no navigation. It reads only the
 * watch's own persisted state, so it works standalone (phone absent, app
 * cold-started) with whatever has already been downloaded.
 */
class WearMainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = WearStore.get(this)
        store.publishWatchState()
        setContent {
            MaterialTheme {
                WearStoreScreen(store)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Catch installs the system prompt completed while we were away.
        WearStore.get(this).publishWatchState()
    }
}

/** Stateless list body for [WearMainActivity]; state comes from [WearStore]. */
@Composable
fun WearStoreScreen(store: WearStore) {
    val catalog by store.catalog.collectAsState()
    val phases by store.phases.collectAsState()
    val requested by store.requested.collectAsState()
    // Recompute rows when any input moves; rows() also folds in PackageManager.
    val rows = store.rows()
    val context = androidx.compose.ui.platform.LocalContext.current
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
        if (rows.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.wear_empty),
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
            return@ScalingLazyColumn
        }
        items(rows, key = { it.app.packageName }) { row ->
            val phase = store.phaseOf(row.app.packageName)
            WearRowChip(row.app.name, phase, row.installRequested || row.app.packageName in requested) {
                if (phase == WatchAppPhase.READY_TO_INSTALL ||
                    phase == WatchAppPhase.INSTALL_FAILED
                ) {
                    WearInstaller.install(
                        context,
                        row.app.packageName,
                        store.apkFile(row.app.packageName),
                    )
                }
            }
        }
    }
}

/** One app row: name + honest phase, tappable only when install can start. */
@Composable
private fun WearRowChip(
    name: String,
    phase: WatchAppPhase,
    installRequested: Boolean,
    onClick: () -> Unit,
) {
    val label = when (phase) {
        WatchAppPhase.LISTED -> stringResource(R.string.wear_state_listed)
        WatchAppPhase.RECEIVING -> stringResource(R.string.wear_state_receiving)
        WatchAppPhase.REJECTED -> stringResource(R.string.wear_state_rejected)
        WatchAppPhase.READY_TO_INSTALL ->
            if (installRequested) {
                stringResource(R.string.wear_state_ready)
            } else {
                stringResource(R.string.wear_state_ready)
            }
        WatchAppPhase.INSTALLING -> stringResource(R.string.wear_state_installing)
        WatchAppPhase.INSTALLED -> stringResource(R.string.wear_state_installed)
        WatchAppPhase.INSTALL_FAILED -> stringResource(R.string.wear_state_failed)
    }
    val tappable = phase == WatchAppPhase.READY_TO_INSTALL ||
        phase == WatchAppPhase.INSTALL_FAILED
    Chip(
        onClick = onClick,
        enabled = tappable,
        label = { Text(name, maxLines = 1) },
        secondaryLabel = { Text(label, maxLines = 2) },
        colors = if (tappable) {
            ChipDefaults.primaryChipColors()
        } else {
            ChipDefaults.secondaryChipColors()
        },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    )
}
