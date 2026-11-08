package com.vayunmathur.web.platform

import android.net.Uri
import android.os.Looper
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.web.domain.EffectiveShields
import com.vayunmathur.web.domain.ShieldsSettings
import com.vayunmathur.web.domain.shields.UrlCleaner
import com.vayunmathur.web.platform.shields.ShieldsEngine
import com.vayunmathur.web.platform.shields.ShieldsWebViewClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * The bundled filter lists carry `$removeparam` rules for parameters the fixed
 * [UrlCleaner] list does not know (e.g. `ref` on reddit.com). The engine reports
 * those as [com.vayunmathur.web.platform.shields.ShieldsCheck.rewritten], and the
 * main-frame navigation path must honor them — subresources cannot be redirected
 * from `shouldInterceptRequest`, so the top-level rewrite is the only consumer.
 */
@RunWith(RobolectricTestRunner::class)
class ShieldsRemoveParamTest {

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

    @Test fun `engine removeparam rewrite reaches main-frame navigation`() {
        runBlocking { ShieldsEngine.load(context) }
        shadowOf(Looper.getMainLooper()).idle()
        if (!ShieldsEngine.ready) return // Fail-open environment: engine contract untestable here.
        val url = "https://www.reddit.com/r/test/?ref=search_posts"
        // UrlCleaner alone does not know `ref` — this pins why the engine must be consulted.
        assertEquals(url, UrlCleaner.clean(url))
        // The engine does rewrite it, without blocking the navigation.
        val check = ShieldsEngine.check(url, url, "document")
        assertNotNull(check, "engine should answer for a document request")
        assertFalse(check.blocked, "removeparam rule must not block the navigation")
        val rewritten = check.rewritten
        assertNotNull(rewritten, "engine should strip the removeparam parameter")
        assertFalse(rewritten.contains("ref="), "rewritten URL must drop the ref parameter")
        // And the client must navigate to the rewritten URL.
        val aggressive = EffectiveShields.resolve(ShieldsSettings.AGGRESSIVE_DEFAULTS)
        var navigated: String? = null
        val client = ShieldsWebViewClient(
            context,
            shieldsFor = { aggressive },
            onNavigate = { _, navigatedUrl -> navigated = navigatedUrl },
        )
        val view = WebView(context)
        assertTrue(
            client.shouldOverrideUrlLoading(view, request(url, mainFrame = true)),
            "client should take over the navigation to apply the rewrite",
        )
        assertEquals(rewritten, navigated)
    }
}
