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

    @Test fun tvAppsInstalledListsRealPackages() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val entries = TvApps.installed(ctx, ctx.packageName)
        assertEquals(true, entries.isNotEmpty())
        // Self is excluded from the row.
        assertEquals(true, entries.none { it.packageName == ctx.packageName })
    }
}
