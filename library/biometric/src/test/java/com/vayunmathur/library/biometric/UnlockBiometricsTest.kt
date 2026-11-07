package com.vayunmathur.library.biometric

import android.content.Context
import android.os.Looper
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ApplicationProvider
import java.security.KeyStore
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [unlockDatabaseWithBiometrics] prompt setup.
 *
 * Robolectric's biometric manager reports BIOMETRIC_SUCCESS and the prompt itself
 * never produces an authentication result, so these pin the parts that run before
 * a result could arrive: key generation on first use, and reaching the prompt
 * without invoking either callback. The unavailable-device branch and the
 * authentication callbacks cannot be driven without a biometric HAL.
 */
@RunWith(RobolectricTestRunner::class)
class UnlockBiometricsTest {

    @Before
    fun setUp() {
        FakeAndroidKeyStore.install()
        FakeAndroidKeyStore.remove("db_auth_key")
        ApplicationProvider.getApplicationContext<Context>()
            .getSharedPreferences("secure_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun activity(): FragmentActivity =
        Robolectric.buildActivity(FragmentActivity::class.java).create().start().resume().get()

    private fun keyStoreHas(alias: String): Boolean {
        val ks = KeyStore.getInstance("AndroidKeyStore")
        ks.load(null)
        return ks.containsAlias(alias)
    }

    @Test
    fun firstUnlockGeneratesKeyAndShowsSetupPrompt() {
        val activity = activity()
        var success: String? = null
        var failure: String? = "not-called"
        unlockDatabaseWithBiometrics(activity, { success = it }, { failure = it })
        shadowOf(Looper.getMainLooper()).idle()
        // The auth-bound key is generated before the setup prompt is shown.
        assertTrue(keyStoreHas("db_auth_key"))
        // No result has arrived, so neither callback may fire.
        assertEquals(null, success)
        assertEquals("not-called", failure)
        // A biometric prompt fragment is now attached to the activity.
        assertTrue(
            activity.supportFragmentManager.fragments.isNotEmpty(),
            "expected a BiometricPrompt fragment",
        )
    }

    @Test
    fun unlockWithExistingKeyShowsUnlockPromptAndKeepsPassphrase() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val helper = BiometricDatabaseHelper(ctx)
        helper.storePassphrase("existing-secret")

        val activity = activity()
        var success: String? = null
        var failure: String? = "not-called"
        unlockDatabaseWithBiometrics(activity, { success = it }, { failure = it })
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(null, success)
        assertEquals("not-called", failure)
        assertTrue(activity.supportFragmentManager.fragments.isNotEmpty())
        // Merely showing the prompt must not rotate or clear the stored passphrase.
        assertEquals("existing-secret", helper.getPassphrase())
    }

    @Test
    fun secondUnlockStillReachesPrompt() {
        val activity = activity()
        var failures = 0
        unlockDatabaseWithBiometrics(activity, {}, { failures++ })
        shadowOf(Looper.getMainLooper()).idle()
        unlockDatabaseWithBiometrics(activity, {}, { failures++ })
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(0, failures)
        assertFalse(
            activity.supportFragmentManager.fragments.isEmpty(),
            "prompt fragment should be attached",
        )
    }
}
