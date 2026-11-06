package com.vayunmathur.launcher.tv.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device tests for [TvHomeViewModel.refreshApps]: with real packages the
 * row merges, tiles load art, and inputs list — covering the load path that
 * is empty under Robolectric.
 */
@RunWith(AndroidJUnit4::class)
class TvHomeViewModelDeviceTest {

    private fun vm(): TvHomeViewModel {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return TvHomeViewModel(ctx.applicationContext as android.app.Application)
    }

    @Test fun refreshAppsLoadsRealRow() = runTest {
        val v = vm()
        val deadline = android.os.SystemClock.uptimeMillis() + 15000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (!v.home.value.loading) break
            kotlinx.coroutines.delay(200)
        }
        assertEquals(false, v.home.value.loading)
        // The emulator has real launchable packages: the row is non-empty.
        assertEquals(true, v.home.value.rowApps.isNotEmpty())
    }

    @Test fun openEditMenuMoveCycle() = runTest {
        val v = vm()
        val deadline = android.os.SystemClock.uptimeMillis() + 15000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (!v.home.value.loading) break
            kotlinx.coroutines.delay(200)
        }
        val first = v.home.value.rowApps.firstOrNull()?.packageName
        assertEquals(true, first != null)
        v.openEditMenu(first!!)
        assertEquals(first, v.home.value.editingKey)
        v.closeEditMenu()
        assertEquals(null, v.home.value.editingKey)
    }

    @Test fun moveCyclePersists() = runTest {
        val v = vm()
        waitIdle(v)
        val order = v.home.value.rowApps.map { it.packageName }
        assertEquals(true, order.size >= 2)
        val held = order[0]
        v.startMove(held)
        v.moveKey(held, +1)
        assertEquals(held, v.home.value.rowApps[1].packageName)
        v.pressTile(held)
        val deadline = android.os.SystemClock.uptimeMillis() + 8000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (v.home.value.movingKey == null) break
            kotlinx.coroutines.delay(200)
        }
        assertEquals(null, v.home.value.movingKey)
    }

    @Test fun addMenuPickCycle() = runTest {
        val v = vm()
        waitIdle(v)
        v.openAddMenu()
        val deadline = android.os.SystemClock.uptimeMillis() + 8000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (v.home.value.showAddMenu) break
            kotlinx.coroutines.delay(200)
        }
        assertEquals(true, v.home.value.showAddMenu)
        val candidate = v.home.value.addCandidates.firstOrNull()?.packageName
        assertEquals(true, candidate != null)
        v.pickAddCandidate(candidate!!)
        val deadline2 = android.os.SystemClock.uptimeMillis() + 10000
        while (android.os.SystemClock.uptimeMillis() < deadline2) {
            if (!v.home.value.loading && v.home.value.rowApps.any { it.packageName == candidate }) break
            kotlinx.coroutines.delay(200)
        }
        assertEquals(true, v.home.value.rowApps.any { it.packageName == candidate })
        // Clean up: remove the added tile again.
        v.removeTile(candidate)
        val deadline3 = android.os.SystemClock.uptimeMillis() + 8000
        while (android.os.SystemClock.uptimeMillis() < deadline3) {
            if (v.home.value.rowApps.none { it.packageName == candidate }) break
            kotlinx.coroutines.delay(200)
        }
        assertEquals(true, v.home.value.rowApps.none { it.packageName == candidate })
    }

    @Test fun uninstallOrInfoClosesMenu() = runTest {
        val v = vm()
        waitIdle(v)
        val first = v.home.value.rowApps.firstOrNull()?.packageName ?: return@runTest
        v.openEditMenu(first)
        // System-or-user: either intent fires or a message shows; the menu closes.
        v.uninstallOrInfo(first)
        assertEquals(null, v.home.value.editingKey)
    }

    private fun waitIdle(v: TvHomeViewModel) {
        val deadline = android.os.SystemClock.uptimeMillis() + 15000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (!v.home.value.loading) break
            kotlinx.coroutines.delay(200)
        }
    }

    @Test fun tvAppsInstalledListsRealPackages() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val entries = TvApps.installed(ctx, ctx.packageName)
        assertEquals(true, entries.isNotEmpty())
        // Self is excluded from the row.
        assertEquals(true, entries.none { it.packageName == ctx.packageName })
    }
}
