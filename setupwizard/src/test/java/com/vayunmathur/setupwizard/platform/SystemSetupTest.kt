package com.vayunmathur.setupwizard.platform

import android.app.Activity
import android.app.Application
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class SystemSetupTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun `markSetupComplete writes provisioning flags without throwing`() {
        val system = SystemSetup(app)
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        // Must return normally: finishSetup() aborts mid-sequence (no task removal,
        // no finish(), no status-bar restore, no disableSelf()) if this throws.
        system.markSetupComplete(activity)
        assertTrue(system.isUserSetupComplete())
        if (system.isPrimaryUser) {
            assertEquals(
                1,
                Settings.Global.getInt(
                    activity.contentResolver,
                    Settings.Global.DEVICE_PROVISIONED,
                    0,
                ),
            )
        }
    }
}
