package com.vayunmathur.library.biometric

import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import java.util.concurrent.Executor
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/**
 * Robolectric has no biometric shadows, so the framework biometric service always
 * reports success and its prompt never answers. These shadows stand in for that
 * missing HAL: they only replace the android.hardware.biometrics framework calls
 * AndroidX delegates to, never the code under test.
 */
@Implements(BiometricManager::class)
class ShadowFrameworkBiometricManager {
    @Implementation
    fun canAuthenticate(authenticators: Int): Int = statusToReturn

    companion object {
        /** The status the next canAuthenticate call reports; BiometricManager codes. */
        var statusToReturn: Int = BiometricManager.BIOMETRIC_SUCCESS
    }
}

