package com.vayunmathur.launcher.wear.ui

import android.graphics.drawable.Drawable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
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
import com.vayunmathur.launcher.wear.platform.InstalledApps
import com.vayunmathur.launcher.wear.sync.requestWearSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Round home: favorites, recents, our-apps shortcuts, then drawer/settings entries. */
@Composable
fun WearLauncherScreen(
    store: WearLauncherStore,
    onOpenAllApps: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snapshot by store.snapshot.collectAsState()
    val recents by store.recents.collectAsState()
    var syncNote by remember { mutableStateOf<String?>(null) }
    val requestedText = stringResource(R.string.wear_sync_requested)
    val noPhoneText = stringResource(R.string.wear_no_phone)
    val installed by produceState(initialValue = emptyList<WatchApp>(), context) {
        value = withContext(Dispatchers.IO) { InstalledApps.load(context) }
    }
    val sections = remember(installed, snapshot, recents) {
        buildWearLauncherSections(installed, snapshot, recents, context.packageName)
    }
    WearLauncherContent(
        sections = sections,
        installedEmpty = installed.isEmpty(),
        onLaunch = { app ->
            val intent = InstalledApps.launchIntent(context, app) ?: return@WearLauncherContent
            store.recordLaunch(app.packageName)
            runCatching { context.startActivity(intent) }
        },
        onOpenAllApps = onOpenAllApps,
        onOpenSettings = onOpenSettings,
        onRequestSync = { requestWearSync(context) },
    )
}

/**
 * Stateless launcher home behind [WearLauncherScreen]: sections are a parameter, so
 * Compose previews (src/screenshotTest) render literal fixtures with no store/PackageManager.
 */
@Composable
internal fun WearLauncherContent(
    sections: com.vayunmathur.launcher.wear.domain.WearLauncherSections,
    installedEmpty: Boolean,
    onLaunch: (WatchApp) -> Unit,
    onOpenAllApps: () -> Unit,
    onOpenSettings: () -> Unit,
    onRequestSync: suspend () -> Boolean,
) {
    val scope = rememberCoroutineScope()
    var syncNote by remember { mutableStateOf<String?>(null) }
    val requestedText = stringResource(R.string.wear_sync_requested)
    val noPhoneText = stringResource(R.string.wear_no_phone)
    val listState = rememberScalingLazyListState()
    fun launch(app: WatchApp) = onLaunch(app)

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
                stringResource(R.string.wear_not_home_note),
                style = MaterialTheme.typography.caption2,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
        syncNote?.let { note ->
            item {
                Text(
                    note,
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
        if (installedEmpty) {
            item {
                Text(
                    stringResource(R.string.wear_no_apps),
                    style = MaterialTheme.typography.body2,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
        } else {
            if (sections.favorites.isNotEmpty()) {
                item { WearSectionHeader(stringResource(R.string.wear_favorites)) }
                items(sections.favorites, key = { "fav:${it.packageName}" }) { app ->
                    WearAppChip(app) { launch(app) }
                }
            } else {
                item {
                    Text(
                        stringResource(R.string.wear_no_favorites),
                        style = MaterialTheme.typography.body2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                }
            }
            sections.missingFavorites.forEach { favorite ->
                item(key = "missing:${favorite.packageName}") {
                    Text(
                        stringResource(R.string.wear_missing_favorite, favorite.label.ifBlank { favorite.packageName }),
                        style = MaterialTheme.typography.caption2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                }
            }
            if (sections.recents.isNotEmpty()) {
                item { WearSectionHeader(stringResource(R.string.wear_recent)) }
                items(sections.recents, key = { "recent:${it.packageName}" }) { app ->
                    WearAppChip(app) { launch(app) }
                }
            }
            if (sections.companions.isNotEmpty()) {
                item { WearSectionHeader(stringResource(R.string.wear_our_apps)) }
                items(sections.companions, key = { "ours:${it.packageName}" }) { app ->
                    WearAppChip(app) { launch(app) }
                }
            }
        }
        item {
            Chip(
                onClick = onOpenAllApps,
                label = { Text(stringResource(R.string.wear_all_apps), maxLines = 1) },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        item {
            Chip(
                onClick = onOpenSettings,
                label = { Text(stringResource(R.string.wear_settings), maxLines = 1) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
        item {
            Chip(
                onClick = {
                    scope.launch {
                        syncNote = if (onRequestSync()) requestedText else noPhoneText
                    }
                },
                label = { Text(stringResource(R.string.wear_request_sync), maxLines = 1) },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            )
        }
    }
}

@Composable
internal fun WearSectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.caption1,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/** One launchable app row with its on-device icon (loaded from PackageManager). */
@Composable
internal fun WearAppChip(app: WatchApp, onClick: () -> Unit) {
    val context = LocalContext.current
    val icon by produceState<Drawable?>(initialValue = null, app.packageName) {
        value = withContext(Dispatchers.IO) {
            runCatching { context.packageManager.getApplicationIcon(app.packageName) }.getOrNull()
        }
    }
    Chip(
        onClick = onClick,
        label = { Text(app.label, maxLines = 1) },
        icon = {
            icon?.let { drawable ->
                Image(
                    bitmap = drawable.toBitmap(48, 48).asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                )
            }
        },
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    )
}
