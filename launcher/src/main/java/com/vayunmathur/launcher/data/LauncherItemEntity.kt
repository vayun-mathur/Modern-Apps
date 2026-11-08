package com.vayunmathur.launcher.data

import androidx.room3.ColumnInfo
import androidx.room3.ColumnTypeConverter
import androidx.room3.Entity
import androidx.room3.Index
import androidx.room3.PrimaryKey
import com.vayunmathur.launcher.domain.CellRect
import com.vayunmathur.launcher.domain.ContainerRef
import com.vayunmathur.launcher.domain.GridItem
import com.vayunmathur.launcher.domain.LauncherItemType
import com.vayunmathur.launcher.domain.PackageKey
import com.vayunmathur.launcher.domain.ReconcileUseCase
import com.vayunmathur.launcher.domain.containerRefOf
import com.vayunmathur.library.util.DatabaseItem

/**
 * Every icon, folder, widget and pinned shortcut on the home screen, in one table.
 *
 * Deliberately polymorphic, mirroring Launcher3's `favorites`, rather than a table per
 * type. Items of all four kinds share a container/screen/cell/span identity, so every
 * placement query is type-agnostic; and drag-and-drop converts between types constantly
 * (an app becomes a folder child, a shortcut becomes a home item). Separate tables would
 * put a union in front of every read and a cross-table move behind every drop.
 *
 * The target is stored as [packageName] + [className] + [profileSerial] rather than a
 * serialized Intent URI. That makes it queryable — orphan cleanup and reconciliation are
 * `WHERE` clauses instead of a full scan and parse of every row — and it round-trips
 * through `LauncherApps`/`UserManager` without losing the user. [profileSerial] is the
 * `UserManager` serial number because a `UserHandle` is not stable across reboots.
 */
@Entity(
    tableName = "launcher_items",
    indices = [
        // Every workspace read is "the items in this container on this page".
        Index("containerId", "screen"),
        // Reconciliation and package events both look items up by target.
        Index("packageName", "profileSerial"),
        // Widget id lookups on host GC. Unique because two rows sharing an id would make
        // deleting one silently break the other's hosted view.
        Index(value = ["appWidgetId"], unique = true),
    ],
)
data class LauncherItemEntity(
    val itemType: LauncherItemType,

    /** Raw container sentinel. Read through [container], never directly. */
    val containerId: Long,

    /** Page index, for [ContainerRef.Desktop] only. Zero elsewhere. */
    val screen: Int = 0,

    val cellX: Int = 0,
    val cellY: Int = 0,
    val spanX: Int = 1,
    val spanY: Int = 1,

    /** Order within a folder or the hotseat. Unused on the desktop, where cells order. */
    val rank: Int = 0,

    /** Folder name, or a user-overridden label. Null means "use the app's own label". */
    val title: String? = null,

    val packageName: String? = null,
    val className: String? = null,

    /** `UserManager` serial number of the owning profile. 0 is the primary user. */
    val profileSerial: Long = 0,

    /** For [LauncherItemType.DEEP_SHORTCUT]. */
    val shortcutId: String? = null,

    /** Allocated by `AppWidgetHost`. Null for everything that is not a widget. */
    val appWidgetId: Int? = null,

    /** Flattened `ComponentName` of the widget provider. */
    val appWidgetProvider: String? = null,

    /**
     * Target exists but cannot be launched right now — a paused work profile, an app on
     * unmounted storage. Hidden rather than deleted so it comes back; see
     * [ReconcileUseCase].
     */
    @ColumnInfo(defaultValue = "0")
    val hidden: Boolean = false,

    @PrimaryKey(autoGenerate = true)
    override var id: Long = 0,
) : DatabaseItem {

    val container: ContainerRef get() = containerRefOf(containerId)

    val rect: CellRect get() = CellRect(cellX, cellY, spanX, spanY)

    val packageKey: PackageKey? get() = packageName?.let { PackageKey(it, profileSerial) }

    fun withRect(screen: Int, rect: CellRect): LauncherItemEntity = copy(
        screen = screen,
        cellX = rect.cellX,
        cellY = rect.cellY,
        spanX = rect.spanX,
        spanY = rect.spanY,
    )

    fun toGridItem(): GridItem = GridItem(id, screen, rect)

    fun toReconcileItem(): ReconcileUseCase.Item = ReconcileUseCase.Item(
        id = id,
        type = itemType,
        packageName = packageName,
        profileSerial = profileSerial,
        appWidgetId = appWidgetId,
        hidden = hidden,
        className = className,
    )
}

/**
 * Stores [LauncherItemType] as its name.
 *
 * The name rather than the ordinal: a column of readable strings survives someone
 * reordering the enum, which an ordinal column does not.
 */
class LauncherConverters {
    @ColumnTypeConverter
    fun fromItemType(value: LauncherItemType): String = value.name

    @ColumnTypeConverter
    fun toItemType(value: String): LauncherItemType = LauncherItemType.valueOf(value)
}
