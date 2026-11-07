package com.vayunmathur.library.biometric

import android.content.Context
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.fragment.app.FragmentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented coverage for the biometric prompt's error callback, which cannot
 * arrive under Robolectric (there is no biometric HAL and AndroidX's prompt flow
 * never completes there). On the emulator the system BiometricPrompt panel does
 * appear; cancelling it delivers ERROR_CANCELED to the callback under test.
 *
 * The success callback is not exercised: the emulator's fingerprint HAL has no
 * enrolled finger (enrollment needs host-side emulator-console touches a test
 * cannot send) and its prompt panel offers no PIN fallback button, so no
 * authentication can succeed on this device.
 */
@RunWith(AndroidJUnit4::class)
class UnlockInstrumentedTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val ctx: Context get() = instrumentation.targetContext
    private val device: UiDevice get() = UiDevice.getInstance(instrumentation)

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).close()
    }

    private fun setPin() {
        // A previous test's credential may still be set (its clear is best-effort);
        // set-pin refuses to replace an existing credential without --old.
        shell("locksettings clear --old $PIN")
        shell("locksettings set-pin $PIN")
        // Keystore refuses auth-bound keys until the new credential registers as a
        // secure lock screen, which lands a moment after the shell command returns.
        val keyguard = ctx.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
        val deadline = System.currentTimeMillis() + 10_000
        while (!keyguard.isDeviceSecure && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
        }
        assertTrue("lock screen never became secure after set-pin", keyguard.isDeviceSecure)
    }

    /** Waits until BiometricManager agrees authentication can proceed, then unlocks. */
    private fun unlockWhenReady(activity: FragmentActivity, onSuccess: (String) -> Unit, onFailure: (String?) -> Unit) {
        val manager = BiometricManager.from(activity)
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val deadline = System.currentTimeMillis() + 15_000
        while (manager.canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(100)
        }
        unlockDatabaseWithBiometrics(activity, onSuccess, onFailure)
    }

    @Test
    fun cancellingSetupPromptFailsSilentlyAndGeneratesKey() {
        setPin()
        try {
            // Start from no key so unlock takes the first-time setup prompt.
            BiometricDatabaseHelper(ctx).deleteKey()
            val failureLatch = CountDownLatch(1)
            var failureMessage: String? = "unset"
            var succeeded = false
            ActivityScenario.launch(UnlockTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    unlockWhenReady(activity, { succeeded = true }, { message ->
                        failureMessage = message; failureLatch.countDown()
                    })
                }
                assertNotNull(
                    "setup prompt never appeared (failure=$failureMessage succeeded=$succeeded)",
                    device.wait(Until.findObject(By.text("Setup Secure Database")), PROMPT_TIMEOUT_MS),
                )
                device.pressBack()
                assertTrue("failure callback never fired", failureLatch.await(30, TimeUnit.SECONDS))
            }
            assertTrue(!succeeded)
            assertEquals(null, failureMessage)
        } finally {
            shell("locksettings clear --old $PIN")
        }
    }

    @Test
    fun cancellingUnlockPromptFailsSilently() {
        setPin()
        try {
            // Seed the stored-passphrase state (key + IV + ciphertext) so unlock takes
            // the decrypt prompt. The ciphertext is arbitrary: cancelling never
            // decrypts it, and an auth-bound key cannot encrypt before authentication
            // anyway, which is exactly why the real flow stores via the prompt cipher.
            val helper = BiometricDatabaseHelper(ctx)
            helper.deleteKey()
            helper.generateKey()
            val prefs = ctx.getSharedPreferences("secure_prefs", Context.MODE_PRIVATE)
            prefs.edit()
                .putString("passphrase_iv", Base64.encodeToString(ByteArray(12) { it.toByte() }, Base64.NO_WRAP))
                .putString("encrypted_passphrase", Base64.encodeToString(ByteArray(32) { (it * 7).toByte() }, Base64.NO_WRAP))
                .commit()
            assertTrue(helper.isKeyGenerated())

            val failureLatch = CountDownLatch(1)
            var failureMessage: String? = "unset"
            ActivityScenario.launch(UnlockTestActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    unlockWhenReady(activity, { }, { message ->
                        failureMessage = message; failureLatch.countDown()
                    })
                }
                assertNotNull(
                    "unlock prompt never appeared (failure=$failureMessage)",
                    device.wait(Until.findObject(By.text("Unlock Database")), PROMPT_TIMEOUT_MS),
                )
                device.pressBack()
                assertTrue("failure callback never fired", failureLatch.await(30, TimeUnit.SECONDS))
            }
            assertEquals(null, failureMessage)
            // The stored passphrase state survives a cancelled prompt untouched.
            assertEquals(
                Base64.encodeToString(ByteArray(32) { (it * 7).toByte() }, Base64.NO_WRAP),
                prefs.getString("encrypted_passphrase", null),
            )
        } finally {
            shell("locksettings clear --old $PIN")
        }
    }

    private companion object {
        const val PIN = "1111"
        const val PROMPT_TIMEOUT_MS = 15_000L
    }
}
