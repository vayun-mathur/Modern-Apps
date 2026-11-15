package com.vayunmathur.passwords.platform.cable

import com.vayunmathur.library.ui.R as UiR
import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import com.vayunmathur.library.log.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.passwords.R

/**
 * Entry point for scanned caBLE QR codes. The Camera app resolves a `FIDO:/…` value to a generic
 * "open URL" whose `fido:` scheme lands here via an `ACTION_VIEW` intent-filter (see the manifest).
 *
 * Flow: validate the URI → a single strong-biometric prompt that serves as both consent and user
 * verification → request the Nearby-devices / notification runtime permissions (first run only) →
 * start [CableService]. The activity finishes immediately after handing off. The credential
 * database is opened by the service via the no-auth key, so the app itself never needs unlocking.
 */
class CableActivity : FragmentActivity() {

    private var fidoUri: String? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { ensureBluetoothThenStart() }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (CableBluetooth.isEnabled(this)) {
            startSessionAndFinish()
        } else {
            AppMessages.show(getString(R.string.cable_bluetooth_disabled_message))
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val uri = intent?.data?.toString() ?: intent?.getStringExtra(CableService.EXTRA_FIDO_URI)
        if (uri == null || !looksLikeFido(uri)) {
            Log.error(TAG, "No FIDO caBLE URI in intent")
            finish()
            return
        }
        fidoUri = uri
        verifyUser()
    }

    private fun looksLikeFido(uri: String): Boolean =
        uri.startsWith("fido:", ignoreCase = true) || uri.startsWith("FIDO:/")

    /** Single biometric prompt = approval + user verification for the cross-device sign-in. */
    private fun verifyUser() {
        val canAuth = BiometricManager.from(this)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        if (canAuth != BiometricManager.BIOMETRIC_SUCCESS) {
            Log.error(TAG, "Strong biometric unavailable: $canAuth")
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
                    Log.dev(TAG, "Biometric error $errorCode: $errString")
                    finish()
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(getString(R.string.cable_prompt_title))
                .setSubtitle(getString(R.string.cable_prompt_subtitle))
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG)
                .setNegativeButtonText(getString(UiR.string.cancel))
                .build()
        )
    }

    private fun requestPermissionsThenStart() {
        val needed = buildList {
            add(Manifest.permission.BLUETOOTH_ADVERTISE)
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.POST_NOTIFICATIONS)
        }.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isEmpty()) ensureBluetoothThenStart()
        else permissionLauncher.launch(needed.toTypedArray())
    }

    /**
     * Pre-flight check before the caBLE flow: prompt to enable Bluetooth (using the existing
     * permission state — BLUETOOTH_CONNECT is granted by now) so a disabled radio shows a
     * dialog/message instead of failing silently in the service notification.
     */
    private fun ensureBluetoothThenStart() {
        if (!CableBluetooth.isSupported(this)) {
            Log.error(TAG, "Bluetooth not supported; cannot run caBLE session")
            AppMessages.show(getString(R.string.cable_bluetooth_unsupported_message))
            finish()
            return
        }
        if (!CableBluetooth.isEnabled(this)) {
            enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            return
        }
        startSessionAndFinish()
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

    /** Shared constants and helpers for CableActivity. */
    companion object {
        private const val TAG = "CableActivity"
    }
}
