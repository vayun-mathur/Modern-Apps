package com.vayunmathur.web.ui

import androidx.compose.ui.res.stringResource
import com.vayunmathur.web.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.Card
import com.vayunmathur.library.ui.CardDefaults
import com.vayunmathur.library.ui.Checkbox
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconCamera
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.IconDelete
import com.vayunmathur.library.ui.IconGlobe
import com.vayunmathur.library.ui.IconLocationOn
import com.vayunmathur.library.ui.IconMic
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.LazyListScaffold
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.web.Route
import com.vayunmathur.web.data.SitePermission
import com.vayunmathur.web.data.StorageInfo
import com.vayunmathur.web.platform.BrowserUtils
import com.vayunmathur.web.platform.SitePermissionType
import com.vayunmathur.web.platform.WebViewModel
import com.vayunmathur.web.platform.clearAllSiteData
import com.vayunmathur.web.platform.clearSiteData
import com.vayunmathur.web.platform.revokePermission

@Composable
fun PermissionPromptSheet(
    origin: String,
    types: List<SitePermissionType>,
    onGrant: (List<SitePermissionType>) -> Unit,
    onDeny: () -> Unit,
) {
    var selected by remember { mutableStateOf(types.toSet()) }

    AlertDialog(
        onDismissRequest = onDeny,
        title = { Text(stringResource(R.string.allow_to_access, BrowserUtils.hostFromUrl(origin))) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(origin, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                types.forEach { t ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        when (t) {
                            SitePermissionType.CAMERA -> IconCamera()
                            SitePermissionType.MICROPHONE -> IconMic()
                            SitePermissionType.LOCATION -> IconLocationOn()
                            SitePermissionType.NOTIFICATIONS -> IconGlobe()
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(t.displayName, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Checkbox(checked = t in selected, onCheckedChange = { checked ->
                            selected = if (checked) selected + t else selected - t
                        })
                    }
                }
                Text(stringResource(R.string.your_choice_is_saved_per_site_change_it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onGrant(selected.toList()) }, enabled = selected.isNotEmpty()) { Text(stringResource(R.string.allow)) } },
        dismissButton = { TextButton(onClick = onDeny) { Text(stringResource(R.string.block)) } }
    )
}

@Composable
internal fun GeolocationPromptSheet(
    origin: String,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDeny,
        title = { Text(stringResource(R.string.allow_location)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.wants_to_know_your_location, BrowserUtils.hostFromUrl(origin)))
                Text(origin, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(stringResource(R.string.uses_system_location_services_private_ta), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onAllow) { Text(stringResource(R.string.allow)) } },
        dismissButton = { TextButton(onClick = onDeny) { Text(stringResource(R.string.block)) } }
    )
}

@Composable
internal fun LocalNetworkPromptSheet(
    host: String,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDeny,
        title = { Text(stringResource(R.string.local_network_title)) },
        text = { Text(stringResource(R.string.local_network_message, host)) },
        confirmButton = { TextButton(onClick = onAllow) { Text(stringResource(R.string.allow)) } },
        dismissButton = { TextButton(onClick = onDeny) { Text(stringResource(R.string.block)) } }
    )
}

@Composable
internal fun FileChooserSheet(
    mimeTypes: List<String>,
    onCancel: () -> Unit,
    onTriggerPicker: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.choose_files)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.select_files_to_upload))
                if (mimeTypes.isNotEmpty()) {
                    Text(stringResource(R.string.accepted, mimeTypes.joinToString()), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onTriggerPicker) { Text(stringResource(R.string.pick_files)) } },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(UiR.string.cancel)) } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SiteDataPage(
    viewModel: WebViewModel,
    backStack: NavBackStack<Route>,
) {
    val storages by viewModel.storageInfos.collectAsStateWithLifecycle()
    val permissions by viewModel.sitePermissions.collectAsStateWithLifecycle()

    LazyListScaffold(
        horizontalPadding = 12.dp,
        verticalArrangement = Arrangement.spacedBy(10.dp),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.site_data)) },
                navigationIcon = { IconNavigation(backStack) },
                actions = {
                    if (storages.isNotEmpty() || permissions.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearAllSiteData() }) { IconDelete() }
                    }
                },
            )
        },
        scrollBehavior = appBarScrollBehavior(),
    ) {
        if (storages.isEmpty() && permissions.isEmpty()) {
            item {
                Text(
                    stringResource(R.string.no_site_data_yet_cookies_localstorage_in),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (storages.isNotEmpty()) {
            item { Text(stringResource(R.string.storage), style = MaterialTheme.typography.titleMedium) }
            items(storages, key = { "storage-${it.id}" }) { info ->
                StorageInfoCard(info, viewModel)
            }
        }

        if (permissions.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.permissions), style = MaterialTheme.typography.titleMedium)
            }
            items(permissions, key = { "perm-${it.id}" }) { perm ->
                PermissionInfoCard(perm, viewModel)
            }
        }
    }
}

@Composable
private fun StorageInfoCard(info: StorageInfo, viewModel: WebViewModel) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(info.host, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(info.origin, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                IconButton(onClick = { viewModel.clearSiteData(info.origin) }) { IconClose() }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (info.cookieCount > 0) ChipBadge("${info.cookieCount} cookies")
                if (info.hasLocalStorage) ChipBadge("localStorage")
                if (info.hasIndexedDb) ChipBadge("IndexedDB")
                if (info.hasServiceWorker) ChipBadge("ServiceWorker")
                if (info.estimatedBytes > 0) ChipBadge("${info.estimatedBytes / 1024} KB")
            }
        }
    }
}

@Composable
private fun PermissionInfoCard(perm: SitePermission, viewModel: WebViewModel) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(BrowserUtils.hostFromUrl(perm.origin), style = MaterialTheme.typography.titleSmall)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                PermRow("Camera", perm.cameraAllowed) { viewModel.revokePermission(perm.origin, SitePermissionType.CAMERA) }
                PermRow("Microphone", perm.microphoneAllowed) { viewModel.revokePermission(perm.origin, SitePermissionType.MICROPHONE) }
                PermRow("Location", perm.locationAllowed) { viewModel.revokePermission(perm.origin, SitePermissionType.LOCATION) }
                PermRow("Notifications", perm.notificationsAllowed) { viewModel.revokePermission(perm.origin, SitePermissionType.NOTIFICATIONS) }
            }
            if (perm.cameraAllowed == null && perm.microphoneAllowed == null && perm.locationAllowed == null && perm.notificationsAllowed == null) {
                Text(stringResource(R.string.no_permissions_set), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun PermRow(label: String, allowed: Boolean?, onClear: () -> Unit) {
    if (allowed == null) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("$label: ${if (allowed) "Allowed" else "Blocked"}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        TextButton(onClick = onClear) { Text(stringResource(UiR.string.clear)) }
    }
}

@Composable
private fun ChipBadge(text: String) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
    }
}
