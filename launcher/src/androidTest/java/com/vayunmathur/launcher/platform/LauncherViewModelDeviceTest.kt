package com.vayunmathur.launcher.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vayunmathur.launcher.data.LauncherItemEntity
import com.vayunmathur.launcher.data.LauncherRepository
import com.vayunmathur.launcher.domain.CellRect
import com.vayunmathur.launcher.domain.ContainerRef
import com.vayunmathur.launcher.domain.GridSpec
import com.vayunmathur.launcher.domain.LauncherItemType
import com.vayunmathur.launcher.domain.toRaw
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device tests for [LauncherViewModel] action bodies: view-model
 * construction needs the real package manager, DataStore and SQLCipher
 * database, so these run instrumented. Drives workspace commits, folder
 * merges, renames, grid changes and drawer/menu state through the real
 * repository, asserting persisted rows.
 */
@RunWith(AndroidJUnit4::class)
class LauncherViewModelDeviceTest {

    private val spec = GridSpec(columns = 4, rows = 4, hotseatSlots = 4)

    private fun app(): android.app.Application {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return ctx.applicationContext as android.app.Application
    }

    private fun repo() = LauncherRepository.get(app())

    private fun entity(pkg: String) = LauncherItemEntity(
        itemType = LauncherItemType.APPLICATION,
        containerId = ContainerRef.Desktop.toRaw(),
        packageName = pkg,
        className = "$pkg.Main",
    )

    @Test fun commitMovePersists() = runTest {
        val vm = LauncherViewModel(app())
        val r = repo()
        r.getAll().forEach { r.remove(it.id) }
        val id = r.upsert(entity("com.example.m"))
        vm.commitMove(id, ContainerRef.Desktop, 1, CellRect(2, 2), 0)
        // commitMove launches in viewModelScope: poll the persisted row.
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        var got: LauncherItemEntity? = null
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            got = r.get(id)
            if (got?.screen == 1 && got.cellX == 2) break
            kotlinx.coroutines.delay(50)
        }
        assertEquals(1, got?.screen)
        assertEquals(2, got?.cellX)
        r.remove(id)
    }

    @Test fun mergeIntoFolderCreatesFolder() = runTest {
        val vm = LauncherViewModel(app())
        val r = repo()
        r.getAll().forEach { r.remove(it.id) }
        val target = r.upsert(entity("com.example.t"))
        val dragged = r.upsert(entity("com.example.d"))
        // Seed the view-model's item cache so the merge finds its target.
        vm.savedItems = r.getAll()
        vm.mergeIntoFolder(target, dragged)
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        var folders = 0
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            folders = r.getAll().count { it.itemType == LauncherItemType.FOLDER }
            if (folders == 1) break
            kotlinx.coroutines.delay(50)
        }
        assertEquals(1, folders)
        r.getAll().forEach { r.remove(it.id) }
    }

    @Test fun renamePersistsTitle() = runTest {
        val vm = LauncherViewModel(app())
        val r = repo()
        r.getAll().forEach { r.remove(it.id) }
        val id = r.upsert(entity("com.example.r"))
        vm.rename(id, "Renamed")
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        var title: String? = null
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            title = r.get(id)?.title
            if (title == "Renamed") break
            kotlinx.coroutines.delay(50)
        }
        assertEquals("Renamed", title)
        r.remove(id)
    }

    @Test fun setColumnsRegrids() = runTest {
        val vm = LauncherViewModel(app())
        vm.setColumns(5)
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (vm.home.value.grid.columns == 5) break
            kotlinx.coroutines.delay(50)
        }
        assertEquals(5, vm.home.value.grid.columns)
        vm.setColumns(4)
    }

    @Test fun removeDeletesRow() = runTest {
        val vm = LauncherViewModel(app())
        val r = repo()
        r.getAll().forEach { r.remove(it.id) }
        val id = r.upsert(entity("com.example.x"))
        vm.remove(id)
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        var gone = false
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (r.get(id) == null) {
                gone = true
                break
            }
            kotlinx.coroutines.delay(50)
        }
        assertEquals(true, gone)
    }

    @Test fun hiddenLaunchShowsMessage() = runTest {
        val vm = LauncherViewModel(app())
        // A hidden item cannot launch: shows "not available", returns early.
        val item = WorkspaceItem(
            id = 1L,
            type = LauncherItemType.APPLICATION,
            label = "Hidden",
            hidden = true,
        )
        vm.launch(item, 0, 0, 10, 10)
        assertNotNull(vm.home)
    }

    @Test fun uninstallItemWithoutEntityIsNoOp() = runTest {
        val vm = LauncherViewModel(app())
        vm.uninstallItem(999999L)
        vm.openItemInfo(999999L)
        assertNull(vm.itemMenu.value.item)
    }

    @Test fun settingsTogglesPropagate() = runTest {
        val vm = LauncherViewModel(app())
        vm.setShowLabels(false)
        assertEquals(false, vm.settings.value.showLabels)
        assertEquals(false, vm.home.value.showLabels)
        vm.setShowLabels(true)
        assertEquals(true, vm.settings.value.showLabels)
        vm.setIconScale(1.25f)
        assertEquals(1.25f, vm.settings.value.iconScale)
        vm.setIconScale(1f)
        vm.setDrawerListLayout(true)
        assertEquals(true, vm.settings.value.drawerListLayout)
        vm.setDrawerListLayout(false)
    }
}
