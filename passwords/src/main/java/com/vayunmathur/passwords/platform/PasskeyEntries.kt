package com.vayunmathur.passwords.platform

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.credentials.provider.BeginGetCredentialOption
import androidx.credentials.provider.BeginGetCredentialResponse
import androidx.credentials.provider.BeginGetPasswordOption
import androidx.credentials.provider.BeginGetPublicKeyCredentialOption
import androidx.credentials.provider.PasswordCredentialEntry
import androidx.credentials.provider.PublicKeyCredentialEntry
import com.vayunmathur.passwords.data.PasskeyDao
import com.vayunmathur.passwords.data.PasswordDao
import com.vayunmathur.passwords.data.PasswordRepository
import com.vayunmathur.passwords.ui.PasskeyAuthActivity
import org.json.JSONObject

/**
 * Builds the credential entries (passkeys + saved passwords) shown to the
 * Credential Manager for a get-credential request. Shared by
 * [PasskeyCredentialService] and [PasskeyAuthActivity] so both produce
 * identical entries / [PendingIntent]s.
 */
suspend fun buildGetCredentialResponse(
    context: Context,
    options: List<BeginGetCredentialOption>,
    passkeyDao: PasskeyDao,
    passwordDao: PasswordDao,
): BeginGetCredentialResponse {
    val responseBuilder = BeginGetCredentialResponse.Builder()
    for (option in options) {
        when (option) {
            is BeginGetPublicKeyCredentialOption -> {
                // requestJson comes from the calling app; skip malformed options.
                val rpId = runCatching { JSONObject(option.requestJson).optString("rpId", "") }.getOrNull() ?: continue
                if (rpId.isBlank()) continue
                for (passkey in passkeyDao.getByRpId(rpId)) {
                    val intent = Intent(context, PasskeyAuthActivity::class.java).apply {
                        putExtra(PasskeyAuthActivity.EXTRA_FLOW, PasskeyAuthActivity.FLOW_GET)
                        putExtra(PasskeyAuthActivity.EXTRA_CREDENTIAL_ID, passkey.credentialId)
                    }
                    val pendingIntent = PendingIntent.getActivity(
                        context,
                        passkey.id.toInt(),
                        intent,
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                    responseBuilder.addCredentialEntry(
                        PublicKeyCredentialEntry.Builder(
                            context,
                            passkey.userName,
                            pendingIntent,
                            option,
                        ).build()
                    )
                }
            }
            is BeginGetPasswordOption -> {
                for (pass in passwordDao.getAll()) {
                    val intent = Intent(context, PasskeyAuthActivity::class.java).apply {
                        putExtra(PasskeyAuthActivity.EXTRA_FLOW, PasskeyAuthActivity.FLOW_PASSWORD)
                        putExtra(PasskeyCredentialService.EXTRA_PASSWORD_ID, pass.id)
                    }
                    val pendingIntent = PendingIntent.getActivity(
                        context,
                        (pass.id + 100000).toInt(),
                        intent,
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                    responseBuilder.addCredentialEntry(
                        PasswordCredentialEntry.Builder(
                            context,
                            pass.username,
                            pendingIntent,
                            option,
                        ).setDisplayName(pass.name.ifBlank { null })
                            .build()
                    )
                }
            }
        }
    }
    return responseBuilder.build()
}

/** Repository-based overload — preferred. */
suspend fun buildGetCredentialResponse(
    context: Context,
    options: List<BeginGetCredentialOption>,
    repository: PasswordRepository,
): BeginGetCredentialResponse =
    buildGetCredentialResponse(
        context,
        options,
        passkeyDao = object : PasskeyDao() {
            override fun getAllFlow(): kotlinx.coroutines.flow.Flow<List<com.vayunmathur.passwords.data.Passkey>> =
                repository.passkeys
            override suspend fun getAll(): List<com.vayunmathur.passwords.data.Passkey> =
                repository.getAllPasskeys()
            override suspend fun getByRpId(rpId: String): List<com.vayunmathur.passwords.data.Passkey> =
                repository.getPasskeysByRpId(rpId)
            override suspend fun getByCredentialId(credentialId: String): com.vayunmathur.passwords.data.Passkey? =
                repository.getPasskeyByCredentialId(credentialId)
            override suspend fun getBySyncId(syncId: String): com.vayunmathur.passwords.data.Passkey? =
                repository.getPasskeyBySyncId(syncId)
            override suspend fun getUpdatedSince(since: Long): List<com.vayunmathur.passwords.data.Passkey> =
                repository.getPasskeysUpdatedSince(since)
            override suspend fun upsertRaw(passkey: com.vayunmathur.passwords.data.Passkey): Long =
                repository.upsertPasskeyRaw(passkey)
            override suspend fun delete(passkey: com.vayunmathur.passwords.data.Passkey): Int =
                repository.deletePasskey(passkey)
            override suspend fun deleteBySyncId(syncId: String): Int =
                repository.deletePasskeyBySyncId(syncId)
            override suspend fun deleteAll() = repository.deleteAllPasskeys()
        },
        passwordDao = object : PasswordDao() {
            override fun getAllFlow(): kotlinx.coroutines.flow.Flow<List<com.vayunmathur.passwords.data.Password>> =
                repository.passwords
            override suspend fun getAll(): List<com.vayunmathur.passwords.data.Password> =
                repository.getAllPasswords()
            override fun getByIdFlow(id: Long): kotlinx.coroutines.flow.Flow<com.vayunmathur.passwords.data.Password?> =
                repository.getPasswordByIdFlow(id)
            override suspend fun getById(id: Long): com.vayunmathur.passwords.data.Password? =
                repository.getPasswordById(id)
            override suspend fun getBySyncId(syncId: String): com.vayunmathur.passwords.data.Password? =
                repository.getPasswordBySyncId(syncId)
            override suspend fun getUpdatedSince(since: Long): List<com.vayunmathur.passwords.data.Password> =
                repository.getPasswordsUpdatedSince(since)
            override suspend fun upsertRaw(value: com.vayunmathur.passwords.data.Password): Long =
                repository.upsertPasswordRaw(value)
            override suspend fun delete(value: com.vayunmathur.passwords.data.Password): Int =
                repository.deletePassword(value)
            override suspend fun deleteBySyncId(syncId: String): Int =
                repository.deletePasswordBySyncId(syncId)
            override suspend fun deleteAll() = repository.deleteAllPasswords()
        },
    )
