package com.vayunmathur.launcher.platform

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.vayunmathur.launcher.domain.toRaw
import com.vayunmathur.launcher.tv.platform.TvInputs
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Extra on-device coverage for the ViewModel/widget/TV-input paths that need
 * real packages: widget-picker enumeration over a populated app list,
 * launching a real app through the monitor, app-details, and TV input
 * listing/opening on the emulator.
 */
@RunWith(AndroidJUnit4::class)
class LauncherViewModelExtraDeviceTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val targetContext: Context = instrumentation.targetContext

    private fun app() = targetContext.applicationContext as android.app.Application

    private suspend fun awaitApps(vm: LauncherViewModel): List<AppEntry> {
        vm.appsMonitor.refresh()
        val deadline = android.os.SystemClock.uptimeMillis() + 15000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            val apps = vm.appsMonitor.apps.value
            if (apps.isNotEmpty()) return apps
            delay(200)
        }
        return vm.appsMonitor.apps.value
    }

    private fun back() {
        val device = UiDevice.getInstance(instrumentation)
        device.waitForIdle(2_000)
        device.pressBack()
        device.waitForIdle(2_000)
    }

    @Test
    fun widgetPickerEnumeratesRealProviders() = runTest {
        val vm = LauncherViewModel(app())
        val apps = awaitApps(vm)
        assertTrue(apps.isNotEmpty())
        vm.loadWidgetPicker()
        val deadline = android.os.SystemClock.uptimeMillis() + 10000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (!vm.widgetPicker.value.loading) break
            delay(100)
        }
        assertEquals(false, vm.widgetPicker.value.loading)
        // Whatever providers the emulator ships are grouped and queryable.
        val all = vm.widgetPicker.value.groups
        vm.applyWidgetQuery("clock")
        vm.applyWidgetQuery("")
        assertEquals(all, vm.widgetPicker.value.groups)
        vm.applyWidgetQuery("zzz-no-such-widget-zzz")
        assertTrue(vm.widgetPicker.value.groups.isEmpty())
    }

    @Test
    fun launchAndAppDetailsForARealApp() = runTest {
        val vm = LauncherViewModel(app())
        val apps = awaitApps(vm)
        assertTrue(apps.isNotEmpty())
        val entry = apps.first()

        // App-details opens the system settings page for the app; leave it.
        assertTrue(vm.appsMonitor.startAppDetails(entry, null))
        back()

        // Launching through the monitor starts the app; Back returns.
        assertTrue(vm.appsMonitor.launch(entry, null))
        back()

        // The shortcut query is role-gated; empty is a valid answer.
        val shortcuts = vm.appsMonitor.shortcuts(entry.componentName.packageName, entry.user)
        assertNotNull(shortcuts)
        if (shortcuts.isNotEmpty()) {
            vm.appsMonitor.shortcutIcon(shortcuts.first(), 320)
        }
        // Icon resolution for a real entry produces a badged drawable.
        assertNotNull(vm.appsMonitor.icon(entry, 320))
    }

    @Test
    fun launchAppThroughViewModelCountsTheLaunch() = runTest {
        val vm = LauncherViewModel(app())
        val apps = awaitApps(vm)
        assertTrue(apps.isNotEmpty())
        val entry = apps.first()
        val key = ComponentKey(entry.componentName, entry.profileSerial)
        val before = vm.launchCounts[key.componentName.flattenToShortString()] ?: 0L
        vm.launchApp(key, 0, 0, 10, 10)
        val after = vm.launchCounts[key.componentName.flattenToShortString()] ?: 0L
        assertEquals(before + 1, after)
        back()
    }

    @Test
    fun launchItemInfoUninstallAndGridForSettings() = runTest {
        val vm = LauncherViewModel(app())
        val apps = awaitApps(vm)
        assertTrue(apps.isNotEmpty())
        val entry = apps.first()
        val key = ComponentKey(entry.componentName, entry.profileSerial)
        val item = WorkspaceItem(
            id = 987001L,
            type = com.vayunmathur.launcher.domain.LauncherItemType.APPLICATION,
            label = entry.label,
            key = key,
        )

        // Launch a real app through the workspace-item path, then return.
        vm.launchImpl(item, 0, 0, 10, 10)
        back()

        // A hidden item refuses with a message and never launches.
        vm.launchImpl(item.copy(hidden = true), 0, 0, 10, 10)

        // App info opens the system details page; uninstall opens the
        // system confirmation for a (removable-by-nobody-here) entry.
        vm.openAppInfoImpl(item)
        back()
        vm.uninstallImpl(item)
        back()

        // Shortcut entry points with an unknown shortcut degrade safely.
        val shortcutEntry = ShortcutEntry("no-such-shortcut", "Nope", entry.componentName.packageName, entry.profileSerial)
        vm.launchShortcutImpl(shortcutEntry)
        vm.pinShortcutToHomeImpl(shortcutEntry)

        // The settings grid path regrids and publishes, then restore 4x4.
        vm.changeGridForSettings { it.copy(columns = 5, rows = 5) }
        val deadline = android.os.SystemClock.uptimeMillis() + 10000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (vm.home.value.grid.columns == 5) break
            delay(100)
        }
        assertEquals(5, vm.home.value.grid.columns)
        assertEquals(5, vm.settings.value.columns)
        vm.changeGridForSettings { it.copy(columns = 4, rows = 4) }
        val deadline2 = android.os.SystemClock.uptimeMillis() + 10000
        while (android.os.SystemClock.uptimeMillis() < deadline2) {
            if (vm.home.value.grid.columns == 4) break
            delay(100)
        }
        assertEquals(4, vm.home.value.grid.columns)
    }

    @Test
    fun workspaceFromMapsRowsToUiItems() = runTest {
        val vm = LauncherViewModel(app())
        fun entity(
            id: Long,
            pkg: String,
            container: Long,
            rank: Int = 0,
            type: com.vayunmathur.launcher.domain.LauncherItemType =
                com.vayunmathur.launcher.domain.LauncherItemType.APPLICATION,
            title: String? = null,
            screen: Int = 0,
            cellX: Int = 0,
        ) = com.vayunmathur.launcher.data.LauncherItemEntity(
            id = id,
            itemType = type,
            containerId = container,
            screen = screen,
            cellX = cellX,
            rank = rank,
            title = title,
            packageName = pkg,
            className = "$pkg.Main",
        )
        val desktop = com.vayunmathur.launcher.domain.ContainerRef.Desktop.toRaw()
        val hotseat = com.vayunmathur.launcher.domain.ContainerRef.Hotseat.toRaw()
        val folder = entity(10, "com.example.folder", desktop, type = com.vayunmathur.launcher.domain.LauncherItemType.FOLDER, title = "Folder")
        val childB = entity(12, "com.example.b", 10L, rank = 1)
        val childA = entity(11, "com.example.a", 10L, rank = 0)
        val widget = entity(13, "com.example.w", desktop, cellX = 1, type = com.vayunmathur.launcher.domain.LauncherItemType.APPWIDGET)
            .copy(appWidgetId = 99, appWidgetProvider = "com.example/.Widget")
        val plain = entity(14, "com.example.plain", desktop, screen = 1, cellX = 2)
        val hot2 = entity(16, "com.example.h2", hotseat, rank = 1)
        val hot1 = entity(15, "com.example.h1", hotseat, rank = 0)

        val (pages, hotseatItems) = vm.workspaceFromImpl(
            listOf(folder, childB, childA, widget, plain, hot2, hot1),
        )
        assertEquals(setOf(0, 1), pages.keys)
        val page0 = pages[0]!!
        val folderUi = page0.single { it.id == 10L }
        // Children come back in rank order regardless of row order.
        assertEquals(listOf(11L, 12L), folderUi.children.map { it.id })
        assertEquals("Folder", folderUi.label)
        val widgetUi = page0.single { it.id == 13L }
        assertEquals(99, widgetUi.appWidgetId)
        assertEquals(false, widgetUi.canUninstall)
        // Hotseat is a rank list.
        assertEquals(listOf(15L, 16L), hotseatItems.map { it.id })
        assertEquals(1, pages[1]!!.size)
    }

    @Test
    fun tvInputsListAndOpenOnEmulator() {
        val inputs = TvInputs.list(targetContext, 6)
        // The phone emulator may expose no HDMI inputs; opening a (possibly
        // bogus) input must still run its fallback chain without crashing.
        val id = inputs.firstOrNull()?.id ?: "passthrough:/dev/input/event0"
        TvInputs.open(targetContext, id)
        back()
    }
}
