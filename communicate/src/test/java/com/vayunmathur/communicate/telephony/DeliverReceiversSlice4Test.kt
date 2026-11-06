package com.vayunmathur.communicate.telephony

import android.content.Intent
import android.provider.Telephony
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Slice-4 coverage for the SMS/MMS deliver receivers ([SmsDeliverReceiver],
 * [MmsDeliverReceiver]) early-out paths.
 *
 * Robolectric. Full delivery needs the telephony provider + system MMS
 * service; what is asserted here is the action filtering and the no-PDU
 * guards, so a misrouted broadcast is caught without device telephony.
 */
@RunWith(RobolectricTestRunner::class)
class DeliverReceiversSlice4Test {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `sms receiver ignores non-deliver actions and empty pdus`() {
        val receiver = SmsDeliverReceiver()
        // Wrong action: returns before touching the provider.
        receiver.onReceive(context, Intent("com.example.WRONG"))
        // Right action but no PDUs: logs and returns (no crash, no insert).
        receiver.onReceive(context, Intent(Telephony.Sms.Intents.SMS_DELIVER_ACTION))
        assertEquals(0, shadowOf(context.contentResolver).notifiedUris.size)
    }

    @Test
    fun `mms receiver ignores unknown actions and push without pdu`() {
        val receiver = MmsDeliverReceiver()
        receiver.onReceive(context, Intent("com.example.WRONG"))
        receiver.onReceive(
            context,
            Intent(Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION),
        )
        assertEquals(0, shadowOf(context.contentResolver).notifiedUris.size)
    }

    @Test
    fun `mms push with garbage pdu is swallowed not thrown`() {
        val receiver = MmsDeliverReceiver()
        receiver.onReceive(
            context,
            Intent(Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION).apply {
                putExtra("data", byteArrayOf(0x01, 0x02))
            },
        )
    }
}
