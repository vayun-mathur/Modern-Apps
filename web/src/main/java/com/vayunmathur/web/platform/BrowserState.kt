package com.vayunmathur.web.platform

import android.net.Uri
import com.vayunmathur.web.domain.LocalNetwork
import kotlinx.serialization.Serializable

@Serializable
data class BrowserTab(
    val id: String,
    val url: String = "",
    val title: String = "",
    val faviconUrl: String? = null,
    val isPrivate: Boolean = false,
)

val BrowserTab.isNewTab: Boolean
    get() = url.isBlank() || url == "about:blank"

enum class SearchEngine(
    val displayName: String,
    val searchUrl: String,
    val homepage: String,
    /**
     * The query parameter that forces this engine's strictest result filter, or null where the
     * engine has no documented one.
     *
     * Null is not "no filtering needed" - it means this engine cannot be trusted to filter, and
     * [safeSearchUrl] substitutes an engine that can rather than silently returning unfiltered
     * results. Getting that wrong would be the worst kind of failure here: a parental control
     * that reports itself on and does nothing.
     */
    val safeSearchParam: String? = null,
) {
    GOOGLE("Google", "https://www.google.com/search?q=%s", "https://www.google.com", "safe=active"),
    DUCKDUCKGO("DuckDuckGo", "https://duckduckgo.com/?q=%s", "https://duckduckgo.com", "kp=1"),
    BING("Bing", "https://www.bing.com/search?q=%s", "https://www.bing.com", "adlt=strict"),
    BRAVE("Brave", "https://search.brave.com/search?q=%s", "https://search.brave.com", "safesearch=strict"),
    // Startpage's family filter is a stored preference rather than a documented query parameter,
    // so it is left null instead of guessing at one.
    STARTPAGE("Startpage", "https://www.startpage.com/do/search?q=%s", "https://www.startpage.com"),
    ECOSIA("Ecosia", "https://www.ecosia.org/search?q=%s", "https://www.ecosia.org", "safesearch=1"),
    QWANT("Qwant", "https://www.qwant.com/?q=%s", "https://www.qwant.com", "safesearch=2"),
    /**
     * First-party search: queries go to our own API and render in a native
     * Compose results page instead of loading engine HTML in a WebView.
     * [searchUrl] is only a template for [buildQueryUrl]; navigation is
     * intercepted in [navigateActiveTab] before any WebView load.
     */
    SELF_HOSTED("Maproj Search", "https://api.maproj.org/api/search?q=%s", "https://api.maproj.org/api/search", "safe_search=on");

    fun buildQueryUrl(query: String): String =
        searchUrl.replace("%s", Uri.encode(query))

    /**
     * The query URL with this engine's strict filter applied.
     *
     * Falls back to [SAFE_FALLBACK] when this engine has no [safeSearchParam], because an engine
     * that cannot filter must not be used while filtering is required.
     */
    fun safeSearchUrl(query: String): String {
        val param = safeSearchParam ?: return SAFE_FALLBACK.safeSearchUrl(query)
        val base = buildQueryUrl(query)
        val separator = if (base.contains('?')) '&' else '?'
        return "$base$separator$param"
    }

    companion object {
        val DEFAULT = DUCKDUCKGO

        /** Used when the selected engine has no strict-filter parameter of its own. */
        val SAFE_FALLBACK = DUCKDUCKGO

        /**
         * Engines offered in the Settings picker. [SELF_HOSTED] is dev-only
         * (its backend text scraping is datacenter-blocked and the UI is
         * unfinished): release builds never offer it. R8 folds
         * [com.vayunmathur.web.BuildConfig.DEV_BUILD], so the reference is
         * stripped from release.
         */
        val PICKABLE: List<SearchEngine> =
            if (com.vayunmathur.web.BuildConfig.DEV_BUILD) entries else entries - SELF_HOSTED

        fun fromName(name: String): SearchEngine =
            entries.find { it.name == name } ?: DEFAULT

        /**
         * The engine to actually use: [SELF_HOSTED] outside a dev build falls
         * back to [DEFAULT] (e.g. a dev-build pref restored on release).
         */
        fun effective(engine: SearchEngine): SearchEngine =
            if (engine == SELF_HOSTED && !com.vayunmathur.web.BuildConfig.DEV_BUILD) DEFAULT
            else engine
    }
}

enum class CacheMode(val title: String, val description: String, val webSettingsValue: Int) {
    DEFAULT("Default", "Use HTTP cache as needed", android.webkit.WebSettings.LOAD_DEFAULT),
    CACHE_ELSE_NETWORK("Cache first", "Prefer cache, fastest", android.webkit.WebSettings.LOAD_CACHE_ELSE_NETWORK),
    NO_CACHE("No cache", "Always from network", android.webkit.WebSettings.LOAD_NO_CACHE),
    CACHE_ONLY("Offline", "Cache only, offline mode", android.webkit.WebSettings.LOAD_CACHE_ONLY);
}

enum class SitePermissionType(val key: String, val displayName: String) {
    CAMERA("camera", "Camera"),
    MICROPHONE("mic", "Microphone"),
    LOCATION("location", "Location"),
    NOTIFICATIONS("notifications", "Notifications")
}

object BrowserUtils {
    private const val MAX_PRETTY_URL_LENGTH = 48
    // Deliberately requires a dot (or localhost, or a dotted quad): loosening this to accept
    // dotless hosts so `router` navigates would turn every one-word search - `weather`,
    // `kotlin` - into a failed navigation. `nas.local` and `192.168.1.1:8080` already match,
    // and `http://router` works through the explicit-scheme path below.
    private val URL_LIKE = Regex(
        // (?i): schemes are case-insensitive per RFC 3986, and pasted links, intents and
        // redirects can carry `HTTP://`. Host labels were already A–Z-tolerant; this also
        // lets `LOCALHOST` match, which DNS treats the same as `localhost`.
        "^(?i)(https?://)?([a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}|localhost|\\d{1,3}(\\.\\d{1,3}){3})(:\\d+)?(/.*)?$"
    )

    /**
     * Case-insensitive `http(s)` scheme test for [Uri.getScheme] values: `Uri` preserves the
     * sender's casing, so `HTTP://` links must not fall through to the external-app path.
     */
    fun isWebScheme(scheme: String?): Boolean =
        scheme.equals("http", ignoreCase = true) || scheme.equals("https", ignoreCase = true)

    /** Case-insensitive `http(s)://` prefix test for raw URL strings. */
    fun isWebUrl(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    /** Kept for migration only â€” new tabs use "" and search uses selected engine. */
    const val HOMEPAGE = "https://duckduckgo.com"

    /** Full address for display â€” no truncation. Keep prettyUrl() for subtitles. */
    fun displayFullUrl(url: String): String = url

    fun looksLikeUrl(text: String): Boolean {
        if (text.contains(" ")) return false
        val trimmed = text.trim()
        if (isWebUrl(trimmed)) return true
        return URL_LIKE.matches(trimmed)
    }

    /** Search or navigate using the selected engine. */
    fun toNavigationUrl(input: String, searchEngine: SearchEngine): String =
        toNavigationUrl(input, searchEngine, safeSearch = false)

    /**
     * Search or navigate, optionally forcing the engine's strict result filter.
     *
     * [safeSearch] comes from `Settings.Secure.search_content_filters_enabled` via
     * [com.vayunmathur.web.platform.ContentFilters]. It only affects the search branch - a
     * typed URL is a typed URL, and the site filter is what governs those.
     */
    fun toNavigationUrl(input: String, searchEngine: SearchEngine, safeSearch: Boolean): String {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return searchEngine.homepage
        if (isWebUrl(trimmed)) return trimmed
        if (looksLikeUrl(trimmed)) {
            // LAN targets are nearly all plain http and have no publicly trusted certificate,
            // so defaulting them to https would fail before the page ever loaded.
            val lan = LocalNetwork.isLanHostSyntactic(LocalNetwork.hostOf(trimmed))
            return if (lan) "http://$trimmed" else "https://$trimmed"
        }
        return if (safeSearch) {
            searchEngine.safeSearchUrl(trimmed)
        } else {
            searchEngine.buildQueryUrl(trimmed)
        }
    }

    fun toNavigationUrl(input: String): String = toNavigationUrl(input, SearchEngine.DEFAULT)

    fun hostFromUrl(url: String): String =
        runCatching { Uri.parse(url).host ?: url }.getOrDefault(url)

    fun originFromUrl(url: String): String {
        return runCatching {
            val u = Uri.parse(url)
            val scheme = u.scheme ?: "https"
            val host = u.host ?: return@runCatching url
            val port = if (u.port != -1) ":${u.port}" else ""
            "$scheme://$host$port"
        }.getOrDefault(url)
    }

    fun prettyUrl(url: String): String {
        if (url.isBlank()) return ""
        return runCatching {
            val parsed = Uri.parse(url)
            val host = parsed.host ?: return@runCatching url
            val path = parsed.path ?: ""
            val display = if (path.isEmpty() || path == "/") host else host + path
            if (display.length > MAX_PRETTY_URL_LENGTH) host else display
        }.getOrDefault(url)
    }
}
