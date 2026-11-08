package com.vayunmathur.appstore.data.accrescent

import android.content.Context
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Device attributes handed to Accrescent for split selection: they must
 * describe this device (SDK, ABIs, density), and a phone-like Robolectric
 * device must not report itself as a TV.
 */
@RunWith(RobolectricTestRunner::class)
class DeviceAttributesProviderTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `device spec describes the running device`() {
        val spec = DeviceAttributesProvider(context).deviceSpec()
        assertEquals(Build.VERSION.SDK_INT, spec.sdkVersion)
        assertEquals(Build.SUPPORTED_ABIS.toList(), spec.supportedAbisList)
        assertEquals(Build.BRAND, spec.buildBrand)
        assertEquals(Build.DEVICE, spec.buildDevice)
        assertTrue(spec.screenDensity > 0, "density was ${spec.screenDensity}")
        assertTrue(spec.supportedLocalesList.isNotEmpty())
    }

    @Test
    fun `device attributes wrap the same device spec`() {
        val provider = DeviceAttributesProvider(context)
        val attributes = provider.deviceAttributes()
        assertEquals(provider.deviceSpec().sdkVersion, attributes.spec.sdkVersion)
        assertEquals(provider.deviceSpec().supportedAbisList, attributes.spec.supportedAbisList)
    }

    @Test
    fun `robolectric device is not a tv`() {
        assertFalse(DeviceAttributesProvider(context).isTv())
    }
}
