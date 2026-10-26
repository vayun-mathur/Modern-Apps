package com.vayunmathur.shop.network

import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.RawResponse
import com.vayunmathur.shop.data.MerchantListing
import com.vayunmathur.shop.data.UcpError
import com.vayunmathur.shop.domain.MerchantUrls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import java.io.ByteArrayOutputStream
import java.net.URI

internal object MerchantDirectoryApi {
    private val repository = MerchantDirectoryRepository()
    suspend fun load(force: Boolean = false): Result<List<MerchantListing>> = repository.load(force)
}

internal class MerchantDirectoryRepository(
    private val request: suspend () -> RawResponse = {
        val bytes = ByteArrayOutputStream()
        val response = NetworkClient.stream(
            "https://ucplist.ai/api/listings?category=merchants",
            headers = mapOf("Accept" to "application/json"),
            followRedirects = false,
            connectTimeoutMs = 10_000,
            readTimeoutMs = 10_000,
        ) { stream, response ->
            require(response.contentLength == null || response.contentLength!! <= MAX_BYTES) { "Directory response is too large" }
            if (stream == null) {
                bytes.write(response.body.toByteArray())
            } else {
                val buffer = ByteArray(8192)
                while (true) {
                    val count = stream.read(buffer)
                    if (count < 0) break
                    currentCoroutineContext().ensureActive()
                    require(bytes.size() + count <= MAX_BYTES) { "Directory response is too large" }
                    bytes.write(buffer, 0, count)
                }
            }
        }
        RawResponse(response.status, response.statusMessage, bytes.toByteArray(), response.headers, response.url)
    },
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private var cached: List<MerchantListing>? = null
    private var fetchedAt = 0L
    private var refreshAt = 0L

    suspend fun load(force: Boolean = false): Result<List<MerchantListing>> = mutex.withLock {
        val now = clock()
        cached?.takeIf { !force && now - fetchedAt in 0 until 86_400_000 }?.let {
            return@withLock Result.success(it)
        }
        if (now < refreshAt) {
            return@withLock Result.failure(UcpHttpException(UcpError.RateLimited(refreshAt - now)))
        }
        refreshAt = now + 60_000
        try {
            val response = withTimeout(15_000) { request() }
            if (response.status == 429) {
                val seconds = response.header("Retry-After")?.toLongOrNull()?.coerceIn(60, 86_400) ?: 60
                refreshAt = clock() + seconds * 1000
                return@withLock Result.failure(UcpHttpException(UcpError.RateLimited(seconds * 1000)))
            }
            if (!response.isSuccess) {
                return@withLock Result.failure(UcpHttpException(UcpError.Http(response.status, "")))
            }
            require(response.bytes.size <= MAX_BYTES) { "Directory response is too large" }
            val listings = parseListings(response.text)
            cached = listings
            fetchedAt = clock()
            Result.success(listings)
        } catch (_: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            Result.failure(UcpHttpException(UcpError.Transport("Merchant directory timed out")))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    companion object {
        private const val MAX_BYTES = 2 * 1024 * 1024

        internal fun parseListings(body: String): List<MerchantListing> {
            val array = Json.parseToJsonElement(body) as? JsonArray
                ?: throw UcpHttpException(UcpError.Malformed("Expected merchant directory array"))
            require(array.size <= 5000) { "Directory has too many records" }
            return array.mapNotNull { item ->
                val row = item.asObject() ?: return@mapNotNull null
                if (row.text("category") != "merchants") return@mapNotNull null
                val id = row.text("id")?.takeIf { it.isNotBlank() && it.length <= 200 } ?: return@mapNotNull null
                val name = row.text("name")?.takeIf { it.isNotBlank() && it.length <= 200 } ?: return@mapNotNull null
                val site = row.text("url")?.let(MerchantUrls::httpsUrl) ?: return@mapNotNull null
                val endpoint = row.text("ucpEndpoint")?.takeIf(String::isNotBlank)
                val discoveryUrl = if (endpoint == null) site else MerchantUrls.httpsUrl(endpoint) ?: return@mapNotNull null
                val domain = MerchantUrls.domain(URI(discoveryUrl).host) ?: return@mapNotNull null
                MerchantListing(
                    id = id,
                    name = name,
                    domain = domain,
                    siteUrl = site,
                    description = row.text("description").orEmpty().take(600),
                    directoryStatus = row.text("ucpStatus").orEmpty().take(40),
                    directoryVerifiedAt = row.text("verifiedAt")?.take(80),
                )
            }.distinctBy { it.id to it.domain }.take(1000)
        }
    }
}
