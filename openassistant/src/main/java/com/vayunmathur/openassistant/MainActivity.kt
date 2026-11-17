package com.vayunmathur.openassistant

import android.content.Intent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.downloadservice.InitialModelDownloadChecker
import com.vayunmathur.library.downloadservice.ModelUrls
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.IntentLauncher
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.openassistant.data.OpenAssistantRepository
import com.vayunmathur.openassistant.platform.AssistantViewModel

/** Main Activity: a ma activity implementation for main activity. */
class MainActivity : MaActivity() {

    /** Shared constants and helpers for MainActivity. */
    companion object {
        lateinit var intentLauncher: IntentLauncher
    }

    private val assistantViewModel: AssistantViewModel by viewModels {
        viewModelFactory {
            initializer {
                AssistantViewModel(application, OpenAssistantRepository.get(application))
            }
        }
    }

    override fun onCreateBeforeContent() {
        NetworkClient.init(this, TrustBundle.FIRST_PARTY)
        intentLauncher = IntentLauncher(this)
        readAssistScreenContext(intent)
    }

    @Composable
    override fun Content() {
        val ds = DataStoreUtils.getInstance(this)
        // No OfflineAware banner here: download gating already surfaces
        // offline state via InitialModelDownloadChecker, and web tools
        // report their own clean error strings when the network is down.
        InitialModelDownloadChecker(ds, ModelUrls.INITIAL) {
            Navigation(assistantViewModel)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readAssistScreenContext(intent)
    }

    /**
     * Picks up the flattened screen text captured by the Assist API session
     * ([com.vayunmathur.openassistant.service.OpenAssistantSession]) and hands it to
     * the ViewModel so it seeds the next inference turn as context.
     */
    private fun readAssistScreenContext(intent: Intent?) {
        val screenText = intent?.getStringExtra("assist_screen_text")
        if (!screenText.isNullOrBlank()) {
            assistantViewModel.setScreenContext(screenText)
        }
    }
}
