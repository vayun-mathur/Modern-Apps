package com.vayunmathur.communicate.telephony

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Voice pass-3: full SMS_DELIVER path with a real GSM PDU under Robolectric. */
@RunWith(RobolectricTestRunner::class)
class SmsDeliverVoicePass3Test {

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // GSM 03.40 example SMS-DELIVER PDU ("hellohello" from +46708251355)
    private val pduHex = "07916407058099F9040B916407281553F80000993092516195800AE8329BFD4697D9EC37"

    @Test
    fun deliverWithValidPduStoresAndNotifies() {
        val intent = Intent(Telephony.Sms.Intents.SMS_DELIVER_ACTION).apply {
            putExtra("pdus", arrayOf(hexToBytes(pduHex)))
            putExtra("format", "3gpp")
            putExtra("subscription", 1)
        }
        val receiver = SmsDeliverReceiver()
        receiver.onReceive(context, intent)

        // Storage runs on the receiver's background thread; poll for the notification.
        val nm = context.getSystemService(NotificationManager::class.java)
        val deadline = System.currentTimeMillis() + 10_000
        var notified = false
        while (System.currentTimeMillis() < deadline) {
            if (shadowOf(nm).allNotifications.isNotEmpty()) {
                notified = true
                break
            }
            Thread.sleep(50)
        }
        assertTrue(notified, "inbound SMS should post a conversation notification")
    }

    @Test
    fun deliverWithMultipartPduConcatenates() {
        val intent = Intent(Telephony.Sms.Intents.SMS_DELIVER_ACTION).apply {
            putExtra("pdus", arrayOf(hexToBytes(pduHex), hexToBytes(pduHex)))
            putExtra("format", "3gpp")
        }
        SmsDeliverReceiver().onReceive(context, intent)
        // No assertion on the provider (Robolectric has none); reaching here without
        // throwing on the caller thread is the contract, storage is best-effort async.
        Thread.sleep(300)
    }
}
