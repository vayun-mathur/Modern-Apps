package com.vayunmathur.library.ui

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * [HtmlText]'s layout modes (#900): the default stays an inline wrap-content
 * view for existing callers; `scrollInternally` fills its bounds and scrolls
 * itself. Also pins that recomposing with unchanged content does not reload
 * the page (a reload resets the reader's scroll position).
 */
@RunWith(RobolectricTestRunner::class)
class HtmlTextScrollModeTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private fun webView(): WebView {
        var found: WebView? = null
        rule.runOnUiThread {
            val q = ArrayDeque<View>()
            q.add(rule.activity.findViewById(android.R.id.content))
            while (q.isNotEmpty() && found == null) {
                val v = q.removeFirst()
                if (v is WebView) found = v
                if (v is ViewGroup) for (i in 0 until v.childCount) q.add(v.getChildAt(i))
            }
        }
        return assertNotNull(found, "no WebView in the hierarchy")
    }

    @Test
    fun `default html text stays inline and wrap-content`() {
        rule.setContent { HtmlText(html = "<p>hello</p>") }
        rule.waitForIdle()
        val view = webView()
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, view.layoutParams.height)
        assertFalse(view.isVerticalScrollBarEnabled)
        assertFalse(view.settings.builtInZoomControls)
        assertTrue(view.settings.useWideViewPort)
    }

    @Test
    fun `scrolling html text fills its bounds and scrolls itself`() {
        rule.setContent {
            Column(Modifier.fillMaxSize()) {
                HtmlText(html = "<p>hello</p>", scrollInternally = true, modifier = Modifier.height(300.dp).testTag("body"))
            }
        }
        rule.waitForIdle()
        val view = webView()
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, view.layoutParams.height)
        assertTrue(view.isVerticalScrollBarEnabled)
        assertTrue(view.settings.builtInZoomControls)
        assertFalse(view.settings.displayZoomControls)
        // The slot keeps the bounded height it was given; Robolectric reports the
        // WebView's own content height as 0, so a wrap-content view would collapse.
        rule.onNodeWithTag("body").assertHeightIsEqualTo(300.dp)
    }

    @Test
    fun `fit to width reaches the loaded document`() {
        rule.setContent { HtmlText(html = "<table width=600><tr><td>x</td></tr></table>", fitContentToWidth = true) }
        rule.waitForIdle()
        val data = shadowOf(webView()).lastLoadDataWithBaseURL?.data.orEmpty()
        assertTrue(data.contains(HtmlTextDocument.FIT_VIEWPORT), data.take(500))
        assertTrue(data.contains("overflow-wrap: anywhere"), data.take(2000))
    }

    @Test
    fun `re-running update with unchanged content does not reload the page`() {
        var scroll by mutableStateOf(false)
        rule.setContent {
            // Flipping a parameter the update block reads, but that does not change
            // the document, re-runs update exactly like any other recomposition.
            HtmlText(html = "<p>same</p>", scrollInternally = scroll, modifier = Modifier.height(200.dp))
        }
        rule.waitForIdle()
        val first = shadowOf(webView()).lastLoadDataWithBaseURL
        scroll = true
        rule.waitForIdle()
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, webView().layoutParams.height, "update ran")
        assertSame(first, shadowOf(webView()).lastLoadDataWithBaseURL)
    }

    @Test
    fun `changed content still reloads`() {
        var body by mutableStateOf("<p>one</p>")
        rule.setContent { HtmlText(html = body) }
        rule.waitForIdle()
        assertTrue(shadowOf(webView()).lastLoadDataWithBaseURL?.data.orEmpty().contains("one"))
        body = "<p>two</p>"
        rule.waitForIdle()
        assertTrue(shadowOf(webView()).lastLoadDataWithBaseURL?.data.orEmpty().contains("two"))
    }
}
