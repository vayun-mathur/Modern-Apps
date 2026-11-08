@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.web.platform

import android.content.Context
import android.content.pm.PackageManager
import com.vayunmathur.library.log.Log
import androidx.core.content.ContextCompat
import com.vayunmathur.web.data.StorageInfo
import com.vayunmathur.web.data.TabThumbnailStore
import com.vayunmathur.web.domain.LocalNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.uuid.Uuid

private const val TAG = "WebViewModel"

// ---- Tabs ----

fun WebViewModel.onTabUrlChange(tabId: String, url: String) {
    tabCurrentUrl[tabId] = url
    updateTab(tabId) { it.copy(url = url) }
    persistTabs()
    if (tabId == activeTabId && !omniboxFocused) {
        // A native search tab shows its query, not its search:// pseudo-URL.
        omniboxText = displayTextForUrl(url)
    }
    if (BrowserUtils.isWebUrl(url)) {
        val origin = BrowserUtils.originFromUrl(url)
        val host = BrowserUtils.hostFromUrl(url)
        scope.launch {
            val existing = repository.storage.byOrigin(origin)
            val info = if (existing == null) {
                StorageInfo(origin = origin, host = host, lastSeen = System.currentTimeMillis())
            } else {
                existing.copy(lastSeen = System.currentTimeMillis(), host = host)
            }
            repository.storage.upsert(info)
        }
    }
}

fun WebViewModel.onTabTitleChange(tabId: String, title: String) {
    tabTitles[tabId] = title
    updateTab(tabId) { it.copy(title = title) }
    persistTabs()
}

fun WebViewModel.getTabTitle(tabId: String): String =
    tabTitles[tabId] ?: tabs.find { it.id == tabId }?.title ?: ""

fun WebViewModel.onTabProgress(tabId: String, progress: Float) {
    tabProgress[tabId] = progress
}

fun WebViewModel.onTabCanGoBack(tabId: String, value: Boolean) {
    tabCanGoBack[tabId] = value
}

fun WebViewModel.onTabCanGoForward(tabId: String, value: Boolean) {
    tabCanGoForward[tabId] = value
}

fun WebViewModel.getProgress(tabId: String) = tabProgress[tabId] ?: 0f
fun WebViewModel.getCanGoBack(tabId: String) = tabCanGoBack[tabId] ?: false
fun WebViewModel.getCanGoForward(tabId: String) = tabCanGoForward[tabId] ?: false
fun WebViewModel.getCurrentUrl(tabId: String) =
    tabCurrentUrl[tabId] ?: tabs.find { it.id == tabId }?.url ?: ""

fun WebViewModel.newTab(url: String = "", makeActive: Boolean = true, isPrivate: Boolean = false) {
    if (makeActive) captureActiveTab()
    // Every tab in an incognito window is private, regardless of the caller's request.
    val tab = BrowserTab(
        id = Uuid.random().toString(),
        url = url,
        isPrivate = isPrivate || incognito,
    )
    tabs.add(tab)
    if (makeActive) {
        activeTabId = tab.id
        omniboxFocused = false
        omniboxText = displayTextForUrl(url)
        searchDraft = if (url.isBlank() || url == "about:blank") "" else url
    }
    persistTabs()
}

fun WebViewModel.closeTab(tabId: String) {
    val idx = tabs.indexOfFirst { it.id == tabId }
    if (idx < 0) return
    tabs.removeAt(idx)
    tabTitles.remove(tabId)
    tabProgress.remove(tabId)
    tabCanGoBack.remove(tabId)
    tabCanGoForward.remove(tabId)
    tabCurrentUrl.remove(tabId)
    blockedCounts.remove(tabId)
    blockedTotals.remove(tabId)
    pwaInfos.remove(tabId)
    TabThumbnailStore.remove(tabId)
    if (activeTabId == tabId) {
        activeTabId = when {
            tabs.isEmpty() -> {
                val tab = blankTab()
                tabs.add(tab)
                tab.id
            }
            idx < tabs.size -> tabs[idx].id
            else -> tabs.last().id
        }
        val cur = activeTab
        val url = cur?.url.orEmpty()
        omniboxText = displayTextForUrl(url)
        searchDraft = if (url.isBlank() || url == "about:blank") "" else url
    }
    persistTabs()
}

/**
 * Tab order is the persisted order — [persistTabsSync] writes the list as it stands — so a
 * reorder needs nothing beyond moving the entry and saving.
 */
fun WebViewModel.moveTab(from: Int, to: Int) {
    if (from == to || from !in tabs.indices || to !in tabs.indices) return
    tabs.add(to, tabs.removeAt(from))
    persistTabs()
}

fun WebViewModel.switchToTab(tabId: String) {
    if (tabId != activeTabId) captureActiveTab()
    activeTabId = tabId
    val cur = activeTab
    val url = cur?.url.orEmpty()
    omniboxText = displayTextForUrl(url)
    searchDraft = if (url.isBlank() || url == "about:blank") "" else url
    showTabSwitcher = false
    persistTabs()
}

fun WebViewModel.navigateActiveTab(input: String) {
    val active = activeTab ?: return
    // Self-hosted engine renders results natively (SearchResultsPage) instead of
    // loading engine HTML in a WebView. The query is stored as a search://
    // pseudo-URL in the tab so tab switching, persistence, and the omnibox keep
    // working unchanged; BrowserContent branches on the scheme. URLs and blank
    // input still go through the normal path below. Dev-gated: release builds
    // resolve SELF_HOSTED to the default engine via SearchEngine.effective.
    val engine = SearchEngine.effective(searchEngine)
    if (engine == SearchEngine.SELF_HOSTED) {
        val trimmed = input.trim()
        val isUrl = BrowserUtils.isWebUrl(trimmed) ||
            BrowserUtils.looksLikeUrl(trimmed)
        if (trimmed.isNotEmpty() && !isUrl) {
            val dest = nativeSearchUrl(trimmed)
            markFreshNavigation(active.id)
            onTabUrlChange(active.id, dest)
            // The query itself is not history-logged (a search box is not a
            // visit); result taps are recorded when their pages load.
            omniboxText = trimmed
            omniboxFocused = false
            return
        }
    }
    // Read live rather than from a cached field: a parent can turn search filtering on while
    // the browser is open, and the next search should honour it.
    val safeSearch = ContentFilters.filterSearchResults(context)
    val dest = BrowserUtils.toNavigationUrl(input, engine, safeSearch)
    noteNavigation(dest)
    markFreshNavigation(active.id)
    onTabUrlChange(active.id, dest)
    omniboxFocused = false
}

// ---- External app redirects ----

/**
 * Records that the user just pointed [tabId] somewhere themselves — typed an address,
 * picked a bookmark, or reloaded — so the page it lands on is a fresh navigation again.
 */
fun WebViewModel.markFreshNavigation(tabId: String) {
    restoredTabIds.remove(tabId)
}

/**
 * Whether a navigation out of [tabId] into another app should be honoured.
 *
 * A restored tab is one the user never asked for on this launch, so a page that bounces
 * into another app would drag them straight back out of the browser they just opened. The
 * redirect is held back until they act in that tab; [userGesture] (a tap on the page) counts,
 * as does any fresh navigation.
 */
fun WebViewModel.allowExternalRedirect(tabId: String, userGesture: Boolean): Boolean {
    if (tabId !in restoredTabIds) return true
    if (!userGesture) return false
    restoredTabIds.remove(tabId)
    return true
}

fun WebViewModel.externalIntentUrl(url: String) {
    // Per product requirement: external links from other apps always open a new tab.
    newTab(url = url, makeActive = true)
}

// ---- Local network permission ----

/**
 * Raises [pendingLocalNetworkHost] when [url] is a LAN address we cannot reach yet.
 *
 * Called from the omnibox (so the prompt lands before the first request in the common
 * typed-URL case) and from `onPageStarted` as the catch-all, since
 * `shouldOverrideUrlLoading` never fires for a programmatic `loadUrl`, a redirect or a
 * session restore.
 *
 * Classification is syntactic only — this runs on the main thread and must never do DNS.
 */
fun WebViewModel.noteNavigation(url: String) {
    val permission = WebPermissions.LOCAL_NETWORK ?: return
    if (WebViewModel.localNetworkAsked || localNetworkDenied || pendingLocalNetworkHost != null) {
        return
    }
    val host = LocalNetwork.hostOf(url)
    if (host.isEmpty() || !LocalNetwork.isLanHostSyntactic(host)) return
    if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
        return
    }
    pendingLocalNetworkHost = host
}

/** Dismisses the prompt. A denial is remembered so LAN pages stop nagging. */
fun WebViewModel.clearLocalNetworkPrompt(denied: Boolean) {
    pendingLocalNetworkHost = null
    WebViewModel.localNetworkAsked = true
    if (!denied) return
    localNetworkDenied = true
    scope.launch(Dispatchers.IO) {
        runCatching {
            context.getSharedPreferences("web_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean(P_LOCAL_NETWORK_DENIED, true).apply()
        }.onFailure { Log.error(TAG, "persist local network denial failed", it) }
    }
}
