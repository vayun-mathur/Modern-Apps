package com.vayunmathur.communicate.telephony

import android.content.ComponentName
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * Slice-4 coverage for the Telecom account registration helpers
 * ([GoogleVoiceTelecom], [InAppCallTelecom]).
 *
 * Robolectric with the TelecomManager shadow: asserts the account handles
 * point at our services and that register/unregister round-trips the account.
 */
@RunWith(RobolectricTestRunner::class)
class TelecomAccountsSlice4Test {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `google voice handle names the connection service and account`() {
        val handle = GoogleVoiceTelecom.handle(context)
        assertEquals(
            ComponentName(context.applicationContext, GoogleVoiceConnectionService::class.java),
            handle.componentName,
        )
        assertEquals("google_voice_line", handle.id)
    }

    @Test
    fun `in-app handle names the in-app connection service and account`() {
        val handle = InAppCallTelecom.handle(context)
        assertEquals(
            ComponentName(context.applicationContext, InAppCallConnectionService::class.java),
            handle.componentName,
        )
        assertEquals("communicate_inapp_calls", handle.id)
    }

    @Test
    fun `google voice register then unregister round-trips`() {
        val tm = context.getSystemService(android.telecom.TelecomManager::class.java)!!
        val shadow = shadowOf(tm)
        GoogleVoiceTelecom.registerPhoneAccount(context, "+1555")
        assertTrue(shadow.allPhoneAccounts.any { it.accountHandle == GoogleVoiceTelecom.handle(context) })
        GoogleVoiceTelecom.unregisterPhoneAccount(context)
        assertTrue(shadow.allPhoneAccounts.none { it.accountHandle == GoogleVoiceTelecom.handle(context) })
    }

    @Test
    fun `in-app register then unregister round-trips`() {
        val tm = context.getSystemService(android.telecom.TelecomManager::class.java)!!
        val shadow = shadowOf(tm)
        InAppCallTelecom.registerPhoneAccount(context, "Calls")
        assertEquals(1, shadow.allPhoneAccounts.count { it.accountHandle == InAppCallTelecom.handle(context) })
        InAppCallTelecom.unregisterPhoneAccount(context)
        assertTrue(shadow.allPhoneAccounts.none { it.accountHandle == InAppCallTelecom.handle(context) })
    }

    @Test
    fun `outgoing and incoming calls run without a telecom service present`() {
        // Robolectric has no real Telecom stack; every call is wrapped in
        // runCatching, so these must simply not throw.
        GoogleVoiceTelecom.placeOutgoing(context, "+1555")
        GoogleVoiceTelecom.addIncoming(context, "+1555")
        InAppCallTelecom.addOutgoing(context, "+1555")
        InAppCallTelecom.addIncoming(context, "+1555")
    }
}
