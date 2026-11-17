package com.vayunmathur.madeveloper

import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.madeveloper.platform.MaDevViewModel
import com.vayunmathur.madeveloper.platform.ReconnectWorker

/** Entry activity for Developer. */
class MainActivity : MaActivity() {
    private val viewModel: MaDevViewModel by viewModels()

    override fun onCreateBeforeContent() {
        NetworkClient.init(this, TrustBundle.FIRST_PARTY)
        ReconnectWorker.schedule(this)
    }

    @Composable
    override fun Content() {
        Navigation(viewModel)
    }
}
