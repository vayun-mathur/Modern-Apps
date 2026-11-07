package com.vayunmathur.appstore.data

import android.content.Context
import android.content.pm.SigningInfo
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows

/**
 * UpdateOwnership over the OS install-source record: only packages
 * this store installed (or owns updates for) count as managed, and an
 * unknown package reads as not managed rather than throwing.
 */
@RunWith(RobolectricTestRunner::class)
class UpdateOwnershipTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun setSource(pkg: String, installer: String?, updateOwner: String? = null) {
        val shadow = Shadows.shadowOf(context.packageManager)
        if (updateOwner == null) {
            shadow.setInstallSourceInfo(pkg, installer, installer)
        } else {
            shadow.setInstallSourceInfo(
                pkg, installer, SigningInfo(), null, installer, updateOwner, 0,
            )
        }
    }

    @Test
    fun `a package this store installed is managed`() {
        setSource("com.example.ours", installer = context.packageName)
        assertTrue(UpdateOwnership.installedByStore(context, "com.example.ours"))
    }

    @Test
    fun `a package another store installed is not managed`() {
        setSource("com.example.theirs", installer = "com.android.vending")
        assertFalse(UpdateOwnership.installedByStore(context, "com.example.theirs"))
    }

    @Test
    fun `an unknown package is not managed`() {
        assertFalse(UpdateOwnership.installedByStore(context, "com.example.absent"))
    }

    @Test
    fun `update ownership alone makes a package managed`() {
        setSource(
            "com.example.owned",
            installer = "com.android.vending",
            updateOwner = context.packageName,
        )
        assertTrue(UpdateOwnership.installedByStore(context, "com.example.owned"))
    }
}
