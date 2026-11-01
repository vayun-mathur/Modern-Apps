package com.vayunmathur.shop.ui

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.appBarScrollBehavior
import com.vayunmathur.library.ui.rememberMessenger
import com.vayunmathur.shop.data.EmbeddedCheckoutRequest
import com.vayunmathur.shop.domain.EcAction
import com.vayunmathur.shop.domain.EmbeddedCheckout
import com.vayunmathur.shop.domain.MerchantUrls
import com.vayunmathur.shop.platform.ShopViewModel

private const val BRIDGE = "EmbeddedCheckoutProtocolConsumer"

/**
 * Hosts the merchant's own checkout (UCP Embedded Checkout, no payment delegation). Payment is
 * entered on the merchant page; the app only answers the handshake, opens links and records
 * completion. The bridge is injected only into the merchant's origins.
 */
@Composable
fun EmbeddedCheckoutScreen(viewModel: ShopViewModel, onClose: () -> Unit) {
    // Snapshot the request: the screen keeps composing through its exit animation after the
    // ViewModel clears it, and navigation must never be triggered from composition.
    val request = remember { viewModel.embeddedCheckout }
    var closed by remember { mutableStateOf(false) }
    val close = {
        if (!closed) {
            closed = true
            onClose()
        }
    }
    if (request == null) {
        LaunchedEffect(Unit) { close() }
        return
    }
    val context = LocalContext.current
    val messenger = rememberMessenger()
    var loading by remember { mutableStateOf(true) }
    var failed by remember { mutableStateOf(false) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    BackHandler(enabled = !closed) {
        val view = webView
        if (view != null && view.canGoBack()) view.goBack() else close()
    }
    DisposableEffect(Unit) {
        onDispose {
            webView?.destroy()
            viewModel.closeEmbeddedCheckout()
        }
    }
    AppScaffold(
        title = request.storeName,
        onClose = close,
        scrollBehavior = appBarScrollBehavior(),
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (failed) {
                Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("The store's checkout didn't load.")
                    Button(onClick = { openContinueUrl(context, request.browserUrl) }) { Text("Open in browser") }
                }
            } else {
                CheckoutWebView(
                    request = request,
                    onCreated = { webView = it },
                    onLoaded = { loading = false },
                    onFailed = { failed = true },
                    onAction = { view, action ->
                        when (action) {
                            is EcAction.Reply -> view.send(action.json)
                            is EcAction.OpenWindow -> {
                                openContinueUrl(context, action.url)
                                view.send(EmbeddedCheckout.windowOpenResult(action.id, ok = true))
                            }
                            is EcAction.Completed -> {
                                viewModel.embeddedCheckoutCompleted(action.orderId, action.permalinkUrl, action.totalMinor, context)
                                messenger.show("Order placed with ${request.storeName}")
                                close()
                            }
                            is EcAction.Failed -> messenger.show(action.message)
                            EcAction.Ignore -> Unit
                        }
                    },
                )
                if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled", "RequiresFeature")
@Composable
private fun CheckoutWebView(
    request: EmbeddedCheckoutRequest,
    onCreated: (WebView) -> Unit,
    onLoaded: () -> Unit,
    onFailed: () -> Unit,
    onAction: (WebView, EcAction) -> Unit,
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                if (WebViewFeature.isFeatureSupported(WebViewFeature.PAYMENT_REQUEST)) {
                    WebSettingsCompat.setPaymentRequestEnabled(settings, true)
                }
                if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
                    WebSettingsCompat.setAlgorithmicDarkeningAllowed(settings, false)
                }
                if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
                    WebViewCompat.addWebMessageListener(this, BRIDGE, bridgeOrigins(request)) { view, message, _, isMainFrame, _ ->
                        val data = message.data
                        if (isMainFrame && data != null) onAction(view, EmbeddedCheckout.handle(data))
                    }
                }
                webViewClient = CheckoutClient(onLoaded, onFailed)
                onCreated(this)
                loadUrl(request.url)
            }
        },
    )
}

/** Merchant domain (and subdomains), the handoff host, and Shopify's hosted checkout. */
private fun bridgeOrigins(request: EmbeddedCheckoutRequest): Set<String> {
    val hosts = buildSet {
        MerchantUrls.domain(request.shopDomain)?.let { add(it.removePrefix("www.")) }
        Uri.parse(request.browserUrl).host?.let { add(it.removePrefix("www.")) }
    }
    return hosts.flatMap { listOf("https://$it", "https://*.$it") }.toSet() + "https://shop.app"
}

private fun WebView.send(json: String) {
    evaluateJavascript(
        "window.EmbeddedCheckoutProtocol&&window.EmbeddedCheckoutProtocol.postMessage($json)",
        null,
    )
}

private class CheckoutClient(private val onLoaded: () -> Unit, private val onFailed: () -> Unit) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val scheme = request.url.scheme?.lowercase()
        if (scheme == "https") return false
        // Bank apps, wallets and mailto links leave the checkout; plain http is never followed.
        if (scheme != "http") {
            try {
                view.context.startActivity(Intent(Intent.ACTION_VIEW, request.url).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: ActivityNotFoundException) {
                // Nothing can handle it; stay on the checkout.
            }
        }
        return true
    }

    override fun onPageFinished(view: WebView, url: String?) = onLoaded()

    override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
        if (request.isForMainFrame) onFailed()
    }
}
