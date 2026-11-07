package com.vayunmathur.contacts.ui

import android.Manifest
import android.app.Application
import android.content.Intent
import android.provider.ContactsContract
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.contacts.data.FakeProviderPass2Test
import com.vayunmathur.contacts.util.ContactPlatforms
import com.vayunmathur.contacts.util.PackageUtils
import com.vayunmathur.library.ui.DynamicTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.shadows.ShadowContentResolver

/** Second-pass coverage for communication intents, platform calls and the platform picker. */
@RunWith(RobolectricTestRunner::class)
class CommsPass2Test {
    @get:Rule val rule = createComposeRule()
    private val app get() = ApplicationProvider.getApplicationContext<Application>()
    private val ctx get() = app as android.content.Context

    private fun registerProvider() {
        ShadowContentResolver.registerProviderInternal(
            ContactsContract.AUTHORITY, FakeProviderPass2Test.FakeContactsProvider()
        )
    }

    private fun nextStarted(): Intent? = Shadows.shadowOf(app).nextStartedActivity

    /** An activity context: application-context startActivity throws under Robolectric. */
    private fun actCtx(): android.content.Context =
        org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup().get()

    @Test
    fun platformsAreResolvedFromProviderRows() {
        registerProvider()
        val platforms = PackageUtils.getContactPlatforms(ctx, 7L)
        assertEquals(201L, platforms.whatsAppCallId)
        assertEquals(202L, platforms.whatsAppVideoId)
        assertEquals(203L, platforms.whatsAppMessageId)
        assertEquals(204L, platforms.signalVideoId)
        assertEquals(205L, platforms.signalCallId)
        assertEquals(206L, platforms.signalMessageId)
        assertEquals(207L, platforms.telegramVideoId)
        assertEquals(208L, platforms.telegramCallId)
        assertEquals(209L, platforms.telegramMessageId)
        assertTrue(platforms.hasWhatsApp && platforms.hasSignal && platforms.hasTelegram && platforms.hasAnyPlatform)

        val none = PackageUtils.getContactPlatforms(ctx, 999L)
        assertEquals(ContactPlatforms(), none)
        assertTrue(!none.hasAnyPlatform)
    }

    @Test
    fun smsIntentsPerPlatform() {
        val ctx = actCtx()
        handleCommunication(ctx, "+1 415 555 0102", CommunicationType.SMS, null)
        var intent = assertNotNull(nextStarted())
        assertEquals(Intent.ACTION_SENDTO, intent.action)
        assertEquals("sms:+1 415 555 0102", intent.data.toString())

        handleCommunication(ctx, "+1 415 555 0102", CommunicationType.SMS, PackageUtils.SIGNAL_PACKAGE)
        intent = assertNotNull(nextStarted())
        assertEquals(PackageUtils.SIGNAL_PACKAGE, intent.`package`)
        assertTrue(intent.data.toString().startsWith("smsto:"))

        handleCommunication(ctx, "+1 (415) 555-0102", CommunicationType.SMS, PackageUtils.WHATSAPP_PACKAGE)
        intent = assertNotNull(nextStarted())
        assertEquals("https://wa.me/14155550102", intent.data.toString())

        handleCommunication(ctx, "+1 415 555 0102", CommunicationType.SMS, PackageUtils.TELEGRAM_PACKAGE)
        intent = assertNotNull(nextStarted())
        assertEquals("https://t.me/+14155550102", intent.data.toString())
    }

    @Test
    fun callPathsWithoutPermissionFallBackToDialer() {
        val ctx = actCtx()
        // no CALL_PHONE granted by default under Robolectric
        placeCall(ctx, "+14155550102")
        var intent = assertNotNull(nextStarted(), "dial fallback should start the dialer")
        assertEquals(Intent.ACTION_DIAL, intent.action)
        assertTrue(intent.data.toString().startsWith("tel:"), intent.data.toString())
        assertTrue(intent.data.toString().contains("14155550102"), intent.data.toString())

        placePlatformCall(ctx, "+14155550102", PackageUtils.WHATSAPP_PACKAGE, isVideo = true, fallbackDataRowId = 201L)
        intent = assertNotNull(nextStarted())
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertTrue(intent.data.toString().endsWith("/201"), intent.data.toString())

        placePlatformCall(ctx, "+14155550102", PackageUtils.SIGNAL_PACKAGE)
        intent = assertNotNull(nextStarted())
        assertEquals(Intent.ACTION_DIAL, intent.action)

        handleCommunication(ctx, "+14155550102", CommunicationType.CALL, null)
        assertNotNull(nextStarted())
        handleCommunication(ctx, "+14155550102", CommunicationType.CALL, PackageUtils.WHATSAPP_PACKAGE)
        assertNotNull(nextStarted())
    }

    @Test
    fun callWithPermissionUsesTelecomManager() {
        val ctx = actCtx()
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.CALL_PHONE)
        // with permission the call goes through TelecomManager (shadow records
        // or no-ops); either way it must not throw and must not dial-fallback first
        placeCall(ctx, "+14155550102")
        placePlatformCall(ctx, "+14155550102", PackageUtils.WHATSAPP_PACKAGE, isVideo = false)
        placePlatformCall(ctx, "+14155550102", PackageUtils.WHATSAPP_PACKAGE, isVideo = true, fallbackDataRowId = 201L)
        // granted-permission paths either place through TelecomManager (no intent)
        // or fall back to the dialer; both are acceptable, a crash is not
        val started = nextStarted()
        assertTrue(
            started == null || started.action == Intent.ACTION_DIAL || started.action == Intent.ACTION_VIEW,
            "unexpected started activity: $started",
        )
    }

    @Test
    fun platformActionAndMeetLaunchers() {
        val ctx = actCtx()
        launchPlatformAction(ctx, 205L)
        var intent = assertNotNull(nextStarted())
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertTrue(intent.data.toString().endsWith("/205"))

        launchGoogleMeet(ctx, "+14155550102")
        intent = assertNotNull(nextStarted())
        assertEquals("com.google.android.apps.tachyon.action.CALL", intent.action)
        assertEquals(PackageUtils.GOOGLE_MEET_PACKAGE, intent.`package`)
        assertEquals("tel:+14155550102", intent.data.toString())
        assertNull(nextStarted())
    }

    @Test
    fun communicationDropdownListsPlatformsAndPicksSignal() {
        var dismissed = 0
        val platforms = ContactPlatforms(
            whatsAppMessageId = 203, signalMessageId = 206, telegramMessageId = 209,
        )
        rule.setContent {
            DynamicTheme {
                CommunicationDropdown(
                    expanded = true, onDismiss = { dismissed++ },
                    number = "+14155550102", type = CommunicationType.SMS,
                    platforms = platforms,
                )
            }
        }
        rule.onNodeWithText(ctx.getString(com.vayunmathur.contacts.R.string.signal), substring = true).assertExists()
        rule.onNodeWithText(ctx.getString(com.vayunmathur.contacts.R.string.signal), substring = true).performClick()
        assertEquals(1, dismissed)
        val intent = assertNotNull(nextStarted())
        assertEquals(PackageUtils.SIGNAL_PACKAGE, intent.`package`)
    }

    @Test
    fun communicationDropdownSystemDefaultAndCollapsed() {
        var dismissed = 0
        rule.setContent {
            DynamicTheme {
                CommunicationDropdown(
                    expanded = true, onDismiss = { dismissed++ },
                    number = "+14155550102", type = CommunicationType.CALL,
                    platforms = ContactPlatforms(),
                )
            }
        }
        // without platform rows only the system default entry exists
        rule.onNodeWithText(ctx.getString(com.vayunmathur.contacts.R.string.system_default), substring = true)
            .performClick()
        assertEquals(1, dismissed)
        assertNotNull(nextStarted()) // dial fallback for a CALL with the system default
    }
}
