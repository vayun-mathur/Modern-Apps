package com.vayunmathur.launcher.platform

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import com.vayunmathur.launcher.domain.CellRect
import com.vayunmathur.launcher.domain.ContainerRef
import com.vayunmathur.launcher.domain.GridSpec
import com.vayunmathur.launcher.domain.LauncherItemType

/**
 * The UI contract between [LauncherViewModel] and the pages in `ui`.
 *
 * Pages take a state value plus an actions interface rather than the ViewModel itself, so
 * they can be rendered by a `@Preview` — which is what the store listing images are
 * generated from (see `src/screenshotTest`). It lives beside the ViewModel rather than in
 * `ui` so the dependency runs one way.
 *
 * Every actions method has a no-op default body, so `HomeActions.Noop` and friends are the
 * whole implementation a preview needs.
 *
 * Note what these models deliberately do *not* contain: no `UserHandle`, no `ShortcutInfo`,
 * no `AppWidgetProviderInfo`. Those cannot be constructed outside a real device, and a
 * state model that cannot be written as a literal cannot be previewed. Profiles are carried
 * as the `UserManager` serial number and resolved back in the ViewModel.
 */

/** The default grid. Overridable in settings; a regrid follows any change. */
val DefaultGrid = GridSpec(columns = 5, rows = 5, hotseatSlots = 5)

/** One thing drawn in a cell, on the desktop, in the hotseat, or inside a folder. */
data class WorkspaceItem(
    val id: Long,
    val type: LauncherItemType,
    val label: String,
    val screen: Int = 0,
    val container: ContainerRef = ContainerRef.Desktop,
    val rect: CellRect = CellRect(0, 0),
    val rank: Int = 0,
    val key: ComponentKey? = null,
    val shortcutId: String? = null,
    val appWidgetId: Int? = null,
    /** Flattened provider `ComponentName`, so the hosted view can be recreated after a restart. */
    val appWidgetProvider: String? = null,
    /** Target exists but is not launchable right now. Drawn dimmed and not clickable. */
    val hidden: Boolean = false,
    /**
     * Whether the user could uninstall this, which is what decides the drop bar's second target.
     *
     * False for a system app and for anything in another profile: cross-profile uninstall is not
     * permitted, and offering a target that always fails is worse than not offering it.
     */
    val canUninstall: Boolean = false,
    /** A folder's children, ordered by rank. Empty for every other type. */
    val children: List<WorkspaceItem> = emptyList(),
)

/**
 * Supplies third-party artwork to the icon composables.
 *
 * A composition local rather than a state field: icons are loaded lazily per item and
 * cached, so putting them in the state would mean rebuilding the whole workspace state
 * every time one resolved. Previews get [Noop] and fall back to a placeholder, which is
 * also what the real loader does for an app whose icon fails to rasterise.
 */
interface IconLoader {
    fun appIcon(key: ComponentKey): ImageBitmap? = null
    fun shortcutIcon(packageName: String, shortcutId: String, profileSerial: Long): ImageBitmap? = null
    fun widgetPreview(provider: String, profileSerial: Long): ImageBitmap? = null

    /** Shared constants and helpers for IconLoader. */
    companion object {
        val Noop: IconLoader = object : IconLoader {}
    }
}

val LocalIconLoader = staticCompositionLocalOf { IconLoader.Noop }

// ------------------------------------------------------------------
// Home
// ------------------------------------------------------------------

/**
 * The home screen.
 *
 * [loading] renders wallpaper and chrome with an empty grid. The database is
 * SQLCipher-encrypted and opening it sits on the cold-start critical path — the launcher is
 * the first thing drawn after a boot — so there is a real window before the layout is
 * known, and it must not be a blank screen.
 */
data class HomeUiState(
    val loading: Boolean = true,
    val grid: GridSpec = DefaultGrid,
    /** Desktop items by page index. Pages with nothing on them are absent. */
    val pages: Map<Int, List<WorkspaceItem>> = emptyMap(),
    val hotseat: List<WorkspaceItem> = emptyList(),
    val showLabels: Boolean = true,
    val iconScale: Float = 1f,
    /**
     * Whether a downward swipe on the workspace can pull the notification shade down, as it does on
     * the system launcher. False on every ordinary device — the swipe is then simply not claimed.
     */
    val canExpandShade: Boolean = false,
)

/** Contract for home actions. */
interface HomeActions {
    /** [boundsInWindow] is the icon's on-screen rect, used for the launch animation. */
    fun launch(item: WorkspaceItem, left: Int, top: Int, right: Int, bottom: Int) {}

    /**
     * Commits a finished drag.
     *
     * [displaced] carries the neighbours the drop pushed aside, as id to new cell, so they are
     * written in the same commit as the move. They are already drawn at those cells - the page
     * previews the whole rearrangement while the finger is down - and committing them separately
     * would let the workspace re-emit with the item moved but its neighbours not, which reads as a
     * jump at the very end of the gesture.
     */
    fun commitMove(
        id: Long,
        container: ContainerRef,
        screen: Int,
        rect: CellRect,
        rank: Int,
        displaced: Map<Long, CellRect> = emptyMap(),
    ) {}

    fun mergeIntoFolder(targetId: Long, draggedId: Long) {}

    /** The drop bar's second target, for an app the user installed. */
    fun uninstallItem(id: Long) {}

    /** The drop bar's second target, for one they cannot uninstall. */
    fun openItemInfo(id: Long) {}

    /**
     * Inserts an app that was dragged in from the drawer, at the cell it was dropped on.
     *
     * Separate from [commitMove] because there is no row to move yet — the drawer deals in apps,
     * not workspace items, and the row is created by the drop. [displaced] means the same thing it
     * does there.
     */
    fun addPendingToHome(
        key: ComponentKey,
        screen: Int,
        rect: CellRect,
        displaced: Map<Long, CellRect> = emptyMap(),
    ) {}

    /** The same insert, into a hotseat slot. */
    fun addPendingToHotseat(key: ComponentKey, slot: Int) {}

    /**
     * Pins a shortcut dragged out of an item's menu, at the cell it was dropped on.
     *
     * Separate from [ItemMenuActions.pinShortcutToHome], which puts it in the first free cell,
     * because a drag names the cell itself — and may have pushed neighbours aside to get it.
     */
    fun addPendingShortcutToHome(
        shortcut: ShortcutEntry,
        screen: Int,
        rect: CellRect,
        displaced: Map<Long, CellRect> = emptyMap(),
    ) {}

    fun remove(id: Long) {}

    /**
     * Blurs the wallpaper behind the whole window, which is how Launcher3 separates the drawer from
     * the workspace. Only the Activity can do it, so it goes through the actions rather than being
     * reached for from the composable.
     */
    fun setWallpaperBlurred(blurred: Boolean) {}

    /** Pulls the notification shade down. Only called when [HomeUiState.canExpandShade]. */
    fun expandNotificationShade() {}

    /**
     * Both the span and the origin, since dragging a left or top edge moves both — plus the
     * neighbours the resize shoved aside, so they are written in the same commit as the widget and
     * the page never renders half of a rearrangement it already previewed.
     */
    fun resizeItem(id: Long, rect: CellRect, displaced: Map<Long, CellRect> = emptyMap()) {}

    /** Shared constants and helpers for HomeActions. */
    companion object {
        val Noop: HomeActions = object : HomeActions {}
    }
}

// ------------------------------------------------------------------
// App drawer
// ------------------------------------------------------------------

/** One row of the drawer. Free of `UserHandle` so it can be written as a literal. */
data class DrawerApp(
    val key: ComponentKey,
    val label: String,
    val isWorkProfile: Boolean = false,
)

/** Immutable value describing drawer ui state. */
data class DrawerUiState(
    val query: String = "",
    /** Already filtered by [query] and sorted case-insensitively by label. */
    val apps: List<DrawerApp> = emptyList(),
    /**
     * The handful of apps most likely to be wanted next, shown along the top of an unfiltered
     * drawer. From a local launch count, not from the system predictor, which is system-only.
     */
    val predictions: List<DrawerApp> = emptyList(),
    val loading: Boolean = false,
    val showLabels: Boolean = true,
    val iconScale: Float = 1f,
    /**
     * Draw the drawer as one app per row — icon, then name beside it — rather than as a grid of
     * tiles. Bigger targets and left-aligned names, which is what makes the list easier to read.
     */
    val listLayout: Boolean = false,
    /**
     * Whether the work profile is paused, or null when this build cannot pause it.
     *
     * Null on every ordinary device: pausing needs `MODIFY_QUIET_MODE`, which is privileged, so the
     * Work tab simply has no switch on it. See
     * [com.vayunmathur.launcher.platform.LauncherPrivilege].
     */
    val workPaused: Boolean? = null,
)

/** Contract for drawer actions. */
interface DrawerActions {
    fun setQuery(query: String) {}
    fun launchApp(key: ComponentKey, left: Int, top: Int, right: Int, bottom: Int) {}

    /** Pauses or resumes the work profile. Only reachable when [DrawerUiState.workPaused] is set. */
    fun setWorkPaused(paused: Boolean) {}

    /** Shared constants and helpers for DrawerActions. */
    companion object {
        val Noop: DrawerActions = object : DrawerActions {}
    }
}

// ------------------------------------------------------------------
// Folder
// ------------------------------------------------------------------

/**
 * Folders have no state of their own.
 *
 * An open folder is an overlay on the home screen rather than a separate destination — that is
 * what lets a child be dragged straight out onto the grid, since the drag never leaves the one
 * gesture owner. So the folder renders from the [WorkspaceItem] the home state already carries,
 * and stays in sync with every write for free.
 */
interface FolderActions {
    fun rename(id: Long, title: String) {}
    fun launchChild(item: WorkspaceItem, left: Int, top: Int, right: Int, bottom: Int) {}

    /** Reorders [itemId] to [rank] within its folder, after a drop onto a sibling. */
    fun reorderInFolder(folderId: Long, itemId: Long, rank: Int) {}

    /** Shared constants and helpers for FolderActions. */
    companion object {
        val Noop: FolderActions = object : FolderActions {}
    }
}

// ------------------------------------------------------------------
// Long-press item menu
// ------------------------------------------------------------------

/** A static, dynamic or pinned shortcut, flattened for the UI. */
data class ShortcutEntry(
    val shortcutId: String,
    val label: String,
    val packageName: String,
    val profileSerial: Long,
)

/** Immutable value describing item menu ui state. */
data class ItemMenuUiState(
    val item: WorkspaceItem? = null,
    val shortcuts: List<ShortcutEntry> = emptyList(),
    /**
     * False for system apps and for anything in another profile. Cross-profile uninstall is
     * not permitted, and offering a button that always fails is worse than not offering it.
     */
    val canUninstall: Boolean = false,
)

/** Contract for item menu actions. */
interface ItemMenuActions {
    fun openAppInfo(item: WorkspaceItem) {}
    fun uninstall(item: WorkspaceItem) {}
    fun launchShortcut(entry: ShortcutEntry) {}
    fun pinShortcutToHome(entry: ShortcutEntry) {}

    /** Shared constants and helpers for ItemMenuActions. */
    companion object {
        val Noop: ItemMenuActions = object : ItemMenuActions {}
    }
}

// ------------------------------------------------------------------
// Widget picker
// ------------------------------------------------------------------

/** Immutable value describing widget entry. */
data class WidgetEntry(
    /** Flattened provider `ComponentName`. */
    val provider: String,
    val label: String,
    val description: String = "",
    val spanX: Int = 1,
    val spanY: Int = 1,
    val profileSerial: Long = 0,
)

/** Widgets grouped by the app that provides them, which is how people look for them. */
data class WidgetGroup(val appLabel: String, val widgets: List<WidgetEntry>)

/** Immutable value describing widget picker ui state. */
data class WidgetPickerUiState(
    val query: String = "",
    val groups: List<WidgetGroup> = emptyList(),
    val loading: Boolean = false,
    /**
     * Whether the picker sheet is up.
     *
     * Here rather than in the home screen's composition because adding a widget leaves the
     * process: the bind-consent dialog is another activity, and the sheet has to still be open
     * when its result comes back or the pick silently does nothing.
     */
    val open: Boolean = false,
)

/** Contract for widget picker actions. */
interface WidgetPickerActions {
    fun setWidgetQuery(query: String) {}
    fun addWidget(entry: WidgetEntry) {}
    fun openWidgetPicker() {}
    fun closeWidgetPicker() {}

    /** Shared constants and helpers for WidgetPickerActions. */
    companion object {
        val Noop: WidgetPickerActions = object : WidgetPickerActions {}
    }
}

// ------------------------------------------------------------------
// Settings
// ------------------------------------------------------------------

/** Immutable value describing settings ui state. */
data class SettingsUiState(
    val columns: Int = DefaultGrid.columns,
    val rows: Int = DefaultGrid.rows,
    val hotseatSlots: Int = DefaultGrid.hotseatSlots,
    val showLabels: Boolean = true,
    val iconScale: Float = 1f,
    val drawerListLayout: Boolean = false,
    val isDefaultHome: Boolean = false,
)

/** Contract for settings actions. */
interface SettingsActions {
    fun setColumns(columns: Int) {}
    fun setRows(rows: Int) {}
    fun setHotseatSlots(slots: Int) {}
    fun setShowLabels(show: Boolean) {}
    fun setIconScale(scale: Float) {}

    /** Switches the app drawer between the tile grid and one app per row. */
    fun setDrawerListLayout(list: Boolean) {}
    fun pickWallpaper() {}
    fun requestDefaultHome() {}

    /** Shared constants and helpers for SettingsActions. */
    companion object {
        val Noop: SettingsActions = object : SettingsActions {}
    }
}
