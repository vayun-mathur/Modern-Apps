package com.vayunmathur.passwords.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import android.util.Base64
import android.util.Log
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.provider.PendingIntentHandler
import androidx.fragment.app.FragmentActivity
import com.vayunmathur.library.biometric.unlockDatabaseWithBiometrics
import com.vayunmathur.library.util.DatabaseHelper
import com.vayunmathur.library.room.buildDatabase
import com.vayunmathur.passwords.cable.WebAuthnAuthenticator
import com.vayunmathur.passwords.data.Passkey
import com.vayunmathur.passwords.data.PasswordDatabase
import com.vayunmathur.passwords.util.Cbor
import com.vayunmathur.passwords.util.PasskeyCredentialService
import com.vayunmathur.passwords.util.PasskeyUtils
import com.vayunmathur.passwords.util.buildGetCredentialResponse
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.util.UUID

class PasskeyAuthActivity : FragmentActivity() {

    private lateinit var db: PasswordDatabase
    private val passkeyDao by lazy { db.passkeyDao() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val helper = DatabaseHelper(applicationContext)
        if (helper.isKeyGenerated()) {
            db = applicationContext.buildDatabase<PasswordDatabase>()
            proceedWithFlow()
        } else {
            unlockDatabaseWithBiometrics(
                activity = this,
                onSuccess = { passphrase ->
                    helper.storePassphrase(passphrase)
                    db = applicationContext.buildDatabase<PasswordDatabase>(encryptionPassword = passphrase)
                    proceedWithFlow()
                },
                onFailure = { message ->
                    message?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
                    setResult(RESULT_CANCELED)
                    finish()
                }
            )
        }
    }

    private fun proceedWithFlow() {
        val flow = intent.getStringExtra(EXTRA_FLOW)
        try {
            when (flow) {
                FLOW_CREATE -> handleCreate()
                FLOW_GET -> handleGet()
                FLOW_PASSWORD -> handlePassword()
                FLOW_UNLOCK -> handleUnlock()
                else -> {
                    Log.e(TAG, "Unknown flow: $flow")
                    setResult(RESULT_CANCELED)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in passkey $flow flow", e)
            setResult(RESULT_CANCELED)
        }
        finish()
    }

    private fun handleCreate() {
        val request = PendingIntentHandler.retrieveProviderCreateCredentialRequest(intent) ?: run {
            Log.e(TAG, "No create credential request in intent")
            setResult(RESULT_CANCELED)
            return
        }
        val publicKeyRequest = request.callingRequest as? CreatePublicKeyCredentialRequest ?: run {
            Log.e(TAG, "Request is not a PublicKeyCredentialRequest")
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

        // Privileged browsers provide clientDataHash directly
        val callingAppInfo = request.callingAppInfo
        val privilegedOrigin = PasskeyUtils.getPrivilegedOrigin(callingAppInfo, applicationContext)
        val isPrivileged = privilegedOrigin != null
        val origin = privilegedOrigin ?: PasskeyUtils.getAndroidOrigin(callingAppInfo)
        Log.d(TAG, "Create passkey for rpId=$rpId, origin=$origin, privileged=$isPrivileged")

        // Generate EC P-256 key pair
        val keyPairGenerator = KeyPairGenerator.getInstance("EC")
        keyPairGenerator.initialize(ECGenParameterSpec("secp256r1"))
        val keyPair = keyPairGenerator.generateKeyPair()
        val ecPublicKey = keyPair.public as ECPublicKey

        val credentialId = PasskeyUtils.generateCredentialId()
        val credentialIdB64 = b64Url(credentialId)

        // Build COSE public key
        val xBytes = toFixedBytes(ecPublicKey.w.affineX, 32)
        val yBytes = toFixedBytes(ecPublicKey.w.affineY, 32)
        val coseKeyMap = linkedMapOf<Any, Any>(
            1L to 2L,    // kty: EC2
            3L to -7L,   // alg: ES256
            -1L to 1L,   // crv: P-256
            -2L to xBytes as Any, // x
            -3L to yBytes as Any, // y
        )
        val coseKeyBytes = Cbor.encode(coseKeyMap)

        // Build authenticator data with attested credential data
        val authDataBase = PasskeyUtils.buildAuthenticatorData(
            rpId = rpId,
            attestedCredentialData = true,
        )
        val authData = authDataBase +
            AAGUID +
            byteArrayOf((credentialId.size shr 8).toByte(), credentialId.size.toByte()) +
            credentialId +
            coseKeyBytes

        // Build attestation object using CBOR
        val attestationObject = Cbor.encode(linkedMapOf<String, Any>(
            "fmt" to "none",
            "attStmt" to emptyMap<Any, Any>(),
            "authData" to authData,
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
                put("publicKey", b64Url(keyPair.public.encoded))
                put("authenticatorData", b64Url(authData))
            })
            put("clientExtensionResults", JSONObject())
        }.toString()

        runBlocking {
            passkeyDao.upsert(
                Passkey(
                    rpId = rpId,
                    rpName = rpName,
                    credentialId = credentialIdB64,
                    userId = userId,
                    userName = userName,
                    userDisplayName = userDisplayName,
                    privateKeyBytes = keyPair.private.encoded,
                    creationTime = System.currentTimeMillis(),
                    lastUsedTime = System.currentTimeMillis(),
                    signCount = 0,
                )
            )
        }

        Log.d(TAG, "Passkey created successfully for rpId=$rpId, credId=$credentialIdB64")
        val credentialResponse = androidx.credentials.CreatePublicKeyCredentialResponse(responseJson)
        val result = Intent()
        PendingIntentHandler.setCreateCredentialResponse(result, credentialResponse)
        setResult(RESULT_OK, result)
    }

    private fun handleGet() {
        val providerRequest = PendingIntentHandler.retrieveProviderGetCredentialRequest(intent) ?: run {
            Log.e(TAG, "No get credential request in intent")
            setResult(RESULT_CANCELED)
            return
        }
        val credentialId = intent.getStringExtra(EXTRA_CREDENTIAL_ID) ?: run {
            Log.e(TAG, "No credential ID in intent")
            setResult(RESULT_CANCELED)
            return
        }

        val passkey = runBlocking { passkeyDao.getByCredentialId(credentialId) } ?: run {
            Log.e(TAG, "Passkey not found for credentialId=$credentialId")
            setResult(RESULT_CANCELED)
            return
        }

        val publicKeyOption = providerRequest.credentialOptions
            .filterIsInstance<GetPublicKeyCredentialOption>()
            .firstOrNull() ?: run {
            Log.e(TAG, "No PublicKeyCredentialOption in request")
            setResult(RESULT_CANCELED)
            return
        }

        val json = JSONObject(publicKeyOption.requestJson)
        val challenge = json.getString("challenge")

        val callingAppInfo = providerRequest.callingAppInfo
        val privilegedOrigin = PasskeyUtils.getPrivilegedOrigin(callingAppInfo, applicationContext)
        val isPrivileged = privilegedOrigin != null
        val origin = privilegedOrigin ?: PasskeyUtils.getAndroidOrigin(callingAppInfo)
        Log.d(TAG, "Get passkey for rpId=${passkey.rpId}, origin=$origin, privileged=$isPrivileged")

        val clientDataJson = JSONObject().apply {
            put("type", "webauthn.get")
            put("challenge", challenge)
            put("origin", origin)
            put("crossOrigin", false)
        }.toString()

        val clientDataHash: ByteArray
        val clientDataJsonB64: String
        if (isPrivileged) {
            clientDataHash = (try { publicKeyOption.clientDataHash } catch (_: Exception) { null })
                ?: MessageDigest.getInstance("SHA-256").digest(clientDataJson.toByteArray())
            clientDataJsonB64 = b64Url("<placeholder>".toByteArray())
        } else {
            clientDataHash = MessageDigest.getInstance("SHA-256").digest(clientDataJson.toByteArray())
            clientDataJsonB64 = b64Url(clientDataJson.toByteArray())
        }

        val assertion = runBlocking {
            WebAuthnAuthenticator.signAssertion(passkey, clientDataHash, passkeyDao)
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

        Log.d(TAG, "Passkey assertion successful for rpId=${passkey.rpId}")
        val credentialResponse = PublicKeyCredential(responseJson)
        val result = Intent()
        PendingIntentHandler.setGetCredentialResponse(
            result,
            androidx.credentials.GetCredentialResponse(credentialResponse),
        )
        setResult(RESULT_OK, result)
    }

    private fun b64Url(data: ByteArray): String =
        Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    private fun toFixedBytes(value: java.math.BigInteger, length: Int): ByteArray {
        val bytes = value.toByteArray()
        return when {
            bytes.size == length -> bytes
            bytes.size > length -> bytes.copyOfRange(bytes.size - length, bytes.size)
            else -> ByteArray(length - bytes.size) + bytes
        }
    }

    private fun handlePassword() {
        val passwordId = intent.getLongExtra(PasskeyCredentialService.EXTRA_PASSWORD_ID, -1)
        if (passwordId == -1L) {
            Log.e(TAG, "No password ID in intent")
            setResult(RESULT_CANCELED)
            return
        }
        val password = runBlocking { db.passwordDao().getById(passwordId) }
        if (password == null) {
            Log.e(TAG, "Password not found for id=$passwordId")
            setResult(RESULT_CANCELED)
            return
        }
        val credentialResponse = androidx.credentials.PasswordCredential(password.userId, password.password)
        val result = Intent()
        PendingIntentHandler.setGetCredentialResponse(
            result,
            androidx.credentials.GetCredentialResponse(credentialResponse),
        )
        setResult(RESULT_OK, result)
    }

    private fun handleUnlock() {
        val request = PendingIntentHandler.retrieveBeginGetCredentialRequest(intent)
        if (request == null) {
            Log.e(TAG, "No BeginGetCredentialRequest in unlock intent")
            setResult(RESULT_CANCELED)
            return
        }

        val response = runBlocking {
            buildGetCredentialResponse(
                applicationContext,
                request.beginGetCredentialOptions,
                passkeyDao,
                db.passwordDao(),
            )
        }

        val result = Intent()
        PendingIntentHandler.setBeginGetCredentialResponse(result, response)
        setResult(RESULT_OK, result)
    }

    companion object {
        const val EXTRA_FLOW = "flow"
        const val EXTRA_CREDENTIAL_ID = "credential_id"
        const val FLOW_CREATE = "create"
        const val FLOW_GET = "get"
        const val FLOW_PASSWORD = "password"
        const val FLOW_UNLOCK = "unlock"
        private const val TAG = "PasskeyAuthActivity"
        private val AAGUID = uuidToBytes(UUID.fromString("a1b2c3d4-e5f6-7890-abcd-ef1234567890"))

        private fun uuidToBytes(uuid: UUID): ByteArray =
            ByteBuffer.allocate(16)
                .putLong(uuid.mostSignificantBits)
                .putLong(uuid.leastSignificantBits)
                .array()
    }
}
