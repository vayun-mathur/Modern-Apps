package com.vayunmathur.contacts.pure

import android.Manifest
import android.app.Application
import android.telephony.SubscriptionManager
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.data.LOCAL_ACCOUNT_TYPE
import com.vayunmathur.contacts.data.SIM_ACCOUNT_TYPE
import com.vayunmathur.contacts.data.SimContactsDataSource
import com.vayunmathur.contacts.data.isDefaultLocalAccount
import com.vayunmathur.contacts.data.isLocalAccountType
import com.vayunmathur.contacts.data.isSimAccountType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Slice coverage for SIM account plumbing ([isSimAccountType],
 * [isLocalAccountType], [isDefaultLocalAccount],
 * [SimContactsDataSource.getActiveSubscriptionIds]).
 *
 * Robolectric (permission grant/deny + subscription shadow): pins which
 * account types the app treats as SIM/local/device-local, and that an empty
 * subscription list yields no ids — the routing the move/copy dialogs and
 * SIM import rely on.
 */
@RunWith(RobolectricTestRunner::class)
class SimAccountTypesTest {

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `sim account type matches only the sim type`() {
        assertTrue(isSimAccountType(SIM_ACCOUNT_TYPE))
        assertFalse(isSimAccountType(LOCAL_ACCOUNT_TYPE))
        assertFalse(isSimAccountType("com.google"))
        assertFalse(isSimAccountType(null))
        assertFalse(isSimAccountType(""))
    }

    @Test
    fun `local account type covers blank and the local type`() {
        assertTrue(isLocalAccountType(null))
        assertTrue(isLocalAccountType(""))
        assertTrue(isLocalAccountType(LOCAL_ACCOUNT_TYPE))
        assertFalse(isLocalAccountType(SIM_ACCOUNT_TYPE))
        assertFalse(isLocalAccountType("com.google"))
    }

    @Test
    fun `default local account needs both fields blank`() {
        assertTrue(isDefaultLocalAccount(null, null))
        assertTrue(isDefaultLocalAccount("", ""))
        assertFalse(isDefaultLocalAccount("a", null))
        assertFalse(isDefaultLocalAccount(null, "t"))
        assertFalse(isDefaultLocalAccount("a", "t"))
    }

    @Test
    fun `no subscriptions yields no ids with or without permission`() {
        shadowOf(context).denyPermissions(Manifest.permission.READ_PHONE_STATE)
        assertTrue(SimContactsDataSource.getActiveSubscriptionIds(context).isEmpty())
        shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        shadowOf(context.getSystemService(SubscriptionManager::class.java))
            .setActiveSubscriptionInfoList(emptyList())
        assertTrue(SimContactsDataSource.getActiveSubscriptionIds(context).isEmpty())
    }

    @Test
    fun `subscription ids mirror the shadow list`() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        val infos = listOf(
            org.robolectric.shadows.ShadowSubscriptionManager.SubscriptionInfoBuilder.newBuilder()
                .setId(11).setSimSlotIndex(0).buildSubscriptionInfo(),
            org.robolectric.shadows.ShadowSubscriptionManager.SubscriptionInfoBuilder.newBuilder()
                .setId(22).setSimSlotIndex(1).buildSubscriptionInfo(),
        )
        shadowOf(context.getSystemService(SubscriptionManager::class.java))
            .setActiveSubscriptionInfoList(infos)
        assertEquals(listOf(11, 22), SimContactsDataSource.getActiveSubscriptionIds(context))
    }
}
