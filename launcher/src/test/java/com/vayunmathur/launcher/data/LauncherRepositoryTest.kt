package com.vayunmathur.launcher.data

import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.launcher.domain.CellRect
import com.vayunmathur.launcher.domain.ContainerRef
import com.vayunmathur.launcher.domain.GridSpec
import com.vayunmathur.launcher.domain.LauncherItemType
import com.vayunmathur.launcher.domain.PackageKey
import com.vayunmathur.launcher.domain.toRaw
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [LauncherRepository] through the real singleton against a
 * Robolectric application context: add/move/hotseat/folder/remove flows,
 * seeding, reconciliation and regrid.
 */
@RunWith(RobolectricTestRunner::class)
class LauncherRepositoryTest {

    private val spec = GridSpec(columns = 4, rows = 4, hotseatSlots = 4)

    private fun repo(): LauncherRepository {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        return LauncherRepository.get(ctx)
    }

    private fun app(
        cellX: Int = 0,
        cellY: Int = 0,
        packageName: String = "com.example.app",
    ) = LauncherItemEntity(
        itemType = LauncherItemType.APPLICATION,
        containerId = ContainerRef.Desktop.toRaw(),
        cellX = cellX,
        cellY = cellY,
        packageName = packageName,
        className = "$packageName.Main",
    )

    @BeforeTest
    fun clearAll() = runTest {
        repo().getAll().forEach { repo().remove(it.id) }
        assertTrue(repo().isEmpty())
    }

    @Test fun addToFirstVacantCellPlacesOnDesktop() = runTest {
        val r = repo()
        val id = r.addToFirstVacantCell(spec, app(packageName = "com.example.one"))
        val got = r.get(id) ?: error("missing")
        assertEquals(ContainerRef.Desktop, got.container)
        assertEquals(0, got.screen)
    }

    @Test fun sequentialAddsFillDistinctCells() = runTest {
        val r = repo()
        val a = r.get(r.addToFirstVacantCell(spec, app(packageName = "com.example.a")))!!
        val b = r.get(r.addToFirstVacantCell(spec, app(packageName = "com.example.b")))!!
        assertTrue(a.cellX != b.cellX || a.cellY != b.cellY)
    }

    @Test fun seedPlacesAppsAndHotseat() = runTest {
        val r = repo()
        r.seed(
            spec,
            apps = listOf(app(packageName = "com.example.a"), app(packageName = "com.example.b")),
            hotseat = listOf(app(packageName = "com.example.h")),
        )
        val all = r.getAll()
        assertEquals(3, all.size)
        assertTrue(all.any { it.container == ContainerRef.Hotseat })
        // Seeding twice does not duplicate: the table is no longer empty.
        r.seed(spec, apps = listOf(app(packageName = "com.example.z")), hotseat = emptyList())
        assertEquals(3, r.getAll().size)
    }

    @Test fun moveToRelocates() = runTest {
        val r = repo()
        val id = r.upsert(app())
        r.moveTo(id, ContainerRef.Desktop, 1, CellRect(2, 2))
        val got = r.get(id) ?: error("missing")
        assertEquals(1, got.screen)
        assertEquals(2, got.cellX)
    }

    @Test fun moveToHotseatRanksAndEvictsOverflowToDesktop() = runTest {
        val r = repo()
        val ids = (0..4).map { r.upsert(app(packageName = "com.example.$it")) }
        ids.forEachIndexed { index, id -> r.moveToHotseat(id, index, spec) }
        val hotseat = r.getAll().filter { it.container == ContainerRef.Hotseat }
        assertEquals(spec.hotseatSlots, hotseat.size)
        assertEquals((0 until spec.hotseatSlots).toList(), hotseat.map { it.rank }.sorted())
        // The evicted item is back on the desktop, not lost.
        val evicted = r.get(ids.first { id -> hotseat.none { it.id == id } })
        assertNotNull(evicted)
        assertEquals(ContainerRef.Desktop, evicted!!.container)
    }

    @Test fun createFolderMergesTwoApps() = runTest {
        val r = repo()
        val target = r.upsert(app(cellX = 0, cellY = 0, packageName = "com.example.t"))
        val dragged = r.upsert(app(cellX = 1, cellY = 0, packageName = "com.example.d"))
        val folderId = r.createFolder(target, dragged) ?: error("no folder")
        val folder = r.get(folderId) ?: error("missing folder")
        assertEquals(LauncherItemType.FOLDER, folder.itemType)
        assertEquals(0, folder.cellX)
        val children = r.getFolderChildren(folderId)
        assertEquals(2, children.size)
        assertEquals(listOf(target, dragged), children.map { it.id })
    }

    @Test fun createFolderRefusesWidgets() = runTest {
        val r = repo()
        val target = r.upsert(app(packageName = "com.example.t"))
        val widget = r.upsert(
            LauncherItemEntity(
                itemType = LauncherItemType.APPWIDGET,
                containerId = ContainerRef.Desktop.toRaw(),
                appWidgetId = 7,
            ),
        )
        assertNull(r.createFolder(target, widget))
    }

    @Test fun addToFolderAndCollapseOnEmpty() = runTest {
        val r = repo()
        val target = r.upsert(app(packageName = "com.example.t"))
        val dragged = r.upsert(app(packageName = "com.example.d"))
        val folderId = r.createFolder(target, dragged) ?: error("no folder")
        val extra = r.upsert(app(packageName = "com.example.e"))
        r.addToFolder(folderId, extra)
        assertEquals(3, r.getFolderChildren(folderId).size)
        // Removing two children collapses the folder, promoting the survivor.
        r.remove(target)
        r.remove(dragged)
        assertNull(r.get(folderId))
        val survivor = r.get(extra) ?: error("survivor lost")
        assertEquals(ContainerRef.Desktop, survivor.container)
    }

    @Test fun reorderInFolderReranks() = runTest {
        val r = repo()
        val target = r.upsert(app(packageName = "com.example.t"))
        val dragged = r.upsert(app(packageName = "com.example.d"))
        val folderId = r.createFolder(target, dragged) ?: error("no folder")
        r.reorderInFolder(folderId, target, 1)
        val children = r.getFolderChildren(folderId)
        assertEquals(listOf(dragged, target), children.map { it.id })
    }

    @Test fun removeDeletesRowAndReturnsWidgetId() = runTest {
        val r = repo()
        val plain = r.upsert(app(packageName = "com.example.p"))
        assertEquals(null, r.remove(plain))
        assertNull(r.get(plain))
        val widget = r.upsert(
            LauncherItemEntity(
                itemType = LauncherItemType.APPWIDGET,
                containerId = ContainerRef.Desktop.toRaw(),
                appWidgetId = 11,
            ),
        )
        assertEquals(11, r.remove(widget))
    }

    @Test fun removeFolderDeletesChildren() = runTest {
        val r = repo()
        val target = r.upsert(app(packageName = "com.example.t"))
        val dragged = r.upsert(app(packageName = "com.example.d"))
        val folderId = r.createFolder(target, dragged) ?: error("no folder")
        r.remove(folderId)
        assertNull(r.get(folderId))
        assertNull(r.get(target))
        assertNull(r.get(dragged))
    }

    @Test fun reconcileDeletesUninstalledAndHidesUnavailable() = runTest {
        val r = repo()
        val gone = r.upsert(app(packageName = "com.example.gone"))
        val quiet = r.upsert(app(packageName = "com.example.quiet"))
        val here = PackageKey("com.example.here", 0)
        val quietKey = PackageKey("com.example.quiet", 0)
        r.upsert(app(packageName = "com.example.here"))
        r.reconcile(
            installed = setOf(here, quietKey),
            unavailable = setOf(quietKey),
            boundWidgetIds = emptySet(),
        )
        assertNull(r.get(gone))
        assertEquals(true, r.get(quiet)?.hidden)
    }

    @Test fun reconcileDeletesUnboundWidgets() = runTest {
        val r = repo()
        val widget = r.upsert(
            LauncherItemEntity(
                itemType = LauncherItemType.APPWIDGET,
                containerId = ContainerRef.Desktop.toRaw(),
                appWidgetId = 99,
            ),
        )
        val orphaned = r.reconcile(emptySet(), emptySet(), boundWidgetIds = setOf(1))
        assertEquals(listOf(99), orphaned)
        assertNull(r.get(widget))
    }

    @Test fun resizeToUpdatesSpan() = runTest {
        val r = repo()
        val id = r.upsert(app())
        r.resizeTo(id, CellRect(0, 0, 2, 2))
        assertEquals(2, r.get(id)?.spanX)
    }

    @Test fun setTitlePersists() = runTest {
        val r = repo()
        val id = r.upsert(app())
        r.setTitle(id, "Renamed")
        assertEquals("Renamed", r.get(id)?.title)
    }

    @Test fun usedWidgetIdsListsBound() = runTest {
        val r = repo()
        r.upsert(app(packageName = "com.example.p"))
        r.upsert(
            LauncherItemEntity(
                itemType = LauncherItemType.APPWIDGET,
                containerId = ContainerRef.Desktop.toRaw(),
                appWidgetId = 5,
            ),
        )
        assertEquals(setOf(5), r.usedWidgetIds())
    }

    @Test fun regridNarrowsHotseatOverflowToDesktop() = runTest {
        val r = repo()
        val ids = (0..3).map { r.upsert(app(packageName = "com.example.$it")) }
        ids.forEachIndexed { index, id -> r.moveToHotseat(id, index, spec) }
        assertEquals(4, r.getAll().count { it.container == ContainerRef.Hotseat })
        r.regrid(spec.copy(hotseatSlots = 2))
        val hotseat = r.getAll().filter { it.container == ContainerRef.Hotseat }
        assertEquals(2, hotseat.size)
        // Overflow items are back on the desktop.
        assertEquals(4, r.getAll().size)
    }
}
