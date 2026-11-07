package com.vayunmathur.launcher.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vayunmathur.launcher.domain.CellRect
import com.vayunmathur.launcher.domain.ContainerRef
import com.vayunmathur.launcher.domain.GridSpec
import com.vayunmathur.launcher.domain.LauncherItemType
import com.vayunmathur.launcher.domain.PackageKey
import com.vayunmathur.launcher.domain.toRaw
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device tests for [LauncherRepository]: the real database path needs
 * AndroidKeyStore (SQLCipher helper), which Robolectric cannot provide, so
 * these run instrumented. Covers add/move/hotseat/folder/remove, seeding,
 * reconciliation and regrid through the real singleton.
 */
@RunWith(AndroidJUnit4::class)
class LauncherRepositoryDeviceTest {

    private val spec = GridSpec(columns = 4, rows = 4, hotseatSlots = 4)

    private fun repo(): LauncherRepository {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return LauncherRepository.get(ctx)
    }

    private fun app(cellX: Int = 0, cellY: Int = 0, pkg: String = "com.example.app") =
        LauncherItemEntity(
            itemType = LauncherItemType.APPLICATION,
            containerId = ContainerRef.Desktop.toRaw(),
            cellX = cellX,
            cellY = cellY,
            packageName = pkg,
            className = "$pkg.Main",
        )

    private fun clean() = runTest {
        repo().getAll().forEach { repo().remove(it.id) }
    }

    @Test fun addMoveRemoveRoundTrip() = runTest {
        clean()
        val r = repo()
        assertTrue(r.isEmpty())
        val id = r.addToFirstVacantCell(spec, app(pkg = "com.example.one"))
        val got = r.get(id) ?: error("missing")
        assertEquals(ContainerRef.Desktop, got.container)
        r.moveTo(id, ContainerRef.Desktop, 1, CellRect(2, 2))
        assertEquals(1, r.get(id)?.screen)
        assertEquals(null, r.remove(id))
        assertNull(r.get(id))
    }

    @Test fun seedHotseatAndRegrid() = runTest {
        clean()
        val r = repo()
        r.seed(
            spec,
            apps = listOf(app(pkg = "com.example.a"), app(pkg = "com.example.b")),
            hotseat = listOf(
                app(pkg = "com.example.h0"),
                app(pkg = "com.example.h1"),
                app(pkg = "com.example.h2"),
                app(pkg = "com.example.h3"),
            ),
        )
        assertEquals(6, r.getAll().size)
        assertEquals(4, r.getAll().count { it.container == ContainerRef.Hotseat })
        // Narrowing the hotseat drops the tail back onto the desktop.
        r.regrid(spec.copy(hotseatSlots = 2))
        assertEquals(2, r.getAll().count { it.container == ContainerRef.Hotseat })
        assertEquals(6, r.getAll().size)
        clean()
    }

    @Test fun hotseatEvictsTailToDesktop() = runTest {
        clean()
        val r = repo()
        val ids = (0..4).map { r.upsert(app(pkg = "com.example.$it")) }
        ids.forEachIndexed { index, id -> r.moveToHotseat(id, index, spec) }
        val hotseat = r.getAll().filter { it.container == ContainerRef.Hotseat }
        assertEquals(spec.hotseatSlots, hotseat.size)
        assertEquals((0 until spec.hotseatSlots).toList(), hotseat.map { it.rank }.sorted())
        clean()
    }

    @Test fun folderMergeAddCollapse() = runTest {
        clean()
        val r = repo()
        val target = r.upsert(app(pkg = "com.example.t"))
        val dragged = r.upsert(app(pkg = "com.example.d"))
        val folderId = r.createFolder(target, dragged) ?: error("no folder")
        assertEquals(LauncherItemType.FOLDER, r.get(folderId)?.itemType)
        val extra = r.upsert(app(pkg = "com.example.e"))
        r.addToFolder(folderId, extra)
        assertEquals(3, r.getFolderChildren(folderId).size)
        r.reorderInFolder(folderId, target, 1)
        assertEquals(listOf(dragged, target, extra), r.getFolderChildren(folderId).map { it.id })
        // Removing two children collapses the folder, promoting the survivor.
        r.remove(target)
        r.remove(dragged)
        assertNull(r.get(folderId))
        assertEquals(ContainerRef.Desktop, r.get(extra)?.container)
        clean()
    }

    @Test fun widgetRemoveReturnsId() = runTest {
        clean()
        val r = repo()
        val widget = r.upsert(
            LauncherItemEntity(
                itemType = LauncherItemType.APPWIDGET,
                containerId = ContainerRef.Desktop.toRaw(),
                appWidgetId = 11,
            ),
        )
        assertEquals(11, r.remove(widget))
        assertEquals(setOf<Int>(), r.usedWidgetIds())
        r.upsert(
            LauncherItemEntity(
                itemType = LauncherItemType.APPWIDGET,
                containerId = ContainerRef.Desktop.toRaw(),
                appWidgetId = 5,
            ),
        )
        assertEquals(setOf(5), r.usedWidgetIds())
        r.setTitle(widget, "nope")
        clean()
    }

    @Test fun reconcileHidesAndDeletes() = runTest {
        clean()
        val r = repo()
        val gone = r.upsert(app(pkg = "com.example.gone"))
        val quiet = r.upsert(app(pkg = "com.example.quiet"))
        val here = PackageKey("com.example.here", 0)
        val quietKey = PackageKey("com.example.quiet", 0)
        r.upsert(app(pkg = "com.example.here"))
        r.reconcile(setOf(here, quietKey), setOf(quietKey), emptySet())
        assertNull(r.get(gone))
        assertEquals(true, r.get(quiet)?.hidden)
        assertNotNull(r.getAll())
        clean()
    }

    @Test fun regridRelocatesAcrossShapes() = runTest {
        clean()
        val r = repo()
        val id = r.addToFirstVacantCell(spec, app(pkg = "com.example.g"))
        // Narrower grid with fewer rows forces relocation and span clamping.
        r.regrid(GridSpec(columns = 2, rows = 2, hotseatSlots = 2))
        val got = r.get(id) ?: error("missing")
        assertEquals(true, got.cellX < 2)
        clean()
    }

    @Test fun reconcileCollapsesLonelyFolderChild() = runTest {
        clean()
        val r = repo()
        val target = r.upsert(app(pkg = "com.example.t"))
        val dragged = r.upsert(app(pkg = "com.example.d"))
        val folderId = r.createFolder(target, dragged) ?: error("no folder")
        // Both children vanish (uninstalled): reconcile deletes them, then
        // sweeps the emptied folder.
        r.reconcile(
            installed = emptySet(),
            unavailable = emptySet(),
            boundWidgetIds = emptySet(),
        )
        assertNull(r.get(folderId))
        assertNull(r.get(target))
        assertNull(r.get(dragged))
        clean()
    }

    @Test fun moveWithDisplacedCommitsTogether() = runTest {
        clean()
        val r = repo()
        val a = r.upsert(app(cellX = 0, cellY = 0, pkg = "com.example.a"))
        val b = r.upsert(app(cellX = 1, cellY = 0, pkg = "com.example.b"))
        // Drop A onto B's cell, shoving B aside in the same commit.
        r.moveTo(a, ContainerRef.Desktop, 0, CellRect(1, 0), displaced = mapOf(b to CellRect(2, 0)))
        assertEquals(1, r.get(a)?.cellX)
        assertEquals(2, r.get(b)?.cellX)
        clean()
    }

    @Test fun resizeWithDisplacedCommitsTogether() = runTest {
        clean()
        val r = repo()
        val id = r.upsert(app(pkg = "com.example.w"))
        val other = r.upsert(app(cellX = 3, cellY = 0, pkg = "com.example.o"))
        r.resizeTo(id, CellRect(0, 0, 2, 1), displaced = mapOf(other to CellRect(2, 0)))
        assertEquals(2, r.get(id)?.spanX)
        clean()
    }

    @Test fun resizeAndTitlePersist() = runTest {
        clean()
        val r = repo()
        val id = r.upsert(app())
        r.resizeTo(id, CellRect(0, 0, 2, 2))
        assertEquals(2, r.get(id)?.spanX)
        r.setTitle(id, "Renamed")
        assertEquals("Renamed", r.get(id)?.title)
        clean()
    }
}
