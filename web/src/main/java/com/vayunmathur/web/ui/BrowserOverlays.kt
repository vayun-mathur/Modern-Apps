package com.vayunmathur.web.ui

import android.webkit.WebView
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Box
import com.vayunmathur.library.ui.DesktopMaxWidthContainer
import com.vayunmathur.library.ui.ExperimentalMaterial3Api
import com.vayunmathur.library.ui.ExternalIntents
import com.vayunmathur.web.R
import com.vayunmathur.web.platform.BrowserTab
import com.vayunmathur.web.platform.WebViewModel
import com.vayunmathur.web.platform.blockedCount
import com.vayunmathur.web.platform.clearFileChooser
import com.vayunmathur.web.platform.clearLocalNetworkPrompt
import com.vayunmathur.web.platform.clearPermissionPrompt
import com.vayunmathur.web.platform.closeTab
import com.vayunmathur.web.platform.denyGeolocation
import com.vayunmathur.web.platform.farblingConfig
import com.vayunmathur.web.platform.grantGeolocation
import com.vayunmathur.web.platform.BrowserUtils
import com.vayunmathur.web.platform.isMultipleSelection
import com.vayunmathur.web.platform.isNativeSearchUrl
import com.vayunmathur.web.platform.isNewTab
import com.vayunmathur.web.platform.markFreshNavigation
import com.vayunmathur.web.platform.moveTab
import com.vayunmathur.web.platform.nativeSearchQuery
import com.vayunmathur.web.platform.newTab
import com.vayunmathur.web.platform.safeAcceptTypes
import com.vayunmathur.web.platform.switchToTab
import com.vayunmathur.web.platform.toLaunchArray
import com.vayunmathur.web.platform.shields.ShieldsWebViewClient

/**
 * Overlays drawn above the browser content: tab switcher, shields panel,
 * permission/geolocation/LAN prompts, file chooser, link menu, install dialog.
 * Extracted so [BrowserPage] stays a state binder plus content switch.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrowserOverlays(
    viewModel: WebViewModel,
    activeTab: BrowserTab?,
    shieldHost: String?,
    webViewPool: MutableMap<String, WebView>,
    linkContextMenuUrl: String?,
    onLinkMenuDismiss: () -> Unit,
    showInstallDialog: Boolean,
    onInstallDialogDismiss: () -> Unit,
    multiDocLauncher: ActivityResultLauncher<Array<String>>,
    singleDocLauncher: ActivityResultLauncher<Array<String>>,
    localNetworkRequest: (() -> Unit)?,
) {
    val context = LocalContext.current
    val linkCopiedText = stringResource(R.string.link_copied)
    val shareLinkTitle = stringResource(R.string.share_link)

    if (viewModel.showTabSwitcher) {
        TabSwitcher(
            tabs = viewModel.tabs,
            activeTabId = viewModel.activeTabId,
            onSwitch = { viewModel.switchToTab(it) },
            onClose = { viewModel.closeTab(it) },
            onReorder = { from, to -> viewModel.moveTab(from, to) },
            onNewTab = { viewModel.newTab(isPrivate = viewModel.incognito || viewModel.activeTab?.isPrivate == true) },
            onNewIncognitoTab = { viewModel.newTab(isPrivate = true) },
            onNewWindow = {
                viewModel.showTabSwitcher = false
                com.vayunmathur.web.platform.launchNewWebWindow(context, incognito = false)
            },
            onNewIncognitoWindow = {
                viewModel.showTabSwitcher = false
                com.vayunmathur.web.platform.launchNewWebWindow(context, incognito = true)
            },
            isIncognitoWindow = viewModel.incognito || viewModel.activeTab?.isPrivate == true,
            onDismiss = { viewModel.showTabSwitcher = false },
            modifier = Modifier.fillMaxSize(),
            thumbnailFor = viewModel::thumbnailFor,
            faviconFor = viewModel::faviconFor,
        )
    }

    if (viewModel.showShieldsPanel && shieldHost != null) {
        ShieldsPanel(
            host = shieldHost,
            blockedCount = activeTab?.let { viewModel.blockedCount(it.id) } ?: 0,
            viewModel = viewModel,
            onReload = {
                activeTab?.let { tab ->
                    webViewPool[tab.id]?.let { webView ->
                        // Re-register before reloading, not after: document-start scripts
                        // only apply to documents that start loading after the call.
                        (webView.webViewClient as? ShieldsWebViewClient)
                            ?.installFarbling(webView, viewModel.farblingConfig())
                        viewModel.markFreshNavigation(tab.id)
                        webView.reload()
                    }
                }
            },
            onDismiss = { viewModel.showShieldsPanel = false },
        )
    }

    viewModel.pendingPermissionPrompt?.let { prompt ->
        PermissionPromptSheet(
            origin = prompt.origin,
            types = prompt.types,
            onGrant = { granted ->
                prompt.onGrant(granted)
                viewModel.clearPermissionPrompt()
            },
            onDeny = {
                prompt.onDeny()
                viewModel.clearPermissionPrompt()
            }
        )
    }

    viewModel.pendingGeolocationPrompt?.let { (origin, _, _) ->
        GeolocationPromptSheet(
            origin = origin,
            onAllow = { viewModel.grantGeolocation() },
            onDeny = { viewModel.denyGeolocation() }
        )
    }

    viewModel.pendingLocalNetworkHost?.let { host ->
        LocalNetworkPromptSheet(
            host = host,
            // Tap-gated: rememberPermissionRequest opens system settings on a
            // denial-without-rationale, so launching this automatically would eject a
            // permanently-denied user out of the app on every LAN page load.
            onAllow = { localNetworkRequest?.invoke() },
            onDeny = { viewModel.clearLocalNetworkPrompt(denied = true) },
        )
    }

    viewModel.pendingFileChooser?.let { (_, params) ->
        val mimeTypes = params.safeAcceptTypes()
        val allowMultiple = params.isMultipleSelection()
        FileChooserSheet(
            mimeTypes = mimeTypes,
            onCancel = { viewModel.clearFileChooser() },
            onTriggerPicker = {
                runCatching {
                    if (allowMultiple) {
                        multiDocLauncher.launch(mimeTypes.toLaunchArray())
                    } else {
                        val mt = mimeTypes.firstOrNull { it.isNotBlank() } ?: "*/*"
                        singleDocLauncher.launch(arrayOf(mt))
                    }
                }.onFailure { viewModel.clearFileChooser() }
            }
        )
    }

    linkContextMenuUrl?.let { linkUrl ->
        LinkContextMenu(
            url = linkUrl,
            onDismiss = onLinkMenuDismiss,
            onCopyLink = {
                ExternalIntents.copyToClipboard(
                    context,
                    linkUrl,
                    linkUrl,
                )
                com.vayunmathur.library.util.AppMessages.show(linkCopiedText)
            },
            onShareLink = {
                ExternalIntents.shareText(
                    context,
                    linkUrl,
                    shareLinkTitle,
                )
            },
            onOpenInNewTab = { viewModel.newTab(url = linkUrl, isPrivate = viewModel.activeTab?.isPrivate ?: true) },
        )
    }

    if (showInstallDialog) {
        InstallAppDialog(
            viewModel = viewModel,
            onDismiss = onInstallDialogDismiss,
        )
    }
}

/**
 * The browser content under the chrome: quick-access on a new tab, otherwise the
 * pooled WebView for the active tab. Extracted so the page body reads as one switch.
 */
@Composable
internal fun BrowserContent(
    viewModel: WebViewModel,
    activeTab: BrowserTab?,
    isNewTabActive: Boolean,
    webViewPool: MutableMap<String, WebView>,
    fullscreenHost: WebFullscreenHost,
    onOpenUrl: (BrowserTab, String) -> Unit,
    onRequestNewTab: (BrowserTab, String) -> Unit,
    onLinkLongPress: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize()) {
        if (isNewTabActive) {
            DesktopMaxWidthContainer {
                QuickAccess(
                    bookmarks = bookmarks.take(12),
                    history = history.take(8),
                    onOpenUrl = { url ->
                        activeTab?.let { onOpenUrl(it, url) }
                    },
                    faviconFor = viewModel::faviconFor,
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else if (activeTab != null && isNativeSearchUrl(activeTab.url)) {
            // Self-hosted engine: native Compose results instead of a WebView.
            // Result taps go through the normal tab navigation so history,
            // shields, and thumbnails behave like any other page load.
            key(activeTab.id, activeTab.url) {
                SearchResultsPage(
                    query = nativeSearchQuery(activeTab.url),
                    onOpenResult = { url -> onOpenUrl(activeTab, url) },
                    onOpenFallback = { url -> onOpenUrl(activeTab, url) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else if (activeTab != null) {
            Box(Modifier.fillMaxSize()) {
                // Force a new WebViewBrowser composition per tabId so the AndroidView
                // factory runs and loads the new URL immediately. Without this, the
                // same AndroidView instance is reused across tab switches and the old
                // page remains visible until an update triggers, causing topbar/content
                // mismatch when an external intent opens a new tab.
                key(activeTab.id) {
                    WebViewBrowser(
                        tabId = activeTab.id,
                        initialUrl = activeTab.url,
                        viewModel = viewModel,
                        webViewPool = webViewPool,
                        fullscreenHost = fullscreenHost,
                        onRequestNewTab = { url -> onRequestNewTab(activeTab, url) },
                        onLinkLongPress = onLinkLongPress,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}
