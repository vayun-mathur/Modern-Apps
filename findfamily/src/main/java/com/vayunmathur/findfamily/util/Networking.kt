package com.vayunmathur.findfamily.util
import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.LocationValueCompatible
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.findfamily.data.RequestStatus
import com.vayunmathur.findfamily.data.UserDao
import com.vayunmathur.findfamily.uwb.UwbEnvelope
import com.vayunmathur.e2ee.E2ee
import com.vayunmathur.e2ee.E2eeIdentity
import com.vayunmathur.e2ee.E2eeKeyStore
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.util.DataStoreUtils
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.random.Random
import kotlin.time.Clock

object Networking {
    private const val URL = "https://findfamily.cc"

    private val json = Json {
        ignoreUnknownKeys = true
    }

    /** Shared end-to-end-encryption identity (key generation/storage/crypto lives in :library:e2ee-p2p). */
    private lateinit var identity: E2eeIdentity
    private var network_is_down = false

    var userid = 0L
        private set

    private lateinit var userDao: UserDao
    private lateinit var dataStoreUtils: DataStoreUtils

    // init() is called from both the app UI (on launch) and the location
    // foreground service (which may start first). Guard it so only one coroutine
    // runs the identity/userid bootstrap at a time, and make it a no-op once done.
    private val initMutex = Mutex()
    @Volatile
    private var initialized = false

    /** Adapts the app's encrypted DataStore to the e2ee module's storage abstraction. */
    private class DataStoreKeyStore(private val ds: DataStoreUtils) : E2eeKeyStore {
        override fun getBytes(name: String): ByteArray? = ds.getByteArray(name)
        override suspend fun setBytes(name: String, value: ByteArray, onlyIfAbsent: Boolean) =
            ds.setByteArray(name, value, onlyIfAbsent)
    }

    suspend fun init(userDao: UserDao, dataStoreUtils: DataStoreUtils, meName: String) {
        if (initialized) return
        initMutex.withLock {
            if (initialized) return
            Networking.dataStoreUtils = dataStoreUtils
            Networking.userDao = userDao
            // Loads the persisted keypair (or generates+stores one on first launch) using the same
            // "publicKey"/"privateKey" datastore entries as before, so existing installs keep their key.
            identity = E2eeIdentity.loadOrCreate(DataStoreKeyStore(dataStoreUtils))
            dataStoreUtils.setLong("userid", Random.nextLong(), true)
            userid = dataStoreUtils.getLong("userid")!!

            if (userDao.getById(userid) == null) {
                userDao.upsert(
                    User(
                        meName,
                        null,
                        "Unnamed Location",
                        true,
                        RequestStatus.MUTUAL_CONNECTION,
                        Clock.System.now(),
                        null,
                        userid,
                    )
                )
            }
            initialized = true
        }
    }

    private suspend fun <T> checkNetworkDown(makeRequest: suspend ()->T?): T? {
        try {
            val x = makeRequest()
            network_is_down = false
            return x
        } catch (e: CancellationException) {
            throw e
        } catch(e: Exception) {
            network_is_down = true
        }
        return null
    }

    private suspend inline fun <reified T, reified I> makeRequest(path: String, body: I): T? {
        return checkNetworkDown {
            try {
                NetworkClient.callJson<T>(
                    url = "$URL$path",
                    method = "POST",
                    headers = mapOf("Content-Type" to "application/json"),
                    body = body
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun register(): Boolean {
        @Serializable
        data class Register(val userid: ULong, val key: String)
        return makeRequest<Boolean, Register>("/api/register", Register(
            userid.toULong(),
            Base64.encode(identity.publicKeyPem)
        )
        ) ?: false
    }

    suspend fun ensureUserExists() {
        if(getKey(userid) == null) {
            register()
        }
    }

    /** Fetches a peer's public key by id, returning its PEM bytes (or null if unknown/offline). */
    private suspend fun getKey(userid: Long): ByteArray? {
        return checkNetworkDown {
            val response = NetworkClient.performRequest(
                url = "$URL/api/getkey",
                method = "POST",
                headers = mapOf("Content-Type" to "application/json"),
                body = json.encodeToString(GetKeyRequest(userid.toULong()))
            )
            if(response.status != 200) {
                return@checkNetworkDown null
            }
            return@checkNetworkDown Base64.decode(response.body)
        }
    }

    /** Local platform tag included in outgoing heartbeat payloads so the peer learns we're on Android. */
    private const val PLATFORM = "android"

    suspend fun publishLocation(location: LocationValue, user: User): Boolean {
        val keyPem = if (user.encryptionKey != null) {
            Base64.decode(user.encryptionKey)
        } else {
            getKey(user.id)?.also {
                userDao.upsert(user.copy(encryptionKey = Base64.encode(it)))
            }
        } ?: return false
        return makeRequest<Boolean, LocationSharingData>("/api/location/publish", encryptLocation(location, user.id, keyPem)) ?: false
    }

    suspend fun publishLocation(location: LocationValue, user: TemporaryLink): Boolean {
        val keyPem = Base64.decode(user.publicKey)
        return makeRequest<Boolean, LocationSharingData>("/api/location/publish", encryptLocation(location, user.id, keyPem)) ?: false
    }

    suspend fun receiveLocations(): List<LocationValue>? {
        val strings: List<String>? = makeRequest("/api/location/receive", json.encodeToString(UserIdRequest(userid)))
        return strings?.map { decryptLocation(it) }?.also { decoded ->
            // Opportunistically update peer platform tags learned from incoming heartbeats.
            decoded.forEach { (loc, platform) ->
                if (platform != null) {
                    val u = userDao.getById(loc.userid)
                    if (u != null && u.platform != platform) {
                        userDao.upsert(u.copy(platform = platform))
                    }
                }
            }
        }?.map { it.first }
    }

    // ----------------------------------------------------------------
    // UWB session-setup channel
    //
    // Mirrors the location publish/receive flow but carries the small UWB
    // handshake envelopes (request / ack / config / cancel) end-to-end
    // encrypted. Each payload is at most a few hundred bytes; ranging samples
    // themselves never touch the server.
    // ----------------------------------------------------------------

    suspend fun publishUwbMessage(envelope: UwbEnvelope, recipientUserId: Long, recipient: User? = null): Boolean {
        val keyPem = if (recipient?.encryptionKey != null) {
            Base64.decode(recipient.encryptionKey)
        } else {
            getKey(recipientUserId) ?: return false
        }
        val str = json.encodeToString(envelope)
        val encryptedData = Base64.encode(E2ee.encryptTo(keyPem, str.encodeToByteArray()))
        // We can't reuse `makeRequest<Boolean, ...>` here because the server
        // returns `204 No Content` (no body) on success, and Ktor's content
        // negotiation throws `NoTransformationFoundException` trying to
        // deserialize an empty body into a `Boolean`. `performRequest` returns
        // the raw response and we treat any 2xx status as success.
        val bodyJson = json.encodeToString(LocationSharingData(recipientUserId.toULong(), encryptedData))
        return checkNetworkDown {
            val resp = NetworkClient.performRequest(
                url = "$URL/api/uwb/publish",
                method = "POST",
                headers = mapOf("Content-Type" to "application/json"),
                body = bodyJson
            )
            resp.status in 200..299
        } ?: false
    }

    /**
     * Drains incoming UWB envelopes addressed to this user. The server queue
     * is cleared on receive (same semantics as `/api/location/receive`).
     */
    suspend fun receiveUwbMessages(): List<UwbEnvelope>? {
        val strings: List<String>? = makeRequest("/api/uwb/receive", json.encodeToString(UserIdRequest(userid)))
        return strings?.mapNotNull { b64 ->
            runCatching {
                val plain = identity.decrypt(Base64.decode(b64)).decodeToString()
                json.decodeFromString<UwbEnvelope>(plain)
            }.getOrNull()
        }
    }

    private suspend fun encryptLocation(location: LocationValue, recipientUserID: Long, keyPem: ByteArray): LocationSharingData {
        val str = json.encodeToString(location.toCompatible(senderPlatform = PLATFORM))
        val encryptedData = Base64.encode(E2ee.encryptTo(keyPem, str.encodeToByteArray()))
        return LocationSharingData(recipientUserID.toULong(), encryptedData)
    }

    private suspend fun decryptLocation(encryptedLocation: String): Pair<LocationValue, String?> {
        val decryptedData = identity.decrypt(Base64.decode(encryptedLocation)).decodeToString()
        val compat = json.decodeFromString<LocationValueCompatible>(decryptedData)
        return compat.toLocationValue() to compat.senderPlatform
    }

    /** Generates a fresh keypair (used for anonymous temporary share links). PEM bytes. */
    suspend fun generateKeyPair(): E2ee.KeyPairPem = E2ee.generateKeyPair()

    /**
     * Computes the verification "security code" for a connection: a fingerprint of *both* this
     * device's and [user]'s public keys. Identical on both peers' devices; comparing them confirms
     * the end-to-end channel isn't being intercepted. Returns null if the peer's key isn't known yet.
     */
    suspend fun securityCode(user: User): String? {
        val theirPem = peerPublicKeyPem(user) ?: return null
        return runCatching { E2ee.securityCode(identity.publicKeyPem, theirPem) }.getOrNull()
    }

    /** The peer's public key as PEM bytes — from the cached [User.encryptionKey] or fetched by id. */
    private suspend fun peerPublicKeyPem(user: User): ByteArray? {
        user.encryptionKey?.let { return Base64.decode(it) }
        val pem = getKey(user.id) ?: return null
        userDao.upsert(user.copy(encryptionKey = Base64.encode(pem)))
        return pem
    }

    @Serializable
    private data class LocationSharingData(val recipientUserID: ULong, val encryptedLocation: String)

    @Serializable
    private data class UserIdRequest(val userid: Long)

    @Serializable
    private data class GetKeyRequest(val userid: ULong)
}
