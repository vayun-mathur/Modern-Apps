package com.vayunmathur.things.platform

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Wire-packet dispatch: RP snapshots, RT updates, PT log drains. */
@RunWith(RobolectricTestRunner::class)
class BleManagerPacketTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun manager(): BleManager {
        DeviceController.init(context)
        DeviceController.waterTempC.value = null
        DeviceController.tds.value = null
        DeviceController.batteryPct.value = null
        DeviceController.bottleVolumePct.value = null
        DeviceController.charging.value = false
        shadowOf(Looper.getMainLooper()).idle()
        return BleManager()
    }

    private fun flush() = shadowOf(Looper.getMainLooper()).idle()

    private fun rp(size: Int = 8): ByteArray {
        val b = ByteArray(size)
        b[0] = 0x52; b[1] = 0x50
        return b
    }

    private fun rt(selector: Int, value: Int, size: Int = 8): ByteArray {
        val b = ByteArray(size)
        b[0] = 0x52; b[1] = 0x54; b[5] = selector.toByte(); b[6] = value.toByte()
        return b
    }

    @Test
    fun `vita snapshot fills every status field`() {
        val mgr = manager()
        val b = rp(52)
        b[2] = 0; b[3] = 0x31
        b[6] = 22; b[9] = 80; b[34] = 1
        b[45] = 0; b[46] = 45 // tds 45
        b[49] = 0x01; b[50] = 0x0A // volume raw 266 -> round(266/530*100)=50
        mgr.dispatch(b)
        assertEquals(22, DeviceController.waterTempC.value)
        assertEquals(45, DeviceController.tds.value)
        assertEquals(80, DeviceController.batteryPct.value)
        assertTrue(DeviceController.charging.value)
        assertEquals(50, DeviceController.bottleVolumePct.value)
    }

    @Test
    fun `boost snapshot fills battery and charging only`() {
        val mgr = manager()
        val b = rp(32)
        b[2] = 0; b[3] = 0x27
        b[6] = 66; b[31] = 2
        mgr.dispatch(b)
        assertEquals(66, DeviceController.batteryPct.value)
        assertTrue(DeviceController.charging.value)
        assertNull(DeviceController.waterTempC.value)
    }

    @Test
    fun `short or signature rp packets are ignored`() {
        val mgr = manager()
        mgr.dispatch(rp(4)) // below RP_MIN_SIZE
        val sig = rp()
        sig[5] = 0x20
        mgr.dispatch(sig)
        val unknown = rp()
        unknown[2] = 9; unknown[3] = 9
        mgr.dispatch(unknown)
        assertNull(DeviceController.waterTempC.value)
        assertNull(DeviceController.batteryPct.value)
    }

    @Test
    fun `sync ack and log availability update drain bookkeeping`() {
        val mgr = manager()
        val ack = rp()
        ack[2] = 0; ack[3] = 0x0F
        mgr.dispatch(ack)
        flush() // lets the queued log request reach writeNext (no gatt: dropped there)
        val avail = rp()
        avail[5] = 0x06; avail[6] = 1
        mgr.expectedLogs = 5
        mgr.dispatch(avail)
        assertEquals(5, mgr.expectedLogs, "available=1 keeps the pending drain")
        avail[6] = 0
        mgr.dispatch(avail)
        assertEquals(0, mgr.expectedLogs, "available=0 cancels the drain")
        assertEquals(0, mgr.parsedRecords)
        assertTrue(mgr.collected.isEmpty())
    }

    @Test
    fun `rt selectors update single fields`() {
        val mgr = manager()
        mgr.dispatch(rt(0x01, 25))
        assertEquals(25, DeviceController.waterTempC.value)
        mgr.dispatch(rt(0x02, 71))
        assertEquals(71, DeviceController.batteryPct.value)
        mgr.dispatch(rt(0x17, 1))
        assertTrue(DeviceController.charging.value)
        mgr.dispatch(rt(0x17, 0))
        assertTrue(!DeviceController.charging.value, "a non-charging state clears the flag")
        // Volume and TDS carry a big-endian word at offset 6.
        val vol = rt(0x08, 0, size = 8)
        vol[6] = 0x02; vol[7] = 0x14 // 532 raw -> 100%
        mgr.dispatch(vol)
        assertEquals(100, DeviceController.bottleVolumePct.value)
        val tds = rt(0x28, 0, size = 8)
        tds[6] = 0; tds[7] = 33
        mgr.dispatch(tds)
        assertEquals(33, DeviceController.tds.value)
    }

    @Test
    fun `rt unknown recalibrate registration and short packets do not emit status`() {
        val mgr = manager()
        mgr.dispatch(rt(0x7E, 5)) // unknown selector
        mgr.dispatch(rt(0xA1, 1)) // recalibrate: logged only
        mgr.dispatch(rt(0x1C, 3)) // registration, but no registration is pending
        mgr.dispatch(byteArrayOf(0x52, 0x54, 0)) // below RT_MIN_SIZE
        assertNull(DeviceController.waterTempC.value)
        assertNull(DeviceController.batteryPct.value)
    }

    @Test
    fun `unhandled packets are dropped`() {
        val mgr = manager()
        mgr.dispatch(byteArrayOf(0x00, 0x01, 0x02))
        mgr.dispatch(byteArrayOf(0x52, 0x51, 0x02)) // 'RQ': no packet family claims it
        assertNull(DeviceController.waterTempC.value)
    }

    private fun logRecord(
        year: Int = 26, month: Int = 1, day: Int = 15,
        hour: Int = 10, min: Int = 30, sec: Int = 0,
        amount: Int = 250, tds: Int = 40, temp: Int = 21, flag: Int = 0,
    ): ByteArray {
        val r = ByteArray(RECORD_SIZE)
        r[0] = year.toByte(); r[1] = month.toByte(); r[2] = day.toByte()
        r[3] = hour.toByte(); r[4] = min.toByte(); r[5] = sec.toByte()
        r[6] = (amount shr 8).toByte(); r[7] = amount.toByte()
        r[8] = (tds shr 8).toByte(); r[9] = tds.toByte()
        r[10] = temp.toByte()
        r[12] = flag.toByte()
        return r
    }

    private fun ptFirst(records: List<ByteArray>, countBytes: Int): ByteArray {
        val head = ByteArray(6)
        head[0] = 0x50; head[1] = 0x54
        head[2] = (countBytes shr 8).toByte(); head[3] = countBytes.toByte()
        head[5] = 0x06
        return records.fold(head) { acc, r -> acc + r }
    }

    @Test
    fun `pt drain parses valid records and skips flagged ones`() {
        val mgr = manager()
        val packet = ptFirst(
            listOf(logRecord(amount = 250), logRecord(amount = 999, flag = 1)),
            countBytes = 2 * RECORD_SIZE,
        )
        mgr.dispatch(packet)
        // The drain completed inside the packet: bookkeeping resets after delivery.
        assertEquals(0, mgr.expectedLogs)
        assertEquals(0, mgr.parsedRecords)
        assertTrue(mgr.collected.isEmpty())
    }

    @Test
    fun `pt drain accumulates across continuation packets`() {
        val mgr = manager()
        // Expect 2 records but deliver only the first in the PT packet.
        mgr.dispatch(ptFirst(listOf(logRecord(amount = 100)), countBytes = 2 * RECORD_SIZE))
        assertEquals(2, mgr.expectedLogs)
        assertEquals(1, mgr.parsedRecords)
        assertEquals(1, mgr.collected.size)
        assertEquals(100, mgr.collected.single().amountMl)
        assertEquals(40, mgr.collected.single().tds)
        assertEquals(21, mgr.collected.single().tempC)
        // Continuation: byte1 = 0x06, records start at offset 2.
        val cont = byteArrayOf(0x50, 0x06) + logRecord(amount = 150)
        mgr.dispatch(cont)
        assertEquals(0, mgr.expectedLogs, "completed drain resets")
        assertTrue(mgr.collected.isEmpty())
    }

    @Test
    fun `pt first packet with a wrong flag or short body is ignored`() {
        val mgr = manager()
        val bad = ptFirst(listOf(logRecord()), countBytes = RECORD_SIZE)
        bad[5] = 0x07
        mgr.dispatch(bad)
        assertEquals(0, mgr.expectedLogs)
        mgr.dispatch(byteArrayOf(0x50, 0x54, 0x00))
        assertEquals(0, mgr.expectedLogs)
    }

    @Test
    fun `sync command carries the current date and fixed framing`() {
        val cmd = buildSyncCommand()
        assertTrue(cmd.startsWith(SYNC_HEAD + SYNC_GOAL + SYNC_MID), cmd)
        assertTrue(cmd.endsWith(SYNC_TAIL + SYNC_REMINDER), cmd)
        assertEquals(
            (SYNC_HEAD + SYNC_GOAL + SYNC_MID + SYNC_TAIL + SYNC_REMINDER).length + 12,
            cmd.length,
            cmd,
        )
    }

    @Test
    fun `hydration reading and bottle status are value types`() {
        val reading = HydrationReading(250, 123L, 40, 21)
        assertEquals(reading, HydrationReading(250, 123L, 40, 21))
        assertEquals(250, reading.copy(amountMl = 250).amountMl)
        val status = BottleStatus(22, 45, 80, charging = true, volumePct = 65)
        assertEquals(status, status.copy())
        assertTrue(status.toString().contains("80"))
    }
}
