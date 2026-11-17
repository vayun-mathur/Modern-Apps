package com.vayunmathur.web.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.AlertDialog
import com.vayunmathur.library.ui.DropdownMenu
import com.vayunmathur.library.ui.DropdownMenuItem
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.library.ui.IconArrowForward
import com.vayunmathur.library.ui.IconBack
import com.vayunmathur.library.ui.IconButton
import com.vayunmathur.library.ui.IconHistory
import com.vayunmathur.library.ui.IconRefresh
import com.vayunmathur.library.ui.IconShare
import com.vayunmathur.library.ui.IconShield
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OutlinedTextField
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TextButton
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.web.R
import com.vayunmathur.web.Route
import com.vayunmathur.web.platform.BrowserUtils
import com.vayunmathur.web.platform.PwaHelper
import com.vayunmathur.web.platform.PwaInfo
import com.vayunmathur.web.platform.WebViewModel
import com.vayunmathur.web.platform.addBookmark
import com.vayunmathur.web.platform.getPwaInfo
import com.vayunmathur.web.platform.getTabTitle
import com.vayunmathur.web.platform.installAsPwa
import com.vayunmathur.web.platform.newTab
import com.vayunmathur.web.platform.removeBookmark

/**
 * Overflow ("dropup") menu for the browser chrome. Its first item is a
 * row of five icon-only buttons — back, forward, reload, share,
 * history — followed by shields, install/pin, bookmark, new tabs, and
 * the remaining library/settings destinations as text rows. The five
 * icon actions deliberately have no text rows below: one entry point
 * per action.
 */
@Composable
internal fun BrowserMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    viewModel: WebViewModel,
    backStack: NavBackStack<Route>,
    isNewTabActive: Boolean,
    isCurrentBookmarked: Boolean,
    canGoBack: Boolean,
    canGoForward: Boolean,
    onGoBack: () -> Unit,
    onGoForward: () -> Unit,
    shieldHost: String?,
    blockedCount: Int,
    onShieldClick: () -> Unit,
    onShowInstallDialog: () -> Unit,
    onReload: () -> Unit,
) {
    val context = LocalContext.current
    val shareLinkTitle = stringResource(R.string.share_link)
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val activeTab = viewModel.activeTab
    val shareEnabled = activeTab?.url?.isNotBlank() == true
    val onShare: () -> Unit = {
        onDismiss()
        activeTab?.let { tab ->
            if (tab.url.isBlank()) return@let
            val sendIntent = android.content.Intent().apply {
                action = android.content.Intent.ACTION_SEND
                putExtra(android.content.Intent.EXTRA_TEXT, tab.url)
                type = "text/plain"
            }
            ExternalIntents.launch(context, android.content.Intent.createChooser(sendIntent, shareLinkTitle))
        }
    }
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        // First item: the five frequent actions as one icon-only row.
        // These replace the old Back/Forward/Reload/Share/History text
        // rows below — those destinations must not appear twice. Icons
        // come from library/ui (IconBack & friends), never material
        // icons directly. IconButton greys its content out when
        // disabled, so unavailable actions (no back history, nothing
        // to reload/share on a new tab) read as disabled in place
        // instead of vanishing and reflowing the row.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            IconButton(onClick = { onDismiss(); onGoBack() }, enabled = canGoBack) { IconBack() }
            IconButton(onClick = { onDismiss(); onGoForward() }, enabled = canGoForward) { IconArrowForward() }
            IconButton(onClick = { onDismiss(); onReload() }, enabled = !isNewTabActive) { IconRefresh() }
            IconButton(onClick = onShare, enabled = shareEnabled) { IconShare() }
            IconButton(onClick = { onDismiss(); backStack.add(Route.History) }) { IconHistory() }
        }
        if (shieldHost != null) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.shields)) },
                leadingIcon = { IconShield() },
                trailingIcon = {
                    if (blockedCount > 0) Text(blockedCount.toString())
                },
                onClick = {
                    onDismiss()
                    onShieldClick()
                },
            )
        }
        if (activeTab != null && !isNewTabActive) {
            val pwa = viewModel.getPwaInfo(activeTab.id)
            val pinSupported = PwaHelper.isPinSupported(context)
            val label = if (pwa?.hasManifest == true) "Install app" else "Add to Home screen"
            DropdownMenuItem(
                text = { Text(label) },
                onClick = {
                    onDismiss()
                    onShowInstallDialog()
                },
                enabled = activeTab.url.isNotBlank() && BrowserUtils.isWebUrl(activeTab.url) && pinSupported
            )
        }
        DropdownMenuItem(
            text = { Text(if (isCurrentBookmarked) "Remove bookmark" else "Add bookmark") },
            onClick = {
                onDismiss()
                activeTab?.let { tab ->
                    if (tab.url.isBlank()) return@let
                    if (isCurrentBookmarked) {
                        bookmarks.find { it.url == tab.url }?.let { viewModel.removeBookmark(it) }
                    } else viewModel.addBookmark(tab.url, tab.title.ifBlank { tab.url })
                }
            }
        )
        DropdownMenuItem(text = { Text(stringResource(R.string.new_tab)) }, onClick = { onDismiss(); viewModel.newTab() })
        DropdownMenuItem(text = { Text(stringResource(R.string.new_private_tab)) }, onClick = { onDismiss(); viewModel.newTab(isPrivate = true) })
        DropdownMenuItem(text = { Text(stringResource(R.string.bookmarks)) }, onClick = { onDismiss(); backStack.add(Route.Bookmarks) })
        DropdownMenuItem(text = { Text(stringResource(R.string.downloads)) }, onClick = { onDismiss(); backStack.add(Route.Downloads) })
        DropdownMenuItem(text = { Text(stringResource(R.string.installed_apps)) }, onClick = { onDismiss(); backStack.add(Route.InstalledSites) })
        DropdownMenuItem(text = { Text(stringResource(R.string.site_data)) }, onClick = { onDismiss(); backStack.add(Route.SiteData) })
        DropdownMenuItem(text = { Text(stringResource(UiR.string.settings)) }, onClick = { onDismiss(); backStack.add(Route.Settings) })
    }
}

/**
 * Install-as-app / add-to-home-screen dialog. Reads the tab's PWA probe result
 * and lets the user rename before pinning.
 */
@Composable
internal fun InstallAppDialog(
    viewModel: WebViewModel,
    onDismiss: () -> Unit,
) {
    val activeTab = viewModel.activeTab
    val tabId = activeTab?.id
    val url = activeTab?.url ?: ""
    val pwa = tabId?.let { viewModel.getPwaInfo(it) }
    val fallbackTitle = tabId?.let { viewModel.getTabTitle(it).ifBlank { activeTab?.title ?: "" } } ?: ""
    val defaultTitle = PwaHelper.displayTitle(pwa, fallbackTitle, url)
    var draftTitle by remember(url, defaultTitle) { mutableStateOf(defaultTitle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (pwa?.hasManifest == true) "Install app?" else "Add to Home screen?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (pwa?.hasManifest == true)
                        "This site has a web manifest — install as standalone app."
                    else
                        "Create pinned shortcut that opens in standalone mode (PwaActivity). Works for any site via best icon (apple-touch-icon, 192x192).",
                    style = MaterialTheme.typography.bodyMedium
                )
                Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(BrowserUtils.prettyUrl(url), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (pwa?.iconUrl != null) {
                            Text(stringResource(R.string.icon, pwa.iconUrl.take(64)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        if (pwa?.themeColor != null) {
                            Text(stringResource(R.string.theme, pwa.themeColor), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                OutlinedTextField(
                    value = draftTitle,
                    onValueChange = { draftTitle = it },
                    label = { Text(stringResource(R.string.app_name_2)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val finalTitle = draftTitle.ifBlank { defaultTitle }
                    onDismiss()
                    if (tabId != null && BrowserUtils.isWebUrl(url)) {
                        viewModel.installAsPwa(
                            tabId = tabId,
                            url = url,
                            pwaInfo = pwa?.copy(name = finalTitle) ?: PwaInfo(
                                name = finalTitle,
                                origin = BrowserUtils.originFromUrl(url),
                                startUrl = url
                            )
                        )
                    }
                },
                enabled = draftTitle.isNotBlank() || defaultTitle.isNotBlank()
            ) { Text(stringResource(UiR.string.add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.cancel)) }
        }
    )
}
