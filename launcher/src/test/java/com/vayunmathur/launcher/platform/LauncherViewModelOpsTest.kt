package com.vayunmathur.launcher.platform

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.os.Bundle
import android.os.Looper
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.launcher.data.LauncherItemEntity
import com.vayunmathur.launcher.data.LauncherRepository
import com.vayunmathur.launcher.data.inMemoryLauncherRepository
import com.vayunmathur.launcher.data.swapLauncherRepositoryDb
import com.vayunmathur.launcher.data.InMemoryLauncherRepository
import com.vayunmathur.launcher.domain.CellRect
import com.vayunmathur.launcher.domain.ContainerRef
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
import kotlin.test.fail
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Ops tests for [LauncherViewModel]: the bodies behind the action holders
 * (LauncherHomeOps/LifecycleOps/MenuOps/Widgets/Predictions) run against an
 * in-memory repository swapped in for the encrypted one, so commit/merge/
 * settings/widget-picker paths assert their real persisted effects.
 */
@RunWith(RobolectricTestRunner::class)
class LauncherViewModelOpsTest {

    private lateinit var holder: InMemoryLauncherRepository
    private lateinit var vm: LauncherViewModel

    @BeforeTest fun setUp() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        holder = inMemoryLauncherRepository(app)
        // The ViewModel's init launches a workspace collector against its
        // repository immediately, so swap the (singleton) repository's db
        // for the in-memory database BEFORE constructing the ViewModel.
        swapLauncherRepositoryDb(LauncherRepository.get(app), holder.database)
        vm = LauncherViewModel(app)
        shadowOf(Looper.getMainLooper()).idle()
    }

    @AfterTest fun tearDown() {
        // Clear the ViewModel so its workspace collector and monitor stop
        // before the database closes; otherwise their coroutines outlive
        // the test and leak uncaught exceptions into sibling classes.
        runCatching {
            val cleared = androidx.lifecycle.ViewModel::class.java
                .getDeclaredMethod("onCleared").apply { isAccessible = true }
            cleared.invoke(vm)
        }
        shadowOf(Looper.getMainLooper()).idle()
        // Deliberately NOT holder.close(): this database is also the
        // LauncherRepository singleton's db (swapped in setUp), and other
        // test classes' ViewModels collect from it after this class ends.
    }

    private fun await(description: String, cond: () -> Boolean) {
        repeat(200) {
            shadowOf(Looper.getMainLooper()).idle()
            if (cond()) return
            Thread.sleep(10)
        }
        fail("timed out waiting for: $description (home=${vm.home.value}, drawer=${vm.drawer.value})")
    }

    private fun entity(
        pkg: String,
        container: ContainerRef = ContainerRef.Desktop,
        cellX: Int = 0,
        cellY: Int = 0,
        screen: Int = 0,
        rank: Int = 0,
        type: LauncherItemType = LauncherItemType.APPLICATION,
        title: String? = null,
        widgetId: Int? = null,
    ) = LauncherItemEntity(
        itemType = type,
        containerId = container.toRaw(),
        screen = screen,
        cellX = cellX,
        cellY = cellY,
        rank = rank,
        title = title,
        packageName = pkg,
        className = "$pkg.Main",
        appWidgetId = widgetId,
        appWidgetProvider = widgetId?.let { "$pkg/.Widget" },
    )

    private fun all(): List<LauncherItemEntity> =
        kotlinx.coroutines.runBlocking { holder.repository.getAll() }

    private fun getSync(id: Long): LauncherItemEntity? =
        kotlinx.coroutines.runBlocking { holder.repository.get(id) }

    private class FakeBridge : ActivityBridge {
        val uninstalls = mutableListOf<String>()
        var wallpaperBlur: Int? = null
        override fun requestBindWidget(appWidgetId: Int, provider: ComponentName, profileSerial: Long, onResult: (Boolean) -> Unit) = onResult(true)
        override fun startWidgetConfigure(appWidgetId: Int, onResult: (Boolean) -> Unit) = onResult(true)
        override fun pickWallpaper() {}
        override fun requestHomeRole() {}
        override fun isDefaultHome(): Boolean = false
        override fun requestUninstall(packageName: String) { uninstalls += packageName }
        override fun setWallpaperBlurRadius(radiusPx: Int) { wallpaperBlur = radiusPx }
        override fun launchAnimationOptions(left: Int, top: Int, right: Int, bottom: Int): Bundle? = null
    }

    /** Replaces the monitor's published app list (its LauncherApps source is empty under Robolectric). */
    @Suppress("UNCHECKED_CAST")
    private fun setMonitorApps(entries: List<AppEntry>) {
        val field = LauncherAppsMonitor::class.java.getDeclaredField("_apps").apply { isAccessible = true }
        (field.get(vm.appsMonitor) as kotlinx.coroutines.flow.MutableStateFlow<List<AppEntry>>).value = entries
    }

    private fun appEntry(pkg: String, label: String = pkg, work: Boolean = false) = AppEntry(
        componentName = ComponentName(pkg, "$pkg.Main"),
        label = label,
        user = Process.myUserHandle(),
        profileSerial = 0L,
        isWorkProfile = work,
    )

    private fun drawerApp(label: String, pkg: String, work: Boolean = false) = DrawerApp(
        key = ComponentKey(ComponentName(pkg, "$pkg.Main"), 0),
        label = label,
        isWorkProfile = work,
    )

    // ---- workspace mapping ----

    @Test fun workspaceFromGroupsPagesHotseatAndFolderChildren() {
        val folder = entity("com.example.folder", type = LauncherItemType.FOLDER, title = "Folder", cellX = 1)
        val desktop = entity("com.example.a", title = "Alpha", cellX = 0)
        val page2 = entity("com.example.b", title = "Beta", screen = 1, cellX = 0)
        val hot = entity("com.example.h", container = ContainerRef.Hotseat, rank = 1, title = "Hot")
        val items = kotlinx.coroutines.runBlocking {
            val repo = holder.repository
            val folderId = repo.upsert(folder)
            val childB = repo.upsert(entity("com.example.cb", container = ContainerRef.Folder(folderId), rank = 1, title = "ChildB"))
            val childA = repo.upsert(entity("com.example.ca", container = ContainerRef.Folder(folderId), rank = 0, title = "ChildA"))
            listOf(
                repo.get(repo.upsert(desktop))!!,
                repo.get(repo.upsert(page2))!!,
                repo.get(repo.upsert(hot))!!,
                repo.get(folderId)!!,
                repo.get(childA)!!,
                repo.get(childB)!!,
            )
        }
        val (pages, hotseat) = vm.workspaceFromImpl(items)
        assertEquals(setOf(0, 1), pages.keys)
        assertEquals(2, pages[0]?.size)
        assertEquals(1, pages[1]?.size)
        assertEquals(listOf("Hot"), hotseat.map { it.label })
        val folderItem = pages[0]!!.first { it.type == LauncherItemType.FOLDER }
        assertEquals(listOf("ChildA", "ChildB"), folderItem.children.map { it.label })
        val alpha = pages[0]!!.first { it.label == "Alpha" }
        assertEquals(ComponentName("com.example.a", "com.example.a.Main"), alpha.key?.componentName)
        assertFalse(alpha.canUninstall) // no installed entry under Robolectric
    }

    @Test fun labelForAndCanUninstallHandleMissingPieces() {
        val noClass = entity("com.example.a").copy(className = null)
        assertEquals("", vm.labelForImpl(noClass))
        assertFalse(vm.canUninstallImpl(noClass))
        assertFalse(vm.canUninstallImpl(entity("com.example.w", type = LauncherItemType.APPWIDGET, widgetId = 1)))
        assertFalse(vm.canUninstallImpl(entity("com.example.a"))) // not installed
        assertEquals("", vm.labelForImpl(entity("com.example.a")))
    }

    // ---- drawer query + predictions ----

    @Test fun applyDrawerQueryRanksPrefixBeforeContains() {
        vm.allApps = listOf(
            drawerApp("Vacation Planner", "com.example.vacation"),
            drawerApp("Calendar", "com.example.calendar"),
            drawerApp("Camera", "com.example.camera"),
            drawerApp("Notes", "com.example.notes"),
        )
        vm.applyDrawerQuery("ca")
        assertEquals(listOf("Calendar", "Camera", "Vacation Planner"), vm.drawer.value.apps.map { it.label })
        assertFalse(vm.drawer.value.loading)
        vm.applyDrawerQuery("")
        assertEquals(4, vm.drawer.value.apps.size)
        vm.applyDrawerQuery("zzz")
        assertEquals(emptyList(), vm.drawer.value.apps)
        assertEquals("zzz", vm.drawer.value.query)
    }

    @Test fun predictionsSortByCountExcludeWorkAndCapAtFive() {
        vm.allApps = (0 until 7).map { drawerApp("App$it", "com.example.a$it") } +
            listOf(drawerApp("WorkApp", "com.example.work", work = true))
        vm.launchCounts.clear()
        for (i in 0 until 7) {
            vm.launchCounts["com.example.a$i/.Main"] = (i + 1).toLong()
        }
        vm.launchCounts["com.example.work/.Main"] = 100L
        val predicted = vm.predictedApps()
        assertEquals(5, predicted.size)
        assertEquals("App6", predicted.first().label)
        assertTrue(predicted.none { it.isWorkProfile })
    }

    @Test fun countLaunchIncrementsAndRefreshesPredictions() {
        val app = drawerApp("Calendar", "com.example.calendar")
        vm.allApps = listOf(app)
        vm.launchCounts.clear()
        vm.countLaunch(app.key)
        vm.countLaunch(app.key)
        assertEquals(2L, vm.launchCounts["com.example.calendar/.Main"])
        assertEquals(listOf(app), vm.drawer.value.predictions)
    }

    // NOTE: a dedicated loadLaunchCounts parse test would need to seed the
    // persisted string first, but DataStore edits to the default store hang
    // under Robolectric in this suite (a zombie sandbox instance holds the
    // file lock). The parse path itself runs at every ViewModel init in
    // setUp, against whatever the shared store already holds.

    // ---- lifecycle ops ----

    @Test fun reconcileNowRefusesEmptyInstalledList() {
        runTest { holder.repository.upsert(entity("com.example.a", title = "A")) }
        vm.reconcileNowImpl()
        shadowOf(Looper.getMainLooper()).idle()
        // The row survives: reconcile must not run against an empty app list.
        assertEquals(1, all().size)
    }

    @Test fun onAppsChangedWithNoAppsKeepsDrawerEmptyAndDoesNotSeed() {
        vm.onAppsChangedImpl(PackageKey("com.example.a", 0))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(emptyList(), vm.drawer.value.apps)
        assertNull(vm.drawer.value.workPaused)
    }

    @Test fun refreshWorkProfileWithoutWorkAppLeavesPausedNull() {
        vm.refreshWorkProfileImpl()
        assertNull(vm.drawer.value.workPaused)
        assertFalse(vm.home.value.canExpandShade)
    }

    @Test fun loadPreferencesPublishesConsistentState() {
        // The DataStore is shared across tests in this class, so assert the
        // three states agree with each other rather than with the defaults.
        vm.loadPreferencesImpl()
        assertEquals(vm.settings.value.columns, vm.home.value.grid.columns)
        assertEquals(vm.settings.value.rows, vm.home.value.grid.rows)
        assertEquals(vm.settings.value.hotseatSlots, vm.home.value.grid.hotseatSlots)
        assertEquals(vm.settings.value.showLabels, vm.home.value.showLabels)
        assertEquals(vm.settings.value.iconScale, vm.home.value.iconScale)
        assertEquals(vm.settings.value.drawerListLayout, vm.drawer.value.listLayout)
        assertTrue(vm.home.value.grid.columns > 0 && vm.home.value.grid.rows > 0)
    }

    @Test fun isDefaultHomeMatchesManifestResolutionAndRefreshPublishesIt() {
        // The launcher manifest itself answers HOME, and Robolectric
        // resolves it to this package: the app IS its own default home here.
        assertTrue(vm.isDefaultHomeImpl())
        vm.refreshDefaultHomeImpl()
        assertTrue(vm.settings.value.isDefaultHome)
    }

    // ---- settings ops ----

    @Test fun showLabelsIconScaleAndListLayoutUpdateAllStates() {
        vm.setShowLabels(false)
        assertFalse(vm.settings.value.showLabels)
        assertFalse(vm.home.value.showLabels)
        assertFalse(vm.drawer.value.showLabels)
        vm.setIconScale(99f)
        assertEquals(LauncherViewModel.MAX_ICON_SCALE, vm.settings.value.iconScale)
        vm.setIconScale(0f)
        assertEquals(LauncherViewModel.MIN_ICON_SCALE, vm.settings.value.iconScale)
        vm.setDrawerListLayout(true)
        assertTrue(vm.settings.value.drawerListLayout)
        assertTrue(vm.drawer.value.listLayout)
    }

    @Test fun setColumnsRegridsRepositoryAndPublishesGrid() = runTest {
        holder.repository.upsert(entity("com.example.a", title = "A", cellX = 3, cellY = 3))
        vm.setColumns(4)
        await("columns published") { vm.settings.value.columns == 4 && vm.home.value.grid.columns == 4 }
        assertEquals(1, holder.repository.getAll().size) // row survives regrid
    }

    // ---- home ops through the action holders ----

    @Test fun commitMoveToDesktopAndHotseatPersistThroughHolder() = runTest {
        val id = holder.repository.upsert(entity("com.example.a", title = "A"))
        vm.commitMove(id, ContainerRef.Desktop, screen = 0, rect = CellRect(2, 2), rank = 0)
        await("desktop move") { getSync(id)?.rect == CellRect(2, 2) }
        vm.commitMove(id, ContainerRef.Hotseat, screen = 0, rect = CellRect(0, 0), rank = 0)
        await("hotseat move") { getSync(id)?.container == ContainerRef.Hotseat }
        assertEquals(0, getSync(id)?.rank)
    }

    @Test fun mergeIntoFolderCreatesThenGrowsFolder() = runTest {
        val a = holder.repository.upsert(entity("com.example.a", title = "A", cellX = 0))
        val b = holder.repository.upsert(entity("com.example.b", title = "B", cellX = 1))
        val c = holder.repository.upsert(entity("com.example.c", title = "C", cellX = 2))
        vm.savedItems = all()
        vm.mergeIntoFolder(a, b)
        await("folder created") {
            all().any { it.itemType == LauncherItemType.FOLDER }
        }
        val folder = all().first { it.itemType == LauncherItemType.FOLDER }
        vm.savedItems = all()
        // savedItems must carry the folder entity so merge routes to addToFolder.
        vm.mergeIntoFolder(folder.id, c)
        await("folder grew") { kotlinx.coroutines.runBlocking { holder.repository.getFolderChildren(folder.id).size } == 3 }
        assertEquals(setOf(a, b, c), kotlinx.coroutines.runBlocking { holder.repository.getFolderChildren(folder.id) }.map { it.id }.toSet())
    }

    @Test fun addPendingToHomeAndHotseatCreateRows() {
        val keyA = ComponentKey(ComponentName("com.example.newa", "com.example.newa.Main"), 0)
        vm.addPendingToHome(keyA, screen = 0, rect = CellRect(1, 1))
        await("pending home row") { all().any { it.packageName == "com.example.newa" } }
        val keyB = ComponentKey(ComponentName("com.example.newb", "com.example.newb.Main"), 0)
        vm.addPendingToHotseat(keyB, slot = 0)
        await("pending hotseat row") {
            all().any { it.packageName == "com.example.newb" && it.container == ContainerRef.Hotseat }
        }
    }

    @Test fun removeResizeAndRenamePersist() = runTest {
        val widget = holder.repository.upsert(
            entity("com.example.w", type = LauncherItemType.APPWIDGET, widgetId = 42, title = "W"),
        )
        val plain = holder.repository.upsert(entity("com.example.a", title = "A", cellX = 1))
        vm.resizeItem(plain, CellRect(1, 0, 2, 1))
        await("resize") { getSync(plain)?.rect == CellRect(1, 0, 2, 1) }
        vm.rename(plain, "Renamed")
        await("rename") { getSync(plain)?.title == "Renamed" }
        vm.remove(widget)
        await("remove") { getSync(widget) == null }
    }

    @Test fun launchHiddenAndUnknownItemsDoNotThrow() {
        // Init preloads any launch counts a sibling test persisted.
        vm.launchCounts.clear()
        val hidden = WorkspaceItem(id = 1, type = LauncherItemType.APPLICATION, label = "Hidden", hidden = true)
        vm.launch(hidden, 0, 0, 10, 10)
        val unknown = WorkspaceItem(
            id = 2, type = LauncherItemType.APPLICATION, label = "Ghost",
            key = ComponentKey(ComponentName("com.example.ghost", "com.example.ghost.Main"), 0),
        )
        vm.launch(unknown, 0, 0, 10, 10) // entry missing: message path, no crash
        vm.launchApp(unknown.key!!, 0, 0, 10, 10)
        assertTrue(vm.launchCounts.isEmpty()) // nothing actually launched
    }

    @Test fun uninstallAndInfoForUnknownIdsAreNoOps() = runTest {
        holder.repository.upsert(entity("com.example.a", title = "A"))
        vm.savedItems = all()
        vm.uninstallItem(9999)
        vm.openItemInfo(9999)
        val id = holder.repository.getAll().single().id
        vm.openItemInfo(id) // entry missing under Robolectric: returns quietly
        vm.uninstallItem(id) // no bridge attached: requestUninstall cannot fire, no crash
        assertEquals(1, holder.repository.getAll().size)
    }

    @Test fun wallpaperBlurAndShadeWithoutBridgeOrPrivilegeAreSafe() {
        vm.setWallpaperBlurred(true)
        vm.setWallpaperBlurred(false)
        vm.expandNotificationShade() // unprivileged: message path only
    }

    @Test fun entityBuildersCarryPackageClassAndProfile() {
        val entry = AppEntry(
            componentName = ComponentName("com.example.a", "com.example.a.Main"),
            label = "A",
            user = android.os.Process.myUserHandle(),
            profileSerial = 10L,
            isWorkProfile = true,
        )
        val fromApp = vm.entityForAppImpl(entry)
        assertEquals("com.example.a", fromApp.packageName)
        assertEquals(10L, fromApp.profileSerial)
        val fromKey = vm.entityForKeyImpl(ComponentKey(entry.componentName, 3L))
        assertEquals(3L, fromKey.profileSerial)
        assertEquals(LauncherItemType.APPLICATION, fromKey.itemType)
    }

    // ---- item menu ops ----

    @Test fun openItemMenuFindsWorkspaceAndFolderChildAndClearsForUnknown() = runTest {
        val childId = 31L
        val child = WorkspaceItem(id = childId, type = LauncherItemType.APPLICATION, label = "Kid")
        val folderItem = WorkspaceItem(
            id = 30L, type = LauncherItemType.FOLDER, label = "Folder", children = listOf(child),
        )
        val plain = WorkspaceItem(id = 32L, type = LauncherItemType.APPLICATION, label = "Plain")
        vm.homeState.value = HomeUiState(loading = false, pages = mapOf(0 to listOf(folderItem, plain)), hotseat = emptyList())
        val folderEntity = entity("com.example.folder", type = LauncherItemType.FOLDER, title = "Folder")
        val childEntity = entity("com.example.kid", title = "Kid")
        val plainEntity = entity("com.example.plain", title = "Plain")
        vm.savedItems = listOf(
            folderEntity.copy(id = 30L),
            childEntity.copy(id = childId),
            plainEntity.copy(id = 32L),
        )
        vm.openItemMenuImpl(childId)
        assertEquals(childId, vm.itemMenu.value.item?.id)
        assertEquals(emptyList(), vm.itemMenu.value.shortcuts)
        assertFalse(vm.itemMenu.value.canUninstall)
        vm.openItemMenuImpl(9999L)
        assertNull(vm.itemMenu.value.item)
    }

    @Test fun openAppInfoUninstallAndShortcutActionsForUnknownAreNoOps() {
        val item = WorkspaceItem(id = 5, type = LauncherItemType.APPLICATION, label = "X")
        vm.openAppInfo(item)
        vm.uninstall(item) // no key: returns
        vm.launchShortcut(ShortcutEntry("s1", "S", "com.example.a", 0))
        vm.openAppInfo(
            WorkspaceItem(
                id = 6, type = LauncherItemType.APPLICATION, label = "Y",
                key = ComponentKey(ComponentName("com.example.y", "com.example.y.Main"), 0),
            ),
        )
    }

    // ---- widget picker ops ----

    @Test fun widgetPickerLoadsEmptyAndQueryFiltersSeededGroups() {
        vm.openWidgetPicker()
        assertTrue(vm.widgetPicker.value.open)
        assertFalse(vm.widgetPicker.value.loading)
        assertEquals(emptyList(), vm.widgetPicker.value.groups)
        val groups = listOf(
            WidgetGroup("Calendar", listOf(
                WidgetEntry("com.example.cal/.Widget", "Month view"),
                WidgetEntry("com.example.cal/.Widget2", "Agenda"),
            )),
            WidgetGroup("Clock", listOf(WidgetEntry("com.example.clock/.Widget", "Analog"))),
        )
        vm.applyWidgetQuery("month", groups)
        assertEquals(listOf("Calendar"), vm.widgetPicker.value.groups.map { it.appLabel })
        assertEquals(listOf("Month view"), vm.widgetPicker.value.groups.single().widgets.map { it.label })
        vm.applyWidgetQuery("calendar") // group-label match keeps whole group
        assertEquals(2, vm.widgetPicker.value.groups.single().widgets.size)
        vm.applyWidgetQuery("")
        assertEquals(2, vm.widgetPicker.value.groups.size)
        vm.applyWidgetQuery("zzz")
        assertEquals(emptyList(), vm.widgetPicker.value.groups)
        vm.closeWidgetPicker()
        assertFalse(vm.widgetPicker.value.open)
    }

    @Test fun addWidgetWithoutBridgeOrProviderIsSafe() {
        vm.addWidget(WidgetEntry("not a component", "Nope"))
        val entry = WidgetEntry("com.example.gone/.Widget", "Gone")
        vm.addWidget(entry) // no bridge: returns before touching the flow
        assertNull(vm.hostedWidgetView(12345))
        assertNull(vm.appIcon(ComponentKey(ComponentName("com.example.a", "com.example.a.Main"), 0)))
        assertNull(vm.widgetPreview("com.example.gone/.Widget", 0))
        assertNull(vm.shortcutIcon("com.example.a", "s1", 0))
    }

    @Test fun seedIfEmptySeedsFromMonitorApps() {
        setMonitorApps((0 until 7).map { appEntry("com.example.s$it", label = "Seed$it") })
        vm.seedIfEmptyImpl()
        await("seeded rows") { all().isNotEmpty() }
        // First row takes up to grid columns of non-work apps on the desktop.
        assertTrue(all().any { it.container == ContainerRef.Desktop })
        assertTrue(all().all { it.itemType == LauncherItemType.APPLICATION })
    }

    @Test fun reconcileNowDeletesRowsForUninstalledApps() = runTest {
        val gone = holder.repository.upsert(entity("com.example.gone", title = "Gone"))
        holder.repository.upsert(entity("com.example.kept", title = "Kept", cellX = 1))
        setMonitorApps(listOf(appEntry("com.example.kept")))
        vm.reconcileNowImpl()
        await("gone reconciled away") { getSync(gone) == null }
        assertEquals(1, all().size)
    }

    @Test fun commitMoveOutOfFolderCollapsesFolder() = runTest {
        val a = holder.repository.upsert(entity("com.example.a", title = "A", cellX = 0))
        val b = holder.repository.upsert(entity("com.example.b", title = "B", cellX = 1))
        val folderId = kotlinx.coroutines.runBlocking { holder.repository.createFolder(a, b) }!!
        vm.savedItems = all()
        vm.commitMove(a, ContainerRef.Desktop, screen = 0, rect = CellRect(3, 3), rank = 0)
        await("folder collapsed") { getSync(folderId) == null }
        // The dragged child lands where dropped; the survivor inherits the folder cell.
        assertEquals(CellRect(3, 3), getSync(a)?.rect)
        assertEquals(ContainerRef.Desktop, getSync(b)?.container)
        assertEquals(CellRect(0, 0), getSync(b)?.rect)
    }

    @Test fun reorderInFolderThroughHolder() = runTest {
        val a = holder.repository.upsert(entity("com.example.a", title = "A", cellX = 0))
        val b = holder.repository.upsert(entity("com.example.b", title = "B", cellX = 1))
        val c = holder.repository.upsert(entity("com.example.c", title = "C", cellX = 2))
        val folderId = kotlinx.coroutines.runBlocking {
            val id = holder.repository.createFolder(a, b)!!
            holder.repository.addToFolder(id, c)
            id
        }
        vm.reorderInFolder(folderId, c, 0)
        await("folder reordered") {
            kotlinx.coroutines.runBlocking { holder.repository.getFolderChildren(folderId).first().id } == c
        }
    }

    @Test fun addWidgetEntryWithBridgePersistsWidgetRow() {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        val manager = AppWidgetManager.getInstance(app)
        val shadow = org.robolectric.Shadows.shadowOf(manager)
        val info = AppWidgetProviderInfo().apply {
            provider = ComponentName("com.example", "com.example.Widget")
            minWidth = 100; minHeight = 100
        }
        shadow.addInstalledProvider(info)
        shadow.addInstalledProvidersForProfile(Process.myUserHandle(), info)
        shadow.setAllowedToBindAppWidgets(true)
        setMonitorApps(listOf(appEntry("com.example")))
        vm.bridge = FakeBridge()
        vm.addWidget(WidgetEntry(provider = "com.example/com.example.Widget", label = "Widget", spanX = 2, spanY = 1))
        await("widget row persisted") {
            all().any { it.itemType == LauncherItemType.APPWIDGET && it.appWidgetProvider == "com.example/com.example.Widget" }
        }
        val row = all().first { it.itemType == LauncherItemType.APPWIDGET }
        assertEquals(2, row.spanX)
        assertNotNull(row.appWidgetId)
        // The bridge also receives wallpaper-blur calls through the holder path.
        val bridge = vm.bridge as FakeBridge
        vm.setWallpaperBlurred(true)
        assertEquals(WALLPAPER_BLUR_PX, bridge.wallpaperBlur)
    }

    @Test fun onLocaleChangedClearsIconsWithoutThrowing() {
        vm.onLocaleChangedImpl()
    }
}
