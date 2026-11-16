package com.vayunmathur.library.ui

import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/**
 * W4: the HtmlText WebView client (private, so reached reflectively):
 * `cid:` attachment serving with its key-normalisation fallbacks, remote
 * content blocking, and external-link overriding.
 */
@RunWith(AndroidJUnit4::class)
class W4WebViewClientTest {

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private val clientClass = Class.forName("com.vayunmathur.library.ui.HtmlTextWebViewClient")

    private fun newClient(): Any {
        val ctor = clientClass.getDeclaredConstructor()
        ctor.isAccessible = true
        return ctor.newInstance()
    }

    private fun setField(client: Any, name: String, value: Any?) {
        val field = clientClass.getDeclaredField(name)
        field.isAccessible = true
        field.set(client, value)
    }

    private fun request(uri: Uri?): WebResourceRequest = object : WebResourceRequest {
        override fun getUrl(): Uri? = uri
        override fun isForMainFrame(): Boolean = true
        override fun isRedirect(): Boolean = false
        override fun hasGesture(): Boolean = false
        override fun getMethod(): String = "GET"
        override fun getRequestHeaders(): Map<String, String> = emptyMap()
    }

    private fun intercept(client: Any, view: WebView?, req: WebResourceRequest?): Any? {
        val method = clientClass.getDeclaredMethod(
            "shouldInterceptRequest", WebView::class.java, WebResourceRequest::class.java)
        method.isAccessible = true
        return method.invoke(client, view, req)
    }

    private fun override(client: Any, view: WebView?, req: WebResourceRequest?): Boolean {
        val method = clientClass.getDeclaredMethod(
            "shouldOverrideUrlLoading", WebView::class.java, WebResourceRequest::class.java)
        method.isAccessible = true
        return method.invoke(client, view, req) as Boolean
    }

    @Test
    fun `cid attachments are served with guessed mime types`() {
        val dir = context.cacheDir
        val png = File(dir, "w4pic.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val client = newClient()
        setField(client, "cidMap", mapOf("img1@x" to png))

        val cm = clientClass.getDeclaredField("cidMap").apply{isAccessible=true}.get(client) as Map<*,*>
        val resp = intercept(client, null, request(Uri.parse("http://cid.local/img1%40x")))
        assertNotNull(resp)
        val mime = resp.javaClass.getMethod("getMimeType").invoke(resp) as String
        assertEquals("image/png", mime)
        val stream = resp.javaClass.getMethod("getData").invoke(resp) as java.io.InputStream
        assertEquals(listOf(1.toByte(), 2.toByte(), 3.toByte()), stream.readBytes().toList())

        // Angle-bracketed (exact case) and case-insensitive Content-IDs resolve too.
        val bracketed = intercept(client, null, request(Uri.parse("http://cid.local/%3Cimg1%40x%3E")))
        assertNotNull(bracketed)
        val upper = intercept(client, null, request(Uri.parse("http://cid.local/IMG1%40X")))
        assertNotNull(upper)
        // Unknown or missing files are left to the WebView.
        assertNull(intercept(client, null, request(Uri.parse("http://cid.local/nope"))))
    }

    @Test
    fun `remote content passes through or is blocked by policy`() {
        val client = newClient()
        val remote = request(Uri.parse("https://cdn.example.org/a.png"))
        assertNull(intercept(client, null, remote))
        assertNull(intercept(client, null, null))

        setField(client, "blockRemoteContent", true)
        val blocked = intercept(client, null, remote)
        assertNotNull(blocked)
        val stream = blocked.javaClass.getMethod("getData").invoke(blocked) as java.io.InputStream
        assertEquals(0, stream.readBytes().size)
    }

    @Test
    fun `url loading override keeps in-place schemes and opens the rest`() {
        val view = WebView(context)
        val client = newClient()
        // Disabled policy: never override.
        setField(client, "openLinksExternally", false)
        assertEquals(false, override(client, view, request(Uri.parse("https://example.org"))))
        setField(client, "openLinksExternally", true)
        // In-place schemes and the synthetic cid document stay in the view.
        assertEquals(false, override(client, view, request(Uri.parse("data:text/html,hi"))))
        assertEquals(false, override(client, view, request(Uri.parse("about:blank"))))
        assertEquals(false, override(client, view, request(Uri.parse("http://cid.local"))))
        // A real external link is consumed (handed to the system; no handler
        // under Robolectric, but the view must not navigate in place).
        assertEquals(true, override(client, view, request(Uri.parse("https://example.org/page"))))
        assertEquals(false, override(client, view, null))
    }
}
