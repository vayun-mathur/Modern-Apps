package com.vayunmathur.passwords

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.vayunmathur.library.biometric.unlockDatabaseWithBiometrics
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.ui.MaFragmentActivity
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.util.DatabaseHelper
import com.vayunmathur.library.util.OfflineAware
import com.vayunmathur.passwords.data.PasswordRepository
import com.vayunmathur.passwords.platform.PasswordsViewModel
import com.vayunmathur.passwords.platform.PasswordsViewModelFactory
import com.vayunmathur.passwords.sync.KdbxSyncScheduler
import com.vayunmathur.passwords.sync.KdbxSyncSettings

/** Main Activity: a ma fragment activity implementation for main activity. */
class MainActivity : MaFragmentActivity() {
    private val passwordsViewModel: PasswordsViewModel by viewModels {
        PasswordsViewModelFactory(application, PasswordRepository.get(application))
    }

    // Composition waits for the biometric gate: the vault must be unlocked before anything renders.
    override val autoSetContent: Boolean = false

    override fun onCreateBeforeContent() {
        // Reduced CA hardening: FIRST_PARTY covers api.maproj.org / data.maproj.org (ISRG+GTS)
        NetworkClient.init(this, TrustBundle.FIRST_PARTY)

        unlockDatabaseWithBiometrics(
            activity = this,
            onSuccess = { passphrase ->
                // Sync passphrase to non-auth key so services can access the database.
                // The repo's lazy db will then open via DatabaseHelper (same passphrase).
                DatabaseHelper(this).storePassphrase(passphrase)
                // Force repository (and thus DB) to be created eagerly so the
                // cached databases[PasswordDatabase] entry matches this passphrase.
                PasswordRepository.get(application)
                lifecycleScope.launch {
                    if (KdbxSyncSettings.enabled(this@MainActivity)) {
                        // Re-registering keeps the periodic work alive across app updates.
                        KdbxSyncScheduler.schedulePeriodic(this@MainActivity)
                        KdbxSyncScheduler.syncNow(this@MainActivity)
                    }
                }
                showContent()
            },
            onFailure = { message ->
                message?.let { AppMessages.show(it) }
                finish()
            }
        )
    }

    @Composable
    override fun Content() {
        OfflineAware {
            Navigation(passwordsViewModel)
        }
    }
}
