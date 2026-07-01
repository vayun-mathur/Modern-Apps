package com.vayunmathur.messages.meta

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Web auth/config bootstrap (#5). Ports the behaviour of messagix's
 * {client,configs,js_module_parser}.go: it loads the messenger.com /
 * instagram.com web page with the user's cookies and parses the embedded JS
 * bundles for the data the realtime layer needs:
 *
 *  - schema versionId (LSVersion module)
 *  - MQTT broker endpoint + appId (MqttWebConfig / CurrentUserInitialData)
 *  - ParentThreadKeys (LSThreadsRangesQuery in the preloaded LS payload)
 *  - LSD / fb_dtsg / jazoest tokens (needed for authenticated HTTP calls such
 *    as the Mercury media upload)
 *  - sync params (LSPlatformMessengerSyncParams) for the DB SyncManager
 *
 * The Go implementation walks the parsed DOM and replays the BBox module
 * graph. On Android we don't have a full HTML/JS module runtime, so this is a
 * best-effort regex/substring extraction over the same page payload. The wire
 * format (where these values live in the page) is stable enough for the values
 * we need, but the network round-trip and exact extraction are
 * runtime-dependent.
 *
 * // UNVERIFIED: extraction relies on live page structure and cannot be
 * // exercised without real messenger.com/instagram.com cookies + network.
 */
data class MetaConfig(
    val versionId: Long = 0L,
    val broker: String? = null,
    val appId: Long = 0L,
    val parentThreadKeys: List<Long> = listOf(-1L),
    val lsdToken: String = "",
    val fbDtsg: String = "",
    val jazoest: String = "",
    val syncParamsMailbox: String = "",
    val syncParamsContact: String = "",
    val syncParamsE2ee: String = "",
    val loaded: Boolean = false,
) {
    fun defaultAppId(platform: MetaAuthData.Platform): Long = when {
        appId != 0L -> appId
        platform == MetaAuthData.Platform.INSTAGRAM -> 936619743392459L
        else -> 219994525426954L
    }
}

object MetaBootstrap {
    private const val TAG = "MetaBootstrap"
    private const val MAX_JS_CRAWL = 6

    // __d("LSVersion", ... ){ e.exports="12345" }  (ref js_module_parser.go versionPattern)
    private val versionPattern =
        Regex("""__d\("LSVersion"[^)]+\)\{\w+\.exports="(\d+)"\}""")
    // MqttWebConfig endpoint, e.g. "endpoint":"wss:\/\/edge-chat.messenger.com\/chat?..."
    private val brokerPattern =
        Regex("""["']endpoint["']\s*:\s*["'](wss:\\?/\\?/[^"']+edge-chat[^"']+)["']""")
    private val appIdPattern =
        Regex("""["'](?:appID|app_id|appId)["']\s*:\s*["']?(\d{6,})["']?""")
    private val lsdPattern =
        Regex("""\["LSD",\[\],\{"token":"([^"]+)"\}""")
    private val dtsgPattern =
        Regex("""\["DTSGInitData",\[\],\{"token":"([^"]+)""")
    private val jazoestPattern = Regex("""jazoest=(\d+)""")
    private val parentThreadKeyPattern = Regex("""["']parent_thread_key["']\s*:\s*(-?\d+)""")
    // "version":123... inside the LSPlatformGraphQLLightspeedRequest preloader payload (the primary
    // schema-version source on the inbox page). Values may be JSON-in-JSON escaped ("version":...
    // or \"version\":...), so allow an optional backslash before the quotes.
    private val lightspeedVersionPattern =
        Regex("""\\?"version\\?"\s*:\s*(\d{6,})""")
    private val syncParamsPattern =
        Regex("""["']?(mailbox|contact|e2ee)["']?\s*:\s*"((?:\\.|[^"\\])*)""")

    /**
     * Fetch + parse the bootstrap config. Always returns a [MetaConfig]; on any
     * failure it returns a config with [MetaConfig.loaded] = false and the
     * callers fall back to their previous hardcoded defaults.
     */
    suspend fun load(
        authData: MetaAuthData,
        httpClient: OkHttpClient,
    ): MetaConfig = withContext(Dispatchers.IO) {
        try {
            // Bootstrap must load the MESSAGES/INBOX page (the one that embeds the Lightspeed
            // bundle + LSVersion), not the site root. Instagram's root (instagram.com/) is the
            // feed and does NOT carry the messaging LS bundle → versionId parses as 0 → every
            // FetchThreads LS request is invalid and times out (zero conversations). Mirror the Go
            // bridge, which loads the platform "messages" endpoint (IG = /direct/inbox/).
            val baseUrl = when (authData.platform) {
                MetaAuthData.Platform.MESSENGER -> MetaProtocol.MESSENGER_BASE_URL + "/"
                MetaAuthData.Platform.INSTAGRAM -> MetaProtocol.INSTAGRAM_BASE_URL + "/direct/inbox/"
            }
            val html = fetch(baseUrl, authData, httpClient) ?: return@withContext MetaConfig()

            // Primary source: the SSJS-preloaded LSPlatformGraphQLLightspeedRequest carries the
            // schema "version" inline on the inbox page (ref modules.go handleRequire). Fall back to
            // the __d("LSVersion") module (inline, then crawled JS bundles).
            var versionId = parseLightspeedVersion(html)
                ?: versionPattern.find(html)?.groupValues?.get(1)?.toLongOrNull()
                ?: 0L

            // The page sometimes preloads the JS instead of inlining LSVersion.
            // Crawl a bounded number of linked script files looking for it.
            if (versionId == 0L) {
                versionId = crawlForVersion(html, authData, httpClient)
            }

            val broker = brokerPattern.find(html)?.groupValues?.get(1)?.let { unescapeSlashes(it) }
                ?: defaultBroker(authData.platform)

            val appId = appIdPattern.find(html)?.groupValues?.get(1)?.toLongOrNull() ?: 0L

            val parentThreadKeys = parentThreadKeyPattern.findAll(html)
                .mapNotNull { it.groupValues[1].toLongOrNull() }
                .toMutableSet()
                .also { if (-1L !in it) it.add(-1L) }
                .toList()
                .ifEmpty { listOf(-1L) }

            val lsd = lsdPattern.find(html)?.groupValues?.get(1) ?: ""
            val dtsg = dtsgPattern.find(html)?.groupValues?.get(1) ?: ""
            val jazoest = jazoestPattern.find(html)?.groupValues?.get(1) ?: ""

            val sync = parseSyncParams(html)

            val config = MetaConfig(
                versionId = versionId,
                broker = broker,
                appId = appId,
                parentThreadKeys = parentThreadKeys,
                lsdToken = lsd,
                fbDtsg = dtsg,
                jazoest = jazoest,
                syncParamsMailbox = sync.first,
                syncParamsContact = sync.second,
                syncParamsE2ee = sync.third,
                loaded = versionId != 0L,
            )
            Log.i(
                TAG,
                "Bootstrap for ${authData.platform}: versionId=$versionId appId=$appId " +
                    "broker=$broker ptks=$parentThreadKeys lsd=${lsd.isNotEmpty()} loaded=${config.loaded}",
            )
            config
        } catch (e: Exception) {
            Log.e(TAG, "Bootstrap failed for ${authData.platform}", e)
            MetaConfig()
        }
    }

    private fun fetch(url: String, authData: MetaAuthData, httpClient: OkHttpClient): String? {
        val request = Request.Builder()
            .url(url)
            .header("Cookie", authData.toCookieHeader())
            .header("User-Agent", MetaProtocol.USER_AGENT)
            .header(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            )
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("sec-fetch-dest", "document")
            .header("sec-fetch-mode", "navigate")
            .header("sec-fetch-site", "none")
            .build()
        return httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "Page fetch $url returned ${resp.code}")
                return null
            }
            resp.body?.string()
        }
    }

    private fun crawlForVersion(
        html: String,
        authData: MetaAuthData,
        httpClient: OkHttpClient,
    ): Long {
        val hrefPattern = Regex("<(?:link|script)[^>]+(?:href|src)=\"(https://[^\"]+\\.js[^\"]*)\"")
        val urls = hrefPattern.findAll(html)
            .map { it.groupValues[1] }
            .distinct()
            .take(MAX_JS_CRAWL)
        for (jsUrl in urls) {
            try {
                val js = fetch(jsUrl, authData, httpClient) ?: continue
                val v = versionPattern.find(js)?.groupValues?.get(1)?.toLongOrNull()
                if (v != null) {
                    Log.i(TAG, "Found LSVersion=$v in $jsUrl")
                    return v
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to crawl $jsUrl: ${e.message}")
            }
        }
        return 0L
    }

    /**
     * Extract the Lightspeed schema version from the inbox page's SSJS-preloaded
     * LSPlatformGraphQLLightspeedRequest payload (the primary source; ref Go modules.go
     * handleRequire). Anchors on the preloader id so we don't pick up an unrelated "version".
     */
    private fun parseLightspeedVersion(html: String): Long? {
        val idx = html.indexOf("LSPlatformGraphQLLightspeedRequest")
        if (idx < 0) return null
        val region = html.substring(idx, minOf(idx + 8000, html.length))
        return lightspeedVersionPattern.find(region)?.groupValues?.get(1)?.toLongOrNull()
    }

    private fun parseSyncParams(html: String): Triple<String, String, String> {
        var mailbox = ""
        var contact = ""
        var e2ee = ""
        // Find the LSPlatformMessengerSyncParams blob if present, then read keys.
        val idx = html.indexOf("LSPlatformMessengerSyncParams")
        val region = if (idx >= 0) html.substring(idx, minOf(idx + 4000, html.length)) else ""
        if (region.isNotEmpty()) {
            for (m in syncParamsPattern.findAll(region)) {
                val key = m.groupValues[1]
                val value = unescapeJson(m.groupValues[2])
                when (key) {
                    "mailbox" -> if (mailbox.isEmpty()) mailbox = value
                    "contact" -> if (contact.isEmpty()) contact = value
                    "e2ee" -> if (e2ee.isEmpty()) e2ee = value
                }
            }
        }
        return Triple(mailbox, contact, e2ee)
    }

    private fun defaultBroker(platform: MetaAuthData.Platform): String = when (platform) {
        MetaAuthData.Platform.MESSENGER -> MetaProtocol.MESSENGER_MQTT_URL
        MetaAuthData.Platform.INSTAGRAM -> MetaProtocol.INSTAGRAM_MQTT_URL
    }

    private fun unescapeSlashes(s: String): String = s.replace("\\/", "/")

    private fun unescapeJson(s: String): String =
        s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\/", "/")
}
