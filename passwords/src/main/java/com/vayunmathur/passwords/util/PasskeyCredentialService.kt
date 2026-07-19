package com.vayunmathur.passwords.util

import android.app.PendingIntent
import android.content.Intent
import android.os.CancellationSignal
import android.os.OutcomeReceiver
import android.util.Log
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialUnknownException
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
import com.vayunmathur.library.room.buildDatabase
import com.vayunmathur.library.util.closeCachedDatabase
import com.vayunmathur.passwords.R
import com.vayunmathur.passwords.data.PasswordDatabase
import com.vayunmathur.passwords.ui.PasskeyAuthActivity
import kotlinx.coroutines.runBlocking

class PasskeyCredentialService : CredentialProviderService() {

    private val db by lazy {
        applicationContext.buildDatabase<PasswordDatabase>()
    }
    private val passkeyDao by lazy { db.passkeyDao() }
    private val passwordDao by lazy { db.passwordDao() }

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
        if (!DatabaseHelper(applicationContext).isKeyGenerated()) {
            callback.onResult(buildUnlockResponse())
            return
        }
        runBlocking {
            try {
                val response = buildGetCredentialResponse(
                    applicationContext,
                    request.beginGetCredentialOptions,
                    passkeyDao,
                    passwordDao,
                )
                callback.onResult(response)
            } catch (e: Exception) {
                Log.e(TAG, "onBeginGetCredentialRequest failed, falling back to unlock", e)
                closeCachedDatabase<PasswordDatabase>()
                DatabaseHelper(applicationContext).deleteKey()
                callback.onResult(buildUnlockResponse())
            }
        }
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
        } catch (e: Exception) {
            Log.e(TAG, "onBeginCreateCredentialRequest failed", e)
            callback.onError(CreateCredentialUnknownException())
        }
    }

    override fun onClearCredentialStateRequest(
        request: ProviderClearCredentialStateRequest,
        cancellationSignal: CancellationSignal,
        callback: OutcomeReceiver<Void?, ClearCredentialException>,
    ) {
        callback.onResult(null)
    }

    companion object {
        private const val TAG = "PasskeyCredService"
        private const val REQUEST_CODE_UNLOCK = 999999
        const val EXTRA_PASSWORD_ID = "password_id"
    }
}
