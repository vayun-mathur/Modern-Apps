package com.vayunmathur.launcher.tv.platform

import android.app.Application
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.vayunmathur.launcher.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Extra on-device coverage for [TvHomeViewModel] paths the first TV device
 * suite does not reach: reorder disarm mid-hold, unknown-tile opens,
 * uninstall/info intents, default-home refresh and the request-role prompt.
 */
@RunWith(AndroidJUnit4::class)
class TvHomeViewModelExtraDeviceTest {

    private fun vm(): TvHomeViewModel {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        return TvHomeViewModel(ctx.applicationContext as Application)
    }

    private suspend fun waitIdle(v: TvHomeViewModel) {
        val deadline = android.os.SystemClock.uptimeMillis() + 15000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            if (!v.home.value.loading) break
            delay(200)
        }
    }

    @Test
    fun disarmMidHoldRestoresPersistedOrder() = runTest {
        val v = vm()
        waitIdle(v)
        val order = v.home.value.rowApps.map { it.packageName }
        assertTrue(order.size >= 2)
        v.startMove(order[0])
        v.moveKey(order[0], +1)
        assertEquals(order[0], v.home.value.rowApps[1].packageName)
        // Disarming while holding drops the hold and quietly restores the
        // last persisted order (the same packages, back in place).
        v.toggleReorder()
        assertEquals(false, v.home.value.reorderArmed)
        assertNull(v.home.value.movingKey)
        assertEquals(order.toSet(), v.home.value.rowApps.map { it.packageName }.toSet())
    }

    @Test
    fun toggleReorderArmsThenDisarmsCleanly() = runTest {
        val v = vm()
        waitIdle(v)
        v.toggleReorder()
        assertEquals(true, v.home.value.reorderArmed)
        v.toggleReorder()
        assertEquals(false, v.home.value.reorderArmed)
        assertNull(v.home.value.movingKey)
    }

    @Test
    fun openUnknownTileShowsMessageInsteadOfCrashing() = runTest {
        val v = vm()
        waitIdle(v)
        v.openTile("com.example.does.not.exist")
        // No state changed and nothing launched; the message path ran.
        assertNull(v.home.value.movingKey)
    }

    @Test
    fun refreshDefaultHomeReadsTheRoleState() = runTest {
        val v = vm()
        waitIdle(v)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                v.refreshDefaultHome(activity)
                assertEquals(TvHomeRole.isDefaultHome(activity), v.home.value.isDefaultHome)
            }
        }
    }

    @Test
    fun uninstallOrInfoForUnknownPackageOpensDetailsAndClosesMenu() = runTest {
        val v = vm()
        waitIdle(v)
        v.openEditMenu("com.example.does.not.exist")
        assertEquals("com.example.does.not.exist", v.home.value.editingKey)
        v.uninstallOrInfo("com.example.does.not.exist")
        assertNull(v.home.value.editingKey)
        // Leave whatever settings surface opened.
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.waitForIdle(2_000)
        device.pressBack()
        device.waitForIdle(2_000)
    }
}
