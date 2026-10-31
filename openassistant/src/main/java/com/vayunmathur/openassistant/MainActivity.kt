package com.vayunmathur.openassistant

import android.content.Intent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.downloadservice.InitialModelDownloadChecker
import com.vayunmathur.library.downloadservice.ModelUrls
import com.vayunmathur.library.util.DataStoreUtils
import com.vayunmathur.library.util.IntentLauncher
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import kotlinx.serialization.Serializable
import com.vayunmathur.openassistant.data.OpenAssistantRepository
import com.vayunmathur.openassistant.ui.LiteRTChatUi
import com.vayunmathur.openassistant.ui.SettingsPage
import com.vayunmathur.openassistant.util.AssistantViewModel

class MainActivity : MaActivity() {

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
        intentLauncher = IntentLauncher(this)
        readAssistScreenContext(intent)
    }

    @Composable
    override fun Content() {
        val ds = DataStoreUtils.getInstance(this)
        // No OfflineAware banner: openassistant takes no :library:network
        // dependency, so the connectivity-monitor UI (which needs
        // ACCESS_NETWORK_STATE from :library:network) cannot live here without
        // reintroducing the #884 leak. Download gating already surfaces
        // offline state via InitialModelDownloadChecker.
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
     * ([com.vayunmathur.openassistant.assist.OpenAssistantSession]) and hands it to
     * the ViewModel so it seeds the next inference turn as context.
     */
    private fun readAssistScreenContext(intent: Intent?) {
        val screenText = intent?.getStringExtra("assist_screen_text")
        if (!screenText.isNullOrBlank()) {
            assistantViewModel.setScreenContext(screenText)
        }
    }
}

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data class ConversationPage(val id: Long): Route
    @Serializable
    data object SettingsPage: Route
}

@Composable
fun Navigation(assistantViewModel: AssistantViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.ConversationPage(0))
    MainNavigation(backStack) {
        // The chat column is the list pane: its NavigationSuite drawer is permanent on
        // Expanded, and settings renders beside it as the detail pane.
        entry<Route.ConversationPage>(metadata = ListPage()) {
            LiteRTChatUi(backStack, it.id, assistantViewModel)
        }
        entry<Route.SettingsPage>(metadata = ListDetailPage()) {
            SettingsPage(backStack, assistantViewModel)
        }
    }
}
