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
import com.vayunmathur.library.log.Log
import com.vayunmathur.passwords.data.PasskeyDao
import com.vayunmathur.passwords.data.PasswordDao
import com.vayunmathur.passwords.data.PasswordRepository
import com.vayunmathur.passwords.ui.PasskeyAuthActivity
import org.json.JSONObject

private const val TAG = "PasskeyEntries"

/**
 * Entry text (username / display name) comes from imported vault rows and is
 * otherwise unbounded. Entries cross to Credential Manager as parcels/slices,
 * so cap every field: a multi-kilobyte imported name must not bloat (or, in
 * bulk, overflow) the response binder transaction.
 */
private const val MAX_ENTRY_TEXT_LENGTH = 128

/** Per-type entry ceiling for the same parcel-size reason as above. */
private const val MAX_ENTRIES_PER_TYPE = 256

// PendingIntent identity is (requestCode + Intent.filterEquals), and
// filterEquals ignores extras: every entry intent targets the same activity,
// so without distinct actions the only thing separating one entry's
// PendingIntent from another's is the request code. Give each flow its own
// action AND a request-code range tagged by flow, so a passkey entry can
// never FLAG_UPDATE_CURRENT-rewrite a password/unlock entry's extras (the
// ranges overlapped when codes were raw row ids / id+100000 / 999999).
internal const val ACTION_GET_PASSKEY = "com.vayunmathur.passwords.action.GET_PASSKEY"
internal const val ACTION_GET_PASSWORD = "com.vayunmathur.passwords.action.GET_PASSWORD"
internal const val ACTION_UNLOCK = "com.vayunmathur.passwords.action.UNLOCK"
internal const val ACTION_CREATE = "com.vayunmathur.passwords.action.CREATE"

private const val REQUEST_CODE_TAG_SHIFT = 28
internal const val REQUEST_CODE_TAG_PASSKEY = 1
internal const val REQUEST_CODE_TAG_PASSWORD = 2
internal const val REQUEST_CODE_TAG_UNLOCK = 3
internal const val REQUEST_CODE_TAG_CREATE = 4

internal fun entryRequestCode(tag: Int, id: Long): Int =
    (tag shl REQUEST_CODE_TAG_SHIFT) or (id.toInt() and ((1 shl REQUEST_CODE_TAG_SHIFT) - 1))

private fun entryText(value: String, fallback: String): String =
    value.take(MAX_ENTRY_TEXT_LENGTH).ifBlank { fallback.take(MAX_ENTRY_TEXT_LENGTH) }

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
    fallbackRpId: String? = null,
): BeginGetCredentialResponse {
    val responseBuilder = BeginGetCredentialResponse.Builder()
    for (option in options) {
        when (option) {
            is BeginGetPublicKeyCredentialOption ->
                addPasskeyEntries(context, option, passkeyDao, responseBuilder, fallbackRpId)
            is BeginGetPasswordOption ->
                addPasswordEntries(context, option, passwordDao, responseBuilder)
        }
    }
    val response = responseBuilder.build()
    // Attributability: a get ceremony that later goes wrong downstream must
    // be traceable to what this provider actually handed Credential Manager.
    Log.dev(
        TAG,
        "Built get response: ${response.credentialEntries.size} entries, " +
            "${response.authenticationActions.size} auth actions for ${options.size} options",
    )
    return response
}

private suspend fun addPasskeyEntries(
    context: Context,
    option: BeginGetPublicKeyCredentialOption,
    passkeyDao: PasskeyDao,
    responseBuilder: BeginGetCredentialResponse.Builder,
    fallbackRpId: String?,
) {
    // requestJson comes from the calling app; skip malformed options.
    val request = runCatching { JSONObject(option.requestJson) }.getOrNull() ?: return
    // rpId is optional in WebAuthn (it defaults to the origin's host); a
    // caller that omits it must still be offered its credentials, so fall
    // back to the origin host the service resolved from CallingAppInfo.
    val rpId = request.optString("rpId", "").ifBlank { fallbackRpId.orEmpty() }
    if (rpId.isBlank()) return
    // An allowCredentials list constrains which credentials may be offered;
    // offering the rest only lets the user pick one the RP will reject.
    val allowedIds = PasskeyUtils.credentialDescriptorIds(request, "allowCredentials")
    // One unreadable vault read (or one bad stored row below) must not fail
    // the whole enumeration: the provider callback has to complete with the
    // entries that do work.
    val passkeys = runCatching { passkeyDao.getByRpId(rpId) }
        .onFailure { Log.error(TAG, "Listing passkeys for rpId=$rpId failed", it) }
        .getOrDefault(emptyList())
    var added = 0
    for (passkey in passkeys) {
        if (added >= MAX_ENTRIES_PER_TYPE) break
        val allowed = allowedIds.isEmpty() ||
            allowedIds.any { PasskeyUtils.credentialIdsMatch(passkey.credentialId, it) }
        // A stored row with a blank id can never be asserted with; offering
        // it would only hand the caller a dead entry.
        if (allowed && passkey.credentialId.isNotBlank()) {
            val built = runCatching {
                val intent = Intent(context, PasskeyAuthActivity::class.java).apply {
                    action = ACTION_GET_PASSKEY
                    putExtra(PasskeyAuthActivity.EXTRA_FLOW, PasskeyAuthActivity.FLOW_GET)
                    putExtra(PasskeyAuthActivity.EXTRA_CREDENTIAL_ID, passkey.credentialId)
                }
                val pendingIntent = PendingIntent.getActivity(
                    context,
                    entryRequestCode(REQUEST_CODE_TAG_PASSKEY, passkey.id),
                    intent,
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                // No setIcon: the builder substitutes the androidx default
                // passkey icon, a *resource* Icon (package + res id parcel,
                // no bitmap), which is the smallest safe parcel shape.
                PublicKeyCredentialEntry.Builder(
                    context,
                    // Entry builders reject blank usernames; fall back so
                    // an import with no name still lists.
                    entryText(
                        passkey.userName.ifBlank { passkey.userDisplayName }.ifBlank { passkey.rpId },
                        "Passkey",
                    ),
                    pendingIntent,
                    option,
                ).build()
            }.onFailure { Log.error(TAG, "Skipping unusable passkey entry id=${passkey.id}", it) }
            if (built.isSuccess) {
                responseBuilder.addCredentialEntry(built.getOrThrow())
                added++
            }
        }
    }
}

private suspend fun addPasswordEntries(
    context: Context,
    option: BeginGetPasswordOption,
    passwordDao: PasswordDao,
    responseBuilder: BeginGetCredentialResponse.Builder,
) {
    val passwords = runCatching { passwordDao.getAll() }
        .onFailure { Log.error(TAG, "Listing passwords failed", it) }
        .getOrDefault(emptyList())
    var added = 0
    for (pass in passwords) {
        if (added >= MAX_ENTRIES_PER_TYPE) break
        val built = runCatching {
            val intent = Intent(context, PasskeyAuthActivity::class.java).apply {
                action = ACTION_GET_PASSWORD
                putExtra(PasskeyAuthActivity.EXTRA_FLOW, PasskeyAuthActivity.FLOW_PASSWORD)
                putExtra(PasskeyCredentialService.EXTRA_PASSWORD_ID, pass.id)
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                entryRequestCode(REQUEST_CODE_TAG_PASSWORD, pass.id),
                intent,
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            PasswordCredentialEntry.Builder(
                context,
                entryText(pass.username.ifBlank { pass.name }, "Password"),
                pendingIntent,
                option,
            ).setDisplayName(pass.name.take(MAX_ENTRY_TEXT_LENGTH).ifBlank { null })
                .build()
        }.onFailure { Log.error(TAG, "Skipping unusable password entry id=${pass.id}", it) }
        if (built.isSuccess) {
            responseBuilder.addCredentialEntry(built.getOrThrow())
            added++
        }
    }
}

/** Repository-based overload — preferred. */
suspend fun buildGetCredentialResponse(
    context: Context,
    options: List<BeginGetCredentialOption>,
    repository: PasswordRepository,
    fallbackRpId: String? = null,
): BeginGetCredentialResponse =
    buildGetCredentialResponse(
        context,
        options,
        fallbackRpId = fallbackRpId,
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
