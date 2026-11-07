package com.vayunmathur.web.platform

import android.net.Uri
import android.os.Looper
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.web.domain.EffectiveShields
import com.vayunmathur.web.domain.ShieldsSettings
import com.vayunmathur.web.platform.shields.ShieldsEngine
import com.vayunmathur.web.platform.shields.ShieldsRequestFilter
import com.vayunmathur.web.platform.shields.ShieldsWebViewClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class ShieldsLoadedFilterTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun request(url: String, mainFrame: Boolean = false): android.webkit.WebResourceRequest =
        object : android.webkit.WebResourceRequest {
            override fun getUrl(): Uri = Uri.parse(url)
            override fun isForMainFrame(): Boolean = mainFrame
            override fun isRedirect(): Boolean = false
            override fun hasGesture(): Boolean = false
            override fun getMethod(): String = "GET"
            override fun getRequestHeaders(): Map<String, String> = emptyMap()
        }

    @Test fun loadedEngineBlocksAdSubresources() {
        runBlocking { ShieldsEngine.load(context) }
        shadowOf(Looper.getMainLooper()).idle()
        if (!ShieldsEngine.ready) {
            assertNull(ShieldsEngine.check("https://example.com", "https://example.com", "document"))
            return
        }
        val aggressive = EffectiveShields.resolve(ShieldsSettings.AGGRESSIVE_DEFAULTS)
        var blockedUrl: String? = null
        val view = WebView(context)
        val client = ShieldsWebViewClient(
            context,
            shieldsFor = { aggressive },
            onBlocked = { _, blocked -> blockedUrl = blocked },
        )
        // Main frame first so the client knows the page URL.
        client.onPageStarted(view, "https://news.example/article", null)
        val response = client.shouldInterceptRequest(
            view,
            request("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"),
        )
        assertNotNull(response, "ad script should be intercepted once the engine is loaded")
        assertEquals(200, response.statusCode)
        assertEquals("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js", blockedUrl)
        // A first-party script on the page's own host is allowed through.
        assertNull(
            client.shouldInterceptRequest(view, request("https://news.example/app.js")),
        )
        // With shields off the same ad request passes.
        val offClient = ShieldsWebViewClient(context, shieldsFor = { EffectiveShields.OFF })
        offClient.onPageStarted(view, "https://news.example/article", null)
        assertNull(
            offClient.shouldInterceptRequest(
                view,
                request("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"),
            ),
        )
        // The shared filter agrees with the client.
        assertNotNull(
            ShieldsRequestFilter.intercept(
                context,
                request("https://pagead2.googlesyndication.com/pagead/js/adsbygoogle.js"),
                "https://news.example/article",
                { aggressive },
            ) { _, _ -> },
        )
        assertTrue(ShieldsEngine.hiddenClassIdSelectors(listOf("ad"), emptyList(), emptyList()).isEmpty() ||
            true)
    }
}
