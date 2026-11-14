package com.vayunmathur.findfamily.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.findfamily.util.dispatchLiveFrame
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pre-unlock inbound spool: frames the relay drains to the pre-unlock socket must survive
 * until the unlocked path replays them (they used to be dropped).
 */
@RunWith(AndroidJUnit4::class)
class InboundSpoolTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir: File get() = context.createDeviceProtectedStorageContext().filesDir
    private val spool: File get() = File(dir, "ff_inbound_spool.bin")

    @BeforeTest
    fun clean() {
        dir.listFiles()?.filter { it.name.startsWith("ff_inbound_spool") }?.forEach { it.delete() }
    }

    @AfterTest
    fun tearDown() = clean()

    private fun frame(i: Int) = byteArrayOf(0x03, 0x00) + "frame-$i".encodeToByteArray()

    @Test
    fun framesComeBackInOrderAndTheSpoolIsCleared() = runBlocking {
        val sizes = (1..3).map { InboundSpool.append(context, frame(it)) }
        assertEquals(sizes.sorted(), sizes, "size grows with each append")
        val seen = mutableListOf<ByteArray>()
        val n = InboundSpool.drain(context) { seen.add(it) }
        assertEquals(3, n)
        (1..3).forEach { assertContentEquals(frame(it), seen[it - 1]) }
        assertFalse(spool.exists(), "spool deleted after a full replay")
        assertEquals(0, InboundSpool.drain(context) { error("nothing left") })
    }

    @Test
    fun aTornFinalRecordIsIgnoredAndEarlierFramesKept() = runBlocking {
        InboundSpool.append(context, frame(1))
        InboundSpool.append(context, frame(2))
        // Simulate power loss mid-append: a length prefix promising 100 bytes, then 3 bytes.
        spool.appendBytes(byteArrayOf(0, 0, 0, 100, 1, 2, 3))
        val seen = mutableListOf<ByteArray>()
        InboundSpool.drain(context) { seen.add(it) }
        assertEquals(2, seen.size)
        assertContentEquals(frame(2), seen[1])
    }

    @Test
    fun aFailedReplayResumesWithoutLosingOrRepeatingFrames() = runBlocking {
        (1..4).forEach { InboundSpool.append(context, frame(it)) }
        val first = mutableListOf<ByteArray>()
        assertFailsWith<IllegalStateException> {
            InboundSpool.drain(context) {
                if (first.size == 2) error("pipeline unavailable")
                first.add(it)
            }
        }
        assertTrue(spool.exists(), "spool kept after a failed replay")
        val second = mutableListOf<ByteArray>()
        InboundSpool.drain(context) { second.add(it) }
        assertEquals(listOf(1, 2), first.map { it.decodeToString().substringAfter("frame-").toInt() })
        assertEquals(listOf(3, 4), second.map { it.decodeToString().substringAfter("frame-").toInt() })
        assertFalse(spool.exists())
    }

    @Test
    fun rawModeHandsMsgFramesOverWithoutDecrypting() = runBlocking {
        val raw = mutableListOf<ByteArray>()
        val msg = frame(7) // op 0x03 = MSG; not valid ciphertext, so decrypting would fail
        dispatchLiveFrame(
            msg,
            onLocations = { error("must not decrypt/deliver in raw mode") },
            onUwb = { error("must not decrypt/deliver in raw mode") },
            onRawMsg = { raw.add(it) },
        )
        assertEquals(1, raw.size)
        assertContentEquals(msg, raw.single())
    }
}
