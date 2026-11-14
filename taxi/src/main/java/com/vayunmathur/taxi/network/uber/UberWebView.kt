package com.vayunmathur.taxi.network.uber

import android.annotation.SuppressLint
import com.vayunmathur.library.log.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.vayunmathur.taxi.data.uber.UberSession
import kotlinx.coroutines.launch

/**
 * Visible in-app WebView for Uber.
 *
 * Uber's native API is unreachable here — it signs every request with `libse_loader.so` and
 * attests the device with Play Integrity, neither of which works on a degoogled phone (see
 * `uber-re/api-notes.md` §3). The web app has no such gate, because it has to run in a browser.
 *
 * So the user signs in normally at m.uber.com and we work from that session. [GRAPHQL_HOOK_JS]
 * wraps fetch/XHR at document-start and reports every `/go/graphql` call — operation name,
 * variables, and a bounded slice of the response — over the bridge. That is how we learn the
 * fare operations, since Apollo introspection is disabled server-side.
 *
 * Once the operations are known, [UberCookies] lets a native client issue them directly.
 */
@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
@Composable
fun UberWebView(
    modifier: Modifier = Modifier,
    onGraphqlCaptured: (UberGraphqlCapture) -> Unit = {},
    onPageChanged: (String) -> Unit = {},
    startUrl: String = UberWeb.HOME,
) {
    val scope = rememberCoroutineScope()
    Box(modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                CookieManager.getInstance().setAcceptCookie(true)
                WebView(ctx).apply {
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    addJavascriptInterface(
                        object {
                            @JavascriptInterface
                            fun onGraphql(operation: String, variables: String, response: String) {
                                Log.dev(TAG, "GQL $operation vars=$variables")
                                Log.dev(TAG, "GQL $operation resp=${response.take(GRAPHQL_RESPONSE_LOG_MAX)}")
                                post {
                                    onGraphqlCaptured(
                                        UberGraphqlCapture(operation, variables, response),
                                    )
                                }
                            }

                            @JavascriptInterface
                            fun onLog(msg: String) {
                                Log.dev(TAG, "js: $msg")
                            }
                        },
                        "AndroidUber",
                    )
                    webViewClient = object : WebViewClient() {
                        override fun onPageStarted(
                            view: WebView,
                            url: String?,
                            favicon: android.graphics.Bitmap?,
                        ) {
                            view.evaluateJavascript(GRAPHQL_HOOK_JS, null)
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            Log.dev(TAG, "PAGE $url")
                            Log.dev(TAG, "COOKIES ${UberCookies.names(UberWeb.HOME)}")
                            if (url == null) return
                            val session = UberSession(view.context.applicationContext)
                            scope.launch {
                                session.setSignedIn(UberSession.looksAuthenticated(url))
                            }
                            view.post { onPageChanged(url) }
                        }
                    }
                    loadUrl(startUrl)
                }
            },
        )
    }
}

private const val TAG = "UberWeb"

/** How much of a captured GraphQL response goes to logcat — the shape, not the payload. */
private const val GRAPHQL_RESPONSE_LOG_MAX = 4000

/** Immutable value describing uber graphql capture. */
data class UberGraphqlCapture(
    val operationName: String,
    val variables: String,
    val responseBody: String,
)

/** Singleton holding uber web state and operations. */
object UberWeb {
    const val HOME = "https://m.uber.com/"
    const val GRAPHQL = "https://m.uber.com/go/graphql"

    /**
     * Uber's web API rejects requests without this header (403 `Missing csrf token.`). It is a
     * presence check only — the literal `x` is accepted, which is what the web app itself sends.
     */
    const val CSRF_HEADER = "x-csrf-token"
    const val CSRF_VALUE = "x"
}

/** Session cookies from the shared process cookie jar, for native calls that reuse the login. */
object UberCookies {
    fun header(url: String = UberWeb.HOME): String? =
        CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }

    private fun parse(url: String): List<Pair<String, String>> =
        header(url)?.split(";")?.mapNotNull { part ->
            val name = part.substringBefore('=').trim()
            val value = part.substringAfter('=', "").trim()
            if (name.isEmpty()) null else name to value
        } ?: emptyList()

    /** Cookie names only, for discovery logging — never the values. */
    fun names(url: String): String =
        parse(url).joinToString(",") { it.first }.ifEmpty { "(none)" }

    fun clear() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }
}

/**
 * Injected at document-start. Wraps fetch + XMLHttpRequest and reports any `/go/graphql` call,
 * so the operation names and response shapes come out of a real session rather than out of
 * probing Uber. Response text is truncated — we want the shape, not the payload.
 */
private const val GRAPHQL_HOOK_JS = """
    (function(){
      if(window.__ubergql)return; window.__ubergql=1;
      var LIMIT=20000;
      function isGql(u){return (''+u).indexOf('/go/graphql')>=0;}
      function report(body,text){
        var op='',vars='';
        try{var b=JSON.parse(body||'{}');
            if(Array.isArray(b)) b=b[0]||{};
            op=b.operationName||'';
            vars=JSON.stringify(b.variables||{});}catch(e){}
        try{AndroidUber.onGraphql(op,vars,(text||'').slice(0,LIMIT));}catch(e){}
      }
      var of=window.fetch;
      if(of){window.fetch=function(input,init){
        var u=(typeof input==='string')?input:(input&&input.url);
        var body=(init&&init.body)||null;
        var p=of.apply(this,arguments);
        if(isGql(u)){try{p.then(function(r){
          try{r.clone().text().then(function(t){report(body,t);});}catch(e){}
        });}catch(e){}}
        return p;};}
      var oo=XMLHttpRequest.prototype.open;
      XMLHttpRequest.prototype.open=function(m,u){this.__u=u;return oo.apply(this,arguments);};
      var os=XMLHttpRequest.prototype.send;
      XMLHttpRequest.prototype.send=function(body){
        var self=this;
        if(isGql(self.__u)){self.addEventListener('load',function(){
          try{report(body,self.responseText||'');}catch(e){}
        });}
        return os.apply(this,arguments);};
      try{AndroidUber.onLog('graphql hook installed');}catch(e){}
    })()
"""
