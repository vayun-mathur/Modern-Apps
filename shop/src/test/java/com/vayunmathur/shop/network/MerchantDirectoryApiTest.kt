package com.vayunmathur.shop.network

import com.vayunmathur.library.network.RawResponse
import com.vayunmathur.shop.data.UcpError
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class MerchantDirectoryApiTest {
    private val valid = """[{"id":"store","name":"Store","category":"merchants","url":"https://store.example","ucpEndpoint":"https://shop.example/.well-known/ucp","ucpStatus":"live"}]"""

    @Test fun parsesBareArrayAndUsesAdvertisedDomainAsDiscoveryLead() {
        val row = MerchantDirectoryRepository.parseListings(valid).single()
        assertEquals("shop.example", row.domain)
        assertEquals("https://store.example", row.siteUrl)
        assertEquals("live", row.directoryStatus)
    }

    @Test fun dropsUnsafeIncompleteAndNonMerchantRows() {
        val rows = """[
            {"id":"one","name":"One","category":"merchants","url":"https://store.example","ucpEndpoint":"http://localhost/.well-known/ucp"},
            {"id":"two","name":"Two","category":"platforms","url":"https://store.example"},
            {"id":"three","category":"merchants","url":"https://store.example"},
            {"id":"four","name":"Four","category":"merchants","url":"intent://store.example"}
        ]"""
        assertTrue(MerchantDirectoryRepository.parseListings(rows).isEmpty())
    }

    @Test fun emptyArrayIsValidButObjectEnvelopeIsNot() {
        assertTrue(MerchantDirectoryRepository.parseListings("[]").isEmpty())
        assertThrows(UcpHttpException::class.java) { MerchantDirectoryRepository.parseListings("{}") }
    }

    @Test fun cachedReadsAndRefreshCooldownDoNotSpendRequests() = runBlocking {
        var calls = 0
        var now = 1000L
        val repository = MerchantDirectoryRepository({ calls++; response(200, valid) }, { now })
        assertTrue(repository.load().isSuccess)
        assertTrue(repository.load().isSuccess)
        assertTrue(repository.load(force = true).isFailure)
        assertEquals(1, calls)
        now += 60_001
        assertTrue(repository.load(force = true).isSuccess)
        assertEquals(2, calls)
    }

    @Test fun honorsRateLimitAndKeepsTheRequestBounded() = runBlocking {
        var now = 0L
        var calls = 0
        val repository = MerchantDirectoryRepository({
            calls++
            response(429, "", mapOf("Retry-After" to listOf("120")))
        }, { now })
        val first = repository.load().exceptionOrNull() as UcpHttpException
        assertEquals(UcpError.RateLimited(120_000), first.error)
        now = 61_000
        assertTrue(repository.load().isFailure)
        assertEquals(1, calls)
    }

    @Test fun doesNotAcceptRedirectsOrRetainFailureAsEmptyCatalog() = runBlocking {
        val repository = MerchantDirectoryRepository({ response(302, "") }, { 0L })
        assertTrue(repository.load().isFailure)
    }

    private fun response(status: Int, body: String, headers: Map<String, List<String>> = emptyMap()) =
        RawResponse(status, "", body.toByteArray(), headers, "https://ucplist.ai/api/listings?category=merchants")
}
