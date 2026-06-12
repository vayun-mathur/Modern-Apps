package com.vayunmathur.launcher

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.graphics.drawable.Drawable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vayunmathur.launcher.data.DockItem
import com.vayunmathur.launcher.data.FolderInfo
import com.vayunmathur.launcher.data.HomeItemType
import com.vayunmathur.launcher.data.HomeScreenItem
import com.vayunmathur.launcher.data.LauncherDatabase
import com.vayunmathur.launcher.data.LauncherPreferences
import com.vayunmathur.launcher.data.WidgetItem
import com.vayunmathur.launcher.search.GroupedResults
import com.vayunmathur.launcher.search.SearchManager
import com.vayunmathur.library.util.buildDatabase
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AppInfo(
    val name: String,
    val packageName: String,
    val activityName: String = "",
    val icon: Drawable
)

@OptIn(FlowPreview::class)
class LauncherViewModel(application: Application) : AndroidViewModel(application) {
    private val searchManager = SearchManager(application)
    private val db: LauncherDatabase = application.buildDatabase(dbName = "launcher-db")
    private val prefs = LauncherPreferences(application)

    private val _apps = MutableStateFlow<List<AppInfo>>(emptyList())
    val apps: StateFlow<List<AppInfo>> = _apps.asStateFlow()

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _searchResults = MutableStateFlow(GroupedResults())
    val searchResults: StateFlow<GroupedResults> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _isDrawerOpen = MutableStateFlow(false)
    val isDrawerOpen: StateFlow<Boolean> = _isDrawerOpen.asStateFlow()

    private val _focusSearch = MutableStateFlow(false)
    val focusSearch: StateFlow<Boolean> = _focusSearch.asStateFlow()

    private val _homeContextMenuVisible = MutableStateFlow(false)
    val homeContextMenuVisible: StateFlow<Boolean> = _homeContextMenuVisible.asStateFlow()

    private val _showWidgetPicker = MutableStateFlow(false)
    val showWidgetPicker: StateFlow<Boolean> = _showWidgetPicker.asStateFlow()

    private val _contextMenuApp = MutableStateFlow<HomeScreenItem?>(null)
    val contextMenuApp: StateFlow<HomeScreenItem?> = _contextMenuApp.asStateFlow()

    private val _contextMenuDockApp = MutableStateFlow<DockItem?>(null)
    val contextMenuDockApp: StateFlow<DockItem?> = _contextMenuDockApp.asStateFlow()

    private val _openFolder = MutableStateFlow<FolderInfo?>(null)
    val openFolder: StateFlow<FolderInfo?> = _openFolder.asStateFlow()

    val homeItems: StateFlow<List<HomeScreenItem>> = db.homeScreenDao().getAllItems()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val dockItems: StateFlow<List<DockItem>> = db.dockDao().getAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val widgets: StateFlow<List<WidgetItem>> = db.widgetDao().getAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val folders: StateFlow<List<FolderInfo>> = db.folderDao().getAll()
        .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

    val gridColumns: StateFlow<Int> = prefs.gridColumns
        .stateIn(viewModelScope, SharingStarted.Lazily, 4)

    val gridRows: StateFlow<Int> = prefs.gridRows
        .stateIn(viewModelScope, SharingStarted.Lazily, 5)

    val pageCount: StateFlow<Int> = prefs.pageCount
        .stateIn(viewModelScope, SharingStarted.Lazily, 1)

    private val iconCache = mutableMapOf<String, Drawable>()

    init {
        viewModelScope.launch {
            searchManager.initialize()
            loadApps()
            indexData()
            autoPopulateIfFirstRun()
        }

        viewModelScope.launch {
            _query.debounce(200).collect { q ->
                if (q.isNotBlank()) {
                    _searchResults.value = searchManager.search(q)
                } else {
                    _searchResults.value = GroupedResults()
                }
            }
        }
    }

    private fun loadApps() {
        val pm = getApplication<Application>().packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos: List<ResolveInfo> = pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        _apps.value = resolveInfos
            .mapNotNull { info ->
                val label = info.loadLabel(pm)?.toString() ?: return@mapNotNull null
                val icon = info.loadIcon(pm)
                iconCache[info.activityInfo.packageName] = icon
                AppInfo(
                    name = label,
                    packageName = info.activityInfo.packageName,
                    activityName = info.activityInfo.name,
                    icon = icon
                )
            }
            .sortedBy { it.name.lowercase() }
    }

    private suspend fun indexData() {
        searchManager.indexApps()
        searchManager.indexContacts()
        searchManager.indexCalendarEvents()
    }

    private suspend fun autoPopulateIfFirstRun() {
        val firstRun = prefs.isFirstRun.first()
        if (!firstRun) return

        val dockCount = db.dockDao().count()
        if (dockCount > 0) {
            prefs.setFirstRun(false)
            return
        }

        val dockCandidates = listOf("phone", "dialer", "messages", "messaging", "sms", "browser", "chrome", "camera", "maps")
        val apps = _apps.value
        var position = 0

        for (keyword in dockCandidates) {
            if (position >= 5) break
            val app = apps.find { it.name.lowercase().contains(keyword) || it.packageName.lowercase().contains(keyword) }
            if (app != null) {
                db.dockDao().upsert(DockItem(position = position, packageName = app.packageName, activityName = app.activityName))
                position++
            }
        }

        prefs.setFirstRun(false)
    }

    fun getAppInfo(packageName: String): AppInfo? = _apps.value.find { it.packageName == packageName }

    fun getIcon(packageName: String): Drawable? = iconCache[packageName]

    fun getPageItems(page: Int): List<HomeScreenItem> =
        homeItems.value.filter { it.pageIndex == page && it.folderId == null }

    fun getPageWidgets(page: Int): List<WidgetItem> =
        widgets.value.filter { it.pageIndex == page }

    fun getFolderItems(folderId: Long): List<HomeScreenItem> =
        homeItems.value.filter { it.folderId == folderId }

    fun setQuery(q: String) {
        _query.value = q
    }

    fun setSearching(active: Boolean) {
        _isSearching.value = active
        if (!active) {
            _query.value = ""
            _searchResults.value = GroupedResults()
        }
    }

    fun openDrawer(focusSearch: Boolean = false) {
        _isDrawerOpen.value = true
        _focusSearch.value = focusSearch
    }

    fun closeDrawer() {
        _isDrawerOpen.value = false
        _focusSearch.value = false
        setSearching(false)
    }

    fun showHomeContextMenu() { _homeContextMenuVisible.value = true }
    fun hideHomeContextMenu() { _homeContextMenuVisible.value = false }

    fun showWidgetPicker() { _showWidgetPicker.value = true }
    fun hideWidgetPicker() { _showWidgetPicker.value = false }

    fun showAppContextMenu(item: HomeScreenItem) { _contextMenuApp.value = item }
    fun hideAppContextMenu() { _contextMenuApp.value = null }

    fun showDockAppContextMenu(item: DockItem) { _contextMenuDockApp.value = item }
    fun hideDockAppContextMenu() { _contextMenuDockApp.value = null }

    fun openFolder(folder: FolderInfo) { _openFolder.value = folder }
    fun closeFolder() { _openFolder.value = null }

    fun addToDock(packageName: String, activityName: String, position: Int) {
        viewModelScope.launch {
            db.dockDao().upsert(DockItem(position = position, packageName = packageName, activityName = activityName))
        }
    }

    fun removeFromDock(item: DockItem) {
        viewModelScope.launch { db.dockDao().delete(item) }
    }

    fun addToPage(packageName: String, activityName: String, page: Int, row: Int, col: Int) {
        viewModelScope.launch {
            db.homeScreenDao().upsert(
                HomeScreenItem(pageIndex = page, row = row, col = col, packageName = packageName, activityName = activityName)
            )
        }
    }

    fun removeFromPage(item: HomeScreenItem) {
        viewModelScope.launch { db.homeScreenDao().delete(item) }
    }

    fun createFolder(title: String, page: Int, row: Int, col: Int) {
        viewModelScope.launch {
            db.folderDao().upsert(FolderInfo(title = title, pageIndex = page, row = row, col = col))
        }
    }

    fun renameFolder(folder: FolderInfo, newTitle: String) {
        viewModelScope.launch {
            db.folderDao().upsert(folder.copy(title = newTitle))
        }
    }

    fun addWidget(appWidgetId: Int, page: Int, row: Int, col: Int, spanX: Int, spanY: Int) {
        viewModelScope.launch {
            db.widgetDao().upsert(
                WidgetItem(pageIndex = page, row = row, col = col, spanX = spanX, spanY = spanY, appWidgetId = appWidgetId)
            )
        }
    }

    fun removeWidget(widgetId: Int) {
        viewModelScope.launch { db.widgetDao().deleteByWidgetId(widgetId) }
    }

    override fun onCleared() {
        super.onCleared()
        searchManager.close()
    }
}
