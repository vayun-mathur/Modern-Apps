package com.vayunmathur.web.platform

import android.app.ActivityManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.web.platform.shields.ShieldsEngine
import com.vayunmathur.web.platform.shields.ShieldsServiceWorkerClient

/**
 * A pinned installed site, running in its own task with no browser chrome.
 *
 * The cleartext gate applies here as everywhere, but there is no local-network prompt: PWAs
 * install from https origins and have no omnibox, and the permission is app-wide so granting it
 * once in the browser covers them. The seam is a PWA installed from a LAN origin — it gets
 * blocked with no in-PWA way to grant.
 */
class PwaActivity : ComponentActivity() {

    companion object {
        const val EXTRA_URL = "pwa_url"
        const val EXTRA_TITLE = "pwa_title"

        /** TaskDescription.Builder exists from Android 13 (Tiramisu). */
        private const val MIN_SDK_TASK_DESCRIPTION_BUILDER = 33
    }

    private val initialUrlState = mutableStateOf<String?>(null)
    private val titleState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Installed sites get the same shields as the browser; loading is a no-op if the
        // engine is already up in this process.
        lifecycleScope.launch { ShieldsEngine.load(applicationContext) }
        // A pinned shortcut can be the process entry point without MainActivity ever running.
        ShieldsServiceWorkerClient.registerOnce(applicationContext)
        handleIntent(intent)
        setContent {
            DynamicTheme {
                val url = initialUrlState.value
                if (url.isNullOrBlank()) {
                    Box(Modifier.fillMaxSize())
                } else {
                    PwaBrowser(
                        initialUrl = url,
                        title = titleState.value,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val urlFromExtra = intent.getStringExtra(EXTRA_URL)
        val urlFromData = intent.dataString ?: intent.getStringExtra(Intent.EXTRA_TEXT)
        // This activity is exported (pinned shortcuts), so the extra is sender-controlled:
        // run it through the same fail-closed extractor as intent data instead of trusting
        // it verbatim. Our own shortcuts carry clean https URLs, which pass through unchanged.
        val url = BrowserUtils.extractHttpUrl(urlFromExtra ?: urlFromData ?: "") ?: return
        initialUrlState.value = url
        titleState.value = intent.getStringExtra(EXTRA_TITLE)
        labelRecentsEntry(url, titleState.value)
    }

    /**
     * Name this task after the site it is showing. Every installed site is the same activity,
     * so without this Recents lists them all as "Web" and they can't be told apart.
     */
    private fun labelRecentsEntry(url: String, title: String?) {
        val label = title?.takeIf { it.isNotBlank() }
            ?: runCatching { BrowserUtils.hostFromUrl(url) }.getOrNull()?.takeIf { it.isNotBlank() }
            ?: return
        setTaskDescription(
            if (Build.VERSION.SDK_INT >= MIN_SDK_TASK_DESCRIPTION_BUILDER) {
                ActivityManager.TaskDescription.Builder().setLabel(label).build()
            } else {
                @Suppress("DEPRECATION")
                ActivityManager.TaskDescription(label)
            }
        )
    }
}
