package com.vayunmathur.shop.network

import com.vayunmathur.shop.data.UcpError
import com.vayunmathur.shop.domain.MerchantUrls
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI

/** Selects one self-contained dated profile, never combining its registries with another leaf. */
internal object UcpDiscovery {
    private const val MAX_REDIRECTS = 3
    private val versions = listOf(UcpApi.VERSION, UcpApi.APRIL_VERSION)

    suspend fun resolve(
        shopDomain: String, capability: String, preferredVersion: String? = null,
    ): Result<UcpService> = resolve(shopDomain, capability, preferredVersion, setOf("mcp", "rest"))

    private suspend fun resolve(
        shopDomain: String, capability: String, preferredVersion: String?, transports: Set<String>,
    ): Result<UcpService> = UcpApi.ioCall {
        UcpApi.catching {
            if (preferredVersion != null && preferredVersion !in versions) {
                unsupported("This merchant uses an unsupported UCP version.")
            }
            val shop = MerchantUrls.domain(shopDomain) ?: unsupported("Invalid merchant domain.")
            val transport = UcpApi.transport
            withTimeout(UcpWire.CALL_TIMEOUT_MS) {
                val root = fetch("https://$shop/.well-known/ucp", transport)
                val ucp = root["ucp"].asObject() ?: malformed("Missing UCP profile")
                val alternatives = ucp["supported_versions"].asObject()
                var failure: UcpHttpException? = null
                for (version in preferredVersion?.let(::listOf) ?: versions) {
                    try {
                        val leaf = if (string(ucp, "version") == version) root else {
                            val link = alternatives?.let { string(it, version) } ?: continue
                            fetch(publicUrl(link), transport)
                        }
                        return@withTimeout parseService(leaf, capability, version, transports)
                    } catch (error: UcpHttpException) {
                        failure = error
                    }
                }
                throw failure ?: UcpHttpException(UcpError.Unsupported("This merchant does not offer a supported dated shopping service."))
            }
        }
    }

    /** Compatibility path for checkout callers still using current-version MCP only. */
    suspend fun endpoint(shopDomain: String, capability: String): Result<String> =
        resolve(shopDomain, capability, UcpApi.VERSION, setOf("mcp")).map { it.endpoint }

    private suspend fun fetch(initialUrl: String, transport: UcpTransport): JsonObject {
        var url = publicUrl(initialUrl)
        val visited = mutableSetOf<String>()
        repeat(MAX_REDIRECTS + 1) { hop ->
            if (!visited.add(url)) malformed("Merchant profile redirect loop")
            val response = transport.execute(UcpRequest(
                url = url, headers = mapOf("Accept" to "application/json"), followRedirects = false,
            ))
            if (response.bytes.size > UcpWire.MAX_BODY_BYTES) malformed("Merchant profile exceeds size limit")
            if (response.status in setOf(301, 302, 303, 307, 308)) {
                if (hop == MAX_REDIRECTS) malformed("Too many merchant profile redirects")
                val location = response.headers.entries.firstOrNull { it.key.equals("Location", true) }
                    ?.value?.singleOrNull() ?: malformed("Missing merchant profile redirect target")
                url = publicUrl(runCatching { URI(url).resolve(location).toString() }.getOrNull()
                    ?: malformed("Invalid merchant profile redirect target"))
            } else {
                UcpWire.checkStatus(response.status)
                return runCatching { UcpApi.json.parseToJsonElement(UcpWire.utf8(response.bytes)) as? JsonObject }
                    .getOrNull() ?: malformed("Invalid merchant profile")
            }
        }
        malformed("Too many merchant profile redirects")
    }

    internal fun parseService(
        profile: JsonObject, capability: String, expectedVersion: String,
    ): UcpService = parseService(profile, capability, expectedVersion, setOf("mcp", "rest"))

    private fun parseService(
        profile: JsonObject, capability: String, expectedVersion: String, transports: Set<String>,
    ): UcpService {
        if (expectedVersion !in versions) unsupported("This merchant uses an unsupported UCP version.")
        val ucp = profile["ucp"].asObject() ?: malformed("Missing UCP profile")
        if (string(ucp, "version") != expectedVersion) {
            unsupported("This merchant uses an unsupported UCP version.")
        }
        val capabilities = exactRegistry(ucp, "capabilities", expectedVersion)
        if (capability !in capabilities) unsupported("This merchant does not advertise $capability.")
        val services = ucp["services"].asObject() ?: malformed("Missing service registry")
        val candidates = services["dev.ucp.shopping"].asArray()?.mapNotNull { it.asObject() }.orEmpty()
        var invalidEndpoint = false
        for (service in candidates) {
            val transport = string(service, "transport") ?: continue
            if (string(service, "version") != expectedVersion || transport !in transports) continue
            val endpoint = string(service, "endpoint")?.let { runCatching { publicUrl(it) }.getOrNull() }
            if (endpoint == null || (transport == "rest" && URI(endpoint).query != null)) {
                invalidEndpoint = true
                continue
            }
            return UcpService(
                expectedVersion, transport, endpoint, capabilities,
                paymentHandlerIds(ucp, expectedVersion),
            )
        }
        if (invalidEndpoint) malformed("Invalid advertised endpoint")
        unsupported("This merchant does not offer a supported shopping service.")
    }

    private fun exactRegistry(ucp: JsonObject, name: String, version: String): Set<String> {
        val registry = ucp[name].asObject() ?: malformed("Missing $name registry")
        return registry.entries.filter { (_, entries) ->
            entries.asArray()?.any { it.asObject()?.let { entry -> string(entry, "version") == version } == true } == true
        }.map { it.key }.toSet()
    }

    private fun paymentHandlerIds(ucp: JsonObject, version: String): Set<String> {
        val registry = ucp["payment_handlers"].asObject() ?: malformed("Missing payment_handlers registry")
        return registry.values.flatMap { entries ->
            entries.asArray()?.mapNotNull { entry ->
                val handler = entry.asObject() ?: return@mapNotNull null
                if (string(handler, "version") != version) return@mapNotNull null
                string(handler, "id")?.takeIf { it.isNotBlank() }
            }.orEmpty()
        }.toSet()
    }

    internal fun parseEndpoint(profile: JsonObject, capability: String): String =
        parseService(profile, capability, UcpApi.VERSION, setOf("mcp")).endpoint

    private fun string(o: JsonObject, key: String): String? =
        (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun publicUrl(value: String): String {
        val url = MerchantUrls.httpsUrl(value) ?: malformed("Invalid public HTTPS URL")
        if (URI(url).fragment != null) malformed("Invalid public HTTPS URL")
        return url
    }

    private fun malformed(reason: String): Nothing = throw UcpHttpException(UcpError.Malformed(reason))
    private fun unsupported(reason: String): Nothing = throw UcpHttpException(UcpError.Unsupported(reason))
}
