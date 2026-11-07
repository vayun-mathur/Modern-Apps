package com.vayunmathur.library.biometric

import android.hardware.biometrics.BiometricManager
import android.os.Looper
import androidx.fragment.app.FragmentActivity
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the unavailable-device and prompt-error paths of
 * [unlockDatabaseWithBiometrics], using [FrameworkShadows] for the missing
 * biometric HAL (see that file for why the shadows exist).
 */
@RunWith(RobolectricTestRunner::class)
class UnlockStatusTest {

    @Before
    fun setUp() {
        FakeAndroidKeyStore.install()
        ShadowFrameworkBiometricManager.statusToReturn = BiometricManager.BIOMETRIC_SUCCESS
    }

    private fun activity(): FragmentActivity =
        Robolectric.buildActivity(FragmentActivity::class.java).create().start().resume().get()

    @Test
    @Config(shadows = [ShadowFrameworkBiometricManager::class])
    fun noneEnrolledShowsScreenLockMessage() {
        ShadowFrameworkBiometricManager.statusToReturn = BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
        var failure: String? = null
        var succeeded = false
        unlockDatabaseWithBiometrics(activity(), { succeeded = true }, { failure = it })
        assertEquals(
            "Set up a screen lock (PIN, pattern, password, or biometric) to use the secure folder.",
            failure,
        )
        assertTrue(!succeeded)
    }

    @Test
    @Config(shadows = [ShadowFrameworkBiometricManager::class])
    fun noHardwareShowsUnavailableMessage() {
        ShadowFrameworkBiometricManager.statusToReturn = BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE
        var failure: String? = null
        unlockDatabaseWithBiometrics(activity(), {}, { failure = it })
        assertEquals(
            "Device authentication isn't available, so the secure folder can't be opened.",
            failure,
        )
    }

    @Test
    @Config(shadows = [ShadowFrameworkBiometricManager::class])
    fun hwUnavailableShowsUnavailableMessage() {
        ShadowFrameworkBiometricManager.statusToReturn = BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE
        var failure: String? = null
        unlockDatabaseWithBiometrics(activity(), {}, { failure = it })
        assertEquals(
            "Device authentication isn't available, so the secure folder can't be opened.",
            failure,
        )
    }

    @Test
    @Config(shadows = [ShadowFrameworkBiometricManager::class])
    fun unknownStatusShowsGenericMessage() {
        ShadowFrameworkBiometricManager.statusToReturn = 999
        var failure: String? = null
        unlockDatabaseWithBiometrics(activity(), {}, { failure = it })
        assertEquals("Device authentication is required to use the secure folder.", failure)
    }

    @Test
    fun keyGenerationFailureShowsGenericMessage() {
        FakeAndroidKeyStore.remove("db_auth_key")
        FakeAndroidKeyStore.failGeneration = true
        try {
            var failure: String? = null
            var succeeded = false
            unlockDatabaseWithBiometrics(activity(), { succeeded = true }, { failure = it })
            assertEquals("Device authentication is required to use the secure folder.", failure)
            assertTrue(!succeeded)
        } finally {
            FakeAndroidKeyStore.failGeneration = false
        }
    }
}
