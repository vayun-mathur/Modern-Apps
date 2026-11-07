package com.vayunmathur.maps.ui

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.util.DataStoreUtils
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/**
 * Recording a consent choice directly (the non-ViewModel path): the write
 * lands on DataStore's IO threads, so the test polls the synchronous gates
 * with a bounded deadline instead of sleeping a fixed duration.
 * Slice-2 coverage for maps (basename G, outside the slice-1 dirs).
 */
@RunWith(AndroidJUnit4::class)
class GoogleConsentRecordSlice2Test {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun await(desc: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(25)
        }
        assertTrue(cond(), "timed out waiting for: $desc")
    }

    @Test fun `recording a choice flips both gates`() {
        val ds = DataStoreUtils.getInstance(context)
        recordGoogleConsent(ds, enabled = true)
        await("consent asked") { !shouldShowGoogleConsent(ds) }
        await("services enabled") { googleServicesEnabled(ds) }
        assertFalse(shouldShowGoogleConsent(ds))
        assertTrue(googleServicesEnabled(ds))

        recordGoogleConsent(ds, enabled = false)
        await("services disabled") { !googleServicesEnabled(ds) }
        assertFalse(googleServicesEnabled(ds))
        assertFalse(shouldShowGoogleConsent(ds))
    }
}
