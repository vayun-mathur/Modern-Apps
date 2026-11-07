package com.vayunmathur.appstore.data.play

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * GsfVersionProvider with GMS and the Play Store actually installed:
 * the reported version codes and vending version string come from the
 * device, not from the bundled defaults.
 */
@RunWith(RobolectricTestRunner::class)
class GsfVersionInstalledTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun register(pkg: String, versionCode: Int, versionName: String?) {
        val info = PackageInfo().apply {
            packageName = pkg
            @Suppress("DEPRECATION")
            this.versionCode = versionCode
            this.versionName = versionName
            applicationInfo = ApplicationInfo().apply { packageName = pkg }
        }
        Shadows.shadowOf(context.packageManager).addPackage(info)
    }

    @Test
    fun `installed gms and vending versions are reported`() {
        register("com.google.android.gsf", 123456, "1.0")
        register("com.android.vending", 654321, "99.1.2")

        val version = GsfVersionProvider.get(context)

        assertEquals(123456, version.gsfVersionCode)
        assertEquals(654321, version.vendingVersionCode)
        assertEquals("99.1.2", version.vendingVersionString)
    }

    @Test
    fun `a vending build with no version name falls back to the default string`() {
        register("com.android.vending", 777, null)

        val version = GsfVersionProvider.get(context)

        assertEquals(777, version.vendingVersionCode)
        assertEquals("21.5.17-21 [0] [PR] 326734551", version.vendingVersionString)
        // GSF is absent in this test, so its code stays at the default.
        assertEquals(203019037, version.gsfVersionCode)
    }
}
