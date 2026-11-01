package com.vayunmathur.shop.domain

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** Classifies an incoming merchant web link. */
sealed interface MerchantLink {
    val host: String

    data class Product(override val host: String, val handle: String) : MerchantLink
    data class Search(override val host: String, val query: String) : MerchantLink
    data class Merchant(override val host: String) : MerchantLink
}

object MerchantLinks {
    private val handlePattern = Regex("^[a-z0-9][a-z0-9-]{0,199}$")
    private val webSchemes = setOf("https", "http")
    private val webPorts = setOf(-1, 80, 443)
    private const val MAX_QUERY = 200

    fun parse(url: String): MerchantLink? {
        val uri = runCatching { URI(url.trim()) }.getOrNull()?.takeIf(::isPlainWebLink) ?: return null
        val host = MerchantUrls.domain(uri.host.orEmpty()) ?: return null
        val segments = uri.rawPath.orEmpty().split('/').filter { it.isNotEmpty() }.map(::decode)
        val query = productHandle(segments)?.let { return MerchantLink.Product(host, it) }
            ?: query(uri.rawQuery)
            ?: collection(segments)
        return if (query != null) MerchantLink.Search(host, query) else MerchantLink.Merchant(host)
    }

    private fun isPlainWebLink(uri: URI): Boolean =
        uri.scheme?.lowercase(Locale.US) in webSchemes && uri.userInfo == null && uri.port in webPorts

    private fun productHandle(segments: List<String>): String? {
        val index = segments.indexOf("products")
        if (index < 0) return null
        return segments.getOrNull(index + 1)?.lowercase(Locale.US)?.takeIf(handlePattern::matches)
    }

    private fun collection(segments: List<String>): String? =
        segments.takeIf { it.firstOrNull() == "collections" }?.getOrNull(1)?.takeIf { it != "all" }?.let(::words)

    private fun query(rawQuery: String?): String? = rawQuery.orEmpty().split('&')
        .map { it.substringBefore('=') to decode(it.substringAfter('=', "")) }
        .firstOrNull { (key, value) -> key in setOf("q", "query") && value.isNotBlank() }
        ?.second?.trim()?.take(MAX_QUERY)

    private fun words(handle: String): String = handle.replace('-', ' ').trim()

    private fun decode(value: String): String = runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
}
