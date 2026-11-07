package com.vayunmathur.communicate.rcs

import android.content.Context
import android.os.PersistableBundle
import android.telephony.CarrierConfigManager
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.communicate.data.rcs.RcsCapabilityExchange
import com.vayunmathur.communicate.data.rcs.RcsGbaBootstrap
import com.vayunmathur.communicate.data.rcs.RcsHiddenApi
import com.vayunmathur.communicate.data.rcs.RcsProvisioning
import com.vayunmathur.communicate.data.rcs.RcsRegistrationState
import com.vayunmathur.communicate.data.rcs.RcsUnavailableReason
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSubscriptionManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice-3 pass 2: the full provisioning decision tree. A shadow
 * ProvisioningManager that reports single-reg capable (this class) plus
 * carrier-config / system-feature control covers Available and both
 * negative mappings; without the shadow the framework verdict is false
 * and the privilege branch decides.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [RcsSlice3Pass2ProvisioningShadow::class])
class RcsSlice3Pass2ProvisioningTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val subId = 5

    private fun setCarrierUrl(url: String?) {
        val ccm = context.getSystemService(CarrierConfigManager::class.java)
        val bundle = PersistableBundle()
        if (url != null) {
            bundle.putString(CarrierConfigManager.KEY_RCS_CONFIG_SERVER_URL_STRING, url)
        }
        shadowOf(ccm).setConfigForSubId(subId, bundle)
    }

    private fun setSingleRegFeature(enabled: Boolean) {
        shadowOf(context.packageManager)
            .setSystemFeature("android.hardware.telephony.ims.singlereg", enabled)
    }

    @Test
    fun probeAvailableWhenCapableAndFeaturePresent() = runTest {
        ShadowSubscriptionManager.setDefaultSmsSubscriptionId(subId)
        setCarrierUrl("https://config.example/rcs")
        setSingleRegFeature(true)
        val state = RcsProvisioning.probe(context, subId)
        assertEquals(RcsRegistrationState.Available, state)
        assertEquals(RcsRegistrationState.Available, RcsProvisioning.state.value)
        assertEquals("https://config.example/rcs", RcsProvisioning.lastConfigServerUrl)
    }

    @Test
    fun probeNotSupportedWhenFeatureMissingDespiteCapable() = runTest {
        setCarrierUrl("https://config.example/rcs")
        setSingleRegFeature(false)
        assertEquals(
            RcsRegistrationState.Unavailable(RcsUnavailableReason.NotSupported),
            RcsProvisioning.probe(context, subId),
        )
    }

    @Test
    fun probeBlankCarrierUrlStillProbesThrough() = runTest {
        // MVNO/Jibe case: no config URL, but single-reg says capable.
        setCarrierUrl(null)
        setSingleRegFeature(true)
        assertEquals(RcsRegistrationState.Available, RcsProvisioning.probe(context, subId))
        assertNull(RcsProvisioning.lastConfigServerUrl)
    }

    @Test
    fun probeProvisioningRequiredGate() = runTest {
        RcsSlice3Pass2ProvisioningShadow.provisioningRequired = true
        try {
            setCarrierUrl("https://config.example/rcs")
            setSingleRegFeature(true)
            assertEquals(
                RcsRegistrationState.Unavailable(RcsUnavailableReason.ProvisioningRequired),
                RcsProvisioning.probe(context, subId),
            )
        } finally {
            RcsSlice3Pass2ProvisioningShadow.reset()
        }
    }

    @Test
    fun probeCarrierConfigReadFailureIsServiceUnavailable() = runTest {
        val ccm = context.getSystemService(CarrierConfigManager::class.java)
        org.robolectric.Shadows.shadowOf(ccm).setReadPhoneStatePermission(false)
        try {
            assertEquals(
                RcsRegistrationState.Unavailable(RcsUnavailableReason.ServiceUnavailable),
                RcsProvisioning.probe(context, subId),
            )
        } finally {
            org.robolectric.Shadows.shadowOf(ccm).setReadPhoneStatePermission(true)
        }
    }

    @Test
    fun probeNoCarrierPrivilegeWhenFrameworkSaysNotCapable() = runTest {
        RcsSlice3Pass2ProvisioningShadow.capable = false
        try {
            setCarrierUrl("https://config.example/rcs")
            setSingleRegFeature(true)
            // Framework says not capable and we hold no carrier privilege.
            assertEquals(
                RcsRegistrationState.Unavailable(RcsUnavailableReason.NoCarrierPrivilege),
                RcsProvisioning.probe(context, subId),
            )
        } finally {
            RcsSlice3Pass2ProvisioningShadow.reset()
        }
    }

    @Test
    fun hiddenApiRegisterCallbackAgainstFrameworkManager() {
        val manager = RcsHiddenApi.provisioningManager(subId)
        assertNotNull(manager)
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        var calls = 0
        val ok = RcsHiddenApi.registerProvisioningCallback(
            manager, executor, "6.0", "UP_2.0",
            object : RcsHiddenApi.ProvisioningCallback {
                override fun onConfigurationChanged(configXml: ByteArray) { calls++ }
                override fun onConfigurationReset() { calls++ }
                override fun onRemoved() { calls++ }
            },
        )
        // The JVM framework accepts the registration (shadow service);
        // what matters here is that it never throws and no callback fires
        // without a carrier config change behind it.
        assertTrue(ok)
        assertEquals(0, calls)
        RcsHiddenApi.unregisterProvisioningCallback(manager)
        executor.shutdown()
    }

    @Test
    fun resetClearsProbeState() = runTest {
        setCarrierUrl("https://config.example/rcs")
        setSingleRegFeature(true)
        RcsProvisioning.probe(context, subId)
        RcsProvisioning.reset()
        assertEquals(RcsRegistrationState.Unknown, RcsProvisioning.state.value)
        assertNull(RcsProvisioning.lastConfigServerUrl)
    }

    @Test
    fun hiddenApiAgainstFrameworkProvisioningManager() {
        val manager = RcsHiddenApi.provisioningManager(subId)
        assertNotNull(manager)
        assertEquals(true, RcsHiddenApi.isSingleRegCapable(manager))
    }

    @Test
    fun capabilityQueriesWithValidSubscriptionReachImsManager() = runTest {
        ShadowSubscriptionManager.setDefaultSmsSubscriptionId(subId)
        // The UCE adapter under Robolectric has no carrier service behind
        // it; queries must still fail closed without throwing.
        assertFalse(RcsCapabilityExchange.isContactRcsCapable(context, "+15551234567"))
        assertEquals(
            emptyList(),
            RcsCapabilityExchange.filterCapable(context, listOf("+15551234567", "+15557654321")),
        )
    }

    @Test
    fun gbaBootstrapWithValidSubscriptionDegradesToNull() = runTest {
        ShadowSubscriptionManager.setDefaultSmsSubscriptionId(subId)
        setCarrierUrl("https://config.example/rcs")
        // No ISIM/GBA runtime on the JVM: bootstrap degrades to null rather
        // than throwing or hanging past its timeout.
        assertNull(RcsGbaBootstrap.bootstrap(context, "https://naf.example/ft", force = false))
        assertNull(RcsGbaBootstrap.bootstrap(context, "https://naf.example/ft", force = true))
    }

    @Test
    fun registrationStatesAndReasonsAreDistinct() {
        val reasons = RcsUnavailableReason.entries
        assertEquals(9, reasons.size)
        assertEquals(RcsUnavailableReason.NoEntitlementUrl, reasons.first())
        assertTrue(RcsRegistrationState.Unavailable(RcsUnavailableReason.Unknown) !=
            RcsRegistrationState.Unavailable(RcsUnavailableReason.NotSupported))
        assertTrue(RcsRegistrationState.Available != RcsRegistrationState.Provisioning)
        assertTrue(RcsRegistrationState.Unknown != RcsRegistrationState.Disabled)
    }
}
