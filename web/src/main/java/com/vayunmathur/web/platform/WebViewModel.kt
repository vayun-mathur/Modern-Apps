@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.web.platform

import kotlin.uuid.Uuid
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.vayunmathur.library.log.Log
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.web.data.Bookmark
import com.vayunmathur.web.data.BookmarkFolder
import com.vayunmathur.web.data.DownloadEntry
import com.vayunmathur.web.data.FaviconStore
import com.vayunmathur.web.data.HistoryEntry
import com.vayunmathur.web.data.InstalledSite
import com.vayunmathur.web.data.SitePermission
import com.vayunmathur.web.data.TabThumbnailStore
import com.vayunmathur.web.data.WebStores
import com.vayunmathur.web.data.ShieldSetting
import com.vayunmathur.web.data.StorageInfo
import com.vayunmathur.web.domain.ShieldLevel
import com.vayunmathur.web.domain.ShieldsSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "WebViewModel"
internal const val P_SAVED_TABS = "web_saved_tabs"
internal const val P_ACTIVE_TAB = "web_active_tab_id"
internal const val P_CACHE_MODE = "web_cache_mode"
internal const val P_JS_ENABLED = "web_js_enabled"
internal const val P_BLOCK_THIRD_PARTY = "web_block_third_party"
internal const val P_DESKTOP_MODE = "web_desktop_mode"
internal const val P_SEARCH_ENGINE = "web_search_engine"
internal const val P_SHIELD_LEVEL = "web_shield_level"
internal const val P_SHIELD_TRACKERS = "web_shield_trackers"
internal const val P_SHIELD_COSMETIC = "web_shield_cosmetic"
internal const val P_SHIELD_FINGERPRINT = "web_shield_fingerprint"
internal const val P_SHIELD_HTTPS = "web_shield_https"
internal const val P_LOCAL_NETWORK_DENIED = "web_local_network_denied"
internal const val P_SEARCH_BAR_BOTTOM = "web_search_bar_bottom"

class WebViewModel(
    internal val repository: WebStores,
    internal val context: Context,
    /** Identifies this window's independent tab set; the default window keeps the legacy pref keys. */
    private val windowId: String = DEFAULT_WINDOW_ID,
    /** An incognito window: every tab is private and nothing is persisted. */
    val incognito: Boolean = false,
    /**
     * Site exceptions read before the UI was allowed to render. Seeding them here rather
     * than waiting for [ShieldSettingDao.allFlow] is what stops the first page of a cold
     * start from being farbled on a site the user turned shields off for.
     */
    initialShieldSettings: List<ShieldSetting> = emptyList(),
) : ViewModel() {

    companion object {
        const val DEFAULT_WINDOW_ID = "main"

        /** Asked at most once per process, however many windows are open. */
        @Volatile
        internal var localNetworkAsked = false
    }

    // Persistence keys are namespaced per window; the default window keeps the legacy keys for back-compat.
    internal val savedTabsKey = if (windowId == DEFAULT_WINDOW_ID) P_SAVED_TABS else "${P_SAVED_TABS}_$windowId"
    internal val activeTabKey = if (windowId == DEFAULT_WINDOW_ID) P_ACTIVE_TAB else "${P_ACTIVE_TAB}_$windowId"

    internal fun blankTab() = BrowserTab(id = Uuid.random().toString(), url = "", isPrivate = incognito)

    val tabs = mutableStateListOf<BrowserTab>()
    var activeTabId by mutableStateOf<String?>(null)
        internal set

    var omniboxText by mutableStateOf("")
    var omniboxFocused by mutableStateOf(false)
    var searchDraft by mutableStateOf("")

    var searchEngine by mutableStateOf(SearchEngine.DEFAULT)
    val homepage: String get() = searchEngine.homepage

    var cacheMode by mutableStateOf(CacheMode.DEFAULT)
    var jsEnabled by mutableStateOf(true)
    var blockThirdPartyCookies by mutableStateOf(false)
    var desktopMode by mutableStateOf(false)

    /** Toolbar edge. Defaults to the bottom, within thumb reach on a phone. */
    var searchBarAtBottom by mutableStateOf(true)

    /** Global Brave Shields defaults; per-site overrides live in [shieldSettings]. */
    var shields by mutableStateOf(ShieldsSettings.AGGRESSIVE_DEFAULTS)
        internal set

    private val _bookmarks = MutableStateFlow<List<Bookmark>>(emptyList())
    val bookmarks: StateFlow<List<Bookmark>> = _bookmarks

    private val _folders = MutableStateFlow<List<BookmarkFolder>>(emptyList())
    val folders: StateFlow<List<BookmarkFolder>> = _folders

    private val _history = MutableStateFlow<List<HistoryEntry>>(emptyList())
    val history: StateFlow<List<HistoryEntry>> = _history

    private val _sitePermissions = MutableStateFlow<List<SitePermission>>(emptyList())
    val sitePermissions: StateFlow<List<SitePermission>> = _sitePermissions

    private val _storageInfos = MutableStateFlow<List<StorageInfo>>(emptyList())
    val storageInfos: StateFlow<List<StorageInfo>> = _storageInfos

    private val _downloads = MutableStateFlow<List<DownloadEntry>>(emptyList())
    val downloads: StateFlow<List<DownloadEntry>> = _downloads

    private val _installedSites = MutableStateFlow<List<InstalledSite>>(emptyList())
    val installedSites: StateFlow<List<InstalledSite>> = _installedSites

    private val _shieldSettings = MutableStateFlow(initialShieldSettings)
    val shieldSettings: StateFlow<List<ShieldSetting>> = _shieldSettings

    /**
     * Per-host overrides mirrored synchronously because `shouldInterceptRequest` runs on the
     * render thread and cannot wait on a coroutine or a database read.
     */
    internal val shieldOverrides = ConcurrentHashMap<String, ShieldsSettings>()
        .apply { initialShieldSettings.forEach { put(it.host, it.toSettings()) } }

    /**
     * Blocked-request tallies. The counting side is hit from the render thread, so the
     * authoritative totals live in a concurrent map and only the UI mirror is a snapshot
     * state ΓÇö writing Compose state off the main thread is not safe.
     */
    internal val blockedTotals = ConcurrentHashMap<String, AtomicInteger>()
    internal val blockedPublishPending = ConcurrentHashMap<String, Boolean>()
    internal val blockedCounts = mutableStateMapOf<String, Int>()
    internal val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

    /** Whether the shields panel is open; the host it targets is always the active tab's. */
    var showShieldsPanel by mutableStateOf(false)

    var pendingPermissionPrompt by mutableStateOf<PermissionPrompt?>(null)
        internal set

    var pendingGeolocationPrompt by mutableStateOf<Triple<String, () -> Unit, () -> Unit>?>(null)
        internal set

    /** The LAN host whose page needs [WebPermissions.LOCAL_NETWORK] before it can load. */
    var pendingLocalNetworkHost by mutableStateOf<String?>(null)
        internal set

    /** A previous denial, remembered across process death so we stop asking. */
    internal var localNetworkDenied = false

    var pendingFileChooser by mutableStateOf<
        Pair<android.webkit.ValueCallback<Array<Uri>>, android.webkit.WebChromeClient.FileChooserParams>?
        >(null)
        internal set

    var showTabSwitcher by mutableStateOf(false)

    internal val tabTitles = mutableMapOf<String, String>()
    internal val tabProgress = mutableMapOf<String, Float>()
    internal val tabCanGoBack = mutableMapOf<String, Boolean>()
    internal val tabCanGoForward = mutableMapOf<String, Boolean>()
    internal val tabCurrentUrl = mutableMapOf<String, String>()

    /**
     * Tabs whose content came back from the saved session and that nobody has navigated since.
     *
     * Only read and written from the main thread (the restore continuation and the WebView
     * client callbacks), so a plain set is enough.
     */
    internal val restoredTabIds = mutableSetOf<String>()

    // PWA / installed site detection per tab
    val pwaInfos = mutableStateMapOf<String, PwaInfo>()

    internal val json = Json { ignoreUnknownKeys = true }

    /**
     * Resolves a tab's live WebView. `BrowserPage` owns the pool, so tab-lifecycle code here
     * has no other way to reach the view it needs to draw before a tab stops being active.
     */
    internal var liveWebViews: ((String) -> WebView?)? = null

    /** Coroutine scope for the extension modules that implement this model's behavior. */
    internal val scope: CoroutineScope get() = viewModelScope

    init {
        viewModelScope.launch {
            runCatching { restorePersistedState() }
                .onFailure { e ->
                    Log.error(TAG, "Failed to load prefs", e)
                    withContext(Dispatchers.Main) { ensureBlankTab() }
                }
        }
        observeRepository()
    }

    /**
     * Reads prefs and the saved tab set off the main thread, then applies them on Main.
     * Throws on corrupt prefs or JSON; the caller falls back to a blank tab.
     */
    private suspend fun restorePersistedState() {
        val snapshot = withContext(Dispatchers.IO) { readPersistedSnapshot() }
        withContext(Dispatchers.Main) { applyPersistedSnapshot(snapshot) }
    }

    private data class PersistedSnapshot(
        val savedTabs: String?,
        val activeId: String?,
        val cacheModeName: String?,
        val searchEngineName: String?,
        val js: Boolean,
        val blockThird: Boolean,
        val desktop: Boolean,
        val lanDenied: Boolean,
        val barAtBottom: Boolean,
        val savedShields: ShieldsSettings,
    )

    private fun readPersistedSnapshot(): PersistedSnapshot {
        val sp = context.getSharedPreferences("web_prefs", Context.MODE_PRIVATE)
        // Incognito windows never restore persisted tabs - they start fresh and private.
        val defaults = ShieldsSettings.AGGRESSIVE_DEFAULTS
        return PersistedSnapshot(
            savedTabs = if (incognito) null else sp.getString(savedTabsKey, null),
            activeId = if (incognito) null else sp.getString(activeTabKey, null),
            cacheModeName = sp.getString(P_CACHE_MODE, null),
            searchEngineName = sp.getString(P_SEARCH_ENGINE, null),
            js = sp.getBoolean(P_JS_ENABLED, true),
            blockThird = sp.getBoolean(P_BLOCK_THIRD_PARTY, false),
            desktop = sp.getBoolean(P_DESKTOP_MODE, false),
            lanDenied = sp.getBoolean(P_LOCAL_NETWORK_DENIED, false),
            barAtBottom = sp.getBoolean(P_SEARCH_BAR_BOTTOM, true),
            savedShields = ShieldsSettings(
                level = sp.getString(P_SHIELD_LEVEL, null)
                    ?.let { runCatching { ShieldLevel.valueOf(it) }.getOrNull() }
                    ?: defaults.level,
                blockTrackers = sp.getBoolean(P_SHIELD_TRACKERS, true),
                cosmeticFiltering = sp.getBoolean(P_SHIELD_COSMETIC, true),
                fingerprintProtection = sp.getBoolean(P_SHIELD_FINGERPRINT, true),
                httpsUpgrade = sp.getBoolean(P_SHIELD_HTTPS, true),
            ),
        )
    }

    private fun applyPersistedSnapshot(snapshot: PersistedSnapshot) {
        shields = snapshot.savedShields
        restoreScalarPrefs(
            snapshot.cacheModeName,
            snapshot.searchEngineName,
            snapshot.js,
            snapshot.blockThird,
            snapshot.desktop,
            snapshot.lanDenied,
            snapshot.barAtBottom,
        )

        // Capture tabs created before restore finished,
        // e.g. from an external intent arriving early.
        val preExisting = tabs.toList()
        val preExistingActive = activeTabId
        val decodedSaved: List<BrowserTab>? = snapshot.savedTabs?.let { saved ->
            runCatching { json.decodeFromString<List<BrowserTab>>(saved) }
                .getOrNull()
                ?.takeIf { it.isNotEmpty() }
        }
        mergeRestoredTabs(decodedSaved, preExisting, preExistingActive, snapshot.activeId)
        if (tabs.isEmpty()) {
            ensureBlankTab()
        } else {
            migrateLegacyTabs(snapshot.activeId)
        }
        decodedSaved?.forEach { restoredTabIds.add(it.id) }

        activeTab?.let {
            omniboxText = if (it.url.isBlank() || it.url == "about:blank") "" else it.url
            searchDraft = omniboxText
        }
    }

    private fun mergeRestoredTabs(
        decodedSaved: List<BrowserTab>?,
        preExisting: List<BrowserTab>,
        preExistingActive: String?,
        activeId: String?,
    ) {
        when {
            decodedSaved != null -> {
                if (preExisting.isNotEmpty()) {
                    // Merge: keep saved tabs plus extras created
                    // before restore (like an external URL).
                    val decodedIds = decodedSaved.map { it.id }.toSet()
                    val extras = preExisting.filter { it.id !in decodedIds }
                    tabs.clear()
                    tabs.addAll(decodedSaved)
                    if (extras.isNotEmpty()) {
                        tabs.addAll(extras)
                        // If the active tab was one of the extras, keep it active.
                        activeTabId = if (preExistingActive != null &&
                            extras.any { it.id == preExistingActive }
                        ) {
                            preExistingActive
                        } else {
                            activeId ?: tabs.firstOrNull()?.id
                        }
                    } else {
                        activeTabId = activeId ?: tabs.firstOrNull()?.id
                    }
                } else {
                    tabs.clear()
                    tabs.addAll(decodedSaved)
                    activeTabId = activeId ?: tabs.firstOrNull()?.id
                }
            }
            preExisting.isNotEmpty() -> {
                // No saved tabs but we already have tabs (external intent race) -> keep them
                if (activeTabId == null) activeTabId = preExisting.firstOrNull()?.id
            }
            else -> {
                // No saved and no pre-existing -> will create blank below
            }
        }
    }

    private fun migrateLegacyTabs(activeId: String?) {
        // Migrate legacy new-tabs that were saved as duckduckgo.com -> blank New Tab
        val homepage = BrowserUtils.HOMEPAGE
        val migrated = tabs.map { t ->
            val isHomepage = t.url == homepage || t.url == "$homepage/"
            val isPlaceholderTitle = t.title.isBlank() ||
                t.title == homepage ||
                t.title == "$homepage/"
            if (isHomepage && isPlaceholderTitle) t.copy(url = "", title = "") else t
        }
        if (migrated != tabs.toList()) {
            tabs.clear()
            tabs.addAll(migrated)
        }
        // Ensure active id is still valid after migration
        if (activeTabId == null || tabs.none { it.id == activeTabId }) {
            activeTabId = activeId ?: tabs.firstOrNull()?.id
        }
    }

    private fun ensureBlankTab() {
        if (tabs.isEmpty()) {
            val tab = blankTab()
            tabs.add(tab)
            activeTabId = tab.id
        }
    }

    private fun observeRepository() {
        viewModelScope.launch { repository.bookmarks.allFlow().collect { _bookmarks.value = it } }
        viewModelScope.launch { repository.bookmarks.foldersFlow().collect { _folders.value = it } }
        viewModelScope.launch { repository.history.allFlow().collect { _history.value = it } }
        viewModelScope.launch { repository.permissions.allFlow().collect { _sitePermissions.value = it } }
        viewModelScope.launch { repository.storage.allFlow().collect { _storageInfos.value = it } }
        viewModelScope.launch { repository.downloads.allFlow().collect { _downloads.value = it } }
        viewModelScope.launch { repository.installed.allFlow().collect { _installedSites.value = it } }
        viewModelScope.launch {
            repository.shields.allFlow().collect { settings ->
                _shieldSettings.value = settings
                shieldOverrides.clear()
                settings.forEach { shieldOverrides[it.host] = it.toSettings() }
            }
        }
    }

    private fun restoreScalarPrefs(
        cacheModeName: String?,
        searchEngineName: String?,
        js: Boolean,
        blockThird: Boolean,
        desktop: Boolean,
        lanDenied: Boolean,
        barAtBottom: Boolean,
    ) {
        cacheModeName?.let {
            runCatching { CacheMode.valueOf(it) }.getOrNull()?.let { cm -> cacheMode = cm }
        }
        searchEngineName?.let {
            runCatching { SearchEngine.valueOf(it) }.getOrNull()?.let { se ->
                // Dev-gated engine never survives into a release build.
                searchEngine = SearchEngine.effective(se)
            }
        }
        jsEnabled = js
        blockThirdPartyCookies = blockThird
        desktopMode = desktop
        localNetworkDenied = lanDenied
        searchBarAtBottom = barAtBottom
    }

    // ---- Brave Shields (see WebViewModelShields.kt) ----

    // ---- Thumbnails and favicons ----

    fun setWebViewLookup(lookup: ((String) -> WebView?)?) { liveWebViews = lookup }

    /** The tab's page thumbnail, or null when there isn't one yet. Compose-observable. */
    fun thumbnailFor(tabId: String): Bitmap? = TabThumbnailStore.get(tabId)

    /** The site icon for [url]'s host, or null if that host has never been visited. */
    fun faviconFor(url: String): Bitmap? = FaviconStore.forUrl(url)

    fun captureThumbnail(tabId: String, webView: WebView) {
        val tab = tabs.find { it.id == tabId } ?: return
        // A blank new tab would store a white rectangle, which reads worse than the
        // placeholder the grid draws when there is no thumbnail at all.
        if (tab.isNewTab) return
        TabThumbnailStore.capture(tabId, webView, incognito || tab.isPrivate)
    }

    /** Draws the tab that is about to stop being active, so its tile is not left stale. */
    internal fun captureActiveTab() {
        val id = activeTabId ?: return
        val webView = liveWebViews?.invoke(id) ?: return
        captureThumbnail(id, webView)
    }

    val activeTab: BrowserTab? get() = tabs.find { it.id == activeTabId }

    internal fun updateTab(tabId: String, transform: (BrowserTab) -> BrowserTab) {
        val idx = tabs.indexOfFirst { it.id == tabId }
        if (idx >= 0) tabs[idx] = transform(tabs[idx])
    }

    override fun onCleared() {
        onClearedPersist()
        super.onCleared()
    }
}
