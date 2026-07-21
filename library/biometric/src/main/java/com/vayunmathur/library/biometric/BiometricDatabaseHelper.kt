package com.vayunmathur.library.biometric

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.vayunmathur.library.util.DatabaseHelper
import javax.crypto.Cipher
import javax.crypto.KeyGenerator

class BiometricDatabaseHelper(context: Context) : DatabaseHelper(context) {
    override val keyStoreAlias = "db_auth_key"
    override val passphraseKey = "encrypted_passphrase"
    override val ivKey = "passphrase_iv"

    override fun generateKey() {
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val builder = KeyGenParameterSpec.Builder(
            keyStoreAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setUserAuthenticationParameters(
                0,
                KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL,
            )
            .setInvalidatedByBiometricEnrollment(false)

        keyGenerator.init(builder.build())
        keyGenerator.generateKey()
    }
}

/**
 * Prompts for a strong-biometric unlock of the encrypted database.
 *
 * [onFailure] receives an optional user-facing message: a non-null message when
 * biometrics can't be used (none enrolled / no hardware / key setup failed) so the
 * caller can show it; null for ordinary cancellations (back / negative button).
 */
fun unlockDatabaseWithBiometrics(
    activity: FragmentActivity,
    onSuccess: (String) -> Unit,
    onFailure: (message: String?) -> Unit
) {
    // Generating an auth-bound key throws IllegalStateException when the device has
    // no way to authenticate the user. Allow either a strong biometric or the device
    // credential (PIN / pattern / password), and check first so we can fail
    // gracefully with a message instead of crashing.
    val allowedAuthenticators =
        BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
    val status = BiometricManager.from(activity).canAuthenticate(allowedAuthenticators)
    if (status != BiometricManager.BIOMETRIC_SUCCESS) {
        onFailure(biometricUnavailableMessage(status))
        return
    }

    val helper = BiometricDatabaseHelper(activity)
    val executor = ContextCompat.getMainExecutor(activity)

    fun prompt(title: String, subtitle: String, cipher: Cipher, onCipher: (Cipher) -> String) {
        val biometricPrompt = BiometricPrompt(activity, executor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onSuccess(onCipher(result.cryptoObject?.cipher!!))
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Stay silent on ordinary user cancellation; surface real errors.
                val message = when (errorCode) {
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    BiometricPrompt.ERROR_CANCELED -> null
                    else -> errString.toString()
                }
                onFailure(message)
            }
        })

        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            // Allowing DEVICE_CREDENTIAL provides the built-in PIN/pattern/password
            // fallback; a custom negative button is disallowed in that case.
            .setAllowedAuthenticators(allowedAuthenticators)
            .build()

        biometricPrompt.authenticate(promptInfo, BiometricPrompt.CryptoObject(cipher))
    }

    if (!helper.isKeyGenerated()) {
        try {
            helper.generateKey()
        } catch (e: Exception) {
            onFailure(biometricUnavailableMessage(status))
            return
        }
        prompt(
            "Setup Secure Database",
            "Authenticate to create your secure encryption key",
            helper.getCipherForEncryption()
        ) { helper.createAndStorePassphrase(it) }
    } else {
        prompt(
            "Unlock Database",
            "Authenticate to access your secure data",
            helper.getCipherForDecryption()
        ) { helper.decryptPassphrase(it) }
    }
}

/** User-facing reason why the device can't authenticate for the secure folder. */
private fun biometricUnavailableMessage(status: Int): String = when (status) {
    BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
        "Set up a screen lock (PIN, pattern, password, or biometric) to use the secure folder."
    BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE,
    BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
        "Device authentication isn't available, so the secure folder can't be opened."
    else ->
        "Device authentication is required to use the secure folder."
}
