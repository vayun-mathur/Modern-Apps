package com.vayunmathur.web

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.image.ImageLoader
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.util.OfflineAware
import com.vayunmathur.web.data.FaviconStore
import com.vayunmathur.web.data.RemoteImageFetchers
import com.vayunmathur.web.data.TabThumbnailStore
import com.vayunmathur.web.data.WebRepository
import com.vayunmathur.web.platform.BrowserTab
import com.vayunmathur.web.platform.BrowserUtils
import com.vayunmathur.web.platform.shields.ShieldsEngine
import com.vayunmathur.web.platform.shields.ShieldsServiceWorkerClient
import com.vayunmathur.web.platform.WebViewModel
import com.vayunmathur.web.platform.WebViewModelFactory
import com.vayunmathur.web.platform.externalIntentUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class MainActivity : MaActivity() {

    private val readyState = mutableStateOf(false)
    private var factoryState by mutableStateOf<WebViewModelFactory?>(null)
    private val externalUrlState = mutableStateOf<String?>(null)

    override fun onCreateBeforeContent() {
        // SYSTEM permissive browser: any host + user CAs for MITM debug/corp proxies (per user exception)
        NetworkClient.init(this, TrustBundle.SYSTEM)
        // Search-result thumbnails and PWA icons load over HTTP(S) through the
        // shared image pipeline; the fetch function is built here from
        // NetworkClient so no library-to-library edge is needed (#884).
        ImageLoader.setDefault(
            ImageLoader.Builder(this).remote(RemoteImageFetchers::network).build(),
        )
        // Parses ~8 MB of filter lists off the main thread. Shields fail open until it lands.
        lifecycleScope.launch { ShieldsEngine.load(applicationContext) }
        // Before any WebView exists: service-worker fetches bypass the WebViewClient entirely.
        ShieldsServiceWorkerClient.registerOnce(applicationContext)

        TabThumbnailStore.init(applicationContext)
        FaviconStore.init(applicationContext)

        // Each task (window) carries its own window id + incognito flag so it keeps an independent tab set.
        val windowId = intent?.getStringExtra(EXTRA_WINDOW_ID) ?: WebViewModel.DEFAULT_WINDOW_ID
        val incognito = intent?.getBooleanExtra(EXTRA_INCOGNITO, false) ?: false

        // Reap tab-sets left behind by windows the user has since closed (swiped from Recents).
        pruneClosedWindowTabs()

        lifecycleScope.launch(Dispatchers.IO) {
            val repository = WebRepository.get(applicationContext)
            val factory = WebViewModelFactory(
                repository = repository,
                context = applicationContext,
                windowId = windowId,
                incognito = incognito,
                initialShieldSettings = repository.shields.all(),
            )
            withContext(Dispatchers.Main) {
                factoryState = factory
                readyState.value = true
                handleIntentUrl(intent)
            }
        }
    }

    @Composable
    override fun Content() {
        OfflineAware {
            Box(Modifier.fillMaxSize()) {
                if (!readyState.value || factoryState == null) {
                    Box(Modifier.fillMaxSize())
                } else {
                    AppRoot(factoryState!!, externalUrlState.value) {
                        externalUrlState.value = null
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntentUrl(intent)
    }

    @Composable
    private fun AppRoot(
        factory: WebViewModelFactory,
        pendingExternalUrl: String?,
        onExternalUrlConsumed: () -> Unit,
    ) {
        val viewModel: WebViewModel = viewModel(factory = factory)
        LaunchedEffect(pendingExternalUrl) {
            if (pendingExternalUrl != null) {
                viewModel.externalIntentUrl(pendingExternalUrl)
                onExternalUrlConsumed()
            }
        }
        Navigation(viewModel)
    }

    private fun handleIntentUrl(intent: Intent?) {
        intent ?: return
        val raw = when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return

        val url = extractHttpUrl(raw) ?: return
        externalUrlState.value = url
    }

    private fun extractHttpUrl(text: String): String? {
        val trimmed = text.trim()
        if (BrowserUtils.isWebUrl(trimmed)) {
            val match = Regex("https?://\\S+", RegexOption.IGNORE_CASE).find(trimmed)
            return match?.value ?: trimmed.substringBefore(" ")
        }
        Regex("https?://\\S+", RegexOption.IGNORE_CASE).find(trimmed)?.let { return it.value }
        return null
    }

    /**
     * Removes persisted tab-sets whose window is no longer a live task. Each non-incognito window
     * stores its tabs under keys suffixed with its window id (see [WebViewModel]); when the user
     * swipes a window out of Recents those keys would otherwise linger forever.
     */
    private fun pruneClosedWindowTabs() {
        // Must mirror WebViewModel's namespaced key format: "<P_SAVED_TABS>_<windowId>" etc.
        val savedTabsPrefix = "web_saved_tabs_"
        val activeTabPrefix = "web_active_tab_id_"
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                val am = getSystemService(ACTIVITY_SERVICE) as? android.app.ActivityManager ?: return@runCatching
                val liveWindowIds = am.appTasks.mapNotNull { task ->
                    val base = runCatching { task.taskInfo?.baseIntent }.getOrNull()
                    base?.getStringExtra(EXTRA_WINDOW_ID)
                        ?: base?.data?.takeIf { it.scheme == "web-window" }?.host
                }.toSet()

                val sp = getSharedPreferences("web_prefs", MODE_PRIVATE)
                val editor = sp.edit()
                val removedKeys = mutableSetOf<String>()
                for (key in sp.all.keys) {
                    val windowId = when {
                        key.startsWith(savedTabsPrefix) -> key.removePrefix(savedTabsPrefix)
                        key.startsWith(activeTabPrefix) -> key.removePrefix(activeTabPrefix)
                        else -> null
                    } ?: continue
                    if (windowId !in liveWindowIds) {
                        editor.remove(key)
                        removedKeys.add(key)
                    }
                }
                if (removedKeys.isNotEmpty()) editor.apply()

                // Whatever tab sets survived the prune are the only tabs that can still be
                // shown, so every other thumbnail file is unreachable.
                val json = Json { ignoreUnknownKeys = true }
                val liveTabIds = sp.all
                    .filterKeys { it == "web_saved_tabs" || it.startsWith(savedTabsPrefix) }
                    .filterKeys { it !in removedKeys }
                    .values
                    .filterIsInstance<String>()
                    .flatMap { saved ->
                        runCatching { json.decodeFromString<List<BrowserTab>>(saved) }
                            .getOrDefault(emptyList())
                            .map { it.id }
                    }
                    .toSet()
                TabThumbnailStore.retainOnly(liveTabIds)
            }.onFailure { e ->
                Log.error("MainActivity", "pruneClosedWindowTabs failed", e)
            }
        }
    }

    companion object {
        const val EXTRA_WINDOW_ID = "com.vayunmathur.web.WINDOW_ID"
        const val EXTRA_INCOGNITO = "com.vayunmathur.web.INCOGNITO"
    }
}
