package com.vayunmathur.passwords.ui

import android.app.Activity
import android.content.Intent
import com.vayunmathur.library.util.DatabaseHelper
import com.vayunmathur.passwords.FakeAndroidKeyStore
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PasskeyAuthActivityTest {

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        // Pretend the vault has already been unlocked once, so the activity
        // proceeds straight to flow dispatch instead of the biometric gate.
        val ctx = org.robolectric.RuntimeEnvironment.getApplication()
        DatabaseHelper(ctx).storePassphrase("test-passphrase")
    }

    private fun launch(flow: String?): PasskeyAuthActivity {
        val intent = Intent(
            org.robolectric.RuntimeEnvironment.getApplication(),
            PasskeyAuthActivity::class.java,
        )
        if (flow != null) intent.putExtra(PasskeyAuthActivity.EXTRA_FLOW, flow)
        val controller = Robolectric.buildActivity(PasskeyAuthActivity::class.java, intent)
        controller.create()
        return controller.get()
    }

    @Test fun unknownFlowCancelsAndFinishes() {
        val activity = launch(null)
        assertTrue(activity.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
    }

    @Test fun getFlowWithoutProviderRequestCancels() {
        val activity = launch(PasskeyAuthActivity.FLOW_GET)
        assertTrue(activity.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
    }

    @Test fun createFlowWithoutProviderRequestCancels() {
        val activity = launch(PasskeyAuthActivity.FLOW_CREATE)
        assertTrue(activity.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
    }

    @Test fun passwordFlowWithoutProviderRequestCancels() {
        val activity = launch(PasskeyAuthActivity.FLOW_PASSWORD)
        assertTrue(activity.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
    }

    @Test fun unlockFlowWithoutRequestCancels() {
        val activity = launch(PasskeyAuthActivity.FLOW_UNLOCK)
        assertTrue(activity.isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(activity).resultCode)
    }
}
