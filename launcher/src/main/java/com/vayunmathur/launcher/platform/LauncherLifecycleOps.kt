package com.vayunmathur.launcher.platform

import android.app.Application
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import androidx.lifecycle.viewModelScope
import com.vayunmathur.launcher.data.LauncherItemEntity
import com.vayunmathur.launcher.domain.CellRect
import com.vayunmathur.launcher.domain.ContainerRef
import com.vayunmathur.launcher.domain.GridSpec
import com.vayunmathur.launcher.domain.LauncherItemType
import com.vayunmathur.launcher.domain.PackageKey
import com.vayunmathur.launcher.domain.toRaw
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// ------------------------------------------------------------------
// Lifecycle + preferences + workspace + first-run seeding — moved from
// LauncherViewModel.kt (TooManyFunctions split); behavior identical.
// ------------------------------------------------------------------

internal fun LauncherViewModel.onStartImpl() {
    widgetHost.startListeningSafely()
    appsMonitor.start(::onAppsChangedImpl)
}

internal fun LauncherViewModel.onStopImpl() {
    appsMonitor.stop()
    widgetHost.stopListeningSafely()
}

/**
 * Cold-start housekeeping: drop rows whose app is gone, hide rows whose app is merely away,
 * and hand back widget ids nothing references any more.
 *
 * Refuses to run while the app list is empty. An empty list is indistinguishable from "every
 * app has been uninstalled", and acting on it would delete the entire home screen - which is
 * exactly what happens if `getActivityList` fails transiently, or if this is called before
 * the first [LauncherAppsMonitor.refresh] has returned.
 */
internal fun LauncherViewModel.reconcileNowImpl() {
    val installed = appsMonitor.installedKeys()
    if (installed.isEmpty()) return
    viewModelScope.launch {
        val orphaned = repository.reconcile(
            installed = installed,
            unavailable = appsMonitor.unavailable.value,
            boundWidgetIds = widgetHost.boundIds(),
            unavailableProfiles = appsMonitor.unavailableProfileSerials(),
            installedActivities = appsMonitor.installedActivityKeys(),
        )
        orphaned.forEach(widgets::release)
        widgets.releaseOrphans(repository.usedWidgetIds())
        refreshDefaultHomeImpl()
    }
}

internal fun LauncherViewModel.onAppsChangedImpl(stale: PackageKey?) {
    // A package update changes its icon, so the cached bitmap for it is wrong now.
    stale?.let { iconCache.evictPackage(it.packageName, it.profileSerial) }
    allApps = appsMonitor.apps.value.map {
        DrawerApp(key = it.key, label = it.label, isWorkProfile = it.isWorkProfile)
    }
    applyDrawerQuery(drawerState.value.query)
    refreshWorkProfileImpl()
    seedIfEmptyImpl()
    reconcileNowImpl()
}

/**
 * Whether the work profile is paused, for the drawer's Work tab.
 *
 * Left null unless this build can actually change it, so the tab has no switch on it rather than
 * one that does nothing.
 */
internal fun LauncherViewModel.refreshWorkProfileImpl() {
    val workApp = appsMonitor.apps.value.firstOrNull { it.isWorkProfile }
    val paused = if (workApp == null || !privilege.canToggleQuietMode()) {
        null
    } else {
        privilege.isQuietModeEnabled(workApp.user)
    }
    drawerState.value = drawerState.value.copy(workPaused = paused)
    homeState.value = homeState.value.copy(canExpandShade = privilege.canExpandNotificationShade())
}

internal fun LauncherViewModel.loadPreferencesImpl() {
    val columns = ds.getLong(KEY_COLUMNS)?.toInt() ?: DefaultGrid.columns
    val rows = ds.getLong(KEY_ROWS)?.toInt() ?: DefaultGrid.rows
    val hotseat = ds.getLong(KEY_HOTSEAT)?.toInt() ?: DefaultGrid.hotseatSlots
    val showLabels = ds.getBoolean(KEY_SHOW_LABELS, true)
    val iconScale = (ds.getDouble(KEY_ICON_SCALE) ?: 1.0).toFloat()
    val drawerListLayout = ds.getBoolean(KEY_DRAWER_LIST_LAYOUT, false)

    settingsState.value = SettingsUiState(
        columns = columns,
        rows = rows,
        hotseatSlots = hotseat,
        showLabels = showLabels,
        iconScale = iconScale,
        drawerListLayout = drawerListLayout,
    )
    homeState.value = homeState.value.copy(
        grid = GridSpec(columns, rows, hotseat),
        showLabels = showLabels,
        iconScale = iconScale,
    )
    drawerState.value = drawerState.value.copy(
        showLabels = showLabels,
        iconScale = iconScale,
        listLayout = drawerListLayout,
    )
}

internal fun LauncherViewModel.observeWorkspaceImpl() {
    viewModelScope.launch {
        repository.items.collect { items ->
            savedItems = items
            // Mapped off the main thread: every row's label comes from the app list and every
            // app row asks the package manager whether it can be uninstalled, and this runs on
            // every single write - including each step of a drag commit.
            val next = withContext(Dispatchers.Default) { workspaceFromImpl(items) }
            homeState.value = homeState.value.copy(
                loading = false,
                pages = next.first,
                hotseat = next.second,
            )
        }
    }
}

/** The desktop by page and the hotseat in rank order, built from the saved rows. */
internal fun LauncherViewModel.workspaceFromImpl(
    items: List<LauncherItemEntity>,
): Pair<Map<Int, List<WorkspaceItem>>, List<WorkspaceItem>> {
    val byContainer = items.groupBy { it.container }
    val folderChildren = items
        .mapNotNull { item -> (item.container as? ContainerRef.Folder)?.let { it.id to item } }
        .groupBy({ it.first }, { it.second })
    // Memoised for the length of one rebuild: several rows commonly share a package, and each
    // answer is a PackageManager call.
    val uninstallable = mutableMapOf<PackageKey, Boolean>()

    fun toUi(entity: LauncherItemEntity): WorkspaceItem = WorkspaceItem(
        id = entity.id,
        type = entity.itemType,
        label = entity.title ?: labelForImpl(entity),
        screen = entity.screen,
        container = entity.container,
        rect = entity.rect,
        rank = entity.rank,
        key = entity.className?.let {
            ComponentKey(android.content.ComponentName(entity.packageName.orEmpty(), it), entity.profileSerial)
        },
        shortcutId = entity.shortcutId,
        appWidgetId = entity.appWidgetId,
        appWidgetProvider = entity.appWidgetProvider,
        hidden = entity.hidden,
        canUninstall = uninstallable.getOrPut(
            PackageKey(entity.packageName.orEmpty(), entity.profileSerial),
        ) { canUninstallImpl(entity) },
        children = folderChildren[entity.id]
            ?.sortedBy { it.rank }
            ?.map { child ->
                // One level only: folders cannot nest, so a child never has children.
                WorkspaceItem(
                    id = child.id,
                    type = child.itemType,
                    label = child.title ?: labelForImpl(child),
                    container = child.container,
                    rank = child.rank,
                    key = child.className?.let {
                        ComponentKey(
                            android.content.ComponentName(child.packageName.orEmpty(), it),
                            child.profileSerial,
                        )
                    },
                    shortcutId = child.shortcutId,
                    hidden = child.hidden,
                )
            }
            .orEmpty(),
    )

    val desktop = byContainer[ContainerRef.Desktop].orEmpty().map(::toUi)
    return desktop.groupBy { it.screen } to
        byContainer[ContainerRef.Hotseat].orEmpty().sortedBy { it.rank }.map(::toUi)
}

/**
 * Whether this row's app could be uninstalled from here.
 *
 * The same test the item popup applies, and the reason it is not just "is it an app": a system
 * app cannot be removed at all, and a copy in another profile cannot be removed from this one.
 */
internal fun LauncherViewModel.canUninstallImpl(entity: LauncherItemEntity): Boolean {
    if (entity.itemType != LauncherItemType.APPLICATION) return false
    val className = entity.className ?: return false
    val component = android.content.ComponentName(entity.packageName.orEmpty(), className)
    val entry = appsMonitor.entryFor(component, entity.profileSerial) ?: return false
    return !entry.isWorkProfile && !isSystemAppImpl(entry)
}

internal fun LauncherViewModel.labelForImpl(entity: LauncherItemEntity): String {
    val className = entity.className ?: return ""
    val component = android.content.ComponentName(entity.packageName.orEmpty(), className)
    return appsMonitor.entryFor(component, entity.profileSerial)?.label.orEmpty()
}

/**
 * Seeds a first run: the hotseat from the apps that answer the obvious intents, and one
 * row on the first page from what is left.
 *
 * Guarded on the table being empty rather than on a "seeded" flag, so a user who
 * deliberately clears their home screen does not have it refilled on the next launch.
 */
internal fun LauncherViewModel.seedIfEmptyImpl() {
    viewModelScope.launch {
        if (!repository.isEmpty()) return@launch
        val apps = appsMonitor.apps.value
        if (apps.isEmpty()) return@launch

        val spec = homeState.value.grid
        val hotseatApps = SEED_INTENTS
            .mapNotNull { resolveAppImpl(it) }
            .distinctBy { it.componentName }
            .take(spec.hotseatSlots)
        val hotseatComponents = hotseatApps.map { it.componentName }.toSet()
        val firstRow = apps
            .filter { it.componentName !in hotseatComponents && !it.isWorkProfile }
            .take(spec.columns)

        repository.seed(
            spec = spec,
            apps = firstRow.map(::entityForAppImpl),
            hotseat = hotseatApps.map(::entityForAppImpl),
        )
    }
}

internal fun LauncherViewModel.entityForAppImpl(entry: AppEntry) = LauncherItemEntity(
    itemType = LauncherItemType.APPLICATION,
    containerId = ContainerRef.Desktop.toRaw(),
    packageName = entry.componentName.packageName,
    className = entry.componentName.className,
    profileSerial = entry.profileSerial,
)

internal fun LauncherViewModel.resolveAppImpl(intent: Intent): AppEntry? {
    val pm = getApplication<Application>().packageManager
    val resolved = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) ?: return null
    val packageName = resolved.activityInfo?.packageName ?: return null
    return appsMonitor.apps.value.firstOrNull {
        it.componentName.packageName == packageName && !it.isWorkProfile
    }
}

internal fun LauncherViewModel.entityForKeyImpl(key: ComponentKey) = LauncherItemEntity(
    itemType = LauncherItemType.APPLICATION,
    containerId = ContainerRef.Desktop.toRaw(),
    packageName = key.componentName.packageName,
    className = key.componentName.className,
    profileSerial = key.profileSerial,
)

internal fun LauncherViewModel.isSystemAppImpl(entry: AppEntry): Boolean =
    systemApps.getOrPut(entry.componentName.packageName) {
        runCatching {
            val flags = getApplication<Application>().packageManager
                .getApplicationInfo(entry.componentName.packageName, 0)
                .flags
            flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM != 0
        }.getOrDefault(true)
    }
