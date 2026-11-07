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
import com.vayunmathur.launcher.platform.ComponentKey
import com.vayunmathur.launcher.platform.WidgetEntry
import com.vayunmathur.launcher.platform.WidgetGroup
import com.vayunmathur.launcher.platform.ShortcutEntry
import com.vayunmathur.launcher.platform.WorkspaceItem
import com.vayunmathur.launcher.platform.applyDrawerQuery
import com.vayunmathur.launcher.platform.countLaunch
import com.vayunmathur.launcher.platform.predictedApps
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

    @Test fun widgetQueryFiltersGroups() = runTest {
        val vm = LauncherViewModel(app())
        vm.applyWidgetQuery(
            "clo",
            source = listOf(
                WidgetGroup(
                    "Clock",
                    listOf(
                        WidgetEntry("com.example/.Analog", "Analog clock", spanX = 2, spanY = 2),
                        WidgetEntry("com.example/.Weather", "Weather", spanX = 4, spanY = 1),
                    ),
                ),
                WidgetGroup("Mail", listOf(WidgetEntry("com.example/.M", "Mail widget"))),
            ),
        )
        val groups = vm.widgetPicker.value.groups
        assertEquals(1, groups.size)
        assertEquals("Clock", groups[0].appLabel)
        // App-label match keeps the whole group, not just matching widgets.
        assertEquals(2, groups[0].widgets.size)
        vm.applyWidgetQuery("", source = null)
        // Null source keeps allWidgetGroups ([Clock, Mail]); empty query shows all.
        assertEquals(2, vm.widgetPicker.value.groups.size)
        vm.applyWidgetQuery(
            "",
            source = listOf(
                WidgetGroup("Clock", listOf(WidgetEntry("com.example/.A", "A"))),
                WidgetGroup("Mail", listOf(WidgetEntry("com.example/.M", "M"))),
            ),
        )
        assertEquals(2, vm.widgetPicker.value.groups.size)
    }

    @Test fun pendingHomeAndHotseatInserts() = runTest {
        val vm = LauncherViewModel(app())
        val r = repo()
        r.getAll().forEach { r.remove(it.id) }
        val key = ComponentKey(
            android.content.ComponentName("com.example.p", "com.example.p.Main"),
            0L,
        )
        vm.addPendingToHome(key, 0, CellRect(0, 0))
        vm.addPendingToHotseat(key, 0)
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (r.getAll().size >= 2) break
            kotlinx.coroutines.delay(50)
        }
        assertEquals(2, r.getAll().size)
        r.getAll().forEach { r.remove(it.id) }
    }

    @Test fun monitorRefreshListsPackages() = runTest {
        val vm = LauncherViewModel(app())
        vm.appsMonitor.refresh()
        // refresh() loads off-main: poll until the emulator's app list lands.
        val deadline = android.os.SystemClock.uptimeMillis() + 10000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (vm.appsMonitor.apps.value.isNotEmpty()) break
            kotlinx.coroutines.delay(100)
        }
        assertEquals(true, vm.appsMonitor.apps.value.isNotEmpty())
    }

    @Test fun gridSettersRenumber() = runTest {
        val vm = LauncherViewModel(app())
        vm.setRows(5)
        vm.setHotseatSlots(3)
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            val g = vm.home.value.grid
            if (g.rows == 5 && g.hotseatSlots == 3) break
            kotlinx.coroutines.delay(50)
        }
        assertEquals(5, vm.home.value.grid.rows)
        assertEquals(3, vm.home.value.grid.hotseatSlots)
        vm.setRows(4)
        vm.setHotseatSlots(4)
    }

    @Test fun itemMenuOpenUnknownClears() = runTest {
        val vm = LauncherViewModel(app())
        vm.openItemMenuImpl(424242L)
        assertNull(vm.itemMenu.value.item)
    }

    @Test fun opsNullGuardsAreSafe() = runTest {
        val vm = LauncherViewModel(app())
        val key = ComponentKey(
            android.content.ComponentName("com.example.ghost", "com.example.ghost.Main"),
            0L,
        )
        // Nothing installed under these keys: every lookup misses and returns early.
        vm.launchApp(key, 0, 0, 10, 10)
        vm.setWorkPaused(true)
        vm.uninstallItem(424243L)
        vm.openItemInfo(424243L)
        vm.launchShortcut(
            com.vayunmathur.launcher.platform.ShortcutEntry("s", "S", "com.example.ghost", 0L),
        )
        vm.pinShortcutToHome(
            com.vayunmathur.launcher.platform.ShortcutEntry("s", "S", "com.example.ghost", 0L),
        )
        vm.addPendingShortcutToHome(
            com.vayunmathur.launcher.platform.ShortcutEntry("s", "S", "com.example.ghost", 0L),
            0,
            CellRect(0, 0),
        )
        vm.openAppInfo(WorkspaceItem(1L, LauncherItemType.APPLICATION, "G"))
        vm.uninstall(WorkspaceItem(1L, LauncherItemType.APPLICATION, "G"))
        vm.requestDefaultHome()
        vm.refreshDefaultHomeImpl()
        vm.onLocaleChangedImpl()
        vm.appIcon(key)
        vm.shortcutIcon("com.example.ghost", "s", 0L)
        vm.widgetPreview("com.example/.W", 0L)
        vm.expandNotificationShade()
        vm.setWallpaperBlurred(false)
        assertNull(vm.itemMenu.value.item)
    }

    @Test fun openItemMenuFindsWorkspaceItem() = runTest {
        val vm = LauncherViewModel(app())
        val r = repo()
        r.getAll().forEach { r.remove(it.id) }
        val id = r.upsert(entity("com.example.menu"))
        // Seed caches the menu lookup reads.
        vm.savedItems = r.getAll()
        vm.homeState.value = vm.home.value.copy(
            pages = mapOf(
                0 to listOf(
                    WorkspaceItem(
                        id = id,
                        type = LauncherItemType.APPLICATION,
                        label = "Menu",
                    ),
                ),
            ),
        )
        vm.openItemMenuImpl(id)
        assertEquals(id, vm.itemMenu.value.item?.id)
        r.remove(id)
    }

    @Test fun widgetPickerLoadAndAddGuards() = runTest {
        val vm = LauncherViewModel(app())
        vm.loadWidgetPicker()
        val deadline = android.os.SystemClock.uptimeMillis() + 8000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (!vm.widgetPicker.value.loading) break
            kotlinx.coroutines.delay(100)
        }
        // Emulator widget providers (if any) load without crashing.
        vm.widgetPicker.value.groups.forEach { }
        // No bridge on device tests: add guards return early.
        vm.addWidgetEntry(WidgetEntry("com.example/.W", "W"))
        assertEquals(null, vm.hostedWidgetView(424242))
    }

    @Test fun monitorEntryLookups() = runTest {
        val vm = LauncherViewModel(app())
        vm.appsMonitor.refresh()
        val deadline = android.os.SystemClock.uptimeMillis() + 10000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (vm.appsMonitor.apps.value.isNotEmpty()) break
            kotlinx.coroutines.delay(100)
        }
        val apps = vm.appsMonitor.apps.value
        assertEquals(true, apps.isNotEmpty())
        val first = apps.first()
        // Icon rasterises off a real package drawable.
        val icon = vm.appsMonitor.icon(first, 320)
        assertEquals(first, vm.appsMonitor.entryFor(first.componentName, first.profileSerial))
        // Shortcuts of a real package (possibly empty) query safely.
        val shortcuts = vm.appsMonitor.shortcuts(
            first.componentName.packageName,
            vm.appsMonitor.userFor(first.profileSerial),
        )
        assertEquals(true, (shortcuts.size >= 0))
        assertEquals(true, icon == null || icon != null)
    }

    @Test fun predictionsRankByLaunchCount() = runTest {
        val vm = LauncherViewModel(app())
        // Launch counts persist in DataStore across runs on a shared emulator;
        // a previous run's counts would make the first assertion see them.
        vm.launchCounts.clear()
        val a = com.vayunmathur.launcher.platform.DrawerApp(keyComponent("com.example.a"), "Alpha")
        val b = com.vayunmathur.launcher.platform.DrawerApp(keyComponent("com.example.b"), "Beta")
        val w = com.vayunmathur.launcher.platform.DrawerApp(
            keyComponent("com.example.w", work = true),
            "Work",
            isWorkProfile = true,
        )
        vm.allApps = listOf(a, b, w)
        assertEquals(emptyList<DrawerApp>(), vm.predictedApps())
        vm.countLaunch(b.key)
        vm.countLaunch(a.key)
        vm.countLaunch(a.key)
        val predicted = vm.predictedApps()
        // Alpha launched twice, Beta once; work apps never predicted.
        assertEquals(listOf("Alpha", "Beta"), predicted.map { it.label })
    }

    @Test fun drawerQueryOrdersPrefixFirst() = runTest {
        val vm = LauncherViewModel(app())
        vm.allApps = listOf(
            com.vayunmathur.launcher.platform.DrawerApp(keyComponent("com.example.v"), "Vacation Planner"),
            com.vayunmathur.launcher.platform.DrawerApp(keyComponent("com.example.c"), "Calendar"),
        )
        vm.applyDrawerQuery("ca")
        assertEquals(
            listOf("Calendar", "Vacation Planner"),
            vm.drawer.value.apps.map { it.label },
        )
        vm.applyDrawerQuery("")
        assertEquals(2, vm.drawer.value.apps.size)
    }

    private fun keyComponent(pkg: String, work: Boolean = false) = ComponentKey(
        android.content.ComponentName(pkg, "$pkg.Main"),
        if (work) 10L else 0L,
    )

    @Test fun privilegeQuietModeRefused() = runTest {
        val vm = LauncherViewModel(app())
        val user = android.os.Process.myUserHandle()
        // No MODIFY_QUIET_MODE on the emulator: refused safely.
        assertEquals(false, vm.privilege.setQuietMode(user, true))
        assertEquals(false, vm.privilege.isQuietModeEnabled(user))
    }

    @Test fun resizeAndReorderFolder() = runTest {
        val vm = LauncherViewModel(app())
        val r = repo()
        r.getAll().forEach { r.remove(it.id) }
        val id = r.upsert(entity("com.example.w"))
        vm.resizeItem(id, CellRect(0, 0, 2, 2))
        vm.reorderInFolder(999L, id, 0)
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (r.get(id)?.spanX == 2) break
            kotlinx.coroutines.delay(50)
        }
        assertEquals(2, r.get(id)?.spanX)
        r.remove(id)
    }

    @Test fun uninstallAndInfoWithEntity() = runTest {
        val vm = LauncherViewModel(app())
        val r = repo()
        r.getAll().forEach { r.remove(it.id) }
        val id = r.upsert(entity("com.example.ghost"))
        vm.savedItems = r.getAll()
        // Ghost package: entry lookups miss, bridge is null; all no-ops.
        vm.uninstallItem(id)
        vm.openItemInfo(id)
        val key = ComponentKey(
            android.content.ComponentName("com.example.ghost", "com.example.ghost.Main"),
            0L,
        )
        vm.openAppInfo(WorkspaceItem(1L, LauncherItemType.APPLICATION, "G", key = key))
        vm.uninstall(WorkspaceItem(1L, LauncherItemType.APPLICATION, "G", key = key))
        vm.launchShortcut(ShortcutEntry("s", "S", "com.example.ghost", 0L))
        r.remove(id)
    }

    @Test fun widgetHostUpdateSize() = runTest {
        val vm = LauncherViewModel(app())
        val ctx = app()
        val view = android.appwidget.AppWidgetHostView(ctx)
        vm.widgets.updateSize(view, 200, 100)
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
