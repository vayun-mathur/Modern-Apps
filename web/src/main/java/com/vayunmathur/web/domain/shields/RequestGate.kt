package com.vayunmathur.web.domain.shields

import com.vayunmathur.web.domain.EffectiveShields

/**
 * Pure decisions on the subresource request path, kept free of `android.*`
 * so the hot-path policy is unit-testable on the JVM.
 *
 * `shouldInterceptRequest` fires once per subresource (tens to hundreds
 * per page) on a WebView background thread. Everything reachable from it
 * either blocks the request's network fetch or stalls that thread, so
 * the cheap exits must come first and in this order.
 */
object RequestGate {

    /** Only web schemes can be tracker requests; `data:`/`blob:`/`file:` etc. never reach the engine. */
    fun isWebScheme(scheme: String?): Boolean = scheme == "http" || scheme == "https"

    /**
     * Whether the shields engine must be consulted for one request.
     *
     * Main-frame documents are never shield-blocked (only rewritten), a
     * site with tracker blocking off has nothing to ask, and a not-yet-
     * loaded engine answers null ("allow") anyway — asking it would pay
     * the host-resolution, resource-type and header work for a guaranteed
     * null. All three conditions are exactly the cases in which the
     * engine call cannot change the outcome.
     */
    fun shouldShieldCheck(
        scheme: String?,
        isMainFrame: Boolean,
        engineReady: Boolean,
        blockTrackers: Boolean,
    ): Boolean = isWebScheme(scheme) && !isMainFrame && engineReady && blockTrackers
}

/**
 * Single-entry memo of the resolved shields for the page being loaded.
 *
 * Resolving [EffectiveShields] allocates and reads a concurrent map;
 * doing it per subresource repeats the same answer hundreds of times
 * for one page. Keyed by page host only: a new host re-resolves, and
 * [invalidate] drops the entry when the owner knows settings changed
 * (the shields panel reloads the page after any edit, which re-enters
 * through a fresh page host or an explicit invalidate).
 */
class HostShieldsCache(
    private val resolve: (host: String) -> EffectiveShields,
) {
    private var cachedHost: String? = null
    private var cached: EffectiveShields? = null

    fun forHost(host: String): EffectiveShields {
        cached?.let { if (cachedHost == host) return it }
        val resolved = resolve(host)
        cachedHost = host
        cached = resolved
        return resolved
    }

    fun invalidate() {
        cachedHost = null
        cached = null
    }
}

/**
 * Gates the two JavaScript probes (storage footprint, PWA manifest)
 * that run when a page finishes loading.
 *
 * Each probe is a full `evaluateJavascript` round trip that walks the
 * page's storage, and `onPageFinished` also fires for reloads and
 * redirect-chain finishes of the same origin, where the answer cannot
 * have changed enough to matter. Probing once per origin change keeps
 * the first visit to each site exact and drops the repeats.
 */
class OriginProbeGate {
    private var lastProbedOrigin: String? = null

    /** True the first time [origin] is seen, and again only after a different origin was probed. */
    fun shouldProbe(origin: String): Boolean {
        if (origin.isBlank() || origin == lastProbedOrigin) return false
        lastProbedOrigin = origin
        return true
    }
}
