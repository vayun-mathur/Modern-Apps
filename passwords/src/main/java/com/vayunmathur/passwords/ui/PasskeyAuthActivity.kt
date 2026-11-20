@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.vayunmathur.passwords.ui

import android.content.Intent
import android.os.Bundle
import android.util.Base64
import com.vayunmathur.library.log.Log
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.provider.PendingIntentHandler
import androidx.fragment.app.FragmentActivity
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.biometric.unlockDatabaseWithBiometrics
import com.vayunmathur.library.util.DatabaseHelper
import com.vayunmathur.passwords.platform.cable.WebAuthnAuthenticator
import com.vayunmathur.passwords.data.PasswordRepository
import com.vayunmathur.passwords.domain.Cbor
import com.vayunmathur.passwords.platform.PasskeyCredentialService
import com.vayunmathur.passwords.platform.PasskeyUtils
import com.vayunmathur.passwords.platform.buildGetCredentialResponse
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Passkey Auth Activity: a fragment activity implementation for passkey auth activity. */
class PasskeyAuthActivity : FragmentActivity() {

    private lateinit var repository: PasswordRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val helper = DatabaseHelper(applicationContext)
        if (helper.isKeyGenerated()) {
            repository = PasswordRepository.get(applicationContext)
            proceedWithFlow()
        } else {
            unlockDatabaseWithBiometrics(
                activity = this,
                onSuccess = { passphrase ->
                    helper.storePassphrase(passphrase)
                    repository = PasswordRepository.get(applicationContext)
                    proceedWithFlow()
                },
                onFailure = { message ->
                    message?.let { AppMessages.show(it) }
                    setResult(RESULT_CANCELED)
                    finish()
                }
            )
        }
    }

    private fun proceedWithFlow() {
        val flow = intent.getStringExtra(EXTRA_FLOW)
        // Repository work (SQLCipher Room open/read, key generation, signing)
        // runs on Dispatchers.IO inside each handler; this coroutine stays on
        // the main thread so setResult/finish keep their original ordering.
        // lifecycleScope cancels it in onDestroy, so a destroyed activity
        // never completes a ceremony late.
        lifecycleScope.launch {
            try {
                when (flow) {
                    FLOW_CREATE -> handleCreate()
                    FLOW_GET -> handleGet()
                    FLOW_PASSWORD -> handlePassword()
                    FLOW_UNLOCK -> handleUnlock()
                    else -> {
                        Log.error(TAG, "Unknown flow: $flow")
                        setResult(RESULT_CANCELED)
                    }
                }
            } catch (expected: CancellationException) {
                // Lifecycle cancellation (activity destroyed): not a flow
                // failure, so don't overwrite the result or finish again.
                throw expected
            } catch (expected: IllegalStateException) {
                Log.error(TAG, "Error in passkey $flow flow", expected)
                setResult(RESULT_CANCELED)
            } catch (expected: org.json.JSONException) {
                // Calling app supplied malformed requestJson; cancel, don't crash.
                Log.error(TAG, "Malformed passkey request JSON in $flow flow", expected)
                setResult(RESULT_CANCELED)
            } catch (expected: IllegalArgumentException) {
                // Bad Base64url user handle / stored credential id from the calling app or import.
                Log.error(TAG, "Malformed passkey data in $flow flow", expected)
                setResult(RESULT_CANCELED)
            } catch (expected: java.security.GeneralSecurityException) {
                // Corrupt imported private key cannot sign; cancel the assertion.
                Log.error(TAG, "Passkey signing failed in $flow flow", expected)
                setResult(RESULT_CANCELED)
            }
            finish()
        }
    }

    private suspend fun handleCreate() {
        val request = PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent) ?: run {
            Log.error(TAG, "No create credential request in intent")
            setResult(RESULT_CANCELED)
            return
        }
        val publicKeyRequest = request.callingRequest as? CreatePublicKeyCredentialRequest ?: run {
            Log.error(TAG, "Request is not a PublicKeyCredentialRequest")
            setResult(RESULT_CANCELED)
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
            setResult(RESULT_CANCELED)
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
                setResult(RESULT_CANCELED)
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
            b64Url("<placeholder>".toByteArray())
        } else {
            val clientDataJson = JSONObject().apply {
                put("type", "webauthn.create")
                put("challenge", challenge)
                put("origin", origin)
                put("crossOrigin", false)
            }.toString()
            b64Url(clientDataJson.toByteArray())
        }

        val responseJson = JSONObject().apply {
            put("id", credentialIdB64)
            put("rawId", credentialIdB64)
            put("type", "public-key")
            put("authenticatorAttachment", "platform")
            put("response", JSONObject().apply {
                put("clientDataJSON", clientDataJsonB64)
                put("attestationObject", b64Url(attestationObject))
                put("transports", JSONArray(listOf("internal", "hybrid")))
                put("publicKeyAlgorithm", -7)
                put("publicKey", b64Url(created.publicKeySpki))
                put("authenticatorData", b64Url(created.authenticatorData))
            })
            put("clientExtensionResults", JSONObject())
        }.toString()

        Log.dev(TAG, "Passkey created successfully for rpId=$rpId, credId=$credentialIdB64")
        val credentialResponse = androidx.credentials.CreatePublicKeyCredentialResponse(responseJson)
        val result = Intent()
        PendingIntentHandler.setCreateCredentialResponse(result, credentialResponse)
        setResult(RESULT_OK, result)
    }

    private suspend fun handleGet() {
        val providerRequest = PendingIntentHandler.retrieveProviderGetCredentialRequest(intent) ?: run {
            Log.error(TAG, "No get credential request in intent")
            setResult(RESULT_CANCELED)
            return
        }
        val credentialId = intent.getStringExtra(EXTRA_CREDENTIAL_ID) ?: run {
            Log.error(TAG, "No credential ID in intent")
            setResult(RESULT_CANCELED)
            return
        }

        val passkey = withContext(Dispatchers.IO) { repository.getPasskeyByCredentialId(credentialId) } ?: run {
            Log.error(TAG, "Passkey not found for credentialId=$credentialId")
            setResult(RESULT_CANCELED)
            return
        }

        val publicKeyOption = providerRequest.credentialOptions
            .filterIsInstance<GetPublicKeyCredentialOption>()
            .firstOrNull() ?: run {
            Log.error(TAG, "No PublicKeyCredentialOption in request")
            setResult(RESULT_CANCELED)
            return
        }

        val json = JSONObject(publicKeyOption.requestJson)
        val challenge = json.getString("challenge")

        // Entry enumeration already filters by allowCredentials, but never
        // sign for a credential this request did not allow.
        val allowedIds = PasskeyUtils.credentialDescriptorIds(json, "allowCredentials")
        if (allowedIds.isNotEmpty() &&
            allowedIds.none { PasskeyUtils.credentialIdsMatch(passkey.credentialId, it) }
        ) {
            Log.error(TAG, "Selected credential is not in allowCredentials for rpId=${passkey.rpId}")
            setResult(RESULT_CANCELED)
            return
        }

        val callingAppInfo = providerRequest.callingAppInfo
        val privilegedOrigin = PasskeyUtils.getPrivilegedOrigin(callingAppInfo, applicationContext)
        val isPrivileged = privilegedOrigin != null
        val origin = privilegedOrigin ?: PasskeyUtils.getAndroidOrigin(callingAppInfo)
        Log.dev(TAG, "Get passkey for rpId=${passkey.rpId}, origin=$origin, privileged=$isPrivileged")

        val clientDataJson = JSONObject().apply {
            put("type", "webauthn.get")
            put("challenge", challenge)
            put("origin", origin)
            put("crossOrigin", false)
        }.toString()

        val clientDataHash: ByteArray
        val clientDataJsonB64: String
        if (isPrivileged) {
            clientDataHash = readPrivilegedHash(publicKeyOption)
                ?: MessageDigest.getInstance("SHA-256").digest(clientDataJson.toByteArray())
            clientDataJsonB64 = b64Url("<placeholder>".toByteArray())
        } else {
            clientDataHash = MessageDigest.getInstance("SHA-256").digest(clientDataJson.toByteArray())
            clientDataJsonB64 = b64Url(clientDataJson.toByteArray())
        }

        val assertion = withContext(Dispatchers.IO) {
            WebAuthnAuthenticator.signAssertion(passkey, clientDataHash, repository)
        }
        val authenticatorData = assertion.authenticatorData
        val sig = assertion.signature

        val credIdBytes = Base64.decode(
            passkey.credentialId,
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )

        val responseJson = JSONObject().apply {
            put("id", passkey.credentialId)
            put("rawId", b64Url(credIdBytes))
            put("type", "public-key")
            put("authenticatorAttachment", "platform")
            put("response", JSONObject().apply {
                put("clientDataJSON", clientDataJsonB64)
                put("authenticatorData", b64Url(authenticatorData))
                put("signature", b64Url(sig))
                put("userHandle", passkey.userId)
            })
            put("clientExtensionResults", JSONObject())
        }.toString()

        Log.dev(TAG, "Passkey assertion successful for rpId=${passkey.rpId}")
        val credentialResponse = PublicKeyCredential(responseJson)
        val result = Intent()
        PendingIntentHandler.setGetCredentialResponse(
            result,
            androidx.credentials.GetCredentialResponse(credentialResponse),
            providerRequest,
        )
        setResult(RESULT_OK, result)
    }

    private fun b64Url(data: ByteArray): String = PasskeyUtils.encodeB64Url(data)

    private fun readPrivilegedHash(option: GetPublicKeyCredentialOption): ByteArray? {
        return try {
            // An empty hash is no hash: signing over it cannot verify, so let
            // the caller fall back to hashing its own clientDataJSON.
            option.clientDataHash?.takeIf { it.isNotEmpty() }
        } catch (expected: GetCredentialUnknownException) {
            Log.dev(TAG, "privileged clientDataHash unavailable", expected)
            null
        } catch (expected: IllegalStateException) {
            Log.dev(TAG, "privileged clientDataHash unavailable", expected)
            null
        }
    }

    private suspend fun handlePassword() {
        val providerRequest = PendingIntentHandler.retrieveProviderGetCredentialRequest(intent) ?: run {
            Log.error(TAG, "No get credential request in intent")
            setResult(RESULT_CANCELED)
            return
        }
        val passwordId = intent.getLongExtra(PasskeyCredentialService.EXTRA_PASSWORD_ID, -1)
        if (passwordId == -1L) {
            Log.error(TAG, "No password ID in intent")
            setResult(RESULT_CANCELED)
            return
        }
        val password = withContext(Dispatchers.IO) { repository.getPasswordById(passwordId) }
        if (password == null) {
            Log.error(TAG, "Password not found for id=$passwordId")
            setResult(RESULT_CANCELED)
            return
        }
        val credentialResponse = androidx.credentials.PasswordCredential(password.username, password.password)
        val result = Intent()
        PendingIntentHandler.setGetCredentialResponse(
            result,
            androidx.credentials.GetCredentialResponse(credentialResponse),
            providerRequest,
        )
        setResult(RESULT_OK, result)
    }

    private suspend fun handleUnlock() {
        val request = PendingIntentHandler.retrieveBeginGetCredentialRequest(intent)
        if (request == null) {
            Log.error(TAG, "No BeginGetCredentialRequest in unlock intent")
            setResult(RESULT_CANCELED)
            return
        }

        val response = withContext(Dispatchers.IO) {
            buildGetCredentialResponse(
                applicationContext,
                request.beginGetCredentialOptions,
                repository,
            )
        }

        val result = Intent()
        PendingIntentHandler.setBeginGetCredentialResponse(result, response)
        setResult(RESULT_OK, result)
    }

    /** Shared constants and helpers for PasskeyAuthActivity. */
    companion object {
        const val EXTRA_FLOW = "flow"
        const val EXTRA_CREDENTIAL_ID = "credential_id"
        const val FLOW_CREATE = "create"
        const val FLOW_GET = "get"
        const val FLOW_PASSWORD = "password"
        const val FLOW_UNLOCK = "unlock"
        private const val TAG = "PasskeyAuthActivity"
    }
}
