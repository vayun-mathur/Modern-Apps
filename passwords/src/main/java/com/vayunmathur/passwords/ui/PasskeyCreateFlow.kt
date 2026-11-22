package com.vayunmathur.passwords.ui

import android.app.Activity
import android.content.Intent
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.provider.PendingIntentHandler
import com.vayunmathur.library.log.Log
import com.vayunmathur.passwords.data.PasswordRepository
import com.vayunmathur.passwords.domain.Cbor
import com.vayunmathur.passwords.platform.PasskeyUtils
import com.vayunmathur.passwords.platform.cable.WebAuthnAuthenticator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "PasskeyAuthActivity"

internal suspend fun PasskeyAuthActivity.handlePasskeyCreate(repository: PasswordRepository) {
    val request = PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent) ?: run {
        Log.error(TAG, "No create credential request in intent")
        setResult(Activity.RESULT_CANCELED)
        return
    }
    val publicKeyRequest = request.callingRequest as? CreatePublicKeyCredentialRequest ?: run {
        Log.error(TAG, "Request is not a PublicKeyCredentialRequest")
        setResult(Activity.RESULT_CANCELED)
        return
    }

    val json = JSONObject(publicKeyRequest.requestJson)
    val rp = json.getJSONObject("rp")
    val rpId = rp.getString("id")
    val rpName = rp.optString("name", rpId)
    val user = json.getJSONObject("user")
    val userId = user.getString("id")
    val userName = user.optString("name", "")
    val userDisplayName = user.optString("displayName", userName)
    val challenge = json.getString("challenge")

    // This authenticator only makes ES256 keys. Honouring a request that
    // does not offer alg -7 would persist a credential the relying party
    // then rejects; refuse before anything is generated or stored.
    if (!PasskeyUtils.supportsEs256(json)) {
        Log.error(TAG, "Create request offers no ES256 in pubKeyCredParams")
        setResult(Activity.RESULT_CANCELED)
        return
    }
    // excludeCredentials names credentials the relying party already has:
    // creating (and, via the shared helper, silently replacing) one of
    // them must not happen.
    val excludedIds = PasskeyUtils.credentialDescriptorIds(json, "excludeCredentials")
    if (excludedIds.isNotEmpty()) {
        val excluded = withContext(Dispatchers.IO) { repository.getPasskeysByRpId(rpId) }.any { stored ->
            excludedIds.any { PasskeyUtils.credentialIdsMatch(stored.credentialId, it) }
        }
        if (excluded) {
            Log.error(TAG, "Create request excludes an existing credential for rpId=$rpId")
            setResult(Activity.RESULT_CANCELED)
            return
        }
    }

    // Privileged browsers provide clientDataHash directly
    val callingAppInfo = request.callingAppInfo
    val privilegedOrigin = PasskeyUtils.getPrivilegedOrigin(callingAppInfo, applicationContext)
    val isPrivileged = privilegedOrigin != null
    val origin = privilegedOrigin ?: PasskeyUtils.getAndroidOrigin(callingAppInfo)
    Log.dev(TAG, "Create passkey for rpId=$rpId, origin=$origin, privileged=$isPrivileged")

    // Generate the credential and persist it; both transports share this.
    val created = withContext(Dispatchers.IO) {
        WebAuthnAuthenticator.createCredential(
            rpId = rpId,
            rpName = rpName,
            // The request JSON carries the user handle already base64url-encoded; the shared
            // helper works in raw bytes and re-encodes when storing.
            userId = PasskeyUtils.decodeB64Url(userId),
            userName = userName,
            userDisplayName = userDisplayName,
            aaguid = PasskeyUtils.SAME_DEVICE_AAGUID,
            store = repository,
        )
    }
    val credentialIdB64 = PasskeyUtils.encodeB64Url(created.credentialId)

    // WebAuthn wraps the authenticator data in an attestationObject. Over CTAP the browser
    // builds this itself from the response fields, which is why the helper returns neither.
    val attestationObject = Cbor.encode(linkedMapOf<String, Any>(
        "fmt" to "none",
        "attStmt" to emptyMap<Any, Any>(),
        "authData" to created.authenticatorData,
    ))

    // For privileged browsers: use placeholder clientDataJSON (browser replaces it)
    // For Android apps: build our own clientDataJSON
    val clientDataJsonB64 = if (isPrivileged) {
        PasskeyUtils.encodeB64Url("<placeholder>".toByteArray())
    } else {
        val clientDataJson = JSONObject().apply {
            put("type", "webauthn.create")
            put("challenge", challenge)
            put("origin", origin)
            put("crossOrigin", false)
        }.toString()
        PasskeyUtils.encodeB64Url(clientDataJson.toByteArray())
    }

    val responseJson = JSONObject().apply {
        put("id", credentialIdB64)
        put("rawId", credentialIdB64)
        put("type", "public-key")
        put("authenticatorAttachment", "platform")
        put("response", JSONObject().apply {
            put("clientDataJSON", clientDataJsonB64)
            put("attestationObject", PasskeyUtils.encodeB64Url(attestationObject))
            put("transports", JSONArray(listOf("internal", "hybrid")))
            put("publicKeyAlgorithm", -7)
            put("publicKey", PasskeyUtils.encodeB64Url(created.publicKeySpki))
            put("authenticatorData", PasskeyUtils.encodeB64Url(created.authenticatorData))
        })
        put("clientExtensionResults", JSONObject())
    }.toString()

    Log.dev(TAG, "Passkey created successfully for rpId=$rpId, credId=$credentialIdB64")
    val credentialResponse = androidx.credentials.CreatePublicKeyCredentialResponse(responseJson)
    val result = Intent()
    PendingIntentHandler.setCreateCredentialResponse(result, credentialResponse)
    setResult(Activity.RESULT_OK, result)
}
