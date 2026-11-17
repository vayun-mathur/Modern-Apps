package com.vayunmathur.securemail

import com.vayunmathur.securemail.platform.IntentState

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.ui.MaFragmentActivity
import com.vayunmathur.securemail.platform.MailViewModel
import com.vayunmathur.securemail.service.MailSocketService

/**
 * SecureMail entry point. A [MaFragmentActivity] (not plain MaActivity) because
 * unlocking the PGP private key needs a fragment host for the biometric prompt.
 * Composition is deferred until the key gate succeeds.
 */
class MainActivity : MaFragmentActivity() {

    override val autoSetContent: Boolean = false

    private val viewModel: MailViewModel by viewModels()

    override fun onCreateBeforeContent() {
        // mail.maemail.org is a pinned first-party host; WKD/VKS lookups for
        // arbitrary external domains ride the platform trust store instead.
        NetworkClient.init(this, TrustBundle.FIRST_PARTY)
        handleIntent(intent)
        viewModel.unlockIfReady(
            activity = this,
            onUnlocked = {
                MailSocketService.start(this)
                showContent()
            },
            onLocked = {
                // Stay on the lock screen: composition waits for the key gate.
                showContent()
            },
        )
    }

    @Composable
    override fun Content() {
        val route = IntentState.navigationRoute
        LaunchedEffect(route) { IntentState.navigationRoute = null }
        Navigation(viewModel, initialRoute = route)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: android.content.Intent?) {
        if (intent == null) return
        val shared = intent.getStringExtra(android.content.Intent.EXTRA_TEXT)
        val to = if (intent.action == android.content.Intent.ACTION_SENDTO) {
            intent.data?.schemeSpecificPart ?: ""
        } else {
            ""
        }
        if (intent.action == android.content.Intent.ACTION_SEND ||
            intent.action == android.content.Intent.ACTION_SENDTO
        ) {
            IntentState.navigationRoute = Route.Composer(
                to = to,
                subject = intent.getStringExtra(android.content.Intent.EXTRA_SUBJECT) ?: "",
                body = shared ?: "",
            )
        }
    }
}
