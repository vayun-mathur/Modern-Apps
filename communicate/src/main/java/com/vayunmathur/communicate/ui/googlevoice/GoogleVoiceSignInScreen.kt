package com.vayunmathur.communicate.ui.googlevoice

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import com.vayunmathur.communicate.R
import com.vayunmathur.communicate.data.googlevoice.GoogleVoiceSession
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.util.AppMessages
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * WebView sign-in for Google Voice.
 *
 * Google Voice has no public API, so the only way to obtain callable credentials is
 * to sign in through the real web app and capture:
 *  - the session cookies (via [CookieManager]), and
 *  - the web `key=` API key, sniffed off the first `voiceclient` RPC URL the page fires
 *    (via [WebViewClient.shouldInterceptRequest]).
 *
 * Once both are present (and the cookie carries a SAPISID for SAPISIDHASH auth) we persist
 * them through [GoogleVoiceSession] and pop back. See `voice-documentation.md`.
 */
@Composable
fun GoogleVoiceSignInScreen(onBack: () -> Unit, onSignedIn: () -> Unit) {
    val context = LocalContext.current
    val signedInMessage = stringResource(R.string.gv_signed_in)
    val session = remember { GoogleVoiceSession.get(context) }
    val scope = rememberCoroutineScope()
    // shouldInterceptRequest runs on a background thread and can fire many times; latch
    // so we only persist + navigate once.
    val captured = remember { AtomicBoolean(false) }

    fun tryCapture(apiKey: String, authUser: String) {
        if (captured.get()) return
        val cookies = CookieManager.getInstance().getCookie("https://voice.google.com") ?: return
        if (GoogleVoiceSession.extractSapisid(cookies) == null) return
        if (!captured.compareAndSet(false, true)) return
        scope.launch {
            session.save(cookieHeader = cookies, apiKey = apiKey, authUser = authUser)
            AppMessages.show(signedInMessage)
            onSignedIn()
        }
    }

    AppScaffold(
        title = stringResource(R.string.gv_sign_in_title),
        onNavigateBack = onBack,
    ) { padding ->
        AndroidView(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            factory = { ctx ->
                CookieManager.getInstance().setAcceptCookie(true)
                @SuppressLint("SetJavaScriptEnabled")
                WebView(ctx).apply {
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    // Voice serves its mobile web app to a Chrome UA; match the RPC UA.
                    settings.userAgentString =
                        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/126.0.0.0 Mobile Safari/537.36"
                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?,
                        ): WebResourceResponse? {
                            val url = request?.url?.toString()
                            if (url != null && url.contains("/voice/v1/voiceclient/")) {
                                val key = request.url.getQueryParameter("key")
                                if (!key.isNullOrBlank()) {
                                    // NOTE: WebView methods (e.g. view.url) must only be called on the
                                    // main thread; shouldInterceptRequest runs on a background thread,
                                    // so read the account index from the request headers instead.
                                    val authUser = request.requestHeaders
                                        ?.entries
                                        ?.firstOrNull { it.key.equals("X-Goog-AuthUser", ignoreCase = true) }
                                        ?.value
                                        ?.takeIf { it.isNotBlank() }
                                        ?: authUserFromUrl(url)
                                        ?: "0"
                                    tryCapture(key, authUser)
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            CookieManager.getInstance().flush()
                        }

                        override fun onRenderProcessGone(
                            view: WebView?,
                            detail: android.webkit.RenderProcessGoneDetail?,
                        ): Boolean {
                            // The renderer died (crash/OOM): the sign-in page is
                            // gone with it, so consume the event instead of crashing.
                            view?.destroy()
                            return true
                        }
                    }
                    loadUrl("https://voice.google.com/u/0/messages")
                }
            },
        )
    }

    // Persist cookies to disk when leaving so a partial session isn't lost.
    LaunchedEffect(Unit) { CookieManager.getInstance().setAcceptCookie(true) }
}

/** Extract the account index from a `/u/<n>/` web app URL. */
private fun authUserFromUrl(url: String?): String? {
    if (url == null) return null
    val match = Regex("/u/(\\d+)/").find(url) ?: return null
    return match.groupValues.getOrNull(1)
}
