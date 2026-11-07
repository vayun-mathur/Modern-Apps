package com.vayunmathur.euicc.telephony

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class EuiccChannelManagerTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Test fun `isdr aid constant is SGP22 aid`() {
        assertEquals("A0000005591010FFFFFFFF8900000100", EuiccChannelManager.ISDR_AID)
    }

    @Test fun `withIsdrChannel fails without eUICC and does not run block`() {
        val manager = EuiccChannelManager(ctx)
        var ran = false
        val ex = assertFailsWith<EuiccException> {
            manager.withIsdrChannel { ran = true }
        }
        assertTrue(!ran)
        assertTrue(ex.message!!.contains("eUICC") || ex.message!!.contains("ISD-R") || ex.message!!.contains("TelephonyManager"), ex.message!!)
    }

    @Test fun `euicc exception carries message`() {
        assertEquals("no card", EuiccException("no card").message)
    }
}
