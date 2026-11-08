package com.vayunmathur.web.ui

import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vayunmathur.library.ui.rememberPermissionRequest
import com.vayunmathur.web.Route
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.web.platform.BrowserUtils
import com.vayunmathur.web.platform.WebPermissions
import com.vayunmathur.web.platform.WebViewModel
import com.vayunmathur.web.platform.blockedCount
import com.vayunmathur.web.platform.clearLocalNetworkPrompt
import com.vayunmathur.web.platform.deliverFileChooserResult
import com.vayunmathur.web.platform.getCanGoBack
import com.vayunmathur.web.platform.getCanGoForward
import com.vayunmathur.web.platform.getProgress
import com.vayunmathur.web.platform.isNewTab
import com.vayunmathur.web.platform.markFreshNavigation
import com.vayunmathur.web.platform.navigateActiveTab
import com.vayunmathur.web.platform.nativeSearchQuery
import com.vayunmathur.web.platform.newTab
import com.vayunmathur.web.platform.onTabUrlChange

@Composable
fun BrowserPage(
    viewModel: WebViewModel,
    backStack: NavBackStack<Route>,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val webViewPool = remember { mutableStateMapOf<String, WebView>() }

    // The view model captures a thumbnail of a tab as it stops being active, and the live
    // WebViews only exist here. Cleared on dispose so the pool's WebViews are not kept alive
    // by the longer-lived view model.
    DisposableEffect(Unit) {
        viewModel.setWebViewLookup { tabId -> webViewPool[tabId] }
        onDispose { viewModel.setWebViewLookup(null) }
    }

    LaunchedEffect(viewModel.tabs.size) {
        if (viewModel.tabs.isEmpty()) viewModel.newTab()
    }

    val activeTab = viewModel.activeTab
    val canGoBack = activeTab?.let { viewModel.getCanGoBack(it.id) } ?: false
    val canGoForward = activeTab?.let { viewModel.getCanGoForward(it.id) } ?: false
    val progress = activeTab?.let { viewModel.getProgress(it.id) } ?: 0f
    val isNewTabActive = activeTab?.isNewTab ?: true

    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val isCurrentBookmarked = activeTab?.url?.let { url -> url.isNotBlank() && bookmarks.any { it.url == url } } ?: false

    val shieldHost = activeTab?.url?.takeIf { BrowserUtils.isWebUrl(it) }?.let { BrowserUtils.hostFromUrl(it) }
    // Navigating away from the site the panel describes has to close it, otherwise it would
    // silently start editing a different host's settings.
    LaunchedEffect(shieldHost) { viewModel.showShieldsPanel = false }

    val multiDocLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        viewModel.deliverFileChooserResult(uris.toTypedArray().takeIf { it.isNotEmpty() })
    }
    val singleDocLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        viewModel.deliverFileChooserResult(uri?.let { arrayOf(it) })
    }

    // A conditional remember, but the condition is the SDK level: constant for the process.
    // Below API 36 there is no permission to ask for and the prompt never appears.
    val localNetworkRequest = WebPermissions.LOCAL_NETWORK?.let { permission ->
        rememberPermissionRequest(permission) { granted ->
            viewModel.clearLocalNetworkPrompt(denied = !granted)
        }
    }

    var showMenu by remember { mutableStateOf(false) }
    var showInstallDialog by remember { mutableStateOf(false) }
    var linkContextMenuUrl by remember { mutableStateOf<String?>(null) }
    val searchFocusRequester = remember { FocusRequester() }
    // Activity-wide: fullscreen video survives tab switches until dismissed.
    val fullscreenHost = remember(context) { WebFullscreenHost(context) }

    BackHandler(enabled = fullscreenHost.isFullscreen) { fullscreenHost.onHideCustomView() }
    BackHandler(enabled = !fullscreenHost.isFullscreen && viewModel.showTabSwitcher) { viewModel.showTabSwitcher = false }
    BackHandler(enabled = !fullscreenHost.isFullscreen && !viewModel.showTabSwitcher && viewModel.omniboxFocused) {
        viewModel.omniboxFocused = false
        focusManager.clearFocus()
    }
    BackHandler(enabled = !fullscreenHost.isFullscreen && !viewModel.showTabSwitcher && !viewModel.omniboxFocused && canGoBack) {
        activeTab?.let { tab -> webViewPool[tab.id]?.goBack() }
    }

    LaunchedEffect(viewModel.omniboxFocused) {
        if (viewModel.omniboxFocused) {
            // Let the new Scaffold compose before requesting focus
            kotlinx.coroutines.delay(100)
            try { searchFocusRequester.requestFocus() } catch (_: Exception) {}
            keyboardController?.show()
        }
    }

    val navigateFromOmnibox: (String) -> Unit = { input ->
        viewModel.navigateActiveTab(input)
        focusManager.clearFocus()
        viewModel.omniboxFocused = false
    }

    Box(Modifier.fillMaxSize()) {
        if (viewModel.omniboxFocused) {
            OmniboxEditor(
                viewModel = viewModel,
                bookmarks = bookmarks,
                history = history,
                searchFocusRequester = searchFocusRequester,
                onNavigate = navigateFromOmnibox,
                onDismiss = {
                    focusManager.clearFocus()
                    viewModel.omniboxFocused = false
                },
            )
        } else {
            BrowserChrome(
                omniboxText = viewModel.omniboxText,
                tabCount = viewModel.tabs.size,
                progress = if (activeTab != null && !isNewTabActive) progress else 0f,
                atBottom = viewModel.searchBarAtBottom,
                onOmniboxClick = {
                    val raw = activeTab?.url?.let { if (it.isBlank() || it == "about:blank") "" else it } ?: ""
                    // A native search tab edits as its query, not its search:// pseudo-URL.
                    viewModel.searchDraft = nativeSearchQuery(raw).ifBlank { raw }
                    viewModel.omniboxFocused = true
                },
                onTabSwitcherClick = {
                    // Before the switcher opens, so the active tile shows the page as the user
                    // left it rather than wherever it was when it last finished loading.
                    activeTab?.let { tab -> webViewPool[tab.id]?.let { viewModel.captureThumbnail(tab.id, it) } }
                    viewModel.showTabSwitcher = true
                },
                onMenuClick = { showMenu = true },
                menu = {
                    BrowserMenu(
                        expanded = showMenu,
                        onDismiss = { showMenu = false },
                        viewModel = viewModel,
                        backStack = backStack,
                        isNewTabActive = isNewTabActive,
                        isCurrentBookmarked = isCurrentBookmarked,
                        canGoBack = canGoBack,
                        canGoForward = canGoForward,
                        onGoBack = { if (canGoBack) activeTab?.let { webViewPool[it.id]?.goBack() } },
                        onGoForward = { if (canGoForward) activeTab?.let { webViewPool[it.id]?.goForward() } },
                        shieldHost = shieldHost,
                        blockedCount = activeTab?.let { viewModel.blockedCount(it.id) } ?: 0,
                        onShieldClick = { viewModel.showShieldsPanel = true },
                        onShowInstallDialog = { showInstallDialog = true },
                        onReload = {
                            activeTab?.let {
                                viewModel.markFreshNavigation(it.id)
                                webViewPool[it.id]?.reload()
                            }
                        },
                    )
                },
            ) { paddingValues ->
                BrowserContent(
                    viewModel = viewModel,
                    activeTab = activeTab,
                    isNewTabActive = isNewTabActive,
                    webViewPool = webViewPool,
                    fullscreenHost = fullscreenHost,
                    onOpenUrl = { tab, url ->
                        viewModel.markFreshNavigation(tab.id)
                        viewModel.onTabUrlChange(tab.id, url)
                    },
                    onRequestNewTab = { tab, url -> viewModel.newTab(url = url, isPrivate = tab.isPrivate) },
                    onLinkLongPress = { url -> linkContextMenuUrl = url },
                    modifier = Modifier.padding(paddingValues),
                )
            }
        }

        BrowserOverlays(
            viewModel = viewModel,
            activeTab = activeTab,
            shieldHost = shieldHost,
            webViewPool = webViewPool,
            linkContextMenuUrl = linkContextMenuUrl,
            onLinkMenuDismiss = { linkContextMenuUrl = null },
            showInstallDialog = showInstallDialog,
            onInstallDialogDismiss = { showInstallDialog = false },
            multiDocLauncher = multiDocLauncher,
            singleDocLauncher = singleDocLauncher,
            localNetworkRequest = localNetworkRequest,
        )
    }
}
