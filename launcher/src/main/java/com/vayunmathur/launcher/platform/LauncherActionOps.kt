package com.vayunmathur.launcher.platform

import android.appwidget.AppWidgetHostView
import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.viewModelScope
import com.vayunmathur.launcher.domain.CellRect
import com.vayunmathur.launcher.domain.ContainerRef
import com.vayunmathur.launcher.domain.GridSpec
import kotlinx.coroutines.launch

// ------------------------------------------------------------------
// State-holding operation facades for the UI-contract action interfaces.
// The ViewModel delegates each actions interface to one holder below; the
// bodies live in LauncherLifecycleOps.kt, LauncherHomeOps.kt and
// LauncherMenuOps.kt (TooManyFunctions split); behavior identical.
// ------------------------------------------------------------------

/** Bodies behind [HomeActions]. */
internal object LauncherHomeOpsHolder : HomeActions {
    private var bound: LauncherViewModel? = null
    fun bind(vm: LauncherViewModel) {
        bound = vm
    }
    private fun vm(): LauncherViewModel = bound ?: error("LauncherHomeOpsHolder not bound")
    override fun launch(item: WorkspaceItem, left: Int, top: Int, right: Int, bottom: Int) =
        vm().launchImpl(item, left, top, right, bottom)
    override fun commitMove(
        id: Long,
        container: ContainerRef,
        screen: Int,
        rect: CellRect,
        rank: Int,
        displaced: Map<Long, CellRect>,
    ) = vm().commitMoveImpl(id, container, screen, rect, rank, displaced)
    override fun mergeIntoFolder(targetId: Long, draggedId: Long) =
        vm().mergeIntoFolderImpl(targetId, draggedId)
    override fun uninstallItem(id: Long) = vm().uninstallItemImpl(id)
    override fun openItemInfo(id: Long) = vm().openItemInfoImpl(id)
    override fun addPendingToHome(
        key: ComponentKey,
        screen: Int,
        rect: CellRect,
        displaced: Map<Long, CellRect>,
    ) = vm().addPendingToHomeImpl(key, screen, rect, displaced)
    override fun addPendingToHotseat(key: ComponentKey, slot: Int) =
        vm().addPendingToHotseatImpl(key, slot)
    override fun addPendingShortcutToHome(
        shortcut: ShortcutEntry,
        screen: Int,
        rect: CellRect,
        displaced: Map<Long, CellRect>,
    ) = vm().addPendingShortcutToHomeImpl(shortcut, screen, rect, displaced)
    override fun remove(id: Long) = vm().removeImpl(id)
    override fun setWallpaperBlurred(blurred: Boolean) = vm().setWallpaperBlurredImpl(blurred)
    override fun expandNotificationShade() = vm().expandNotificationShadeImpl()
    override fun resizeItem(id: Long, rect: CellRect, displaced: Map<Long, CellRect>) =
        vm().resizeItemImpl(id, rect, displaced)
}

/** Bodies behind [DrawerActions]. */
internal object LauncherDrawerOpsHolder : DrawerActions {
    private var bound: LauncherViewModel? = null
    fun bind(vm: LauncherViewModel) {
        bound = vm
    }
    private fun vm(): LauncherViewModel = bound ?: error("LauncherDrawerOpsHolder not bound")
    override fun setQuery(query: String) = vm().applyDrawerQuery(query)
    override fun launchApp(key: ComponentKey, left: Int, top: Int, right: Int, bottom: Int) =
        vm().launchAppImpl(key, left, top, right, bottom)
    override fun setWorkPaused(paused: Boolean) = vm().setWorkPausedImpl(paused)
}

/** Bodies behind [FolderActions]. */
internal object LauncherFolderOpsHolder : FolderActions {
    private var bound: LauncherViewModel? = null
    fun bind(vm: LauncherViewModel) {
        bound = vm
    }
    private fun vm(): LauncherViewModel = bound ?: error("LauncherFolderOpsHolder not bound")
    override fun rename(id: Long, title: String) = vm().renameImpl(id, title)
    override fun launchChild(item: WorkspaceItem, left: Int, top: Int, right: Int, bottom: Int) =
        vm().launchChildImpl(item, left, top, right, bottom)
    override fun reorderInFolder(folderId: Long, itemId: Long, rank: Int) =
        vm().reorderInFolderImpl(folderId, itemId, rank)
}

/** Bodies behind [ItemMenuActions]. */
internal object LauncherItemMenuOpsHolder : ItemMenuActions {
    private var bound: LauncherViewModel? = null
    fun bind(vm: LauncherViewModel) {
        bound = vm
    }
    private fun vm(): LauncherViewModel = bound ?: error("LauncherItemMenuOpsHolder not bound")
    override fun openAppInfo(item: WorkspaceItem) = vm().openAppInfoImpl(item)
    override fun uninstall(item: WorkspaceItem) = vm().uninstallImpl(item)
    override fun launchShortcut(entry: ShortcutEntry) = vm().launchShortcutImpl(entry)
    override fun pinShortcutToHome(entry: ShortcutEntry) = vm().pinShortcutToHomeImpl(entry)
}

/** Bodies behind [WidgetPickerActions]. */
internal object LauncherWidgetOpsHolder : WidgetPickerActions {
    private var bound: LauncherViewModel? = null
    fun bind(vm: LauncherViewModel) {
        bound = vm
    }
    private fun vm(): LauncherViewModel = bound ?: error("LauncherWidgetOpsHolder not bound")
    override fun setWidgetQuery(query: String) = vm().applyWidgetQuery(query)
    override fun addWidget(entry: WidgetEntry) = vm().addWidgetEntry(entry)
    override fun openWidgetPicker() {
        vm().widgetPickerState.value = vm().widgetPickerState.value.copy(open = true)
        vm().loadWidgetPicker()
    }
    override fun closeWidgetPicker() {
        vm().widgetPickerState.value = vm().widgetPickerState.value.copy(open = false)
    }
    fun hostedView(appWidgetId: Int): AppWidgetHostView? = vm().hostedWidgetView(appWidgetId)
    fun updateSize(view: AppWidgetHostView, widthDp: Int, heightDp: Int) =
        vm().widgets.updateSize(view, widthDp, heightDp)
}

/** Bodies behind [SettingsActions]. */
internal object LauncherSettingsOpsHolder : SettingsActions {
    private var bound: LauncherViewModel? = null
    fun bind(vm: LauncherViewModel) {
        bound = vm
    }
    private fun vm(): LauncherViewModel = bound ?: error("LauncherSettingsOpsHolder not bound")
    override fun setColumns(columns: Int) = vm().setColumnsImpl(columns)
    override fun setRows(rows: Int) = vm().setRowsImpl(rows)
    override fun setHotseatSlots(slots: Int) = vm().setHotseatSlotsImpl(slots)
    override fun setShowLabels(show: Boolean) = vm().setShowLabelsImpl(show)
    override fun setIconScale(scale: Float) = vm().setIconScaleImpl(scale)
    override fun setDrawerListLayout(list: Boolean) = vm().setDrawerListLayoutImpl(list)
    override fun pickWallpaper() = vm().pickWallpaperImpl()
    override fun requestDefaultHome() = vm().requestDefaultHomeImpl()
}

/** Bodies behind [IconLoader]. */
internal object LauncherIconOpsHolder : IconLoader {
    private var bound: LauncherViewModel? = null
    fun bind(vm: LauncherViewModel) {
        bound = vm
    }
    private fun vm(): LauncherViewModel = bound ?: error("LauncherIconOpsHolder not bound")
    override fun appIcon(key: ComponentKey): ImageBitmap? = vm().appIconImpl(key)
    override fun shortcutIcon(
        packageName: String,
        shortcutId: String,
        profileSerial: Long,
    ): ImageBitmap? = vm().shortcutIconImpl(packageName, shortcutId, profileSerial)
    override fun widgetPreview(provider: String, profileSerial: Long): ImageBitmap? =
        vm().widgetPreviewImpl(provider, profileSerial)
}

/** One-line grid mutation shared by the three grid setters. */
internal fun LauncherViewModel.changeGridForSettings(transform: (GridSpec) -> GridSpec) {
    // See changeGridImpl: the transform must read the grid inside the
    // launch, or back-to-back setters lose each other's changes.
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
