package com.vayunmathur.library.ui

import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * HtmlText under Robolectric: the WebView loads the wrapped document, so the
 * cid rewrite, quote CSS and remote-content blocking are all assertable
 * through the shadow's last load. Slice-1 coverage for library/ui (HtmlText).
 */
@RunWith(RobolectricTestRunner::class)
class HtmlTextComposeSlice1Test {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun loadedData(): String {
        var data: String? = null
        rule.runOnUiThread {
            val root = rule.activity.findViewById<android.view.View>(android.R.id.content)
            val q = ArrayDeque<android.view.View>()
            q.add(root)
            while (q.isNotEmpty()) {
                val v = q.removeFirst()
                if (v is WebView) {
                    data = shadowOf(v).lastLoadDataWithBaseURL?.data
                    return@runOnUiThread
                }
                if (v is ViewGroup) for (i in 0 until v.childCount) q.add(v.getChildAt(i))
            }
        }
        return data ?: error("no WebView loaded")
    }

    @Test
    fun `plain html loads into the view`() {
        rule.setContent {
            HtmlText(html = "<p>hello</p>")
        }
        rule.waitForIdle()
        assertTrue(loadedData().contains("hello"), loadedData().take(500))
    }

    @Test
    fun `blocked remote content adds a content-security-policy`() {
        rule.setContent {
            HtmlText(html = "<p>hi</p>", blockRemoteContent = true)
        }
        rule.waitForIdle()
        assertTrue(loadedData().contains("Content-Security-Policy"), loadedData().take(500))
    }

    @Test
    fun `hiding quotes adds quote-hiding css`() {
        rule.setContent {
            HtmlText(html = "<p>hi</p>", hideQuotes = true)
        }
        rule.waitForIdle()
        assertTrue(loadedData().contains("gmail_quote"), loadedData().take(2000))
    }

    @Test
    fun `cid references rewrite to the local host`() {
        val f = File.createTempFile("img", ".png")
        try {
            rule.setContent {
                HtmlText(
                    html = "<img src=\"cid:abc123\">",
                    cidMap = mapOf("abc123" to f),
                )
            }
            rule.waitForIdle()
            val data = loadedData()
            assertTrue(data.contains("https://cid.local/"), data.take(1000))
            assertTrue(!data.contains("cid:abc123"), data.take(1000))
        } finally {
            f.delete()
        }
    }
}
