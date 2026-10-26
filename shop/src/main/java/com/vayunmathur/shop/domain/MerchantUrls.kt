package com.vayunmathur.shop.domain

import java.net.URI
import java.util.Locale

/** Public HTTPS URLs only; merchant input must not name local devices or app intents. */
object MerchantUrls {
    fun httpsUrl(value: String): String? {
        val uri = runCatching { URI(value) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.userInfo != null) return null
        if (uri.port != -1 && uri.port != 443) return null
        val host = uri.host?.lowercase(Locale.US) ?: return null
        if (!publicHostname(host)) return null
        return uri.toASCIIString()
    }

    fun domain(value: String): String? {
        val input = value.trim()
        val url = httpsUrl(if (input.contains("://")) input else "https://$input") ?: return null
        val uri = URI(url)
        if (uri.path !in listOf("", "/") || uri.query != null || uri.fragment != null) return null
        return uri.host.lowercase(Locale.US)
    }

    private fun publicHostname(host: String): Boolean {
        if (!host.contains('.') || host.contains(':') || host.all { it.isDigit() || it == '.' }) return false
        if (host.endsWith(".localhost") || host.endsWith(".local") || host.endsWith(".internal")) return false
        return host.split('.').all { label ->
            label.isNotEmpty() && label.length <= 63 &&
                label.first().isLetterOrDigit() && label.last().isLetterOrDigit() &&
                label.all { it in 'a'..'z' || it.isDigit() || it == '-' }
        }
    }
}
