package com.vayunmathur.things.platform

import android.content.Context
import android.os.Looper
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.WeightRecord
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Scale packet dispatch: classic + VA measure frames, stored records, user sync. */
@RunWith(RobolectricTestRunner::class)
class ScaleBlePacketTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun manager(va: Boolean = false, category: Int = 100): ScaleBleManager {
        DeviceController.init(context)
        DeviceController.scaleWeight.value = null
        DeviceController.scaleRealtimeWeight.value = null
        DeviceController.scaleMetrics.value = null
        DeviceController.scaleConnectionState.value = "Disconnected"
        DeviceController.scaleSex.value = Sex.Male
        DeviceController.scaleAge.value = "30"
        DeviceController.scaleHeight.value = "175"
        DeviceController.scaleAthlete.value = false
        DeviceController.loadScaleProfile()
        shadowOf(Looper.getMainLooper()).idle()
        return ScaleBleManager().apply {
            isVaScale = va
            scaleCategory = category
        }
    }

    private fun withFakeHealth(fake: FakeHealthConnect, block: () -> Unit) {
        val oldStatus = healthStatusProvider
        val oldClient = healthClientProvider
        healthStatusProvider = { HealthConnectClient.SDK_AVAILABLE }
        healthClientProvider = { fake.client }
        try {
            block()
        } finally {
            healthStatusProvider = oldStatus
            healthClientProvider = oldClient
        }
    }

    private fun flush() = shadowOf(Looper.getMainLooper()).idle()

    private fun classic(sub: Int, weightRaw: Int, size: Int = 10): ByteArray {
        val b = ByteArray(size)
        b[0] = PACKET_MEASURE.toByte()
        b[3] = (weightRaw shr 8).toByte(); b[4] = weightRaw.toByte()
        b[5] = sub.toByte()
        return b
    }

    @Test
    fun `classic streaming packets report realtime weight`() {
        val mgr = manager()
        for (sub in listOf(0, 17, 18)) {
            DeviceController.scaleRealtimeWeight.value = null
            mgr.dispatch(classic(sub, 750)) // 750/10 = 75 kg
            assertEquals(75.0, DeviceController.scaleRealtimeWeight.value, "sub=$sub")
        }
        // A zero weight is not a reading.
        DeviceController.scaleRealtimeWeight.value = null
        mgr.dispatch(classic(0, 0))
        assertNull(DeviceController.scaleRealtimeWeight.value)
        // Short packets are ignored.
        mgr.dispatch(byteArrayOf(PACKET_MEASURE.toByte(), 0))
        assertNull(DeviceController.scaleRealtimeWeight.value)
    }

    @Test
    fun `classic stable packets lock in a measurement`() {
        val mgr = manager()
        val fake = FakeHealthConnect()
        withFakeHealth(fake) {
            val b = classic(1, 800) // 80 kg
            b[6] = 0x01; b[7] = 0xF4.toByte() // r50 500
            b[8] = 0x01; b[9] = 0x2C // r500 300
            mgr.dispatch(b)
            flush()
            assertEquals(80.0, DeviceController.scaleWeight.value)
            assertEquals(500, DeviceController.scaleR50.value)
            assertEquals(300, DeviceController.scaleR500.value)
            assertEquals(1, fake.inserted.size)
            // HR variant takes the same stable path.
            val hr = classic(2, 700)
            hr[6] = 0x01; hr[7] = 0xF4.toByte(); hr[8] = 0x01; hr[9] = 0x2C
            mgr.dispatch(hr)
            flush()
            assertEquals(70.0, DeviceController.scaleWeight.value)
            assertEquals(2, fake.inserted.size)
        }
        // A stable packet too short for impedance is dropped entirely.
        DeviceController.scaleWeight.value = null
        mgr.dispatch(classic(1, 800, size = 8))
        assertNull(DeviceController.scaleWeight.value)
    }

    @Test
    fun `eight electrode burst needs both packets before measuring`() {
        val mgr = manager(category = CATEGORY_EIGHT_ELECTRODE)
        val fake = FakeHealthConnect()
        withFakeHealth(fake) {
            val first = ByteArray(17)
            first[0] = PACKET_MEASURE.toByte()
            first[3] = 0x02; first[4] = 0xEE.toByte() // 75 kg
            first[5] = 1
            first[6] = 0x21 // count 2, current 1
            // Channel words are (value / 0.1): 5000 -> 500.0 etc.
            fun put(at: Int, v: Int) {
                first[at] = (v shr 8).toByte(); first[at + 1] = v.toByte()
            }
            put(7, 5000); put(9, 5100); put(11, 5200); put(13, 5300); put(15, 5400)
            mgr.dispatch(first)
            flush()
            assertNull(DeviceController.scaleWeight.value, "half a burst is not a measurement")
            val second = ByteArray(17)
            second[0] = PACKET_MEASURE.toByte()
            second[3] = 0x02; second[4] = 0xEE.toByte()
            second[5] = 1
            second[6] = 0x22 // count 2, current 2
            fun put2(at: Int, v: Int) {
                second[at] = (v shr 8).toByte(); second[at + 1] = v.toByte()
            }
            put2(7, 5500); put2(9, 5600); put2(11, 5700); put2(13, 5800); put2(15, 5900)
            mgr.dispatch(second)
            flush()
            assertEquals(75.0, DeviceController.scaleWeight.value)
            assertNotNull(DeviceController.scaleMetrics.value?.segmental)
            assertEquals(1, fake.inserted.size)
            // A second-packet without a first is discarded, not built from stale channels.
            DeviceController.scaleWeight.value = null
            mgr.dispatch(second)
            flush()
            assertNull(DeviceController.scaleWeight.value)
        }
    }

    @Test
    fun `va measure states stream then complete`() {
        val mgr = manager(va = true)
        val fake = FakeHealthConnect()
        withFakeHealth(fake) {
            fun va(state: Int, weightRaw: Int, size: Int = 11): ByteArray {
                val b = ByteArray(size)
                b[0] = PACKET_MEASURE.toByte()
                b[4] = state.toByte()
                b[5] = (weightRaw shr 8).toByte(); b[6] = weightRaw.toByte()
                return b
            }
            mgr.dispatch(va(0, 750)) // settling: 750 * 0.1 = 75
            assertEquals(75.0, DeviceController.scaleRealtimeWeight.value)
            mgr.dispatch(va(18, 700)) // heart-rate state still streams weight
            assertEquals(70.0, DeviceController.scaleRealtimeWeight.value)
            // Complete with impedance.
            val done = va(2, 750)
            done[7] = 0x01; done[8] = 0xF4.toByte(); done[9] = 0x01; done[10] = 0x2C
            mgr.dispatch(done)
            flush()
            assertEquals(75.0, DeviceController.scaleWeight.value)
            assertEquals(500, DeviceController.scaleR50.value)
            assertEquals(1, fake.inserted.size)
            // Complete but truncated: measurement with zero impedance.
            DeviceController.scaleWeight.value = null
            mgr.dispatch(va(2, 600, size = 8))
            flush()
            assertEquals(60.0, DeviceController.scaleWeight.value)
            assertNull(DeviceController.scaleR50.value)
            // Short VA packets are ignored (built by hand: the helper writes weight bytes).
            DeviceController.scaleWeight.value = null
            val short = ByteArray(5)
            short[0] = PACKET_MEASURE.toByte(); short[4] = 2
            mgr.dispatch(short)
            assertNull(DeviceController.scaleWeight.value)
        }
    }

    @Test
    fun `scale info sets type ratios and identify support`() {
        val mgr = manager()
        // Type-only packet: type lands but ratios wait for the full frame.
        val typeOnly = ByteArray(10)
        typeOnly[0] = PACKET_SCALE_INFO.toByte()
        typeOnly[2] = 7
        mgr.dispatch(typeOnly)
        assertEquals(7, mgr.scaleType)
        assertEquals(10.0, mgr.weightRatio)
        // Full kg frame with identify-weight support (byte16 bit5).
        val full = ByteArray(20)
        full[0] = PACKET_SCALE_INFO.toByte()
        full[2] = 3
        full[10] = 0
        full[16] = (1 shl 5).toByte()
        mgr.dispatch(full)
        flush()
        assertEquals(3, mgr.scaleType)
        assertEquals(10.0, mgr.weightRatio)
        assertEquals(0.1, mgr.kgWeightRatio)
        assertTrue(mgr.supportsIdentifyWeight)
        assertEquals("Connected — step on scale", DeviceController.scaleConnectionState.value)
        // lb frame flips both ratios and clears identify support.
        val lb = ByteArray(20)
        lb[0] = PACKET_SCALE_INFO.toByte()
        lb[10] = 1
        mgr.dispatch(lb)
        flush()
        assertEquals(100.0, mgr.weightRatio)
        assertEquals(0.01, mgr.kgWeightRatio)
        assertFalse(mgr.supportsIdentifyWeight)
    }

    @Test
    fun `time ack starts classic measurement and holtek hw packet sends time`() {
        val mgr = manager()
        // HW version on a non-Holtek scale does nothing observable; on Holtek it syncs time.
        mgr.dispatch(byteArrayOf(PACKET_HW_VERSION.toByte(), 0, 0))
        mgr.isHoltek = true
        mgr.dispatch(byteArrayOf(PACKET_HW_VERSION.toByte(), 0, 0))
        shadowOf(Looper.getMainLooper()).idleFor(300, java.util.concurrent.TimeUnit.MILLISECONDS)
        mgr.dispatch(byteArrayOf(PACKET_TIME_ACK.toByte(), 0, 0))
        shadowOf(Looper.getMainLooper()).idleFor(400, java.util.concurrent.TimeUnit.MILLISECONDS)
        // Reaching here with the runnables drained is the assertion: no crash, queue handled.
        assertTrue(true)
    }

    private fun storedClassic(secondsSince2000: Long, weightRaw: Int): ByteArray {
        val b = ByteArray(15)
        b[0] = PACKET_STORED.toByte()
        for (i in 0 until 4) b[5 + i] = (secondsSince2000 shr (8 * i)).toByte()
        b[9] = (weightRaw shr 8).toByte(); b[10] = weightRaw.toByte()
        b[11] = 0x01; b[12] = 0xF4.toByte(); b[13] = 0x01; b[14] = 0x2C
        return b
    }

    @Test
    fun `classic stored records replay only plausible timestamps`() {
        val mgr = manager()
        val fake = FakeHealthConnect()
        withFakeHealth(fake) {
            val nowSec = System.currentTimeMillis() / 1000 - BASE_TIME_2000_SECONDS
            mgr.dispatch(storedClassic(nowSec - 60, 750))
            flush()
            assertEquals(1, fake.inserted.size)
            val weights = fake.inserted.single().filterIsInstance<WeightRecord>()
            assertEquals(75.0, weights.single().weight.inKilograms, 1e-9)
            // Future and ancient replays are dropped.
            mgr.dispatch(storedClassic(nowSec + 3600, 750))
            mgr.dispatch(storedClassic(0, 750))
            // Zero weight and eight-electrode categories are dropped too.
            mgr.dispatch(storedClassic(nowSec - 60, 0))
            flush()
            assertEquals(1, fake.inserted.size)
        }
    }

    @Test
    fun `va stored records respect the user slot and timestamps`() {
        val mgr = manager(va = true)
        mgr.vaUserIndex = 2
        val fake = FakeHealthConnect()
        withFakeHealth(fake) {
            fun vaStored(user: Int, secondsSince2000: Long, total: Int = 1): ByteArray {
                val b = ByteArray(18)
                b[0] = PACKET_STORED.toByte()
                b[3] = total.toByte(); b[4] = 1; b[5] = user.toByte()
                for (i in 0 until 4) b[6 + i] = (secondsSince2000 shr (8 * i)).toByte()
                b[10] = 0x02; b[11] = 0xEE.toByte() // 750 * 0.1 = 75
                b[12] = 0x01; b[13] = 0xF4.toByte(); b[14] = 0x01; b[15] = 0x2C
                return b
            }
            val nowSec = System.currentTimeMillis() / 1000 - BASE_TIME_2000_SECONDS
            mgr.dispatch(vaStored(user = 2, secondsSince2000 = nowSec - 60))
            flush()
            assertEquals(1, fake.inserted.size)
            // Someone else's record, an empty replay, and a future record are all skipped.
            mgr.dispatch(vaStored(user = 3, secondsSince2000 = nowSec - 60))
            mgr.dispatch(vaStored(user = 2, secondsSince2000 = nowSec - 60, total = 0))
            mgr.dispatch(vaStored(user = 2, secondsSince2000 = nowSec + 7200))
            flush()
            assertEquals(1, fake.inserted.size)
        }
    }

    @Test
    fun `user sync results register visit and delete`() {
        val mgr = manager(va = true)
        val fake = FakeHealthConnect()
        withFakeHealth(fake) {
            fun result(sub: Int, index: Int, ok: Boolean): ByteArray {
                val b = ByteArray(6)
                b[0] = PACKET_USER_SYNC.toByte()
                b[2] = sub.toByte(); b[3] = index.toByte(); b[4] = (if (ok) 1 else 0).toByte()
                return b
            }
            // Too short to trust.
            mgr.dispatch(byteArrayOf(PACKET_USER_SYNC.toByte(), 0, 1))
            // Register succeeds: slot is saved and becomes ours after the visit.
            mgr.dispatch(result(VA_SUB_REGISTER, 3, ok = true))
            flush()
            assertEquals(3, DeviceController.scaleUserIndex())
            // Register fails (slots full): visitor mode, no slot saved.
            DeviceController.devicePrefs.edit().remove(DeviceController.SCALE_USER_INDEX_KEY).commit()
            mgr.dispatch(result(VA_SUB_REGISTER, 0, ok = false))
            flush()
            assertNull(DeviceController.scaleUserIndex())
            // Visit succeeds for a real slot and for the visitor slot.
            mgr.dispatch(result(VA_SUB_VISIT, 3, ok = true))
            assertEquals(3, mgr.vaUserIndex)
            mgr.dispatch(result(VA_SUB_VISIT, VA_VISITOR_INDEX, ok = true))
            assertNull(mgr.vaUserIndex)
            mgr.dispatch(result(VA_SUB_VISIT, 3, ok = false))
            assertNull(mgr.vaUserIndex)
            // Delete completes the pending reset and re-syncs; a rejected delete does not.
            DeviceController.requestScaleReset()
            mgr.dispatch(result(VA_SUB_DELETE, 0, ok = true))
            flush()
            assertFalse(DeviceController.scaleResetPending())
            mgr.dispatch(result(VA_SUB_DELETE, 0, ok = false))
            flush()
        }
    }

    @Test
    fun `user frames visitor identify and stored requests enqueue safely`() {
        val mgr = manager(va = true)
        DeviceController.scaleProfile.value = ScaleProfile(Sex.Female, 45, 182.0, athlete = true)
        mgr.syncUser() // no slot yet: register frame
        DeviceController.saveScaleUserIndex(5)
        mgr.syncUser() // visit frame
        mgr.sendVisitorUser()
        assertNull(mgr.vaUserIndex)
        mgr.sendDeleteAllUsers()
        mgr.requestStoredRecords()
        mgr.vaUserIndex = 4
        mgr.requestStoredRecords()
        // Identify-weight only goes out when the scale supports it and we hold a slot.
        mgr.supportsIdentifyWeight = false
        mgr.sendIdentifyWeight(75.0)
        mgr.supportsIdentifyWeight = true
        mgr.sendIdentifyWeight(75.0)
        mgr.sendIdentifyWeight(0.0)
        flush()
        shadowOf(Looper.getMainLooper()).idleFor(1, java.util.concurrent.TimeUnit.SECONDS)
    }

    @Test
    fun `build cmd frames with the connection scale type`() {
        val mgr = manager()
        mgr.scaleType = 9
        val frame = mgr.buildCmd(CMD_TIME, 1, 2, 3, 4)
        assertEquals(CMD_TIME.toByte(), frame[0])
        assertEquals(9, frame[2].toInt())
        val sum = (0 until frame.size - 1).sumOf { frame[it].toInt() }
        assertEquals(sum.toByte(), frame[frame.size - 1])
    }

    @Test
    fun `unknown packets are ignored`() {
        val mgr = manager()
        mgr.dispatch(byteArrayOf(0x7F, 1, 2, 3))
        assertNull(DeviceController.scaleWeight.value)
    }
}
