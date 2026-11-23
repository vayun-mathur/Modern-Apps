package com.vayunmathur.passwords.platform

import android.app.PendingIntent
import android.content.Intent
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import com.vayunmathur.library.log.Log
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.ClearCredentialUnknownException
import androidx.credentials.provider.AuthenticationAction
import androidx.credentials.provider.BeginCreateCredentialRequest
import androidx.credentials.provider.BeginCreateCredentialResponse
import androidx.credentials.provider.BeginCreatePublicKeyCredentialRequest
import androidx.credentials.provider.BeginGetCredentialRequest
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.CreateEntry
import androidx.credentials.provider.CredentialProviderService
import androidx.credentials.provider.ProviderClearCredentialStateRequest
import com.vayunmathur.library.util.DatabaseHelper
import com.vayunmathur.library.util.closeCachedDatabase
import com.vayunmathur.passwords.R
import com.vayunmathur.passwords.data.PasswordDatabase
import com.vayunmathur.passwords.data.PasswordRepository
import com.vayunmathur.passwords.ui.PasskeyAuthActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Passkey Credential Service: a credential provider service implementation for passkey credential service. */
class PasskeyCredentialService : CredentialProviderService() {

    private val repository by lazy { PasswordRepository.get(applicationContext) }

    // Owned by the service (no lifecycleScope on a Service): cancelled in
    // onDestroy. All repository work runs on Dispatchers.IO.
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun buildUnlockResponse(): BeginGetCredentialResponse {
        val intent = Intent(applicationContext, PasskeyAuthActivity::class.java).apply {
            putExtra(PasskeyAuthActivity.EXTRA_FLOW, PasskeyAuthActivity.FLOW_UNLOCK)
        }
        val pendingIntent = PendingIntent.getActivity(
            applicationContext,
            REQUEST_CODE_UNLOCK,
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val action = AuthenticationAction(
            applicationContext.getString(R.string.unlock_database),
            pendingIntent,
        )
        return BeginGetCredentialResponse.Builder()
            .addAuthenticationAction(action)
            .build()
    }

    override fun onBeginGetCredentialRequest(
        request: BeginGetCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>,
    ) {
        // Nothing in a provider callback may throw across the binder: the
        // framework/caller (e.g. Vanadium) has no recovery for a provider
        // exception, and an uncaught one can take the calling flow down.
        // Every path here completes the callback exactly once, or logs when
        // even completion fails (dead binder).
        try {
            val keyGenerated = runCatching {
                DatabaseHelper(applicationContext).isKeyGenerated()
            }.onFailure { Log.error(TAG, "isKeyGenerated failed, treating vault as locked", it) }
                .getOrDefault(false)
            if (!keyGenerated) {
                deliverGet(callback) { buildUnlockResponse() }
                return
            }
            // Credential Manager awaits the OutcomeReceiver, so returning here
            // without blocking is the framework's async contract: the callback
            // is invoked exactly once, from the IO coroutine, when the vault
            // read completes (or with the unlock fallback on failure).
            val job = serviceScope.launch {
                try {
                    val response = buildGetCredentialResponse(
                        applicationContext,
                        request.beginGetCredentialOptions,
                        repository,
                    )
                    deliverGet(callback) { response }
                } catch (expected: CancellationException) {
                    throw expected
                } catch (expected: IllegalStateException) {
                    Log.error(TAG, "onBeginGetCredentialRequest failed, falling back to unlock", expected)
                    runCatching {
                        closeCachedDatabase<PasswordDatabase>()
                        DatabaseHelper(applicationContext).deleteKey()
                    }
                    deliverGetOrError(callback)
                } catch (expected: Throwable) {
                    // e.g. SQLCipher native load failure opening the vault: the
                    // callback must still complete, so fall back to unlock rather
                    // than leaving Credential Manager waiting on a dead coroutine.
                    Log.error(TAG, "onBeginGetCredentialRequest failed, falling back to unlock", expected)
                    deliverGetOrError(callback)
                }
            }
            runCatching { cancellationSignal.setOnCancelListener { job.cancel() } }
        } catch (expected: Throwable) {
            Log.error(TAG, "onBeginGetCredentialRequest failed before dispatch", expected)
            deliverGetError(callback)
        }
    }

    /** Completes [callback] with [build]'s result; a throwing build or delivery is only logged. */
    private fun deliverGet(
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>,
        build: () -> BeginGetCredentialResponse,
    ) {
        runCatching { callback.onResult(build()) }
            .onFailure { Log.error(TAG, "Delivering get response failed", it) }
    }

    /** Unlock fallback, degrading to a provider error if even that cannot be built/delivered. */
    private fun deliverGetOrError(
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>,
    ) {
        val delivered = runCatching { callback.onResult(buildUnlockResponse()) }.isSuccess
        if (!delivered) deliverGetError(callback)
    }

    private fun deliverGetError(
        callback: OutcomeReceiver<BeginGetCredentialResponse, GetCredentialException>,
    ) {
        runCatching { callback.onError(GetCredentialUnknownException()) }
            .onFailure { Log.error(TAG, "Delivering get error failed", it) }
    }

    override fun onBeginCreateCredentialRequest(
        request: BeginCreateCredentialRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<BeginCreateCredentialResponse, CreateCredentialException>,
    ) {
        try {
            if (request is BeginCreatePublicKeyCredentialRequest) {
                val intent = Intent(applicationContext, PasskeyAuthActivity::class.java).apply {
                    putExtra(PasskeyAuthActivity.EXTRA_FLOW, PasskeyAuthActivity.FLOW_CREATE)
                }
                val pendingIntent = PendingIntent.getActivity(
                    applicationContext,
                    0,
                    intent,
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                val createEntry = CreateEntry.Builder(
                    applicationContext.getString(R.string.app_name),
                    pendingIntent,
                ).build()

                callback.onResult(
                    BeginCreateCredentialResponse.Builder()
                        .addCreateEntry(createEntry)
                        .build()
                )
            } else {
                callback.onResult(BeginCreateCredentialResponse.Builder().build())
            }
        } catch (expected: Throwable) {
            // Never throw across the binder (see onBeginGetCredentialRequest):
            // any failure becomes a provider error outcome.
            Log.error(TAG, "onBeginCreateCredentialRequest failed", expected)
            runCatching { callback.onError(CreateCredentialUnknownException()) }
                .onFailure { Log.error(TAG, "Delivering create error failed", it) }
        }
    }

    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>,
    ) {
        try {
            callback.onResult(null)
        } catch (expected: Throwable) {
            Log.error(TAG, "onClearCredentialStateRequest failed", expected)
            runCatching { callback.onError(ClearCredentialUnknownException()) }
                .onFailure { Log.error(TAG, "Delivering clear error failed", it) }
        }
    }

    /** Shared constants and helpers for PasskeyCredentialService. */
    companion object {
        private const val TAG = "PasskeyCredService"
        private const val REQUEST_CODE_UNLOCK = 999999
        const val EXTRA_PASSWORD_ID = "password_id"
    }
}
