package com.vayunmathur.launcher.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vayunmathur.launcher.domain.CellRect
import com.vayunmathur.launcher.domain.ContainerRef
import com.vayunmathur.launcher.domain.GridSpec
import com.vayunmathur.launcher.domain.LauncherItemType
import com.vayunmathur.launcher.domain.toRaw
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Extra on-device coverage for [LauncherRepository] paths the first device
 * suite does not reach: hotseat eviction, folder collapse, removal of a
 * folder with children, regrid relocation, seed idempotence and widget-id
 * bookkeeping. Runs against the real (encrypted) singleton, cleaning up
 * every row it creates.
 */
@RunWith(AndroidJUnit4::class)
class LauncherRepositoryExtraDeviceTest {

    private val spec = GridSpec(columns = 4, rows = 4, hotseatSlots = 2)

    private fun repo(): LauncherRepository {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return LauncherRepository.get(ctx)
    }

    private fun app(pkg: String, cellX: Int = 0, cellY: Int = 0, screen: Int = 0) =
        LauncherItemEntity(
            itemType = LauncherItemType.APPLICATION,
            containerId = ContainerRef.Desktop.toRaw(),
            screen = screen,
            cellX = cellX,
            cellY = cellY,
            packageName = pkg,
            className = "$pkg.Main",
        )

    private suspend fun clean() {
        repo().getAll().forEach { repo().remove(it.id) }
    }

    @Test
    fun hotseatEvictionReturnsEvictedToDesktop() = runTest {
        clean()
        val repo = repo()
        val first = repo.upsert(app("com.example.hs1"))
        val second = repo.upsert(app("com.example.hs2", cellX = 1))
        val third = repo.upsert(app("com.example.hs3", cellX = 2))
        repo.moveToHotseat(first, 0, spec)
        repo.moveToHotseat(second, 1, spec)
        // The hotseat has two slots; inserting a third evicts one to the desktop.
        repo.moveToHotseat(third, 0, spec)
        val hotseat = repo.getAll().filter { it.containerId == ContainerRef.Hotseat.toRaw() }
        assertEquals(2, hotseat.size)
        assertTrue(hotseat.any { it.id == third })
        val evicted = repo.getAll().filter { it.containerId == ContainerRef.Desktop.toRaw() }
        assertEquals(1, evicted.size)
        clean()
    }

    @Test
    fun folderLifecycleCollapseAndRemove() = runTest {
        clean()
        val repo = repo()
        val a = repo.upsert(app("com.example.fa", cellX = 0))
        val b = repo.upsert(app("com.example.fb", cellX = 1))
        val c = repo.upsert(app("com.example.fc", cellX = 2))
        val folderId = repo.createFolder(a, b)
        assertNotNull(folderId)
        repo.addToFolder(folderId!!, c)
        assertEquals(3, repo.getFolderChildren(folderId).size)

        // Reorder inside the folder: c goes first.
        val children = repo.getFolderChildren(folderId)
        repo.reorderInFolder(folderId, children.last().id, 0)
        val reordered = repo.getFolderChildren(folderId).sortedBy { it.rank }
        assertEquals(children.last().id, reordered.first().id)

        // Remove two children one at a time; removing the second-to-last
        // collapses the folder (remove does this itself), leaving the
        // survivor on the desktop at the folder's cell.
        val childIds = repo.getFolderChildren(folderId).map { it.id }
        repo.remove(childIds[0])
        assertEquals(2, repo.getFolderChildren(folderId).size)
        repo.remove(childIds[1])
        assertNull(repo.get(folderId))
        val survivors = repo.getAll()
        assertEquals(1, survivors.size)
        assertEquals(ContainerRef.Desktop.toRaw(), survivors.single().containerId)
        clean()
    }

    @Test
    fun removingAFolderRemovesItsChildren() = runTest {
        clean()
        val repo = repo()
        val a = repo.upsert(app("com.example.ra", cellX = 0))
        val b = repo.upsert(app("com.example.rb", cellX = 1))
        val folderId = repo.createFolder(a, b)!!
        repo.remove(folderId)
        assertTrue(repo.getAll().isEmpty())
        clean()
    }

    @Test
    fun moveWithDisplacementAndResizeWithDisplacement() = runTest {
        clean()
        val repo = repo()
        val a = repo.upsert(app("com.example.ma", cellX = 0, cellY = 0))
        val b = repo.upsert(app("com.example.mb", cellX = 1, cellY = 0))
        repo.moveTo(a, ContainerRef.Desktop, 0, CellRect(1, 0), displaced = mapOf(b to CellRect(0, 0)))
        assertEquals(1, repo.get(a)?.cellX)
        assertEquals(0, repo.get(b)?.cellX)

        val widget = repo.upsert(
            app("com.example.mw", cellX = 0, cellY = 2).copy(
                itemType = LauncherItemType.APPWIDGET,
                spanX = 2,
                spanY = 1,
                appWidgetId = 4242,
            ),
        )
        assertEquals(setOf(4242), repo.usedWidgetIds())
        val neighbour = repo.upsert(app("com.example.mn", cellX = 3, cellY = 2))
        repo.resizeTo(widget, CellRect(1, 2, spanX = 2, spanY = 1), displaced = mapOf(neighbour to CellRect(0, 3)))
        assertEquals(1, repo.get(widget)?.cellX)
        assertEquals(0, repo.get(neighbour)?.cellX)
        assertEquals(3, repo.get(neighbour)?.cellY)
        clean()
    }

    @Test
    fun seedIsIdempotentAndRegridKeepsItemsOnGrid() = runTest {
        clean()
        val repo = repo()
        val apps = listOf(app("com.example.sa"), app("com.example.sb", cellX = 1), app("com.example.sc", cellX = 3, cellY = 3))
        repo.seed(spec, apps, apps.take(1))
        val seeded = repo.getAll()
        assertTrue(seeded.isNotEmpty())
        repo.seed(spec, apps, apps.take(1))
        assertEquals(seeded.size, repo.getAll().size)

        // Shrink the grid: everything must land inside the smaller spec.
        repo.regrid(GridSpec(columns = 2, rows = 2, hotseatSlots = 2))
        val after = repo.getAll().filter { it.containerId == ContainerRef.Desktop.toRaw() }
        assertTrue(after.all { it.cellX in 0..1 && it.cellY in 0..1 })
        assertEquals(false, repo.isEmpty())
        clean()
        assertEquals(true, repo.isEmpty())
    }

    @Test
    fun createFolderWithMissingOrIncompatibleItemsFails() = runTest {
        clean()
        val repo = repo()
        val a = repo.upsert(app("com.example.xa", cellX = 0))
        assertNull(repo.createFolder(a, 987654L))
        assertNull(repo.createFolder(987654L, a))
        // A widget cannot merge into a folder.
        val widget = repo.upsert(
            app("com.example.xw", cellX = 1).copy(itemType = LauncherItemType.APPWIDGET, appWidgetId = 7),
        )
        assertNull(repo.createFolder(a, widget))
        clean()
    }

    @Test
    fun hotseatInsertShiftsRanksAndReorderWithinHotseat() = runTest {
        clean()
        val repo = repo()
        val spec4 = GridSpec(columns = 4, rows = 4, hotseatSlots = 4)
        val a = repo.upsert(app("com.example.ha", cellX = 0))
        val b = repo.upsert(app("com.example.hb", cellX = 1))
        val c = repo.upsert(app("com.example.hc", cellX = 2))
        repo.moveToHotseat(a, 0, spec4)
        repo.moveToHotseat(b, 1, spec4)
        // Insert c at the front: existing hotseat items shift rank.
        repo.moveToHotseat(c, 0, spec4)
        var hotseat = repo.getAll()
            .filter { it.containerId == ContainerRef.Hotseat.toRaw() }
            .sortedBy { it.rank }
        assertEquals(listOf(c, a, b), hotseat.map { it.id })
        // Move an item already in the hotseat to the end.
        repo.moveToHotseat(c, 2, spec4)
        hotseat = repo.getAll()
            .filter { it.containerId == ContainerRef.Hotseat.toRaw() }
            .sortedBy { it.rank }
        assertEquals(listOf(a, b, c), hotseat.map { it.id })
        clean()
    }

    @Test
    fun regridDropsHotseatOverflowToDesktop() = runTest {
        clean()
        val repo = repo()
        val spec4 = GridSpec(columns = 4, rows = 4, hotseatSlots = 4)
        val ids = (0..2).map { repo.upsert(app("com.example.o$it", cellX = it)) }
        ids.forEachIndexed { index, id -> repo.moveToHotseat(id, index, spec4) }
        assertEquals(
            3,
            repo.getAll().count { it.containerId == ContainerRef.Hotseat.toRaw() },
        )
        repo.regrid(GridSpec(columns = 4, rows = 4, hotseatSlots = 1))
        val after = repo.getAll()
        assertEquals(1, after.count { it.containerId == ContainerRef.Hotseat.toRaw() })
        assertEquals(2, after.count { it.containerId == ContainerRef.Desktop.toRaw() })
        clean()
    }

    @Test
    fun reconcileSweepsFoldersEmptiedByDeletions() = runTest {
        clean()
        val repo = repo()
        val a = repo.upsert(app("com.example.ea", cellX = 0))
        val b = repo.upsert(app("com.example.eb", cellX = 1))
        val folderId = repo.createFolder(a, b)!!
        // Both children uninstalled: the folder is swept too.
        repo.reconcile(
            installed = emptySet(),
            unavailable = emptySet(),
            boundWidgetIds = emptySet(),
        )
        assertTrue(repo.getAll().isEmpty())
        assertNull(repo.get(folderId))
        clean()
    }

    @Test
    fun reconcileHidesUnavailableInsteadOfDeleting() = runTest {
        clean()
        val repo = repo()
        val a = repo.upsert(app("com.example.ua", cellX = 0))
        repo.reconcile(
            installed = emptySet(),
            unavailable = setOf(
                com.vayunmathur.launcher.domain.PackageKey("com.example.ua", 0L),
            ),
            boundWidgetIds = emptySet(),
        )
        val kept = repo.get(a)
        assertNotNull(kept)
        assertEquals(true, kept!!.hidden)
        clean()
    }

    @Test
    fun reconcileDropsUninstalledAndKeepsInstalled() = runTest {
        clean()
        val repo = repo()
        val keep = repo.upsert(app("com.example.keep", cellX = 0))
        repo.upsert(app("com.example.gone", cellX = 1))
        val orphaned = repo.reconcile(
            installed = setOf(
                com.vayunmathur.launcher.domain.PackageKey("com.example.keep", 0L),
            ),
            unavailable = emptySet(),
            boundWidgetIds = emptySet(),
        )
        assertEquals(emptyList<Int>(), orphaned)
        val remaining = repo.getAll()
        assertEquals(listOf(keep), remaining.map { it.id })
        clean()
    }
}
