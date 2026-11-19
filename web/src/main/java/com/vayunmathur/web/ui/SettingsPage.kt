package com.vayunmathur.web.ui

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.vayunmathur.web.R
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.HorizontalDivider
import com.vayunmathur.library.ui.IconNavigation
import com.vayunmathur.library.ui.ListItem
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.RadioButton
import com.vayunmathur.library.ui.Scaffold
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Switch
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.TopAppBar
import com.vayunmathur.web.Route
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.web.platform.CacheMode
import com.vayunmathur.web.platform.SearchEngine
import com.vayunmathur.web.platform.WebViewModel
import com.vayunmathur.web.platform.clearAllDownloads
import com.vayunmathur.web.platform.clearAllSiteData
import com.vayunmathur.web.platform.clearHistory
import com.vayunmathur.web.platform.updateBlockThirdParty
import com.vayunmathur.web.platform.updateCacheMode
import com.vayunmathur.web.platform.updateDesktopMode
import com.vayunmathur.web.platform.updateJsEnabled
import com.vayunmathur.web.platform.updateSearchBarAtBottom
import com.vayunmathur.web.platform.updateSearchEngine

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsPage(
    viewModel: WebViewModel,
    backStack: NavBackStack<Route>,
) {
    val context = LocalContext.current
    var showClearDataDialog by remember { mutableStateOf(false) }
    var showCacheDialog by remember { mutableStateOf(false) }
    var showSearchEngineDialog by remember { mutableStateOf(false) }

    val historyEntries by viewModel.history.collectAsStateWithLifecycle()
    val downloadEntries by viewModel.downloads.collectAsStateWithLifecycle()
    val storageCount by viewModel.storageInfos.collectAsStateWithLifecycle()
    val permCount by viewModel.sitePermissions.collectAsStateWithLifecycle()
    val installedCount by viewModel.installedSites.collectAsStateWithLifecycle()

    AppScaffold(
        title = stringResource(UiR.string.settings),
        backStack = backStack,
    ) { paddingValues ->
        DesktopMaxWidthContainer(modifier = Modifier.padding(paddingValues)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            item {
                Text(stringResource(R.string.general), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }

            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.search_engine)) },
                    supportingContent = { Text(viewModel.searchEngine.displayName) },
                    modifier = Modifier.clickable { showSearchEngineDialog = true }
                )
            }

            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.cache_mode)) },
                    supportingContent = { Text("${viewModel.cacheMode.title} — ${viewModel.cacheMode.description}") },
                    modifier = Modifier.clickable { showCacheDialog = true }
                )
            }

            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.toolbar_at_bottom)) },
                    supportingContent = { Text(stringResource(R.string.keeps_the_address_bar_within_thumb_reach)) },
                    trailingContent = {
                        Switch(checked = viewModel.searchBarAtBottom, onCheckedChange = { viewModel.updateSearchBarAtBottom(it) })
                    }
                )
            }

            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.javascript)) },
                    supportingContent = { Text(stringResource(R.string.required_by_most_sites)) },
                    trailingContent = {
                        Switch(checked = viewModel.jsEnabled, onCheckedChange = { viewModel.updateJsEnabled(it) })
                    }
                )
            }

            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.block_third_party_cookies)) },
                    supportingContent = { Text(stringResource(R.string.may_break_logins)) },
                    trailingContent = {
                        Switch(checked = viewModel.blockThirdPartyCookies, onCheckedChange = { viewModel.updateBlockThirdParty(it) })
                    }
                )
            }

            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.desktop_mode)) },
                    supportingContent = { Text(stringResource(R.string.request_desktop_site)) },
                    trailingContent = {
                        Switch(checked = viewModel.desktopMode, onCheckedChange = { viewModel.updateDesktopMode(it) })
                    }
                )
            }

            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.shields)) },
                    supportingContent = {
                        Text(
                            stringResource(
                                when (viewModel.shields.level) {
                                    com.vayunmathur.web.domain.ShieldLevel.OFF -> R.string.shields_level_off_desc
                                    com.vayunmathur.web.domain.ShieldLevel.STANDARD -> R.string.shields_level_standard_desc
                                    else -> R.string.shields_level_aggressive_desc
                                }
                            )
                        )
                    },
                    modifier = Modifier.clickable { backStack.add(Route.Shields) }
                )
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }

            item {
                Text(stringResource(R.string.privacy_data), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }

            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.clear_browsing_data)) },
                    supportingContent = { Text(stringResource(R.string.cookies_cache_history_storage_permission)) },
                    modifier = Modifier.clickable { showClearDataDialog = true }
                )
            }

            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.site_data)) },
                    supportingContent = { Text(stringResource(R.string.sites_permission_grants, storageCount.size, permCount.size)) },
                    modifier = Modifier.clickable { backStack.add(Route.SiteData) }
                )
            }

            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.history)) },
                    supportingContent = { Text(pluralStringResource(R.plurals.entries, historyEntries.size, historyEntries.size)) },
                    modifier = Modifier.clickable { backStack.add(Route.History) }
                )
            }

            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.downloads)) },
                    supportingContent = { Text(pluralStringResource(R.plurals.files, downloadEntries.size, downloadEntries.size)) },
                    modifier = Modifier.clickable { backStack.add(Route.Downloads) }
                )
            }

            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.installed_apps)) },
                    supportingContent = { Text(pluralStringResource(R.plurals.pwas, installedCount.size, installedCount.size)) },
                    modifier = Modifier.clickable { backStack.add(Route.InstalledSites) }
                )
            }

            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }

            item {
                Text(stringResource(R.string.about), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }

            item {
                ListItem(
                    headlineContent = { Text(stringResource(R.string.app_name)) },
                )
            }

            item {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.padding(16.dp)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.engine, WebView.getCurrentWebViewPackage()?.let { "${it.packageName} ${it.versionName}" } ?: "System WebView"), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        }
    }

    if (showCacheDialog) {
        AlertDialog(
            onDismissRequest = { showCacheDialog = false },
            title = { Text(stringResource(R.string.cache_mode)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    CacheMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                viewModel.updateCacheMode(mode); showCacheDialog = false
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = viewModel.cacheMode == mode, onClick = {
                                viewModel.updateCacheMode(mode); showCacheDialog = false
                            })
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(mode.title, style = MaterialTheme.typography.bodyMedium)
                                Text(mode.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showCacheDialog = false }) { Text(stringResource(UiR.string.close)) } }
        )
    }


    if (showSearchEngineDialog) {
        AlertDialog(
            onDismissRequest = { showSearchEngineDialog = false },
            title = { Text(stringResource(R.string.search_engine)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SearchEngine.PICKABLE.forEach { engine ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                viewModel.updateSearchEngine(engine); showSearchEngineDialog = false
                            }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = viewModel.searchEngine == engine, onClick = {
                                viewModel.updateSearchEngine(engine); showSearchEngineDialog = false
                            })
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(engine.displayName, style = MaterialTheme.typography.bodyMedium)
                                Text(engine.homepage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showSearchEngineDialog = false }) { Text(stringResource(UiR.string.close)) } }
        )
    }

    if (showClearDataDialog) {
        AlertDialog(
            onDismissRequest = { showClearDataDialog = false },
            title = { Text(stringResource(R.string.clear_browsing_data_2)) },
            text = { Text(stringResource(R.string.clears_cookies_cache_storage_history_dow)) },
            confirmButton = {
                TextButton(onClick = {
                    try { CookieManager.getInstance().removeAllCookies(null); CookieManager.getInstance().flush() } catch (_: Exception) {}
                    try { WebStorage.getInstance().deleteAllData() } catch (_: Exception) {}
                    try { WebView(context).clearCache(true) } catch (_: Exception) {}
                    viewModel.clearHistory()
                    viewModel.clearAllDownloads()
                    viewModel.clearAllSiteData()
                    showClearDataDialog = false
                }) { Text(stringResource(UiR.string.clear)) }
            },
            dismissButton = { TextButton(onClick = { showClearDataDialog = false }) { Text(stringResource(UiR.string.cancel)) } }
        )
    }
}
