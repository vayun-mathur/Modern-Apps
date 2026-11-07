package com.vayunmathur.appstore.data.accrescent

import android.content.Context
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * The TV verdict: leanback present and touchscreen absent is a TV;
 * either half flipped is not, so a leanback-capable tablet or a
 * touchscreen TV box is not treated as a leanback-only device.
 */
@RunWith(RobolectricTestRunner::class)
class DeviceAttributesTvTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun provider(leanback: Boolean, touchscreen: Boolean): DeviceAttributesProvider {
        val shadow = Shadows.shadowOf(context.packageManager)
        shadow.setSystemFeature(PackageManager.FEATURE_LEANBACK, leanback)
        shadow.setSystemFeature(PackageManager.FEATURE_TOUCHSCREEN, touchscreen)
        return DeviceAttributesProvider(context)
    }

    @Test
    fun `leanback without touchscreen is a tv`() {
        assertTrue(provider(leanback = true, touchscreen = false).isTv())
    }

    @Test
    fun `leanback with touchscreen is not a tv`() {
        assertFalse(provider(leanback = true, touchscreen = true).isTv())
    }

    @Test
    fun `touchscreen without leanback is not a tv`() {
        assertFalse(provider(leanback = false, touchscreen = true).isTv())
    }
}
