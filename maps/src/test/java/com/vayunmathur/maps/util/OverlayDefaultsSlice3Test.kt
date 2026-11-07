package com.vayunmathur.maps.util

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Slice 3/3 (root package files M-Z): [MapSettingsViewModel]'s overlay
 * progress fields read zero on a clean store.
 *
 * Isolated in its own class (fresh JVM per class) so no sibling test can
 * write the overlay keys first — the defaults assertion is inherently
 * order-dependent and this makes the order irrelevant.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class OverlayDefaultsSlice3Test {

    private val app get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Test
    fun `overlay progress fields default to zero`() {
        val vm = MapSettingsViewModel(app)
        assertEquals(0L, vm.overlayAppliedSeq.value)
        assertEquals(0L, vm.overlayPendingBytes.value)
        assertEquals(0L, vm.overlayLastCheck.value)
    }
}
