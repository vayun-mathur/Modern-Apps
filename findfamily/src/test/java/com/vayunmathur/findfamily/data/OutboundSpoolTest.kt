package com.vayunmathur.findfamily.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Latest-only outbound spool: fixes published while the relay socket is down must
 * survive (newest per recipient only), flush on reconnect, and clear on ack.
 */
@RunWith(AndroidJUnit4::class)
class OutboundSpoolTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir: File get() = context.createDeviceProtectedStorageContext().filesDir
    private val spool: File get() = File(dir, "ff_outbound_spool.bin")

    @BeforeTest
    fun clean() {
        dir.listFiles()?.filter { it.name.startsWith("ff_outbound_spool") }?.forEach { it.delete() }
    }

    @AfterTest
    fun tearDown() = clean()

    private fun payload(i: Int) = "sealed-fix-$i".encodeToByteArray()

    @Test
    fun fixSpooledWhileSocketDownIsRetained() = runBlocking {
        OutboundSpool.store(context, recipient = 42L, reportedAtMs = 1_000L, payload = payload(1))
        val pending = OutboundSpool.pending(context)
        assertEquals(1, pending.size)
        assertContentEquals(payload(1), pending[42L])
        assertTrue(spool.exists(), "spooled fix is persisted in device-protected storage")
    }

    @Test
    fun newerFixReplacesOlderForSameRecipient() = runBlocking {
        OutboundSpool.store(context, recipient = 42L, reportedAtMs = 1_000L, payload = payload(1))
        OutboundSpool.store(context, recipient = 42L, reportedAtMs = 2_000L, payload = payload(2))
        val pending = OutboundSpool.pending(context)
        assertEquals(1, pending.size, "mailbox slot: one fix per recipient, no queue growth")
        assertContentEquals(payload(2), pending[42L])
    }

    @Test
    fun olderFixDoesNotDisplaceNewerSpooledFix() = runBlocking {
        OutboundSpool.store(context, recipient = 42L, reportedAtMs = 2_000L, payload = payload(2))
        OutboundSpool.store(context, recipient = 42L, reportedAtMs = 1_000L, payload = payload(1))
        assertContentEquals(payload(2), OutboundSpool.pending(context)[42L])
    }

    @Test
    fun recipientsHaveIndependentSlots() = runBlocking {
        OutboundSpool.store(context, recipient = 42L, reportedAtMs = 1_000L, payload = payload(1))
        OutboundSpool.store(context, recipient = 43L, reportedAtMs = 1_000L, payload = payload(3))
        OutboundSpool.store(context, recipient = 42L, reportedAtMs = 2_000L, payload = payload(2))
        val pending = OutboundSpool.pending(context)
        assertEquals(2, pending.size)
        assertContentEquals(payload(2), pending[42L])
        assertContentEquals(payload(3), pending[43L])
    }

    @Test
    fun reconnectFlushDeliversLatestPerRecipientAndAckClears() = runBlocking {
        OutboundSpool.store(context, recipient = 42L, reportedAtMs = 1_000L, payload = payload(1))
        OutboundSpool.store(context, recipient = 42L, reportedAtMs = 2_000L, payload = payload(2))
        OutboundSpool.store(context, recipient = 43L, reportedAtMs = 1_500L, payload = payload(3))
        val delivered = mutableListOf<Pair<Long, ByteArray>>()
        val sent = OutboundSpool.flush(context) { recipient, payload ->
            delivered += recipient to payload
            true
        }
        assertEquals(2, sent)
        assertEquals(listOf(42L, 43L), delivered.map { it.first })
        assertContentEquals(payload(2), delivered[0].second)
        assertContentEquals(payload(3), delivered[1].second)
        assertTrue(OutboundSpool.pending(context).isEmpty(), "acked slots are cleared")
        assertFalse(spool.exists(), "spool file removed once every slot is acked")
    }

    @Test
    fun failedFlushKeepsEverySlotForNextReconnect() = runBlocking {
        OutboundSpool.store(context, recipient = 42L, reportedAtMs = 1_000L, payload = payload(1))
        val sent = OutboundSpool.flush(context) { _, _ -> false }
        assertEquals(0, sent)
        assertContentEquals(payload(1), OutboundSpool.pending(context)[42L])
    }

    @Test
    fun ackDoesNotClearNewerFixSpooledDuringFlush() = runBlocking {
        OutboundSpool.store(context, recipient = 42L, reportedAtMs = 1_000L, payload = payload(1))
        // While the flush of fix 1 is in flight, a newer fix fails and takes the slot.
        val sent = OutboundSpool.flush(context) { _, _ ->
            OutboundSpool.store(context, recipient = 42L, reportedAtMs = 2_000L, payload = payload(2))
            true
        }
        assertEquals(1, sent, "the in-flight fix was delivered")
        assertContentEquals(
            payload(2), OutboundSpool.pending(context)[42L],
            "the older fix's ack must not clear the newer spooled fix",
        )
    }

    @Test
    fun successfulLivePublishRetiresSupersededSpoolOnly() = runBlocking {
        OutboundSpool.store(context, recipient = 42L, reportedAtMs = 1_000L, payload = payload(1))
        OutboundSpool.clearSuperseded(context, recipient = 42L, reportedAtMs = 2_000L)
        assertTrue(OutboundSpool.pending(context).isEmpty(), "older spooled fix retired by newer live fix")

        OutboundSpool.store(context, recipient = 42L, reportedAtMs = 3_000L, payload = payload(3))
        OutboundSpool.clearSuperseded(context, recipient = 42L, reportedAtMs = 2_000L)
        assertContentEquals(
            payload(3), OutboundSpool.pending(context)[42L],
            "newer spooled fix survives an older live publish",
        )
    }

    @Test
    fun tornTailRecordIsIgnoredAndCompleteSlotsKept() = runBlocking {
        OutboundSpool.store(context, recipient = 42L, reportedAtMs = 1_000L, payload = payload(1))
        // Simulate a crash mid-write: a partial header promising more than was written.
        spool.appendBytes(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 7))
        assertContentEquals(payload(1), OutboundSpool.pending(context)[42L])
    }
}
