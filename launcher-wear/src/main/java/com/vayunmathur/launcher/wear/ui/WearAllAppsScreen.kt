package com.vayunmathur.launcher.wear.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.vayunmathur.launcher.wear.R
import com.vayunmathur.launcher.wear.data.WearLauncherStore
import com.vayunmathur.launcher.wear.domain.WatchApp
import com.vayunmathur.launcher.wear.domain.buildWearLauncherSections
import com.vayunmathur.launcher.wear.domain.filterWatchApps
import com.vayunmathur.launcher.wear.platform.InstalledApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Full drawer: every launchable watch app, with a local text filter. */
@Composable
fun WearAllAppsScreen(store: WearLauncherStore, onBack: () -> Unit) {
    val context = LocalContext.current
    val snapshot by store.snapshot.collectAsState()
    val installed by produceState(initialValue = emptyList<WatchApp>(), context) {
        value = withContext(Dispatchers.IO) { InstalledApps.load(context) }
    }
    val all = remember(installed, snapshot) {
        buildWearLauncherSections(installed, snapshot, emptyList(), context.packageName).all
    }
    WearAllAppsContent(
        all = all,
        onLaunch = { app ->
            val intent = InstalledApps.launchIntent(context, app) ?: return@WearAllAppsContent
            store.recordLaunch(app.packageName)
            runCatching { context.startActivity(intent) }
        },
        onBack = onBack,
    )
}

/**
 * Stateless drawer behind [WearAllAppsScreen]: the app list is a parameter, so Compose
 * previews (src/screenshotTest) render literal fixtures with no store/PackageManager.
 */
@Composable
internal fun WearAllAppsContent(
    all: List<WatchApp>,
    onLaunch: (WatchApp) -> Unit,
    onBack: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(all, query) { filterWatchApps(all, query) }
    val listState = rememberScalingLazyListState()

    ScalingLazyColumn(state = listState) {
        item {
            Text(
                stringResource(R.string.wear_all_apps),
                style = MaterialTheme.typography.title3,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
            )
        }
        item {
            // SCAFFOLD SEARCH EXCEPTION: Wear Compose Material has no scaffold
            // search slot; this is the watch drawer's only search field.
            BasicTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 14.sp),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text(stringResource(R.string.wear_search_apps), color = Color.Gray, fontSize = 14.sp)
                    }
                    inner()
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        if (filtered.isEmpty()) {
            item {
                Text(
                    if (all.isEmpty()) stringResource(R.string.wear_no_apps)
                    else stringResource(R.string.wear_no_matches),
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
        } else {
            items(filtered, key = { it.packageName }) { app ->
                WearAppChip(app) { onLaunch(app) }
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
