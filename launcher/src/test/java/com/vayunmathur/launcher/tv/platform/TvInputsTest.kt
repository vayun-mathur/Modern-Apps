package com.vayunmathur.launcher.tv.platform

import androidx.test.core.app.ApplicationProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Tests for [TvInputs]: with no TV input service under Robolectric the
 * list is empty, and opening an input with no handlers is a safe no-op.
 */
@RunWith(RobolectricTestRunner::class)
class TvInputsTest {

    private fun ctx() = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun noServiceListsNothing() {
        assertEquals(emptyList(), TvInputs.list(ctx(), max = 4))
    }

    @Test fun openWithNoHandlersDoesNotThrow() {
        // No Live TV apps installed: every startActivity throws and is swallowed.
        TvInputs.open(ctx(), "hdmi1")
    }

    @Test fun maxHdmiSlotsMath() {
        // Sanity: the bottom row always keeps room for Settings and Add.
        assertTrue(
            com.vayunmathur.launcher.tv.domain.TvRowOrder.maxHdmiSlots(hasStore = true) <=
                com.vayunmathur.launcher.tv.domain.TvRowOrder.maxHdmiSlots(hasStore = false),
        )
    }
}
