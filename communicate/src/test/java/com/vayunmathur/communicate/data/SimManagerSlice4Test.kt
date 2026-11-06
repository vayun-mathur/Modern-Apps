package com.vayunmathur.communicate.data

import android.Manifest
import android.app.Application
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Slice-4 coverage for SIM enumeration ([SimManager]).
 *
 * Robolectric (permission grant/deny + subscription shadow): pins the
 * no-permission empty fallback, the single/multi-SIM line choices, the
 * display-name/carrier/fallback label chain, and the subscription lookup —
 * the logic that decides which SIM line a message or call goes out on.
 */
@RunWith(RobolectricTestRunner::class)
class SimManagerSlice4Test {

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    private fun grantPhoneState() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
    }

    private fun denyPhoneState() {
        shadowOf(context).denyPermissions(Manifest.permission.READ_PHONE_STATE)
    }

    private fun subManager(): SubscriptionManager = context.getSystemService(SubscriptionManager::class.java)!!

    private fun setSubs(vararg infos: SubscriptionInfo) {
        shadowOf(subManager()).setActiveSubscriptionInfoList(infos.toList())
    }

    private fun info(subId: Int, slot: Int, display: String?, carrier: String?): SubscriptionInfo =
        org.robolectric.shadows.ShadowSubscriptionManager.SubscriptionInfoBuilder.newBuilder()
            .setId(subId)
            .setSimSlotIndex(slot)
            .apply { display?.let { setDisplayName(it) } }
            .apply { carrier?.let { setCarrierName(it) } }
            .buildSubscriptionInfo()

    @Test
    fun `no permission yields no sims and default line choice`() {
        denyPhoneState()
        assertTrue(SimManager.activeSims(context).isEmpty())
        val choices = SimManager.simLineChoices(context)
        assertEquals(1, choices.size)
        assertEquals(SimManager.defaultSmsSubscriptionId(), choices.single().subscriptionId)
        assertNull(SimManager.labelForSubscription(context, 1))
        assertNull(SimManager.labelForSubscription(context, null))
    }

    @Test
    fun `empty subscription list falls back to the default line`() {
        grantPhoneState()
        setSubs()
        assertTrue(SimManager.activeSims(context).isEmpty())
        assertEquals(1, SimManager.simLineChoices(context).size)
    }

    @Test
    fun `single sim keeps its name`() {
        grantPhoneState()
        setSubs(info(subId = 7, slot = 0, display = "Personal", carrier = "Carrier"))
        val sims = SimManager.activeSims(context)
        assertEquals(listOf(CommunicateSim(7, 0, "Personal")), sims)
        val choices = SimManager.simLineChoices(context)
        assertEquals(1, choices.size)
        assertEquals(LineChoice.Sim(7, "Personal"), choices.single())
        assertEquals("Personal", SimManager.labelForSubscription(context, 7))
    }

    @Test
    fun `blank display falls back to carrier then slot label`() {
        grantPhoneState()
        setSubs(
            info(subId = 1, slot = 0, display = "  ", carrier = "CarrierA"),
            info(subId = 2, slot = 1, display = null, carrier = ""),
        )
        val sims = SimManager.activeSims(context)
        assertEquals("CarrierA", sims[0].displayName)
        assertEquals("SIM 2", sims[1].displayName)
        // Multiple SIMs: one choice each.
        assertEquals(2, SimManager.simLineChoices(context).size)
        assertEquals("CarrierA", SimManager.labelForSubscription(context, 1))
        assertNull(SimManager.labelForSubscription(context, 999))
    }

    @Test
    fun `default sms id never throws`() {
        grantPhoneState()
        SimManager.defaultSmsSubscriptionId()
    }
}
