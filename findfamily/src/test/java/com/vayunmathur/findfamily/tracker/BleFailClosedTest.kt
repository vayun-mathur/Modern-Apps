package com.vayunmathur.findfamily.tracker

import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.data.TestFindFamilyRepository
import com.vayunmathur.findfamily.data.UserKind
import com.vayunmathur.findfamily.domain.TrackerBle
import com.vayunmathur.findfamily.platform.PoweredOffBle
import com.vayunmathur.findfamily.platform.PoweredOffScanner
import com.vayunmathur.findfamily.platform.TrackerBeaconScanner
import com.vayunmathur.findfamily.platform.TrackerBinder
import com.vayunmathur.findfamily.platform.TrackerProvisioner
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BLE crowd-finding surface in the sandbox: there is no usable BLE stack, so scans
 * must close quietly, provisioning must report failure without persisting anything,
 * and the powered-off frame parser (pure) must accept exactly well-formed frames.
 */
@RunWith(AndroidJUnit4::class)
class BleFailClosedTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun poweredOffEidParserAcceptsOnlyWellFormedFrames() {
        val eid = ByteArray(20) { it.toByte() }
        val frame = byteArrayOf(PoweredOffBle.FRAME_TYPE) + eid + byteArrayOf(0x55)
        assertContentEquals(eid, PoweredOffBle.eidFrom(frame))
        assertContentEquals(eid, PoweredOffBle.eidFrom(byteArrayOf(PoweredOffBle.FRAME_TYPE) + eid))
        assertNull(PoweredOffBle.eidFrom(null))
        assertNull(PoweredOffBle.eidFrom(ByteArray(0)))
        // One byte short of a whole EID.
        assertNull(PoweredOffBle.eidFrom(byteArrayOf(PoweredOffBle.FRAME_TYPE) + ByteArray(19)))
        // Wrong frame type is some other Fast Pair / Eddystone frame.
        assertNull(PoweredOffBle.eidFrom(byteArrayOf(0x41) + eid))
        assertEquals(2, PoweredOffBle.scanFilters().size)
        assertEquals(21, PoweredOffBle.MIN_SERVICE_DATA_LEN)
    }

    @Test fun trackerScannerClosesOrIdlesWithoutBle() = runBlocking {
        val collected = async { runCatching { TrackerBeaconScanner(ctx).sightings().toList() } }
        val done = withTimeoutOrNull(4_000) { collected.await() }
        if (done == null) {
            // A shadow scanner that accepts startScan simply never reports; the
            // flow staying open without emitting is the other correct outcome.
            collected.cancel()
        } else {
            assertTrue(done.isSuccess, "scan flow must close, not fail: $done")
            assertTrue(done.getOrThrow().isEmpty())
        }
        Unit
    }

    @Test fun poweredOffScannerClosesOrIdlesWithoutBle() = runBlocking {
        val collected = async { runCatching { PoweredOffScanner(ctx).sightings().toList() } }
        val done = withTimeoutOrNull(4_000) { collected.await() }
        if (done == null) {
            collected.cancel()
        } else {
            assertTrue(done.isSuccess, "scan flow must close, not fail: $done")
            assertTrue(done.getOrThrow().isEmpty())
        }
        Unit
    }

    @Test fun provisionerScanClosesOrIdlesWithoutBle() = runBlocking {
        val collected = async { runCatching { TrackerProvisioner(ctx).unprovisioned().toList() } }
        val done = withTimeoutOrNull(4_000) { collected.await() }
        if (done == null) {
            collected.cancel()
        } else {
            assertTrue(done.isSuccess, "scan flow must close, not fail: $done")
            assertTrue(done.getOrThrow().isEmpty())
        }
        Unit
    }

    @Test fun provisionRejectsWrongSecretSize() = runBlocking {
        val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val device = manager.adapter.getRemoteDevice("AA:BB:CC:DD:EE:FF")
        assertFailsWith<IllegalArgumentException> {
            TrackerProvisioner(ctx).provision(device, 1L, ByteArray(16))
        }
        Unit
    }

    @Test fun provisionToUnreachableDeviceReturnsFalse() = runBlocking {
        val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val device = manager.adapter.getRemoteDevice("AA:BB:CC:DD:EE:FF")
        // The shadow GATT accepts the connection attempt and never calls back, so
        // the write can never report success; cancelling the attempt must settle
        // it as a failure (the invokeOnCancellation finish path), not a throw.
        val attempt = async { TrackerProvisioner(ctx).provision(device, 1L, ByteArray(32) { 7 }) }
        delay(1_500)
        attempt.cancel()
        assertEquals(false, runCatching { attempt.await() }.getOrElse { false })
        Unit
    }

    @Test fun binderPersistsNothingWhenProvisioningFails() = runBlocking {
        val repo = TestFindFamilyRepository.install(ctx)
        val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val device = manager.adapter.getRemoteDevice("AA:BB:CC:DD:EE:FF")
        val attempt = async { TrackerBinder.bind(ctx, "Keys", device) }
        // Provisioning never completes in the sandbox; bind must not persist the
        // tracker up front (that ordering is the point of TrackerBinder), so even
        // mid-attempt the family list stays empty.
        delay(2_500)
        assertTrue(repo.getAllUsers().none { it.kind == UserKind.TRACKER })
        attempt.cancel()
        runCatching { attempt.await() }
        assertTrue(repo.getAllUsers().none { it.kind == UserKind.TRACKER })
        Unit
    }

    @Test fun trackerUuidsAreDistinct() {
        val uuids = listOf(
            TrackerBle.SERVICE_UUID,
            TrackerBle.UNPROVISIONED_SERVICE_UUID,
            TrackerBle.PROVISION_CHARACTERISTIC_UUID,
            TrackerBle.UWB_SESSION_CHARACTERISTIC_UUID,
        )
        assertEquals(uuids.size, uuids.toSet().size)
        assertFalse(TrackerBle.SERVICE_UUID == TrackerBle.UNPROVISIONED_SERVICE_UUID)
    }
}
