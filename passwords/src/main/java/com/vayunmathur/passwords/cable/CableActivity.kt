package com.vayunmathur.passwords.cable

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Entry point for scanned caBLE QR codes. The Camera app resolves a `FIDO:/…` value to a generic
 * "open URL" whose `fido:` scheme lands here via an `ACTION_VIEW` intent-filter (see the manifest).
 *
 * Flow: validate the URI → ask the user to approve the cross-device sign-in → verify the user with
 * a strong biometric → request the Nearby-devices / notification runtime permissions → start
 * [CableService] to run the protocol. The activity finishes immediately after handing off.
 */
class CableActivity : FragmentActivity() {

    private var fidoUri: String? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { startSessionAndFinish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent?.data?.toString() ?: intent?.getStringExtra(CableService.EXTRA_FIDO_URI)
        if (uri == null || !looksLikeFido(uri)) {
            Log.e(TAG, "No FIDO caBLE URI in intent")
            finish()
            return
        }
        fidoUri = uri
        confirmThenVerify()
    }

    private fun looksLikeFido(uri: String): Boolean =
        uri.startsWith("fido:", ignoreCase = true) || uri.startsWith("FIDO:/")

    private fun confirmThenVerify() {
        AlertDialog.Builder(this)
            .setTitle("Cross-device sign-in")
            .setMessage("Approve signing in on your other device with a passkey from Passwords?")
            .setPositiveButton("Approve") { _, _ -> verifyUser() }
            .setNegativeButton("Cancel") { _, _ -> finish() }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun verifyUser() {
        val canAuth = BiometricManager.from(this)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            Log.e(TAG, "Strong biometric unavailable: $canAuth")
            finish()
            return
        }

        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    requestPermissionsThenStart()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    Log.d(TAG, "Biometric error $errorCode: $errString")
                    finish()
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Verify it's you")
                .setSubtitle("Confirm cross-device sign-in")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText("Cancel")
                .build()
        )
    }

    private fun requestPermissionsThenStart() {
        val needed = buildList {
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isEmpty()) startSessionAndFinish()
        else permissionLauncher.launch(needed.toTypedArray())
    }

    private fun startSessionAndFinish() {
        val uri = fidoUri
        if (uri != null) {
            val intent = Intent(this, CableService::class.java).apply {
                putExtra(CableService.EXTRA_FIDO_URI, uri)
                putExtra(CableService.EXTRA_USER_VERIFIED, true)
            }
            ContextCompat.startForegroundService(this, intent)
        }
        finish()
    }

    companion object {
        private const val TAG = "CableActivity"
    }
}
