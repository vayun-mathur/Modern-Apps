package com.vayunmathur.appstore.data.play

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The device profile handed to Play's auth dispenser: the bundled
 * Aurora (Pixel 9a) profile loads with its build identity intact.
 * (buildDeviceProperties, the fallback for a missing profile resource,
 * reads PackageManager feature lists that Robolectric returns null
 * for, so it is not exercised here.)
 */
@RunWith(RobolectricTestRunner::class)
class DeviceInfoProviderTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `aurora profile loads a usable property set`() {
        val props = DeviceInfoProvider.auroraProfile(context)
        assertTrue(props.isNotEmpty())
        // The bundled Pixel 9a profile the dispenser is tuned for.
        assertEquals("google", props.getProperty("Build.BRAND"))
        assertEquals("Pixel 9a", props.getProperty("Build.MODEL"))
    }

    @Test
    fun `gsf version provider answers without crashing`() {
        // The GSF id/version come from a content provider that does not
        // exist under Robolectric; the provider must degrade, not throw.
        GsfVersionProvider.get(context)
    }
}
