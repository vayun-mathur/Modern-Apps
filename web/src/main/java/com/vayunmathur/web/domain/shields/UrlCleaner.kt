package com.vayunmathur.web.domain.shields

import com.vayunmathur.web.domain.LocalNetwork

/**
 * Brave's query-parameter stripping and HTTPS upgrading for top-level navigations.
 *
 * The engine's own `$removeparam` rules cover most tracking parameters, but they only
 * apply to requests it inspects. Brave additionally strips a fixed list on every
 * navigation, which is what this reproduces — it catches links pasted into the omnibox
 * and cross-site redirects that never reach a subresource check.
 *
 * Pure Kotlin (no `android.net.Uri`) so it runs in JVM unit tests.
 */
object UrlCleaner {

    /**
     * Exact parameter names Brave removes. Kept in sync with `brave-core`'s
     * `query_filter.cc` tracking-parameter list.
     */
    private val TRACKING_PARAMS = setOf(
        "fbclid", "gclid", "gclsrc", "dclid", "msclkid", "mc_eid", "mkt_tok",
        "igshid", "igsh", "twclid", "yclid", "ttclid", "rb_clickid", "s_cid",
        "wickedid", "oly_anon_id", "oly_enc_id", "vero_conv", "vero_id",
        "_hsenc", "_hsmi", "__hssc", "__hstc", "hsCtaTracking", "__s",
        "_openstat", "wt_zmc", "guccounter", "guce_referrer", "guce_referrer_sig",
        "cmpid", "os_ehash", "pk_campaign", "pk_kwd", "piwik_campaign", "piwik_kwd",
        "spm", "scm", "share_source", "share_medium", "share_plat", "share_tag",
        "share_session_id", "share_from", "unique_k", "vd_source",
    )

    /** Prefixes covering whole families: `utm_*`, `ga_*`, `hsa_*`, `matomo_*`. */
    private val TRACKING_PREFIXES = listOf("utm_", "ga_", "hsa_", "matomo_")

    private fun isTracking(name: String): Boolean {
        val lower = name.lowercase()
        return lower in TRACKING_PARAMS || TRACKING_PREFIXES.any { lower.startsWith(it) }
    }

    /**
     * Removes tracking parameters from [url], preserving parameter order, repeated keys
     * and the fragment. Returns [url] unchanged when there is nothing to strip, so callers
     * can compare by identity to decide whether a reload is warranted.
     */
    fun clean(url: String): String {
        val fragmentStart = url.indexOf('#')
        val queryStart = url.indexOf('?')
        // A '?' at or after the '#' is fragment content (hash-routed app state), not a
        // query: stripping it would corrupt the page's own state on every navigation.
        if (queryStart < 0 || (fragmentStart >= 0 && queryStart > fragmentStart)) return url

        val query = if (fragmentStart < 0) {
            url.substring(queryStart + 1)
        } else {
            url.substring(queryStart + 1, fragmentStart)
        }
        if (query.isEmpty()) return url

        val kept = query.split('&').filter { param ->
            param.isNotEmpty() && !isTracking(param.substringBefore('='))
        }
        if (kept.size == query.split('&').count { it.isNotEmpty() }) return url

        val prefix = url.substring(0, queryStart)
        val fragment = if (fragmentStart < 0) "" else url.substring(fragmentStart)
        return if (kept.isEmpty()) prefix + fragment else "$prefix?${kept.joinToString("&")}$fragment"
    }

    /**
     * The `https://` form of an `http://` [url], or null when it is already secure or is
     * an address HTTPS cannot serve.
     *
     * Bare IPs and LAN hosts are left alone: they have no publicly trusted certificate and
     * upgrading them breaks local development servers, which is also Brave's behaviour. Both
     * clauses are needed — a public bare IP like `1.2.3.4` is not LAN but still has no valid
     * cert, and `nas.local` is LAN but not an IP.
     */
    fun httpsUpgrade(url: String): String? {
        if (!url.startsWith("http://", ignoreCase = true)) return null
        val host = LocalNetwork.hostOf(url)
        if (host.isEmpty()) return null
        if (LocalNetwork.isIpLiteral(host)) return null
        if (LocalNetwork.isLanHostSyntactic(host)) return null
        return "https://" + url.substring("http://".length)
    }
}
