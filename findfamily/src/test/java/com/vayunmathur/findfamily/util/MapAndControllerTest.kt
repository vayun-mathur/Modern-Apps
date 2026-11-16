package com.vayunmathur.findfamily.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.findfamily.data.Coord
import com.vayunmathur.findfamily.data.FindFamilyRepository
import com.vayunmathur.findfamily.data.Waypoint
import com.vayunmathur.findfamily.platform.FakeAndroidKeyStore
import com.vayunmathur.library.map.GeoPoint
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest

/** MapPageState form/history logic and LocationServiceController persisted flags. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class MapAndControllerTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @BeforeTest fun setUp() { FakeAndroidKeyStore.install() }

    private fun mapState(): MapPageState =
        MapPageState(CoroutineScope(Dispatchers.Unconfined), FindFamilyRepository.get(app))

    @Test fun personOpenedClearsHistory() {
        val s = mapState()
        assertTrue(s.isShowingPresent.value)
        s.setHistoricalPosition(GeoPoint(1.0, 2.0))
        // Opening a person sheet drops any stale historical pin and
        // returns to present mode (selection itself lives in the stack).
        s.onPersonOpened()
        assertTrue(s.isShowingPresent.value)
        assertNull(s.historicalPosition.value)

        s.setHistoricalPosition(GeoPoint(3.0, 4.0))
        s.setShowingPresent(true)
        assertNull(s.historicalPosition.value)
        s.setShowingPresent(false)
        s.setHistoricalPosition(GeoPoint(5.0, 6.0))
        assertEquals(GeoPoint(5.0, 6.0), s.historicalPosition.value)
        s.onPersonOpened()
        assertNull(s.historicalPosition.value)
    }

    @Test fun waypointFormCreateEditAndInvalidSaves() {
        val s = mapState()
        s.beginCreateWaypoint()
        assertEquals("", s.waypointName.value)
        assertEquals("100", s.waypointRange.value)
        // Invalid forms silently no-op (return false, no DB write).
        assertFalse(s.saveCurrentWaypoint(0L))
        s.setWaypointName("Home")
        s.setWaypointRange("not-a-number")
        assertFalse(s.saveCurrentWaypoint(0L))
        s.setWaypointRange("250")
        s.setWaypointCoord(Coord(38.7, -9.1))
        assertEquals(Coord(38.7, -9.1), s.waypointCoord.value)

        val existing = Waypoint("Work", 500.0, Coord(1.0, 2.0), id = 12L)
        s.beginEditWaypoint(existing)
        assertEquals("Work", s.waypointName.value)
        assertEquals("500.0", s.waypointRange.value)
        assertEquals(Coord(1.0, 2.0), s.waypointCoord.value)
    }

    @Test fun controllerPersistedFlags() = runTest {
        val ctx: android.content.Context = app
        // Defaults are opt-out (true) for both switches.
        assertTrue(LocationServiceController.isTrackingEnabled(ctx))
        assertTrue(LocationServiceController.isGlobalSharingEnabled(ctx))
        assertTrue(LocationServiceController.globalSharingEnabledFlow(ctx).first())
        assertTrue(LocationServiceController.crowdFindingEnabledFlow(ctx).first())
        assertTrue(LocationServiceController.CROWD_FINDING_ENABLED)
        assertFalse(LocationServiceController.hasFineLocationPermission(ctx))

        LocationServiceController.setGlobalSharingEnabled(ctx, false)
        assertFalse(LocationServiceController.isGlobalSharingEnabled(ctx))
        assertFalse(LocationServiceController.globalSharingEnabledFlow(ctx).first())
        LocationServiceController.setGlobalSharingEnabled(ctx, true)
        assertTrue(LocationServiceController.isGlobalSharingEnabled(ctx))

        LocationServiceController.setCrowdFindingEnabled(ctx, false)
        // Finding is mandatory: the flow stays true regardless of the stored key.
        assertTrue(LocationServiceController.crowdFindingEnabledFlow(ctx).first())

        // Note: setTrackingEnabled/syncServiceState hop to Dispatchers.Main,
        // which deadlocks against Robolectric's paused main looper from a
        // test body, so only the DataStore-backed reads are asserted here.
    }
}
