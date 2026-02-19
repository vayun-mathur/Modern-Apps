package com.vayunmathur.findfamily

import com.vayunmathur.findfamily.data.LocationValue
import com.vayunmathur.findfamily.data.LocationValueCompatible
import com.vayunmathur.findfamily.data.TemporaryLink
import com.vayunmathur.findfamily.data.User
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.DatabaseViewModel
import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA512
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.network.sockets.SocketTimeoutException
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.io.encoding.Base64
import kotlin.random.Random

object Networking {
    private fun getUrl() = "https://findfamily.cc"

    private val client = HttpClient() {
        install(ContentNegotiation) {
            json()
        }
    }
    private val crypto = CryptographyProvider.Default.get(RSA.OAEP)
    private var publickey: RSA.OAEP.PublicKey? = null
    private var privatekey: RSA.OAEP.PrivateKey? = null
    private var network_is_down = false

    var userid = 0L
        private set

    private lateinit var viewModel : DatabaseViewModel
    private lateinit var dataStoreUtils: DataStoreUtils

    suspend fun init(viewModel: DatabaseViewModel, dataStoreUtils: DataStoreUtils) {
        Networking.dataStoreUtils = dataStoreUtils
        Networking.viewModel = viewModel
        val (privateKey, publicKey) = crypto.keyPairGenerator(digest = SHA512).generateKey().let { Pair(it.privateKey, it.publicKey) }
        dataStoreUtils.setByteArray("privateKey", privateKey.encodeToByteArray(RSA.PrivateKey.Format.PEM), true)
        dataStoreUtils.setByteArray("publicKey", publicKey.encodeToByteArray(RSA.PublicKey.Format.PEM), true)
        dataStoreUtils.setLong("userid", Random.nextLong(), true)

        publickey = crypto.publicKeyDecoder(SHA512).decodeFromByteArray(RSA.PublicKey.Format.PEM, dataStoreUtils.getByteArray("publicKey")!!)
        privatekey = crypto.privateKeyDecoder(SHA512).decodeFromByteArray(RSA.PrivateKey.Format.PEM, dataStoreUtils.getByteArray("privateKey")!!)
        userid = dataStoreUtils.getLong("userid")!!
    }

    private suspend fun <T> checkNetworkDown(try_connect: suspend ()->T?): T? {
        try {
            val x = try_connect()
            network_is_down = false
            return x
        } catch(_: ConnectTimeoutException) {
            if (!network_is_down) {
                //TODO: notify user
                println("network is down")
            }
            network_is_down = true
        } catch(_: SocketTimeoutException) {
            if (!network_is_down) {
                //TODO: notify user
                println("network is down")
            }
            network_is_down = true
        } catch(e: Throwable) {
            println(e.printStackTrace())
        }
        return null
    }

    suspend fun problem(arg: String) {
        @Serializable
        data class Problem(val problem: String)
        checkNetworkDown {
            client.post("${getUrl()}/api/problem") {
                contentType(ContentType.Application.Json)
                setBody(Problem(arg))
            }
        }
    }

    private suspend inline fun <reified T, reified I> makeRequest(path: String, body: I): T? {
        return checkNetworkDown {
            val response = client.post("${getUrl()}$path") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }
            if(response.status != HttpStatusCode.OK) return@checkNetworkDown null
            val result = response.body<T>()
            return@checkNetworkDown result
        }
    }

    private suspend fun register(): Boolean {
        @Serializable
        data class Register(val userid: ULong, val key: String)
        return makeRequest("/api/register", Register(
            userid.toULong(),
            Base64.encode(publickey!!.encodeToByteArray(RSA.PublicKey.Format.PEM))
        )
        ) ?: false
    }

    suspend fun ensureUserExists() {
        if(getKey(userid) == null) {
            register()
        }
    }

    private suspend fun getKey(userid: Long): RSA.OAEP.PublicKey? {
        return checkNetworkDown {
            val response = client.post("${getUrl()}/api/getkey") {
                contentType(ContentType.Application.Json)
                setBody("{\"userid\": ${userid.toULong()}}")
            }
            if(response.status != HttpStatusCode.OK) {
                return@checkNetworkDown null
            }
            return@checkNetworkDown crypto.publicKeyDecoder(SHA512).decodeFromByteArray(RSA.PublicKey.Format.PEM,
                Base64.decode(response.bodyAsText())
            )
        }
    }

    suspend fun publishLocation(location: LocationValue, user: User): Boolean {
        val key = if(user.encryptionKey != null) {
            crypto.publicKeyDecoder(SHA512).decodeFromByteArray(RSA.PublicKey.Format.PEM,
                Base64.decode(user.encryptionKey)
            )
        } else {
            getKey(user.id)?.also {
                val keyString = Base64.encode(it.encodeToByteArray(RSA.PublicKey.Format.PEM))
                viewModel.upsert(user.copy(encryptionKey = keyString))
            }
        } ?: return false
        return makeRequest("/api/location/publish", encryptLocation(location, user.id, key)) ?: false
    }

    suspend fun publishLocation(location: LocationValue, user: TemporaryLink): Boolean {
        val key = crypto.publicKeyDecoder(SHA512).decodeFromByteArray(RSA.PublicKey.Format.PEM,
            Base64.decode(user.publicKey)
        )
        return makeRequest("/api/location/publish", encryptLocation(location, user.id, key)) ?: false
    }

    suspend fun receiveLocations(): List<LocationValue>? {
        val strings: List<String> = makeRequest("/api/location/receive", "{\"userid\": $userid}") ?: return null
        return strings.map { decryptLocation(it) }
    }

    private suspend fun encryptLocation(location: LocationValue, recipientUserID: Long, key: RSA.OAEP.PublicKey): LocationSharingData {
        val cipher = key.encryptor()
        val str = Json.encodeToString(location.toCompatible())
        val encryptedData = Base64.encode(cipher.encrypt(str.encodeToByteArray()))
        return LocationSharingData(recipientUserID.toULong(), encryptedData)
    }

    private suspend fun decryptLocation(encryptedLocation: String): LocationValue {
        val cipher = privatekey!!.decryptor()
        val decryptedData = cipher.decrypt(Base64.decode(encryptedLocation)).decodeToString()
        return Json.decodeFromString<LocationValueCompatible>(decryptedData).toLocationValue()
    }

    suspend fun generateKeyPair(): RSA.OAEP.KeyPair {
        return crypto.keyPairGenerator(digest = SHA512).generateKey()
    }

    @Serializable
    private data class LocationSharingData(val recipientUserID: ULong, val encryptedLocation: String)
}