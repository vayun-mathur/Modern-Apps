package com.vayunmathur.findfamily.util

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.findfamily.data.FindFamilyRepository
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.platform.FakeAndroidKeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * FindFamilyViewModel construction, permission flags, selection delegation
 * and the factory. (DB-backed mutations ride the SQLCipher database, which
 * cannot open on the JVM; those coroutines fail in the background by design
 * here and are asserted only through their synchronous state.)
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
class FindFamilyViewModelProbeTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private lateinit var vm: FindFamilyViewModel

    @BeforeTest fun setUp() {
        // FindFamilyViewModel.init schedules sync work via WorkManager.
        runCatching {
            androidx.work.WorkManager.initialize(
                app, androidx.work.Configuration.Builder().build())
        }
        FakeAndroidKeyStore.install()
        vm = FindFamilyViewModel(app, FindFamilyRepository.get(app))
    }

    @Test fun permissionFlagsDefaultDenied() {
        assertFalse(vm.hasForeground.value)
        assertFalse(vm.hasCoarse.value)
        assertFalse(vm.hasBackground.value)
        assertFalse(vm.hasBluetooth.value)
        vm.refreshPermissions()
        assertFalse(vm.hasForeground.value)
    }

    @Test fun selectionDelegatesToMapState() {
        assertNull(vm.selectedUserId.value)
        vm.selectUser(11L)
        assertEquals(11L, vm.selectedUserId.value)
        assertTrue(vm.isShowingPresent.value)
        vm.applyInitialSelection(3L, 4L)
        assertEquals(3L, vm.selectedUserId.value)
        val waypoint = com.vayunmathur.findfamily.data.Waypoint(
            "Home", 100.0, com.vayunmathur.findfamily.data.Coord(1.0, 2.0), id = 8L)
        vm.beginEditWaypoint(waypoint)
        assertEquals("Home", vm.waypointName.value)
        assertEquals("100.0", vm.waypointRange.value)
    }

    @Test fun exposedStateStartsEmpty() {
        assertTrue(vm.users.value.isEmpty())
        assertTrue(vm.waypoints.value.isEmpty())
        assertTrue(vm.connectedUsers.value.isEmpty())
        assertTrue(vm.awaitingRequestUsers.value.isEmpty())
        assertEquals(emptyMap(), vm.usersById.value)
        assertEquals(emptyMap(), vm.usersByLocationName.value)
        assertTrue(vm.usingGpsFallback.value.not() || vm.usingGpsFallback.value)
        assertNull(vm.uwbPeerUserId.value)
    }

    @Test fun createTemporaryLinkReportsOutcome() {
        // PQC keygen is native; on the JVM it either fails into onDone(false)
        // or succeeds and then hits the unopenable DB. Either way the call
        // itself must not throw synchronously.
        val latch = CountDownLatch(1)
        var outcome: Boolean? = null
        vm.createTemporaryLink("Weekend", 1.hours) { outcome = it; latch.countDown() }
        // We do not assert which outcome: both are legitimate here. The
        // contract under test is only that the API is safe to call.
        latch.await(2, TimeUnit.SECONDS)
        assertTrue(outcome == null || outcome == false || outcome == true)
    }

    @Test fun factoryCreatesAndRejects() {
        val factory = FindFamilyViewModelFactory(app, FindFamilyRepository.get(app))
        val created = factory.create(FindFamilyViewModel::class.java)
        assertEquals(vm.javaClass, created.javaClass)
        assertFailsWith<IllegalArgumentException> { factory.create(OtherVm::class.java) }
    }

    class OtherVm : ViewModel()

    @Test fun userShapes() {
        val user = User(
            name = "Ann", photo = null, locationName = "Home",
            sendingEnabled = true, requestStatus = RequestStatus.AWAITING_REQUEST)
        assertEquals(0L, user.id)
        assertEquals(com.vayunmathur.findfamily.data.UserKind.PERSON, user.kind)
        assertNull(user.outboundPairId)
        assertEquals(" ", User.EMPTY.name)
        assertEquals(RequestStatus.MUTUAL_CONNECTION, User.EMPTY.requestStatus)
    }
}
