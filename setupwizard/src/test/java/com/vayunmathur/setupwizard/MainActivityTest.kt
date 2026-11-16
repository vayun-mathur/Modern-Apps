package com.vayunmathur.setupwizard

import android.app.Application
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class MainActivityTest {
    private val app get() = ApplicationProvider.getApplicationContext<Application>()

    @Test fun `launch after setup completed finishes instead of showing the wizard again`() {
        Settings.Secure.putInt(app.contentResolver, "user_setup_complete", 1)
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.setup().get()
        // onCreateBeforeContent sees setup complete and runs finishSetup, whose last steps
        // finish the activity and disable the package: a re-launch must not re-run the flow.
        assertTrue(activity.isFinishing || activity.isDestroyed)
        Settings.Secure.putInt(app.contentResolver, "user_setup_complete", 0)
    }
}
