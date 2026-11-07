package com.vayunmathur.launcher.platform

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import androidx.lifecycle.viewModelScope
import com.vayunmathur.launcher.data.LauncherItemEntity
import com.vayunmathur.launcher.domain.CellRect
import com.vayunmathur.launcher.domain.ContainerRef
import com.vayunmathur.launcher.domain.GridSpec
import com.vayunmathur.launcher.domain.LauncherItemType
import com.vayunmathur.launcher.domain.toRaw
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.launch

// ------------------------------------------------------------------
// ItemMenuActions / SettingsActions / misc bodies — moved from
// LauncherViewModel.kt (TooManyFunctions split); behavior identical.
// ------------------------------------------------------------------

internal fun LauncherViewModel.openItemMenuImpl(id: Long) {
    val entity = savedItems.firstOrNull { it.id == id }
    // Folder children are not on a page or in the hotseat, so the search has to reach into
    // folders too - a child's menu is opened by releasing it in place inside its folder.
    val onWorkspace = homeState.value.pages.values.flatten() + homeState.value.hotseat
    val item = (onWorkspace + onWorkspace.flatMap { it.children }).firstOrNull { it.id == id }
    if (entity == null || item == null) {
        itemMenuState.value = ItemMenuUiState()
        return
    }
    val key = item.key
    val entry = key?.let { appsMonitor.entryFor(it.componentName, it.profileSerial) }
    val shortcuts = if (key != null && item.type == LauncherItemType.APPLICATION) {
        appsMonitor.shortcuts(key.componentName.packageName, appsMonitor.userFor(key.profileSerial))
            .filterNot { it.isPinned && it.id == item.shortcutId }
            .mapNotNull { shortcut ->
                val label = (shortcut.shortLabel ?: shortcut.longLabel)?.toString()
                    ?: return@mapNotNull null
                ShortcutEntry(shortcut.id, label, shortcut.`package`, key.profileSerial)
            }
    } else {
        emptyList()
    }
    itemMenuState.value = ItemMenuUiState(
        item = item,
        shortcuts = shortcuts,
        canUninstall = entry != null && !entry.isWorkProfile && !isSystemAppImpl(entry),
    )
}

internal fun LauncherViewModel.openAppInfoImpl(item: WorkspaceItem) {
    val key = item.key ?: return
    val entry = appsMonitor.entryFor(key.componentName, key.profileSerial) ?: return
    appsMonitor.startAppDetails(entry, null)
}

internal fun LauncherViewModel.uninstallImpl(item: WorkspaceItem) {
    val key = item.key ?: return
    bridge?.requestUninstall(key.componentName.packageName)
}

internal fun LauncherViewModel.launchShortcutImpl(entry: ShortcutEntry) {
    val user = appsMonitor.userFor(entry.profileSerial)
    val shortcut = appsMonitor.shortcuts(entry.packageName, user)
        .firstOrNull { it.id == entry.shortcutId } ?: return
    appsMonitor.startShortcut(shortcut, null)
}

internal fun LauncherViewModel.pinShortcutToHomeImpl(entry: ShortcutEntry) {
    viewModelScope.launch {
        val row = pinnedShortcutRowImpl(entry) ?: return@launch
        repository.addToFirstVacantCell(homeState.value.grid, row)
    }
}

internal fun LauncherViewModel.addPendingShortcutToHomeImpl(
    shortcut: ShortcutEntry,
    screen: Int,
    rect: CellRect,
    displaced: Map<Long, CellRect>,
) {
    viewModelScope.launch {
        val row = pinnedShortcutRowImpl(shortcut) ?: return@launch
        val id = repository.upsert(row)
        repository.moveTo(id, ContainerRef.Desktop, screen, rect, displaced = displaced)
    }
}

/**
 * The row for a shortcut, pinned with the system first.
 *
 * Pinned before persisted: an unpinned shortcut can be revoked by its app at any time, and a
 * row pointing at a revoked shortcut can never be launched.
 */
internal suspend fun LauncherViewModel.pinnedShortcutRowImpl(entry: ShortcutEntry): LauncherItemEntity? {
    val user = appsMonitor.userFor(entry.profileSerial)
    val shortcut = appsMonitor.shortcuts(entry.packageName, user)
        .firstOrNull { it.id == entry.shortcutId } ?: return null
    if (!appsMonitor.pinShortcut(shortcut)) {
        AppMessages.show("Could not add ${entry.label}")
        return null
    }
    return LauncherItemEntity(
        itemType = LauncherItemType.DEEP_SHORTCUT,
        containerId = ContainerRef.Desktop.toRaw(),
        title = entry.label,
        packageName = entry.packageName,
        className = shortcut.activity?.className,
        profileSerial = entry.profileSerial,
        shortcutId = entry.shortcutId,
    )
}

internal fun LauncherViewModel.setColumnsImpl(columns: Int) = changeGridImpl { it.copy(columns = columns) }

internal fun LauncherViewModel.setRowsImpl(rows: Int) = changeGridImpl { it.copy(rows = rows) }

internal fun LauncherViewModel.setHotseatSlotsImpl(slots: Int) = changeGridImpl { it.copy(hotseatSlots = slots) }

/**
 * Applies a grid change and re-lays the workspace out for it.
 *
 * The regrid runs before the new spec reaches the UI, so the home screen never renders
 * old coordinates against a new grid - which would briefly show items overlapping or
 * hanging off the edge.
 */
internal fun LauncherViewModel.changeGridImpl(transform: (GridSpec) -> GridSpec) {
    // The transform is applied inside the launch, against the then-current
    // grid: computing it here would snapshot the grid before any earlier
    // setter's coroutine lands, so two setters in a row (rows, then hotseat
    // slots) would each publish a whole grid and the second would silently
    // undo the first.
    viewModelScope.launch {
        val next = transform(homeState.value.grid)
        repository.regrid(next)
        homeState.value = homeState.value.copy(grid = next)
        settingsState.value = settingsState.value.copy(
            columns = next.columns,
            rows = next.rows,
            hotseatSlots = next.hotseatSlots,
        )
        ds.setLong(KEY_COLUMNS, next.columns.toLong())
        ds.setLong(KEY_ROWS, next.rows.toLong())
        ds.setLong(KEY_HOTSEAT, next.hotseatSlots.toLong())
    }
}

internal fun LauncherViewModel.setShowLabelsImpl(show: Boolean) {
    settingsState.value = settingsState.value.copy(showLabels = show)
    homeState.value = homeState.value.copy(showLabels = show)
    drawerState.value = drawerState.value.copy(showLabels = show)
    viewModelScope.launch { ds.setBoolean(KEY_SHOW_LABELS, show) }
}

internal fun LauncherViewModel.setIconScaleImpl(scale: Float) {
    val clamped = scale.coerceIn(LauncherViewModel.MIN_ICON_SCALE, LauncherViewModel.MAX_ICON_SCALE)
    settingsState.value = settingsState.value.copy(iconScale = clamped)
    homeState.value = homeState.value.copy(iconScale = clamped)
    drawerState.value = drawerState.value.copy(iconScale = clamped)
    viewModelScope.launch { ds.setDouble(KEY_ICON_SCALE, clamped.toDouble()) }
}

internal fun LauncherViewModel.setDrawerListLayoutImpl(list: Boolean) {
    settingsState.value = settingsState.value.copy(drawerListLayout = list)
    drawerState.value = drawerState.value.copy(listLayout = list)
    viewModelScope.launch { ds.setBoolean(KEY_DRAWER_LIST_LAYOUT, list) }
}

internal fun LauncherViewModel.pickWallpaperImpl() {
    bridge?.pickWallpaper()
}

internal fun LauncherViewModel.requestDefaultHomeImpl() {
    bridge?.requestHomeRole()
}

internal fun LauncherViewModel.refreshDefaultHomeImpl() {
    settingsState.value = settingsState.value.copy(isDefaultHome = isDefaultHomeImpl())
}

internal fun LauncherViewModel.isDefaultHomeImpl(): Boolean {
    val app = getApplication<android.app.Application>()
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val resolved = app.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
    return resolved?.activityInfo?.packageName == app.packageName
}

/** A locale change relabels and re-themes every icon, so nothing cached survives it. */
internal fun LauncherViewModel.onLocaleChangedImpl() {
    iconCache.clear()
    appsMonitor.refresh()
}
