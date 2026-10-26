package com.vayunmathur.shop.network

import com.vayunmathur.library.network.NetworkDataStream
import com.vayunmathur.library.network.RawResponse
import com.vayunmathur.library.network.SimpleResponse
import com.vayunmathur.shop.data.UcpError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class UcpDiscoveryResolveTest {
    private val rootUrl = "https://shop.example/.well-known/ucp"
    private val search = "dev.ucp.shopping.catalog.search"
    private val april = UcpApi.APRIL_VERSION
    private val current = UcpApi.VERSION

    private fun leaf(
        version: String = current, endpoint: String = "https://gateway.example/custom", transport: String = "rest",
        capabilityVersion: String = version, serviceVersion: String = version, links: String = "{}",
    ): String = """{"ucp":{"version":"$version","supported_versions":$links,
        "services":{"dev.ucp.shopping":[{"version":"$serviceVersion","transport":"$transport","endpoint":"$endpoint"}]},
        "capabilities":{"$search":[{"version":"$capabilityVersion"}],"dev.ucp.shopping.checkout":[{"version":"$version"}]},
        "payment_handlers":{"com.example.pay":[{"id":"handler-$version","version":"$version"},{"id":"foreign","version":"2099-01-01"}]}}}"""

    private fun obj(raw: String) = UcpApi.json.parseToJsonElement(raw) as JsonObject
    private fun response(url: String, body: String, status: Int = 200, headers: Map<String, List<String>> = emptyMap()) =
        RawResponse(status, "", body.toByteArray(Charsets.UTF_8), headers, url)

    private class FakeTransport(val reply: suspend (UcpRequest) -> RawResponse) : UcpTransport {
        val requests = mutableListOf<UcpRequest>()
        override suspend fun execute(request: UcpRequest): RawResponse {
            requests.add(request)
            return reply(request)
        }
        override suspend fun stream(request: UcpRequest, block: suspend (NetworkDataStream?, SimpleResponse) -> Unit): SimpleResponse =
            error("Discovery must not stream or call a service")
    }

    private fun withTransport(fake: FakeTransport, block: suspend () -> Unit) = runBlocking {
        val previous = UcpApi.transport
        try {
            UcpApi.transport = fake
            block()
        } finally {
            UcpApi.transport = previous
        }
    }

    @Test
    fun rootLeafUsesOnlyAdvertisedServiceAndExactRegistries() {
        val fake = FakeTransport { response(it.url, leaf()) }
        withTransport(fake) {
            val service = UcpDiscovery.resolve("SHOP.EXAMPLE", search).getOrThrow()
            assertEquals(current, service.version)
            assertEquals("rest", service.transport)
            assertEquals("https://gateway.example/custom", service.endpoint)
            assertEquals(setOf(search, "dev.ucp.shopping.checkout"), service.capabilities)
            assertEquals(setOf("handler-$current"), service.paymentHandlerIds)
            assertEquals(listOf(rootUrl), fake.requests.map { it.url })
            assertTrue(fake.requests.all { it.method == "GET" && it.body == null })
            assertTrue(fake.requests.all { !it.followRedirects })
        }
    }

    @Test
    fun prefersCurrentLinkedLeafEvenWhenRootIsApril() {
        val currentUrl = "https://profiles.example/august"
        val fake = FakeTransport {
            response(it.url, if (it.url == rootUrl) leaf(april, links = """{"$current":"$currentUrl"}""")
                else leaf(current, endpoint = "https://new.example/api"))
        }
        withTransport(fake) {
            val service = UcpDiscovery.resolve("shop.example", search).getOrThrow()
            assertEquals(current, service.version)
            assertEquals("https://new.example/api", service.endpoint)
            assertEquals(setOf("handler-$current"), service.paymentHandlerIds)
            assertEquals(listOf(rootUrl, currentUrl), fake.requests.map { it.url })
        }
    }

    @Test
    fun fallsBackToCompleteAprilLeafWithoutMergingRootCapabilities() {
        val aprilUrl = "https://profiles.example/april"
        val fake = FakeTransport {
            response(it.url, if (it.url == rootUrl) leaf(current, capabilityVersion = april,
                links = """{"$april":"$aprilUrl"}""") else leaf(april, endpoint = "https://april.example/api"))
        }
        withTransport(fake) {
            val service = UcpDiscovery.resolve("shop.example", search).getOrThrow()
            assertEquals(april, service.version)
            assertEquals("https://april.example/api", service.endpoint)
            assertEquals(setOf("handler-$april"), service.paymentHandlerIds)
            assertEquals(listOf(rootUrl, aprilUrl), fake.requests.map { it.url })
        }
    }

    @Test
    fun preferredVersionIsExactAndUnknownVersionDoesNotFetch() {
        val fake = FakeTransport { response(it.url, leaf(april)) }
        withTransport(fake) {
            assertTrue(UcpDiscovery.resolve("shop.example", search, current).isFailure)
            assertEquals(april, UcpDiscovery.resolve("shop.example", search, april).getOrThrow().version)
            val before = fake.requests.size
            assertTrue(UcpDiscovery.resolve("shop.example", search, "2026-01-11").isFailure)
            assertEquals(before, fake.requests.size)
        }
    }

    @Test
    fun linkedLeafCannotBorrowServicesOrCapabilitiesFromRoot() {
        val aprilUrl = "https://profiles.example/april"
        for (broken in listOf(
            """{"ucp":{"version":"$april","capabilities":{"$search":[{"version":"$april"}]},"payment_handlers":{}}}""",
            leaf(april, capabilityVersion = current), leaf(april, serviceVersion = current), leaf(current),
        )) {
            val fake = FakeTransport {
                response(it.url, if (it.url == rootUrl) leaf(current, capabilityVersion = april,
                    links = """{"$april":"$aprilUrl"}""") else broken)
            }
            withTransport(fake) {
                assertTrue(UcpDiscovery.resolve("shop.example", search).isFailure)
                assertEquals(2, fake.requests.size)
            }
        }
    }

    @Test
    fun serviceArraySkipsUnsupportedBindingsAndKeepsMcpShim() {
        val profile = obj(leaf().replace(
            """"services":{"dev.ucp.shopping":[""",
            """"services":{"dev.ucp.shopping":[{"version":"$current","transport":"embedded"},
                {"version":"$april","transport":"mcp","endpoint":"https://old.example/mcp"},""",
        ))
        assertEquals("rest", UcpDiscovery.parseService(profile, search, current).transport)
        val both = obj(leaf().replace(
            """"transport":"rest","endpoint":"https://gateway.example/custom"}""",
            """"transport":"rest","endpoint":"https://gateway.example/custom"},
                {"version":"$current","transport":"mcp","endpoint":"https://gateway.example/mcp"}""",
        ))
        assertEquals("https://gateway.example/mcp", UcpDiscovery.parseEndpoint(both, search))
        val fake = FakeTransport { response(it.url, both.toString()) }
        withTransport(fake) {
            assertEquals("https://gateway.example/mcp", UcpDiscovery.endpoint("shop.example", search).getOrThrow())
        }
    }

    @Test
    fun followsOnlyBoundedValidatedPublicHttpsRedirects() {
        val fake = FakeTransport {
            if (it.url == rootUrl) response(it.url, "", 302, mapOf("location" to listOf("/profiles/current")))
            else response(it.url, leaf())
        }
        withTransport(fake) {
            assertTrue(UcpDiscovery.resolve("shop.example", search).isSuccess)
            assertEquals(listOf(rootUrl, "https://shop.example/profiles/current"), fake.requests.map { it.url })
            assertTrue(fake.requests.all { !it.followRedirects })
        }
        for (target in listOf("http://public.example/profile", "https://127.0.0.1/profile",
            "https://private.local/profile", "https://user:password@public.example/profile", "https://public.example:8443/profile")) {
            val unsafe = FakeTransport { response(it.url, "", 307, mapOf("Location" to listOf(target))) }
            withTransport(unsafe) {
                assertTrue(UcpDiscovery.resolve("shop.example", search).isFailure)
                assertEquals(1, unsafe.requests.size)
            }
        }
    }

    @Test
    fun rejectsRedirectLoopsAndExcessiveHops() {
        val loop = FakeTransport { response(it.url, "", 301, mapOf("Location" to listOf(rootUrl))) }
        withTransport(loop) {
            assertTrue(UcpDiscovery.resolve("shop.example", search).isFailure)
            assertEquals(1, loop.requests.size)
        }
        var hop = 0
        val endless = FakeTransport { response(it.url, "", 308, mapOf("Location" to listOf("/hop/${++hop}"))) }
        withTransport(endless) {
            assertTrue(UcpDiscovery.resolve("shop.example", search).isFailure)
            assertEquals(4, endless.requests.size)
        }
    }

    @Test
    fun checksBytesBeforeParsingAndRejectsInvalidUtf8OrHttpErrors() {
        val valid = leaf()
        val exact = valid + " ".repeat(UcpWire.MAX_BODY_BYTES - valid.toByteArray().size)
        val boundary = FakeTransport { response(it.url, exact) }
        withTransport(boundary) { assertTrue(UcpDiscovery.resolve("shop.example", search).isSuccess) }
        val oversized = FakeTransport { response(it.url, exact + " ") }
        withTransport(oversized) {
            assertIs<UcpError.Malformed>((UcpDiscovery.resolve("shop.example", search).exceptionOrNull() as UcpHttpException).error)
        }
        val badUtf8 = FakeTransport { RawResponse(200, "", byteArrayOf(0xC3.toByte(), 0x28), emptyMap(), it.url) }
        withTransport(badUtf8) { assertTrue(UcpDiscovery.resolve("shop.example", search).isFailure) }
        val busy = FakeTransport { response(it.url, "", 429) }
        withTransport(busy) {
            assertIs<UcpError.RateLimited>((UcpDiscovery.resolve("shop.example", search).exceptionOrNull() as UcpHttpException).error)
        }
    }

    @Test
    fun rejectsUnsafeProfileLinksAndInvalidDomainsBeforeFollowingThem() {
        val fake = FakeTransport { response(it.url, leaf("2099-01-01", links = """{"$current":"https://localhost.local/profile"}""")) }
        withTransport(fake) {
            assertTrue(UcpDiscovery.resolve("shop.example", search).isFailure)
            assertEquals(1, fake.requests.size)
            assertTrue(UcpDiscovery.resolve("127.0.0.1", search).isFailure)
            assertEquals(1, fake.requests.size)
        }
    }

    @Test
    fun callerCancellationIsNotConvertedToMerchantFailure() {
        val fake = FakeTransport { throw CancellationException("cancelled") }
        withTransport(fake) {
            assertFailsWith<CancellationException> { UcpDiscovery.resolve("shop.example", search) }
        }
    }
}
