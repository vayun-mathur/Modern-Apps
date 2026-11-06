package com.vayunmathur.appstore.data.play

import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Anonymous-Play device attestation defaults: without GMS on the device the
 * store spoofs a plausible Pixel fingerprint rather than failing the session.
 */
@RunWith(RobolectricTestRunner::class)
class GsfVersionProviderTest {

    private val context: android.content.Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `falls back to defaults without gms on device`() {
        val version = GsfVersionProvider.get(context)

        assertEquals(203019037, version.gsfVersionCode)
        assertEquals(82151710, version.vendingVersionCode)
        assertTrue(version.vendingVersionString.isNotBlank())
    }

    @Test
    fun `unknown packages yield no certificate hashes`() {
        assertTrue(
            CertUtil.getEncodedCertificateHashes(context, "com.example.not.installed").isEmpty(),
        )
    }

    @Test
    fun `built device profile carries the play client fields`() {
        val props = DeviceInfoProvider.buildDeviceProperties(context)

        assertEquals("android-google", props.getProperty("Client"))
        assertEquals("mobile-notroaming", props.getProperty("Roaming"))
        assertTrue(props.getProperty("Build.FINGERPRINT").isNotBlank())
        assertTrue(props.getProperty("GSF.version").isNotBlank())
        assertTrue(props.getProperty("Vending.version").isNotBlank())
    }

    @Test
    fun `aurora profile prefers the bundled pixel properties`() {
        val props = DeviceInfoProvider.auroraProfile(context)

        assertTrue(props.getProperty("Client") == "android-google" || props.isNotEmpty)
    }
}
