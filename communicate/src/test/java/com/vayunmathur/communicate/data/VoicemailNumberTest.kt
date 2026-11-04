package com.vayunmathur.communicate.data

import android.Manifest
import android.app.Application
import android.content.Context
import android.telephony.TelephonyManager
import androidx.test.core.app.ApplicationProvider
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowTelephonyManager

/**
 * Pilot lookups for long-press-1-calls-voicemail (issue #899).
 *
 * [TelephonyManager.getVoiceMailNumber] is a framework call the unit-test stub android.jar
 * answers with "not mocked", so these run under Robolectric: the shadow holds a settable
 * pilot per manager, plus the permission flag the lookup is gated on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VoicemailNumberTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private lateinit var telephonyManager: TelephonyManager
    private lateinit var shadowTelephonyManager: ShadowTelephonyManager

    @BeforeTest
    fun setUp() {
        telephonyManager =
            RuntimeEnvironment.getApplication().getSystemService(Context.TELEPHONY_SERVICE)
                as TelephonyManager
        shadowTelephonyManager = Shadow.extract(telephonyManager)
        shadowOf(context as Application).grantPermissions(Manifest.permission.READ_PHONE_STATE)
    }

    /** A manager for [subId], registered the way the framework wires subscription managers. */
    private fun telephonyManagerFor(subId: Int): TelephonyManager {
        val scoped = Shadow.newInstance(
            TelephonyManager::class.java,
            arrayOf(Context::class.java, Int::class.java),
            arrayOf(context, subId),
        )
        shadowTelephonyManager.setTelephonyManagerForSubscriptionId(subId, scoped)
        return scoped
    }

    @Test
    fun `returns carrier pilot when permission granted`() {
        shadowTelephonyManager.setVoiceMailNumber("+15551234567")

        assertEquals(
            "+15551234567",
            CommunicateRepository.voicemailNumber(context, choice = null),
        )
    }

    @Test
    fun `returns null without phone-state permission`() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.READ_PHONE_STATE)
        shadowTelephonyManager.setVoiceMailNumber("+15551234567")

        assertNull(CommunicateRepository.voicemailNumber(context, choice = null))
    }

    @Test
    fun `returns null when carrier stored no pilot`() {
        // Shadow default: no pilot set.

        assertNull(CommunicateRepository.voicemailNumber(context, choice = null))
    }

    @Test
    fun `returns null for blank pilot`() {
        shadowTelephonyManager.setVoiceMailNumber("  ")

        assertNull(CommunicateRepository.voicemailNumber(context, choice = null))
    }

    @Test
    fun `returns null for google voice line`() {
        shadowTelephonyManager.setVoiceMailNumber("+15551234567")

        assertNull(
            CommunicateRepository.voicemailNumber(context, LineChoice.GoogleVoice),
        )
    }

    @Test
    fun `resolves sim pilot through its subscription manager`() {
        shadowTelephonyManager.setVoiceMailNumber("+15550000000")
        shadowOf(telephonyManagerFor(7)).setVoiceMailNumber("+15557654321")

        assertEquals(
            "+15557654321",
            CommunicateRepository.voicemailNumber(context, LineChoice.Sim(7, "SIM 1")),
        )
    }

    @Test
    fun `falls back to default manager for unmapped subscription`() {
        shadowTelephonyManager.setVoiceMailNumber("+15550000000")

        assertEquals(
            "+15550000000",
            CommunicateRepository.voicemailNumber(context, LineChoice.Sim(99, "SIM 2")),
        )
    }
}
