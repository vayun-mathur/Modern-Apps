package com.vayunmathur.web.platform.shields

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.vayunmathur.library.log.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.ScriptHandler
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.vayunmathur.web.domain.EffectiveShields
import com.vayunmathur.web.domain.shields.ResourceTypes
import com.vayunmathur.web.domain.shields.UrlCleaner
import com.vayunmathur.web.platform.AdultSites
import com.vayunmathur.web.platform.ContentFilters
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayInputStream
import kotlin.random.Random

private const val TAG = "ShieldsWebViewClient"

@Serializable
private data class CosmeticQuery(
    val classes: List<String> = emptyList(),
    val ids: List<String> = emptyList(),
    val exceptions: List<String> = emptyList(),
)

/**
 * A [WebViewClient] that applies Brave Shields.
 *
 * Subclasses add the host's own navigation and title plumbing; everything shields-related
 * lives here so `WebViewBrowser` and `PwaActivity` cannot drift apart. Subclasses that
 * override [onPageStarted] or [shouldOverrideUrlLoading] **must** call through to super.
 *
 * Request filtering itself is [ShieldsRequestFilter], which service workers share; this class
 * only owns the [pageUrl] a `WebResourceRequest` cannot tell us.
 *
 * @param shieldsFor resolved shields for a host, consulted per request
 * @param onBlocked  called on the render thread each time a request is blocked
 * @param onNavigate asks the host to load a URL shields rewrote (HTTPS upgrade, param strip)
 */
open class ShieldsWebViewClient(
    private val context: Context,
    private val shieldsFor: (host: String) -> EffectiveShields,
    private val onBlocked: (pageUrl: String, blockedUrl: String) -> Unit = { _, _ -> },
    private val onNavigate: (WebView, String) -> Unit = { view, url -> view.loadUrl(url) },
) : WebViewClient() {

    /**
     * The document the current subresources belong to. `WebResourceRequest` has no
     * initiator, so first-party detection depends entirely on tracking this.
     */
    @Volatile
    private var pageUrl: String = ""

    /** Per-session salt; combined with the site's host it gives Brave's per-origin seed. */
    private val sessionSalt: Int = Random.nextInt()

    private val json = Json { ignoreUnknownKeys = true }

    /** URLs this client already rewrote, so the reload does not bounce forever. */
    private var lastRewritten: String? = null

    private var cosmeticChannelInstalled = false
    private var farbleHandle: ScriptHandler? = null
    private var installedFarbling: String? = null

    /**
     * Installs the fingerprinting defence on [view].
     *
     * Must be called before the first `loadUrl`: `addDocumentStartJavaScript` only applies
     * to documents that begin loading after registration, and farbling is worthless once
     * the page has read the real values. Re-registering after a settings change is cheap
     * — the previous script handle is removed first.
     */
    fun installFarbling(view: WebView, config: FarblingConfig) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        val script = ShieldsInjection.farbling(context, config, sessionSalt)
        if (script == installedFarbling) return
        runCatching {
            farbleHandle?.remove()
            farbleHandle = WebViewCompat.addDocumentStartJavaScript(view, script, setOf("*"))
            installedFarbling = script
        }.onFailure { Log.status(TAG, "farbling script rejected", it) }
    }

    // ---------------------------------------------------------------- network

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        if (request.isForMainFrame) {
            pageUrl = request.url.toString()
            blockedByContentFilter(request)?.let { return it }
        }
        return ShieldsRequestFilter.intercept(context, request, pageUrl, shieldsFor, onBlocked)
    }

    /**
     * The supervision site filter, checked before shields and only on top-level navigation.
     *
     * Main-frame only, deliberately. A parental filter is about which pages a child can open;
     * running the list against every subresource would multiply the cost by the number of
     * requests on a page for no gain, and half-loading a blocked page is worse than not loading
     * it. Returning a response rather than letting the load proceed means the block also covers
     * URLs typed directly, redirects, and links from other apps.
     */
    private fun blockedByContentFilter(request: WebResourceRequest): WebResourceResponse? {
        if (!ContentFilters.blockExplicitSites(context)) return null
        val host = request.url.host ?: return null
        if (!AdultSites.blocks(context, host)) return null
        onBlocked(pageUrl, request.url.toString())
        val body = context.assets.open("content_filter_blocked.html").bufferedReader().use { it.readText() }
        return WebResourceResponse(
            "text/html",
            "utf-8",
            ByteArrayInputStream(body.toByteArray()),
        )
    }

    // ------------------------------------------------------------- navigation

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        if (!request.isForMainFrame) return false
        val url = request.url.toString()
        val rewritten = rewrite(url, shieldsFor(hostOf(url)))
        if (rewritten != null && rewritten != url) {
            lastRewritten = rewritten
            onNavigate(view, rewritten)
            return true
        }
        return false
    }

    /**
     * The HTTPS upgrade and parameter strip Brave applies to top-level navigations.
     * Returns null when the URL is already clean.
     */
    private fun rewrite(url: String, shields: EffectiveShields): String? {
        if (url == lastRewritten) return null
        var result = url
        if (shields.httpsUpgrade) {
            UrlCleaner.httpsUpgrade(result)?.let { result = it }
        }
        if (shields.blockTrackers) {
            result = UrlCleaner.clean(result)
            // The bundled lists carry $removeparam rules for parameters the fixed
            // UrlCleaner list above does not know (e.g. `ref` on reddit.com). The
            // engine only reports a rewrite when the navigation is not blocked, and
            // main-frame blocking stays out of scope — a null check fails open.
            ShieldsEngine.check(result, result, ResourceTypes.of(result, isMainFrame = true, headers = emptyMap()))
                ?.takeIf { !it.blocked }
                ?.rewritten
                ?.takeIf { it != result }
                ?.let { result = it }
        }
        return if (result == url) null else result
    }

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        url ?: return
        pageUrl = url
        if (url == lastRewritten) lastRewritten = null
        applyShields(view, url)
    }

    // -------------------------------------------------------------- injection

    /**
     * Applies the cosmetic payload for the document that just started loading.
     *
     * Farbling is not done here — see [installFarbling]. Hide rules are URL-specific and
     * only cosmetic, so evaluating them at page start is soon enough.
     */
    private fun applyShields(view: WebView, url: String) {
        if (!url.startsWith("http")) return
        val shields = shieldsFor(hostOf(url))
        if (!shields.cosmeticFiltering) return

        installCosmeticChannel(view)
        val script = ShieldsInjection.cosmetic(ShieldsEngine.cosmetic(url))
        if (script.isNotEmpty()) view.evaluateJavascript(script, null)
    }

    /**
     * Wires the page's `MutationObserver` to [ShieldsEngine.hiddenClassIdSelectors] so
     * generic hide rules can be resolved for classes and ids that appear after load.
     */
    private fun installCosmeticChannel(view: WebView) {
        if (cosmeticChannelInstalled) return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        runCatching {
            WebViewCompat.addWebMessageListener(
                view,
                ShieldsInjection.COSMETIC_CHANNEL,
                setOf("*"),
                { _, message, _, isMainFrame, reply -> onCosmeticQuery(message, isMainFrame, reply) },
            )
            cosmeticChannelInstalled = true
        }.onFailure { Log.status(TAG, "cosmetic channel unavailable", it) }
    }

    private fun onCosmeticQuery(
        message: WebMessageCompat,
        isMainFrame: Boolean,
        reply: JavaScriptReplyProxy,
    ) {
        if (!isMainFrame) return
        val raw = message.data ?: return
        val query = runCatching { json.decodeFromString<CosmeticQuery>(raw) }.getOrNull() ?: return
        val selectors = ShieldsEngine.hiddenClassIdSelectors(query.classes, query.ids, query.exceptions)
        if (selectors.isEmpty()) return
        runCatching { reply.postMessage(json.encodeToString(selectors)) }
    }

    private fun hostOf(url: String): String =
        runCatching { Uri.parse(url).host.orEmpty() }.getOrDefault("")
}
