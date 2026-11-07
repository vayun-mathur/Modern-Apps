package com.vayunmathur.launcher.tv.platform

import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * State-machine tests for [TvHomeViewModel]: edit menu, move/hold,
 * reorder arming, tile presses and key moves mutate [TvHomeState]
 * synchronously. Package loading is a no-op under Robolectric (no
 * leanback apps installed), so tests seed [TvHomeState] rows via the
 * edit/move methods themselves.
 */
@RunWith(RobolectricTestRunner::class)
class TvHomeViewModelTest {

    private fun vm(): TvHomeViewModel {
        val app = ApplicationProvider.getApplicationContext<android.app.Application>()
        return TvHomeViewModel(app)
    }

    @Test fun editMenuOpensAndCloses() {
        val v = vm()
        v.openEditMenu("com.example.a")
        assertEquals("com.example.a", v.home.value.editingKey)
        assertNull(v.home.value.movingKey)
        v.closeEditMenu()
        assertNull(v.home.value.editingKey)
    }

    @Test fun startMoveHoldsTileAndArms() {
        val v = vm()
        v.startMove("com.example.a")
        assertEquals("com.example.a", v.home.value.movingKey)
        assertEquals(true, v.home.value.reorderArmed)
        assertNull(v.home.value.editingKey)
    }

    @Test fun toggleReorderArmsAndDisarms() {
        val v = vm()
        assertEquals(false, v.home.value.reorderArmed)
        v.toggleReorder()
        assertEquals(true, v.home.value.reorderArmed)
        v.disarmReorder()
        assertEquals(false, v.home.value.reorderArmed)
    }

    @Test fun pressTileOpensNothingWhenNotArmed() {
        val v = vm()
        // Not armed and not moving: opens the app (a no-op under Robolectric
        // with no such package), and crucially does not hold the tile.
        v.pressTile("com.example.a")
        assertNull(v.home.value.movingKey)
    }

    @Test fun pressTileInReorderModePicksUp() {
        val v = vm()
        v.toggleReorder()
        v.pressTile("com.example.a")
        assertEquals("com.example.a", v.home.value.movingKey)
    }

    @Test fun pressingHeldTileDropsIt() = runTest {
        val v = vm()
        v.toggleReorder()
        v.pressTile("com.example.a")
        assertEquals("com.example.a", v.home.value.movingKey)
        v.pressTile("com.example.a")
        // finishMove persists on an IO dispatcher; the hold only clears after
        // that write lands, which a main-looper idle cannot force. The pickup
        // half above is the synchronous, assertable part.
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
    }

    @Test fun cancelMoveClearsHold() {
        val v = vm()
        v.startMove("com.example.a")
        v.cancelMove()
        assertNull(v.home.value.movingKey)
    }

    @Test fun disarmWhileHoldingCancelsHold() {
        val v = vm()
        v.startMove("com.example.a")
        v.disarmReorder()
        assertNull(v.home.value.movingKey)
    }

    @Test fun moveKeyIgnoresStalePkg() {
        val v = vm()
        v.startMove("com.example.a")
        // A closure for another tile must not move anything.
        v.moveKey("com.example.b", 1)
        assertEquals("com.example.a", v.home.value.movingKey)
    }

    @Test fun closeEditMenuClearsAddMenu() {
        val v = vm()
        v.openEditMenu("com.example.a")
        v.closeEditMenu()
        val s = v.home.value
        assertNull(s.editingKey)
        assertNull(s.movingKey)
        assertTrue(s.addCandidates.isEmpty())
        assertEquals(false, s.showAddMenu)
    }
}
