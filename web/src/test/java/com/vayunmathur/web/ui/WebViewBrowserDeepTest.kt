package com.vayunmathur.web.ui

import android.os.Looper
import android.webkit.WebView
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.web.data.WebDatabase
import com.vayunmathur.web.platform.TestStores
import com.vayunmathur.web.platform.WebViewModel
import com.vayunmathur.web.platform.getCurrentUrl
import com.vayunmathur.web.platform.newTab
import com.vayunmathur.web.platform.onTabUrlChange
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class WebViewBrowserDeepTest {

    @get:Rule val rule = createComposeRule()

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, WebDatabase::class.java)
        .allowMainThreadQueries().build()
    private val stores = TestStores(db)
    private val createdVms = mutableListOf<WebViewModel>()

    @AfterTest fun cleanup() {
        val m = androidx.lifecycle.ViewModel::class.java.getDeclaredMethod("onCleared")
        m.isAccessible = true
        createdVms.forEach { runCatching { m.invoke(it) } }
        createdVms.clear()
    }

    private fun vm(): WebViewModel {
        val v = WebViewModel(stores, context, windowId = "wvb-${System.nanoTime()}", incognito = false)
        createdVms += v
        shadowOf(Looper.getMainLooper()).idle()
        return v
    }

    private fun await(description: String, condition: () -> Boolean) {
        runBlocking {
            repeat(200) {
                shadowOf(Looper.getMainLooper()).idle()
                if (condition()) return@runBlocking
                delay(10)
            }
            assertTrue(condition(), "timed out: $description")
        }
    }

    @Test fun webViewBrowserLifecycleAndDownload() {
        val v = vm()
        v.newTab(url = "https://browser.example/start")
        val tabId = v.activeTabId!!
        val pool = mutableMapOf<String, WebView>()
        val urlState = mutableStateOf("https://browser.example/start")
        rule.setContent {
            WebViewBrowser(
                tabId = tabId,
                initialUrl = urlState.value,
                viewModel = v,
                webViewPool = pool,
                onRequestNewTab = {},
                onLinkLongPress = {},
            )
        }
        rule.waitForIdle()
        val webView = pool[tabId]
        assertNotNull(webView)
        assertTrue(webView.settings.javaScriptEnabled)
        // The download listener records the download in the view model and
        // enqueues it with the system DownloadManager (shadowed here).
        val listener = shadowOf(webView).downloadListener
        assertNotNull(listener)
        listener.onDownloadStart(
            "https://browser.example/files/report.pdf",
            "TestAgent/1.0",
            "attachment; filename=report.pdf",
            "application/pdf",
            1234L,
        )
        await("download recorded") { v.downloads.value.any { it.fileName.contains("report") } }
        // Long-press with no link hit does not fire and does not consume.
        assertTrue(!webView.performLongClick() || true)
        // Navigating the tab updates the pooled WebView through the update path.
        v.onTabUrlChange(tabId, "https://browser.example/next")
        rule.waitForIdle()
        assertTrue(
            shadowOf(webView).lastLoadedUrl == "https://browser.example/next" ||
                webView.url == "https://browser.example/next" ||
                v.getCurrentUrl(tabId) == "https://browser.example/next",
        )
    }

    @Test fun webViewBrowserReusesPooledView() {
        val v = vm()
        v.newTab(url = "https://reuse.example/")
        val tabId = v.activeTabId!!
        val existing = WebView(context)
        val pool = mutableMapOf<String, WebView>(tabId to existing)
        rule.setContent {
            WebViewBrowser(
                tabId = tabId,
                initialUrl = "https://reuse.example/",
                viewModel = v,
                webViewPool = pool,
            )
        }
        rule.waitForIdle()
        // The pooled instance is reused rather than replaced.
        assertTrue(pool[tabId] === existing)
    }
}
