package com.vayunmathur.parentalcontrols.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExemptPackagesTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun selfAndSettingsAreAlwaysExempt() {
        val exempt = ExemptPackages.resolve(ctx)
        assertTrue(ctx.packageName in exempt)
        assertTrue("com.android.settings" in exempt)
        assertTrue(ExemptPackages.isExempt(ctx, "com.android.settings"))
        assertTrue(ExemptPackages.isExempt(ctx, ctx.packageName))
    }

    @Test
    fun ordinaryPackagesAreNotExempt() {
        // No home/dialer role holders are installed in the harness, so only the
        // always-exempt packages above resolve; a normal app stays governable.
        assertFalse(ExemptPackages.isExempt(ctx, "com.example.game"))
    }
}
