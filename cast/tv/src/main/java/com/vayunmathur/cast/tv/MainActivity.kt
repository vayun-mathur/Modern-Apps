package com.vayunmathur.cast.tv

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import com.vayunmathur.cast.tv.platform.MirrorActivity
import com.vayunmathur.cast.tv.platform.ReceiverController
import com.vayunmathur.cast.tv.platform.ReceiverPhase
import com.vayunmathur.cast.tv.service.ReceiverService
import com.vayunmathur.cast.tv.ui.CastTvTheme
import com.vayunmathur.library.ui.MaActivity
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.launch

/**
 * The idle screen, and where the receiver is started from.
 *
 * Opening the app is what makes this TV castable: [ReceiverService] then keeps the sockets and the
 * mDNS registration alive whether or not this Activity is in front, which is why nothing here owns any
 * session state.
 *
 * The screens are [Route] destinations on a one-deep host (Navigation.kt):
 * the controller's phase is the session engine, the route is only its
 * projection onto the screen. There is no back behaviour between receiver
 * states; the graph buys typed destinations and restore.
 */
class MainActivity : MaActivity() {
    override val edgeToEdge: Boolean = false

    override fun onCreateBeforeContent() {
        ReceiverService.start(this)
        // The picture gets its own full-screen Activity, launched the moment frames start arriving.
        // distinctUntilChangedBy so a stream that reports its size again does not relaunch it.
        lifecycleScope.launch {
            ReceiverController.state
                .distinctUntilChangedBy { it.phase is ReceiverPhase.Mirroring }
                .collect { state ->
                    if (state.phase is ReceiverPhase.Mirroring) {
                        startActivity(Intent(this@MainActivity, MirrorActivity::class.java))
                    }
                }
        }
    }

    @Composable
    override fun AppTheme(content: @Composable () -> Unit) {
        CastTvTheme(content = content)
    }

    @Composable
    override fun Content() {
        val state by ReceiverController.state.collectAsState()
        ReceiverNavigation(state)
    }
}
