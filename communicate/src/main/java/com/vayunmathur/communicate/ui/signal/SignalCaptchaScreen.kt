package com.vayunmathur.communicate.ui.signal

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.vayunmathur.communicate.data.signal.registration.RegistrationHttpClient

/**
 * WebView hCaptcha challenge for Signal registration.
 *
 * Mirrors Signal-Android's `CaptchaScreen`/`CaptchaFragment`: it loads
 * [RegistrationHttpClient.CAPTCHA_URL] (`signalcaptchas.org/registration/generate.html`), which completes
 * hCaptcha and redirects to `signalcaptcha://signal-hcaptcha.{sitekey}.registration.{token}`. We intercept that
 * redirect in [WebViewClient.shouldOverrideUrlLoading], strip the `signalcaptcha://` scheme, and hand the
 * remaining value to [onToken] — which is exactly what the server expects as the `captcha` field
 * (see [RegistrationHttpClient.submitCaptcha]).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SignalCaptchaWebView(
    onToken: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    settings.javaScriptEnabled = true
                    clearCache(true)
                    webViewClient = object : WebViewClient() {
                        override fun onRenderProcessGone(
                            view: WebView,
                            detail: android.webkit.RenderProcessGoneDetail?,
                        ): Boolean {
                            // Renderer died (crash/OOM): consume the event; the
                            // captcha page can be re-created by recomposition.
                            view.destroy()
                            return true
                        }

                        @Deprecated("Deprecated in Java")
                        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                            if (url.startsWith(RegistrationHttpClient.SIGNAL_CAPTCHA_SCHEME)) {
                                onToken(url.substring(RegistrationHttpClient.SIGNAL_CAPTCHA_SCHEME.length))
                                return true
                            }
                            return false
                        }
                    }
                    loadUrl(RegistrationHttpClient.CAPTCHA_URL)
                }
            },
        )
    }
}
