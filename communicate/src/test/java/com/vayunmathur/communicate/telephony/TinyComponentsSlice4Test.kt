package com.vayunmathur.communicate.telephony

import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

/**
 * Slice-4 coverage for the trivially-shaped services ([CommunicateCarrierService],
 * [RespondViaMessageService]).
 *
 * Robolectric: pins the bind/no-op contract so a lifecycle regression (e.g. a
 * service that starts binding) is caught without device telephony.
 */
@RunWith(RobolectricTestRunner::class)
class TinyComponentsSlice4Test {

    @Test
    fun `carrier service loads an empty config`() {
        val service = Robolectric.setupService(CommunicateCarrierService::class.java)
        val config = service.onLoadConfig(
            android.service.carrier.CarrierIdentifier("mcc", "mnc", "spn", "imsi", "gid1", "gid2"),
        )
        assertEquals(0, config.size())
    }

    @Test
    fun `respond-via-message binds nothing`() {
        val service = Robolectric.setupService(RespondViaMessageService::class.java)
        assertNull(service.onBind(android.content.Intent()))
    }
}
