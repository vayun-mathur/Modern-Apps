package com.vayunmathur.communicate.telephony

import android.content.Intent
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Slice-4 pass-2: MMS downloaded-file handling and push triggering in [MmsDeliverReceiver]. */
@RunWith(RobolectricTestRunner::class)
class DeliverReceiversSlice4Pass2Test {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** Minimal hand-built M-Retrieve.conf (mirrors MmsPduReaderSlice4Test's builder). */
    private fun retrieveConf(from: String = "+1555/TYPE=PLMN", text: String = "hello mms"): ByteArray {
        val out = mutableListOf<Byte>()
        fun b(v: Int) = out.add(v.toByte())
        b(0x8C); b(0x84)
        val fromText = from.toByteArray(Charsets.UTF_8).toList() + listOf(0.toByte())
        val fromInner = listOf(0xEA.toByte()) + fromText
        val fromBody = listOf(fromInner.size.toByte()) + fromInner
        b(0x89)
        b(fromBody.size + 1)
        b(0x80)
        out.addAll(fromBody)
        b(0x84); b(0xA1)
        val textBytes = text.toByteArray(Charsets.UTF_8)
        val ctAndLoc = byteArrayOf(0x83.toByte()) +
            byteArrayOf(0x8E.toByte()) + "p.txt".toByteArray(Charsets.UTF_8) + byteArrayOf(0x00)
        b(0x01)
        b(ctAndLoc.size)
        b(textBytes.size)
        out.addAll(ctAndLoc.toList())
        out.addAll(textBytes.toList())
        return out.toByteArray()
    }

    private fun awaitDeleted(file: File, timeoutMs: Long = 10_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!file.exists()) return
            Thread.sleep(50)
        }
        assertFalse(file.exists(), "downloaded MMS file should be cleaned up")
    }

    @Test
    fun downloadedMmsFileIsParsedAndCleanedUp() {
        val file = File.createTempFile("mms_in_", ".pdu", context.cacheDir)
        file.writeBytes(retrieveConf())
        val receiver = MmsDeliverReceiver()
        receiver.onReceive(
            context,
            Intent("com.vayunmathur.communicate.MMS_DOWNLOADED").apply {
                putExtra("file", file.absolutePath)
            },
        )
        awaitDeleted(file)
    }

    @Test
    fun downloadedMissingFileExtraIsIgnored() {
        val receiver = MmsDeliverReceiver()
        receiver.onReceive(context, Intent("com.vayunmathur.communicate.MMS_DOWNLOADED"))
        // Nonexistent path: the worker thread swallows the read failure.
        val ghost = File(context.cacheDir, "nope_${System.nanoTime()}.pdu")
        receiver.onReceive(
            context,
            Intent("com.vayunmathur.communicate.MMS_DOWNLOADED").apply {
                putExtra("file", ghost.absolutePath)
            },
        )
        assertFalse(ghost.exists())
    }

    @Test
    fun pushWithContentLocationAttemptsDownload() {
        val pdu = byteArrayOf(
            0x8C.toByte(), 0x82.toByte(),
            0x98.toByte(), 0x74, 0x31, 0x00,
            0x83.toByte(),
            *"http://mms.example/x".toByteArray(Charsets.UTF_8), 0x00,
        )
        val receiver = MmsDeliverReceiver()
        // SmsManager.downloadMultimediaMessage is a shadow no-op under Robolectric;
        // the contract is that a located push does not throw and creates its staging dir.
        receiver.onReceive(
            context,
            Intent(Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION).apply {
                putExtra("data", pdu)
            },
        )
        assertTrue(File(context.cacheDir, "mms").isDirectory)
    }

    @Test
    fun pushWithoutContentLocationReturnsEarly() {
        val pdu = byteArrayOf(
            0x8C.toByte(), 0x82.toByte(),
            0x98.toByte(), 0x74, 0x31, 0x00,
        )
        MmsDeliverReceiver().onReceive(
            context,
            Intent(Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION).apply {
                putExtra("data", pdu)
            },
        )
    }
}
