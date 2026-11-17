package com.vayunmathur.vpn.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.vpn.R
import com.vayunmathur.vpn.Route
import com.vayunmathur.vpn.ui.components.AppIconImage
import com.vayunmathur.vpn.ui.components.BypassApp
import com.vayunmathur.vpn.ui.components.LockdownWarning
import com.vayunmathur.vpn.ui.components.loadApps
import com.vayunmathur.vpn.platform.BypassList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Per-app split tunnelling. Anything switched on here is passed to
 * `VpnService.Builder.addDisallowedApplication` next time the tunnel is established.
 *
 * The list only offers apps that hold INTERNET — excluding the rest keeps it to apps where
 * the choice actually means something — and never offers this app itself, since bypassing
 * our own traffic has no useful meaning here.
 */
@Composable
fun BypassListPage(backStack: NavBackStack<Route>) {
    val context = LocalContext.current
    val bypassed by BypassList.flow(context).collectAsState(initial = emptySet())

    // PackageManager queries are slow enough to jank the first frame; do them off the main
    // thread and show a spinner until they land.
    val apps by produceState<List<BypassApp>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { loadApps(context.packageManager, context.packageName) }
    }

    AppScaffold(
        title = stringResource(R.string.bypass_list_title),
        backStack = backStack,
        scrollBehavior = appBarScrollBehavior(),
    ) { pad ->
        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Text(
                        stringResource(R.string.bypass_loading_apps),
                        Modifier.padding(top = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            return@AppScaffold
        }

        LazyColumn(Modifier.fillMaxSize().padding(pad)) {
            item {
                LockdownWarning()
            }
            item {
                Text(
                    pluralStringResource(R.plurals.bypass_selected_count, bypassed.size, bypassed.size),
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(list, key = { it.packageName }) { app ->
                val isBypassed = app.packageName in bypassed
                ListItem(
                    modifier = Modifier.fillMaxWidth(),
                    leadingContent = { AppIconImage(app.icon) },
                    trailingContent = {
                        Switch(
                            checked = isBypassed,
                            onCheckedChange = { BypassList.setBypassed(context, app.packageName, it) },
                        )
                    },
                    supportingContent = { Text(app.packageName) },
                    content = { Text(app.label) },
                )
            }
        }
    }
}
