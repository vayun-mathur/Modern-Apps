package com.vayunmathur.web.platform.shields

import android.content.Context
import com.vayunmathur.library.log.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import androidx.webkit.ServiceWorkerClientCompat
import androidx.webkit.ServiceWorkerControllerCompat
import androidx.webkit.WebViewFeature
import com.vayunmathur.web.domain.EffectiveShields
import com.vayunmathur.web.domain.ShieldsSettings
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "ShieldsServiceWorker"

/**
 * Applies [ShieldsRequestFilter] to service-worker fetches, which never reach a
 * `WebViewClient` and were therefore unfiltered — a page could dodge the ad-blocker, and the
 * cleartext gate, simply by registering a worker.
 *
 * The controller is process-wide and outlives every screen, so this must never capture an
 * Activity or a ViewModel.
 */
class ShieldsServiceWorkerClient(
    private val appContext: Context,
) : ServiceWorkerClientCompat() {

    override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? =
        ShieldsRequestFilter.intercept(
            context = appContext,
            request = request,
            // A process-wide client has no page context. Passing the request as its own source
            // degrades first/third-party detection, but passing "" would make the engine treat
            // every worker fetch as third-party. The cleartext gate ignores source either way.
            pageUrl = request.url.toString(),
            // No panel can reach a worker, so it gets the preset the browser ships with.
            shieldsFor = { EffectiveShields.resolve(ShieldsSettings.AGGRESSIVE_DEFAULTS) },
            // The block counter is per-page and there is no page to attribute to.
            onBlocked = { _, _ -> },
        )

    /** Shared constants and helpers for ShieldsServiceWorkerClient. */
    companion object {
        private val registered = AtomicBoolean(false)

        /**
         * Installs the client, once per process.
         *
         * Called from both `MainActivity` and `PwaActivity`: a pinned PWA shortcut can be the
         * process entry point without `MainActivity` ever running.
         */
        fun registerOnce(context: Context) {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_BASIC_USAGE)) return
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.SERVICE_WORKER_SHOULD_INTERCEPT_REQUEST)) return
            if (!registered.compareAndSet(false, true)) return
            val app = context.applicationContext
            // getInstance() can throw if the WebView package is being updated underneath us.
            runCatching {
                ServiceWorkerControllerCompat.getInstance()
                    .setServiceWorkerClient(ShieldsServiceWorkerClient(app))
            }.onFailure {
                registered.set(false)
                Log.status(TAG, "service worker client unavailable", it)
            }
        }
    }
}
