package com.vayunmathur.messages.meta

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * In-app WebView login for Meta platforms (Messenger + Instagram), replacing the old
 * cookie-paste flow. Loads the real Instagram/Facebook login page; once the user authenticates,
 * the resulting cookie jar is harvested from [CookieManager] and fed into the existing
 * [MetaClient.saveAuthData] / [InstagramClient.saveAuthData] bootstrap (which persists + connects).
 *
 * Success is detected when the full set of required auth cookies is present
 * (see [MetaAuthData.isValid]) — for Messenger that means `c_user`/`xs`/`datr` (xs/c_user are only
 * set after a successful login), for Instagram `sessionid`/`csrftoken`/`ds_user_id`/`mid`/`ig_did`.
 *
 * All login logic lives here in meta/; the host screen (ui/setup) only needs to render this
 * composable and pop the back stack from [onSuccess].
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MetaWebViewLogin(
    platform: MetaAuthData.Platform,
    modifier: Modifier = Modifier,
    onSuccess: () -> Unit,
) {
    val loginUrl = when (platform) {
        MetaAuthData.Platform.MESSENGER -> "https://www.messenger.com/login/"
        MetaAuthData.Platform.INSTAGRAM -> "https://www.instagram.com/accounts/login/"
    }
    // Cookie jar to read from once authenticated (matches the domain that sets the auth cookies).
    val cookieUrl = when (platform) {
        MetaAuthData.Platform.MESSENGER -> MetaProtocol.MESSENGER_BASE_URL
        MetaAuthData.Platform.INSTAGRAM -> MetaProtocol.INSTAGRAM_BASE_URL
    }

    // Guard so we only harvest + hand off once, even across multiple page callbacks.
    var completed by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }

    fun tryHarvest() {
        if (completed) return
        val cm = CookieManager.getInstance()
        cm.flush()
        val cookies = MetaAuthData.parseCookieHeader(cm.getCookie(cookieUrl))
        if (cookies.isEmpty()) return
        val userId = when (platform) {
            MetaAuthData.Platform.MESSENGER -> cookies["c_user"]
            MetaAuthData.Platform.INSTAGRAM -> cookies["ds_user_id"]
        } ?: return
        val candidate = MetaAuthData(platform = platform, userId = userId, cookies = cookies)
        if (!candidate.isValid()) return

        completed = true
        when (platform) {
            MetaAuthData.Platform.MESSENGER -> MetaClient.saveAuthData(cookies)
            MetaAuthData.Platform.INSTAGRAM -> InstagramClient.saveAuthData(cookies)
        }
        onSuccess()
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                val cm = CookieManager.getInstance()
                cm.setAcceptCookie(true)
                WebView(context).apply {
                    cm.setAcceptThirdPartyCookies(this, true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.userAgentString = MetaProtocol.USER_AGENT
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            loading = true
                            // Auth cookies can land on a redirect before the page finishes.
                            tryHarvest()
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            loading = false
                            tryHarvest()
                        }

                        override fun doUpdateVisitedHistory(
                            view: WebView?,
                            url: String?,
                            isReload: Boolean,
                        ) {
                            tryHarvest()
                        }
                    }
                    loadUrl(loginUrl)
                }
            },
            onRelease = { webView ->
                webView.stopLoading()
                webView.destroy()
            },
        )
        if (loading) {
            LinearProgressIndicator(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(),
            )
        }
    }
}
