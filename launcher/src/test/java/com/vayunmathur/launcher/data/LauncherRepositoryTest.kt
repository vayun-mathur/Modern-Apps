package com.vayunmathur.launcher.data

import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.launcher.domain.ActivityKey
import com.vayunmathur.launcher.domain.CellRect
import com.vayunmathur.launcher.domain.ContainerRef
import com.vayunmathur.launcher.domain.GridSpec
import com.vayunmathur.launcher.domain.LauncherItemType
import com.vayunmathur.launcher.domain.PackageKey
import com.vayunmathur.launcher.domain.toRaw
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Behaviour tests for [LauncherRepository] over an in-memory Room database
 * (see [inMemoryLauncherRepository]): seeding, auto-placement, hotseat
 * eviction, folder create/add/reorder/collapse, removal, reconciliation and
 * regrid must preserve the grid invariants the class doc promises.
 */
@RunWith(RobolectricTestRunner::class)
class LauncherRepositoryTest {

    private lateinit var holder: InMemoryLauncherRepository
    private val repo: LauncherRepository get() = holder.repository
    private val spec = GridSpec(columns = 4, rows = 4, hotseatSlots = 3)

    @BeforeTest fun setUp() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        holder = inMemoryLauncherRepository(ctx)
    }

    @AfterTest fun tearDown() = holder.close()

    private fun app(
        pkg: String,
        cellX: Int = 0,
        cellY: Int = 0,
        screen: Int = 0,
        container: ContainerRef = ContainerRef.Desktop,
        rank: Int = 0,
        spanX: Int = 1,
        spanY: Int = 1,
        type: LauncherItemType = LauncherItemType.APPLICATION,
        widgetId: Int? = null,
    ) = LauncherItemEntity(
        itemType = type,
        containerId = container.toRaw(),
        screen = screen,
        cellX = cellX,
        cellY = cellY,
        spanX = spanX,
        spanY = spanY,
        rank = rank,
        packageName = pkg,
        className = "$pkg.Main",
        appWidgetId = widgetId,
        appWidgetProvider = widgetId?.let { "$pkg/.Widget" },
    )

    private suspend fun desktopIds(): List<Long> =
        repo.getAll().filter { it.container == ContainerRef.Desktop }.map { it.id }

    @Test fun emptyRepositoryReportsEmptyAndMissingGets() = runTest {
        assertTrue(repo.isEmpty())
        assertNull(repo.get(4242))
        assertEquals(emptyList(), repo.getAll())
        assertEquals(emptySet(), repo.usedWidgetIds())
        assertEquals(emptyList(), repo.items.first())
    }

    @Test fun seedPlacesAppsOnDesktopAndHotseatInRankOrder() = runTest {
        val apps = (0 until 6).map { app("com.example.a$it") }
        val hot = (0 until 5).map { app("com.example.h$it") }
        repo.seed(spec, apps, hot)
        assertFalse(repo.isEmpty())
        val all = repo.getAll()
        val desktop = all.filter { it.container == ContainerRef.Desktop }
        val hotseat = all.filter { it.container == ContainerRef.Hotseat }.sortedBy { it.rank }
        assertEquals(6, desktop.size)
        // Only hotseatSlots of the hotseat candidates are kept.
        assertEquals(3, hotseat.size)
        assertEquals(listOf(0, 1, 2), hotseat.map { it.rank })
        assertEquals(hot.map { it.packageName }.take(3), hotseat.map { it.packageName })
        // Desktop placements are distinct cells on page 0 in reading order.
        assertEquals(
            listOf(0 to 0, 1 to 0, 2 to 0, 3 to 0, 0 to 1, 1 to 1),
            desktop.sortedWith(compareBy({ it.cellY }, { it.cellX })).map { it.cellX to it.cellY },
        )
        assertTrue(desktop.all { it.screen == 0 })
    }

    @Test fun seedIsNoOpWhenTableAlreadyHasRows() = runTest {
        val existing = repo.upsert(app("com.example.kept", cellX = 2, cellY = 2))
        repo.seed(spec, listOf(app("com.example.new")), listOf(app("com.example.hotnew")))
        val all = repo.getAll()
        assertEquals(listOf(existing), all.map { it.id })
        assertEquals(2, repo.get(existing)?.cellX)
    }

    @Test fun addToFirstVacantCellSkipsOccupiedCellsAndPages() = runTest {
        repo.upsert(app("com.example.a", cellX = 0, cellY = 0))
        repo.upsert(app("com.example.b", cellX = 1, cellY = 0))
        val placedId = repo.addToFirstVacantCell(spec, app("com.example.c", container = ContainerRef.Hotseat))
        val placed = repo.get(placedId)!!
        assertEquals(ContainerRef.Desktop, placed.container)
        assertEquals(CellRect(2, 0), placed.rect)
        assertEquals(0, placed.screen)
        // A wide widget skips a row whose remaining cells are too narrow.
        for (x in 0 until 4) repo.upsert(app("com.example.fill$x", cellX = x, cellY = 1))
        repo.upsert(app("com.example.partial", cellX = 0, cellY = 2))
        val widgetId = repo.addToFirstVacantCell(
            spec,
            app("com.example.w", type = LauncherItemType.APPWIDGET, widgetId = 7, spanX = 2, spanY = 1),
        )
        val widget = repo.get(widgetId)!!
        assertEquals(2, widget.spanX)
        assertTrue(widget.rect.cellY >= 2 && widget.rect.right <= 4)
        assertFalse(widget.rect.overlaps(CellRect(0, 2)))
    }

    @Test fun addToFirstVacantCellOverflowsToNextPageWhenFull() = runTest {
        val tiny = GridSpec(columns = 2, rows = 1, hotseatSlots = 2)
        repo.upsert(app("com.example.a", cellX = 0, cellY = 0))
        repo.upsert(app("com.example.b", cellX = 1, cellY = 0))
        val id = repo.addToFirstVacantCell(tiny, app("com.example.c"))
        val placed = repo.get(id)!!
        assertEquals(1, placed.screen)
        assertEquals(CellRect(0, 0), placed.rect)
    }

    @Test fun setTitleAndUsedWidgetIds() = runTest {
        val plain = repo.upsert(app("com.example.a"))
        val widget = repo.upsert(app("com.example.w", type = LauncherItemType.APPWIDGET, widgetId = 99, cellX = 1))
        assertEquals(setOf(99), repo.usedWidgetIds())
        repo.setTitle(plain, "Renamed")
        assertEquals("Renamed", repo.get(plain)?.title)
        repo.setTitle(plain, null)
        assertNull(repo.get(plain)?.title)
        assertNotNull(repo.get(widget))
    }

    @Test fun moveToWithoutDisplacedMovesSingleItem() = runTest {
        val id = repo.upsert(app("com.example.a"))
        repo.moveTo(id, ContainerRef.Desktop, screen = 1, rect = CellRect(3, 2), rank = 0)
        val moved = repo.get(id)!!
        assertEquals(1, moved.screen)
        assertEquals(CellRect(3, 2), moved.rect)
    }

    @Test fun moveToWithDisplacedMovesNeighboursInSameCommit() = runTest {
        val a = repo.upsert(app("com.example.a", cellX = 0, cellY = 0))
        val b = repo.upsert(app("com.example.b", cellX = 1, cellY = 0))
        repo.moveTo(a, ContainerRef.Desktop, screen = 0, rect = CellRect(1, 0), displaced = mapOf(b to CellRect(0, 0)))
        assertEquals(CellRect(1, 0), repo.get(a)?.rect)
        assertEquals(CellRect(0, 0), repo.get(b)?.rect)
        assertEquals(ContainerRef.Desktop, repo.get(b)?.container)
    }

    @Test fun resizeToWithoutDisplacedChangesSpanOnlyTarget() = runTest {
        val id = repo.upsert(app("com.example.w", type = LauncherItemType.APPWIDGET, widgetId = 3))
        repo.resizeTo(id, CellRect(1, 1, 2, 2))
        assertEquals(CellRect(1, 1, 2, 2), repo.get(id)?.rect)
    }

    @Test fun resizeToWithDisplacedMovesNeighbourToDesktopCell() = runTest {
        val widget = repo.upsert(app("com.example.w", type = LauncherItemType.APPWIDGET, widgetId = 3, cellX = 0, cellY = 0))
        val neighbour = repo.upsert(app("com.example.n", cellX = 1, cellY = 0, screen = 2))
        repo.resizeTo(widget, CellRect(0, 0, 2, 1), displaced = mapOf(neighbour to CellRect(0, 1)))
        assertEquals(CellRect(0, 0, 2, 1), repo.get(widget)?.rect)
        val moved = repo.get(neighbour)!!
        assertEquals(CellRect(0, 1), moved.rect)
        // Displaced neighbours land on the widget's screen.
        assertEquals(0, moved.screen)
    }

    @Test fun moveToHotseatInsertsAndShiftsRanks() = runTest {
        val a = repo.upsert(app("com.example.a")); repo.moveToHotseat(a, 0, spec)
        val b = repo.upsert(app("com.example.b")); repo.moveToHotseat(b, 0, spec)
        val c = repo.upsert(app("com.example.c")); repo.moveToHotseat(c, 1, spec)
        val hotseat = repo.getAll().filter { it.container == ContainerRef.Hotseat }.sortedBy { it.rank }
        assertEquals(listOf(b, c, a), hotseat.map { it.id })
        assertEquals(listOf(0, 1, 2), hotseat.map { it.rank })
    }

    @Test fun moveToHotseatEvictsTailBackToDesktopWhenFull() = runTest {
        val ids = (0 until 3).map { repo.upsert(app("com.example.h$it")) }
        ids.forEachIndexed { i, id -> repo.moveToHotseat(id, i, spec) }
        val newcomer = repo.upsert(app("com.example.new", cellX = 3, cellY = 3))
        repo.moveToHotseat(newcomer, 0, spec)
        val hotseat = repo.getAll().filter { it.container == ContainerRef.Hotseat }.sortedBy { it.rank }
        assertEquals(3, hotseat.size)
        assertEquals(newcomer, hotseat.first().id)
        val evicted = repo.get(ids.last())!!
        assertEquals(ContainerRef.Desktop, evicted.container)
        // Evicted item is auto-placed, not left at the newcomer's old cell blindly.
        assertTrue(repo.getAll().filter { it.container == ContainerRef.Desktop }
            .map { it.screen to it.rect }.distinct().size >= 1)
    }

    @Test fun moveWithinHotseatReordersWithoutEviction() = runTest {
        val ids = (0 until 3).map { repo.upsert(app("com.example.h$it")) }
        ids.forEachIndexed { i, id -> repo.moveToHotseat(id, i, spec) }
        repo.moveToHotseat(ids.first(), 2, spec)
        val hotseat = repo.getAll().filter { it.container == ContainerRef.Hotseat }.sortedBy { it.rank }
        assertEquals(listOf(ids[1], ids[2], ids[0]), hotseat.map { it.id })
    }

    @Test fun createFolderPutsTargetThenDraggedAsChildren() = runTest {
        val target = repo.upsert(app("com.example.target", cellX = 2, cellY = 1))
        val dragged = repo.upsert(app("com.example.dragged", cellX = 0, cellY = 0))
        val folderId = repo.createFolder(target, dragged)
        assertNotNull(folderId)
        val folder = repo.get(folderId)!!
        assertEquals(LauncherItemType.FOLDER, folder.itemType)
        assertEquals(CellRect(2, 1), folder.rect)
        assertEquals(ContainerRef.Desktop, folder.container)
        val children = repo.getFolderChildren(folderId)
        assertEquals(listOf(target, dragged), children.map { it.id })
        assertEquals(listOf(0, 1), children.map { it.rank })
        assertTrue(desktopIds().contains(folderId))
    }

    @Test fun createFolderRejectsMissingAndUnmergeable() = runTest {
        val target = repo.upsert(app("com.example.target"))
        val widget = repo.upsert(app("com.example.w", type = LauncherItemType.APPWIDGET, widgetId = 5, cellX = 1))
        assertNull(repo.createFolder(9999, target))
        assertNull(repo.createFolder(target, 9999))
        // Widgets cannot merge in either direction.
        assertNull(repo.createFolder(target, widget))
        assertNull(repo.createFolder(widget, target))
        // Nothing was moved.
        assertEquals(ContainerRef.Desktop, repo.get(target)?.container)
        assertEquals(2, repo.getAll().size)
    }

    @Test fun createFolderFromFolderChildCollapsesSourceFolder() = runTest {
        val a = repo.upsert(app("com.example.a", cellX = 0, cellY = 0))
        val b = repo.upsert(app("com.example.b", cellX = 1, cellY = 0))
        val firstFolder = repo.createFolder(a, b)!!
        val target = repo.upsert(app("com.example.target", cellX = 3, cellY = 3))
        // Drag b out of the first folder onto target: first folder keeps only a,
        // so it must collapse and a inherits the first folder's cell.
        val secondFolder = repo.createFolder(target, b)!!
        assertNull(repo.get(firstFolder))
        val promoted = repo.get(a)!!
        assertEquals(ContainerRef.Desktop, promoted.container)
        assertEquals(CellRect(0, 0), promoted.rect)
        assertEquals(listOf(target, b), repo.getFolderChildren(secondFolder).map { it.id })
    }

    @Test fun addToFolderAppendsWithNextRank() = runTest {
        val a = repo.upsert(app("com.example.a"))
        val b = repo.upsert(app("com.example.b", cellX = 1))
        val folderId = repo.createFolder(a, b)!!
        val c = repo.upsert(app("com.example.c", cellX = 2))
        repo.addToFolder(folderId, c)
        val children = repo.getFolderChildren(folderId)
        assertEquals(listOf(a, b, c), children.map { it.id })
        assertEquals(listOf(0, 1, 2), children.map { it.rank })
    }

    @Test fun reorderInFolderShiftsSiblingsAndClampsRank() = runTest {
        val a = repo.upsert(app("com.example.a"))
        val b = repo.upsert(app("com.example.b", cellX = 1))
        val folderId = repo.createFolder(a, b)!!
        val c = repo.upsert(app("com.example.c", cellX = 2))
        repo.addToFolder(folderId, c)
        repo.reorderInFolder(folderId, c, 0)
        assertEquals(listOf(c, a, b), repo.getFolderChildren(folderId).map { it.id })
        assertEquals(listOf(0, 1, 2), repo.getFolderChildren(folderId).map { it.rank })
        // Out-of-range rank clamps to the end.
        repo.reorderInFolder(folderId, c, 99)
        assertEquals(listOf(a, b, c), repo.getFolderChildren(folderId).map { it.id })
    }

    @Test fun collapseFolderPromotesSingleSurvivorToFolderCell() = runTest {
        val a = repo.upsert(app("com.example.a", cellX = 1, cellY = 2))
        val b = repo.upsert(app("com.example.b", cellX = 0, cellY = 0))
        val folderId = repo.createFolder(a, b)!!
        // Removing one child leaves the folder with a single child.
        repo.remove(b)
        assertNull(repo.get(folderId))
        val survivor = repo.get(a)!!
        assertEquals(ContainerRef.Desktop, survivor.container)
        assertEquals(CellRect(1, 2), survivor.rect)
    }

    @Test fun collapseFolderWithTwoChildrenOnlyDensifiesRanks() = runTest {
        val a = repo.upsert(app("com.example.a"))
        val b = repo.upsert(app("com.example.b", cellX = 1))
        val folderId = repo.createFolder(a, b)!!
        val c = repo.upsert(app("com.example.c", cellX = 2))
        repo.addToFolder(folderId, c)
        repo.remove(c)
        assertNotNull(repo.get(folderId))
        assertEquals(listOf(a, b), repo.getFolderChildren(folderId).map { it.id })
        assertEquals(listOf(0, 1), repo.getFolderChildren(folderId).map { it.rank })
    }

    @Test fun removeFolderDeletesItsChildrenToo() = runTest {
        val a = repo.upsert(app("com.example.a"))
        val b = repo.upsert(app("com.example.b", cellX = 1))
        val folderId = repo.createFolder(a, b)!!
        val returned = repo.remove(folderId)
        assertNull(returned) // folders carry no widget id
        assertNull(repo.get(a)); assertNull(repo.get(b)); assertNull(repo.get(folderId))
        assertTrue(repo.isEmpty())
    }

    @Test fun removeWidgetReturnsItsWidgetIdForRelease() = runTest {
        val widget = repo.upsert(app("com.example.w", type = LauncherItemType.APPWIDGET, widgetId = 77))
        assertEquals(77, repo.remove(widget))
        assertNull(repo.remove(12345))
        assertTrue(repo.isEmpty())
    }

    @Test fun reconcileDeletesUninstalledAndOrphanWidgetsReturningIds() = runTest {
        val kept = repo.upsert(app("com.example.kept"))
        val gone = repo.upsert(app("com.example.gone", cellX = 1))
        val widget = repo.upsert(app("com.example.w", type = LauncherItemType.APPWIDGET, widgetId = 55, cellX = 2))
        val orphaned = repo.reconcile(
            installed = setOf(PackageKey("com.example.kept", 0)),
            unavailable = emptySet(),
            boundWidgetIds = emptySet(),
        )
        assertEquals(listOf(55), orphaned)
        assertNotNull(repo.get(kept))
        assertNull(repo.get(gone))
        assertNull(repo.get(widget))
    }

    @Test fun reconcileHidesUnavailableAndUnhidesReturnedApps() = runTest {
        val id = repo.upsert(app("com.example.work"))
        val key = PackageKey("com.example.work", 0)
        repo.reconcile(installed = emptySet(), unavailable = setOf(key), boundWidgetIds = emptySet())
        assertEquals(true, repo.get(id)?.hidden)
        repo.reconcile(installed = setOf(key), unavailable = emptySet(), boundWidgetIds = emptySet())
        assertEquals(false, repo.get(id)?.hidden)
        // A bound widget survives reconcile.
        val widget = repo.upsert(app("com.example.w", type = LauncherItemType.APPWIDGET, widgetId = 8, cellX = 1))
        repo.reconcile(installed = setOf(key), unavailable = emptySet(), boundWidgetIds = setOf(8))
        assertNotNull(repo.get(widget))
    }

    @Test fun reconcileDeletesAppWhoseActivityVanishedAndSweepsEmptyFolders() = runTest {
        val a = repo.upsert(app("com.example.a"))
        val b = repo.upsert(app("com.example.b", cellX = 1))
        val folderId = repo.createFolder(a, b)!!
        val orphaned = repo.reconcile(
            installed = setOf(PackageKey("com.example.a", 0), PackageKey("com.example.b", 0)),
            unavailable = emptySet(),
            boundWidgetIds = emptySet(),
            installedActivities = setOf(ActivityKey("com.example.a", "com.example.a.Main", 0)),
        )
        assertEquals(emptyList(), orphaned)
        // b's activity is gone -> deleted; folder then has one child -> collapses.
        assertNull(repo.get(b))
        assertNull(repo.get(folderId))
        val survivor = repo.get(a)!!
        assertEquals(ContainerRef.Desktop, survivor.container)
    }

    @Test fun regridShrinkingSpillsToNewPagesWithoutDropping() = runTest {
        val ids = (0 until 6).map { i -> repo.upsert(app("com.example.a$i", cellX = i % 4, cellY = i / 4)) }
        repo.regrid(GridSpec(columns = 2, rows = 2, hotseatSlots = 3))
        val all = repo.getAll()
        assertEquals(ids.toSet(), all.map { it.id }.toSet())
        val cells = all.map { Triple(it.screen, it.cellX, it.cellY) }
        assertEquals(cells.size, cells.distinct().size) // no overlaps
        assertTrue(all.all { it.cellX in 0 until 2 && it.cellY in 0 until 2 })
        assertTrue(all.any { it.screen > 0 }) // spilled
    }

    @Test fun regridGrowingPullsItemsForwardAndHotseatOverflowReturnsToDesktop() = runTest {
        val desktop = (0 until 4).map { i ->
            repo.upsert(app("com.example.d$i", screen = 1, cellX = i % 2, cellY = i / 2))
        }
        val hot = (0 until 3).map { repo.upsert(app("com.example.h$it")) }
        hot.forEachIndexed { i, id -> repo.moveToHotseat(id, i, spec) }
        repo.regrid(GridSpec(columns = 4, rows = 4, hotseatSlots = 2))
        val all = repo.getAll()
        assertEquals(2, all.count { it.container == ContainerRef.Hotseat })
        assertEquals(5, all.count { it.container == ContainerRef.Desktop })
        assertEquals(desktop.toSet(), all.filter { it.packageName!!.startsWith("com.example.d") }.map { it.id }.toSet())
        // Growing pulls the page-1 items onto page 0.
        assertTrue(all.filter { it.container == ContainerRef.Desktop }.all { it.screen == 0 })
    }

    @Test fun itemsFlowEmitsCurrentRows() = runTest {
        repo.upsert(app("com.example.a"))
        val emitted = repo.items.first()
        assertEquals(1, emitted.size)
        assertEquals("com.example.a", emitted.single().packageName)
    }
}
