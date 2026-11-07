package com.vayunmathur.launcher.data

import android.content.Context
import androidx.room3.withWriteTransaction
import com.vayunmathur.launcher.domain.ActivityKey
import com.vayunmathur.launcher.domain.AutoPlacer
import com.vayunmathur.launcher.domain.CellRect
import com.vayunmathur.launcher.domain.ContainerRef
import com.vayunmathur.launcher.domain.FolderRules
import com.vayunmathur.launcher.domain.GridItem
import com.vayunmathur.launcher.domain.GridSpec
import com.vayunmathur.launcher.domain.HotseatArrange
import com.vayunmathur.launcher.domain.LauncherItemType
import com.vayunmathur.launcher.domain.PackageKey
import com.vayunmathur.launcher.domain.PagedRect
import com.vayunmathur.launcher.domain.ReconcileUseCase
import com.vayunmathur.launcher.domain.toRaw
import com.vayunmathur.library.room.RoomRepository
import kotlinx.coroutines.flow.Flow

/**
 * Single owner of the workspace layout.
 *
 * Everything above this — the ViewModel, the drag controller's commit, the package
 * receiver, the widget host's garbage collection — reads and writes through here, so the
 * grid invariants (no two items in one cell, no folder with one child, no widget row
 * without a bound id) are enforced in one place.
 *
 * Writes that must not be seen half-done are wrapped in [withWriteTransaction]: creating a
 * folder moves two items and inserts a third, and the workspace flow re-emits promptly
 * enough that an intermediate state would render.
 */
class LauncherRepository private constructor(context: Context) :
    RoomRepository<LauncherDatabase>(context, LauncherDatabase::class, DB_NAME) {

    private val dao: LauncherItemDao get() = db.itemDao()

    val items: Flow<List<LauncherItemEntity>> get() = dao.getAllFlow()

    suspend fun isEmpty(): Boolean = dao.count() == 0

    suspend fun get(id: Long): LauncherItemEntity? = dao.get(id)

    suspend fun getAll(): List<LauncherItemEntity> = dao.getAll()

    suspend fun getFolderChildren(folderId: Long): List<LauncherItemEntity> =
        dao.getFolderChildren(folderId)

    suspend fun usedWidgetIds(): Set<Int> = dao.getUsedWidgetIds().toSet()

    suspend fun setTitle(id: Long, title: String?) = dao.setTitle(id, title)

    /**
     * Writes a new span and origin together, with any neighbours the resize shoved aside.
     *
     * One transaction rather than several, because dragging a widget's left or top edge changes both
     * its origin and its span, and a workspace re-emit between the writes would render the widget
     * half-resized, or resized on top of a neighbour that has not moved yet.
     */
    suspend fun resizeTo(id: Long, rect: CellRect, displaced: Map<Long, CellRect> = emptyMap()) {
        if (displaced.isEmpty()) {
            dao.resizeTo(id, rect.cellX, rect.cellY, rect.spanX, rect.spanY)
            return
        }
        val screen = dao.get(id)?.screen ?: 0
        db.withWriteTransaction {
            displaced.forEach { (other, to) ->
                dao.move(other, ContainerRef.Desktop.toRaw(), screen, to.cellX, to.cellY, 0)
            }
            dao.resizeTo(id, rect.cellX, rect.cellY, rect.spanX, rect.spanY)
        }
    }

    // ------------------------------------------------------------------
    // Adding
    // ------------------------------------------------------------------

    /** Inserts [item] at the first free desktop cell that fits its span. */
    suspend fun addToFirstVacantCell(spec: GridSpec, item: LauncherItemEntity): Long {
        val existing = dao.getDesktopItems().map { PagedRect(it.screen, it.rect) }
        val target = AutoPlacer.place(spec, existing, item.rect)
        return dao.upsert(
            item.copy(containerId = ContainerRef.Desktop.toRaw()).withRect(target.screen, target.rect),
        )
    }

    suspend fun upsert(item: LauncherItemEntity): Long = dao.upsert(item)

    /**
     * Seeds the first run.
     *
     * Called only when the table is empty, so a user who deliberately empties their home
     * screen does not get it refilled on the next launch.
     */
    suspend fun seed(spec: GridSpec, apps: List<LauncherItemEntity>, hotseat: List<LauncherItemEntity>) {
        db.withWriteTransaction {
            if (dao.count() > 0) return@withWriteTransaction
            val placements = AutoPlacer.placeAll(spec, emptyList(), apps.map { it.rect })
            dao.upsertAll(
                apps.mapIndexed { index, app ->
                    val at = placements[index]
                    app.copy(containerId = ContainerRef.Desktop.toRaw()).withRect(at.screen, at.rect)
                },
            )
            dao.upsertAll(
                hotseat.take(spec.hotseatSlots).mapIndexed { index, app ->
                    app.copy(containerId = ContainerRef.Hotseat.toRaw(), rank = index)
                },
            )
        }
    }

    // ------------------------------------------------------------------
    // Moving
    // ------------------------------------------------------------------

    /**
     * Commits a finished drag, together with any neighbours the drop pushed aside.
     *
     * One statement when nothing was displaced, so the workspace flow emits once; a transaction
     * when something was, so it still emits once and never with the item moved but its neighbours
     * left behind — which the page would render as a jump.
     */
    suspend fun moveTo(
        id: Long,
        container: ContainerRef,
        screen: Int,
        rect: CellRect,
        rank: Int = 0,
        displaced: Map<Long, CellRect> = emptyMap(),
    ) {
        if (displaced.isEmpty()) {
            dao.move(id, container.toRaw(), screen, rect.cellX, rect.cellY, rank)
            return
        }
        db.withWriteTransaction {
            // Displaced items are neighbours on the same page, so only their cell changes.
            displaced.forEach { (other, to) ->
                dao.move(other, ContainerRef.Desktop.toRaw(), screen, to.cellX, to.cellY, 0)
            }
            dao.move(id, container.toRaw(), screen, rect.cellX, rect.cellY, rank)
        }
    }

    /**
     * Moves [id] into the hotseat at [slot].
     *
     * The hotseat is a rank list rather than a cell grid, so an insert renumbers instead of finding
     * a hole — and once the row is full, something has to give. [HotseatArrange] decides what;
     * whatever it is goes back onto the desktop rather than nowhere, because the alternative is a
     * row the database still holds and the screen never draws.
     */
    suspend fun moveToHotseat(id: Long, slot: Int, spec: GridSpec) {
        db.withWriteTransaction {
            val plan = HotseatArrange.arrange(
                current = dao.getHotseatItems().map { it.id },
                id = id,
                toRank = slot,
                slots = spec.hotseatSlots,
            )
            plan.evicted?.let { evicted ->
                val occupied = dao.getDesktopItems().map { PagedRect(it.screen, it.rect) }
                val at = AutoPlacer.place(spec, occupied, CellRect(0, 0))
                dao.move(
                    evicted,
                    ContainerRef.Desktop.toRaw(),
                    at.screen,
                    at.rect.cellX,
                    at.rect.cellY,
                    0,
                )
            }
            plan.ranks.forEach { (item, rank) ->
                if (item == id) {
                    dao.move(id, ContainerRef.Hotseat.toRaw(), 0, 0, 0, rank)
                } else {
                    dao.setRank(item, rank)
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Folders
    // ------------------------------------------------------------------

    /**
     * Turns two items into a folder in [target]'s cell.
     *
     * The folder takes the target's exact placement so the icon does not appear to jump,
     * and both items become children ranked in drop order — target first, since it was
     * already there.
     */
    suspend fun createFolder(targetId: Long, draggedId: Long): Long? = db.withWriteTransaction {
        val target = dao.get(targetId) ?: return@withWriteTransaction null
        val dragged = dao.get(draggedId) ?: return@withWriteTransaction null
        if (!FolderRules.canMerge(dragged.itemType, target.itemType)) return@withWriteTransaction null

        val folderId = dao.upsert(
            LauncherItemEntity(
                itemType = LauncherItemType.FOLDER,
                containerId = target.containerId,
                screen = target.screen,
                cellX = target.cellX,
                cellY = target.cellY,
                rank = target.rank,
                title = FolderRules.DEFAULT_FOLDER_TITLE,
            ),
        )
        dao.move(target.id, folderId, 0, 0, 0, 0)
        dao.move(dragged.id, folderId, 0, 0, 0, 1)
        folderId
    }

    /** Appends [itemId] to an existing folder. */
    suspend fun addToFolder(folderId: Long, itemId: Long) {
        db.withWriteTransaction {
            val ranks = dao.getFolderChildren(folderId).map { it.rank }
            dao.move(itemId, folderId, 0, 0, 0, FolderRules.nextRank(ranks))
        }
    }

    /** Moves [itemId] to [rank] within its folder, shifting the siblings it passes. */
    suspend fun reorderInFolder(folderId: Long, itemId: Long, rank: Int) {
        db.withWriteTransaction {
            val others = dao.getFolderChildren(folderId).filter { it.id != itemId }
            val reordered = others.toMutableList()
            val index = rank.coerceIn(0, reordered.size)
            dao.setRank(itemId, index)
            reordered.forEachIndexed { position, child ->
                val next = if (position >= index) position + 1 else position
                if (child.rank != next) dao.setRank(child.id, next)
            }
        }
    }

    /**
     * Renumbers a folder's children to dense ranks, and collapses the folder if too few are left.
     *
     * Called *after* whatever removed a child — a delete, or a move out onto the grid — so it
     * simply reads what remains rather than being told what went. The last child inherits the
     * folder's own cell rather than being auto-placed, which is what makes dragging one of two
     * apps out of a folder look like the folder turning back into the other app.
     */
    suspend fun collapseFolderIfNeeded(folderId: Long) {
        db.withWriteTransaction {
            val remaining = dao.getFolderChildren(folderId)
            FolderRules.denseRanks(remaining.map { it.id }).forEach { (id, rank) ->
                dao.setRank(id, rank)
            }
            if (!FolderRules.shouldCollapse(remaining.size)) return@withWriteTransaction

            val folder = dao.get(folderId) ?: return@withWriteTransaction
            remaining.singleOrNull()?.let { survivor ->
                dao.move(
                    survivor.id,
                    folder.containerId,
                    folder.screen,
                    folder.cellX,
                    folder.cellY,
                    folder.rank,
                )
            }
            dao.deleteById(folderId)
        }
    }

    // ------------------------------------------------------------------
    // Removal
    // ------------------------------------------------------------------

    /**
     * Deletes an item, and the folder that contained it if that leaves it too small.
     *
     * Returns the widget id the caller must hand back to `AppWidgetHost`; the repository
     * cannot call `deleteAppWidgetId` itself without dragging a platform dependency into
     * the data layer, and leaking the id means the provider keeps being updated for a
     * widget nobody can see.
     */
    suspend fun remove(id: Long): Int? = db.withWriteTransaction {
        val item = dao.get(id) ?: return@withWriteTransaction null
        val container = item.container
        // Deleting a folder takes its children with it - they have no cell of their own to
        // fall back to, so leaving them would strand rows nothing can reach.
        if (item.itemType == LauncherItemType.FOLDER) {
            dao.getFolderChildren(id).forEach { dao.deleteById(it.id) }
        }
        dao.deleteById(id)
        if (container is ContainerRef.Folder) {
            collapseFolderIfNeeded(container.id)
        }
        item.appWidgetId
    }

    // ------------------------------------------------------------------
    // Reconciliation and regrid
    // ------------------------------------------------------------------

    /**
     * Applies [ReconcileUseCase] to the saved rows, and returns the widget ids of any rows
     * it deleted so the caller can release them on the host.
     */
    suspend fun reconcile(
        installed: Set<PackageKey>,
        unavailable: Set<PackageKey>,
        boundWidgetIds: Set<Int>,
        unavailableProfiles: Set<Long> = emptySet(),
        installedActivities: Set<ActivityKey> = emptySet(),
    ): List<Int> = db.withWriteTransaction {
        val all = dao.getAll()
        val actions = ReconcileUseCase.reconcile(
            items = all.map { it.toReconcileItem() },
            installed = installed,
            unavailable = unavailable,
            boundWidgetIds = boundWidgetIds,
            unavailableProfiles = unavailableProfiles,
            installedActivities = installedActivities,
        )
        val byId = all.associateBy { it.id }
        val orphanedWidgetIds = mutableListOf<Int>()
        for (action in actions) {
            when (action) {
                is ReconcileUseCase.Action.Delete -> {
                    byId[action.id]?.appWidgetId?.let(orphanedWidgetIds::add)
                    dao.deleteById(action.id)
                }
                is ReconcileUseCase.Action.SetHidden -> dao.setHidden(action.id, action.hidden)
            }
        }
        // Folders can be emptied by the deletions above, so they are swept afterwards
        // rather than judged alongside their children.
        collapseEmptyFolders()
        orphanedWidgetIds
    }

    /** Re-lays out the desktop for a new grid shape. */
    suspend fun regrid(to: GridSpec) {
        db.withWriteTransaction {
            val desktop = dao.getDesktopItems()
            val moved = AutoPlacer.regrid(desktop.map { it.toGridItem() }, to)
            val before = desktop.associateBy { it.id }
            for (item in moved) {
                moveIfRelocated(before[item.id], item)
            }
            // The hotseat is a rank list, so a narrower one drops the tail back onto the
            // desktop rather than renumbering into slots that no longer exist.
            val hotseat = dao.getHotseatItems()
            if (hotseat.size > to.hotseatSlots) {
                val overflow = hotseat.drop(to.hotseatSlots)
                val occupied = dao.getDesktopItems().map { PagedRect(it.screen, it.rect) }.toMutableList()
                for (item in overflow) {
                    val at = AutoPlacer.place(to, occupied, CellRect(0, 0))
                    occupied.add(at)
                    dao.move(item.id, ContainerRef.Desktop.toRaw(), at.screen, at.rect.cellX, at.rect.cellY, 0)
                }
            }
        }
    }

    private suspend fun moveIfRelocated(original: LauncherItemEntity?, item: GridItem) {
        if (original == null) return
        if (original.screen == item.screen && original.rect == item.rect) return
        dao.move(
            item.id,
            original.containerId,
            item.screen,
            item.rect.cellX,
            item.rect.cellY,
            original.rank,
        )
        if (original.rect.spanX == item.rect.spanX && original.rect.spanY == item.rect.spanY) return
        dao.resizeTo(item.id, item.rect.cellX, item.rect.cellY, item.rect.spanX, item.rect.spanY)
    }

    private suspend fun collapseEmptyFolders() {
        val folders = dao.getAll().filter { it.itemType == LauncherItemType.FOLDER }
        for (folder in folders) {
            val children = dao.getFolderChildren(folder.id)
            if (!FolderRules.shouldCollapse(children.size)) continue
            children.singleOrNull()?.let { survivor ->
                dao.move(
                    survivor.id,
                    folder.containerId,
                    folder.screen,
                    folder.cellX,
                    folder.cellY,
                    folder.rank,
                )
            }
            dao.deleteById(folder.id)
        }
    }

    companion object {
        /**
         * Explicit, because [RoomRepository]'s default is `"passwords-db"` — a default that
         * predates it being shared and that `clock` still inherits by accident.
         */
        private const val DB_NAME = "launcher-db"

        @Volatile
        private var instance: LauncherRepository? = null

        fun get(context: Context): LauncherRepository =
            instance ?: synchronized(this) {
                instance ?: LauncherRepository(context).also { instance = it }
            }
    }
}
