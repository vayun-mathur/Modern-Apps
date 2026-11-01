package com.vayunmathur.shop

import android.content.Intent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.shop.platform.AppInit
import com.vayunmathur.shop.platform.ShopViewModel

class MainActivity : MaActivity() {
    private val viewModel: ShopViewModel by viewModels()

    override fun onCreateBeforeContent() {
        // Trust-bundle warm-up off the main thread; screens await AppInit.awaitReady().
        AppInit.start(this)
        // The ViewModel outlives configuration changes, which re-deliver the launch intent.
        if (!viewModel.launchLinkHandled) {
            viewModel.launchLinkHandled = true
            handleLink(intent)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLink(intent)
    }

    private fun handleLink(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        intent.dataString?.let(viewModel::openLink)
    }

    @Composable
    override fun Content() {
        Navigation(viewModel)
    }
}
