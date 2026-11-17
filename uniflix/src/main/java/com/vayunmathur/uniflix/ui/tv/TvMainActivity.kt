package com.vayunmathur.uniflix.ui.tv

import androidx.compose.runtime.Composable
import com.vayunmathur.library.network.NetworkClient
import com.vayunmathur.library.network.TrustBundle
import com.vayunmathur.library.tv.MaTvTheme
import com.vayunmathur.library.tv.TvAccent
import com.vayunmathur.library.ui.MaActivity

/**
 * The TV entry: opened from TV launcher rows (LEANBACK_LAUNCHER). The phone
 * MainActivity stays exactly as it is — this activity hosts the D-pad UI.
 *
 * Non-edge-to-edge per the manifest (TV displays are landscape), always dark in the
 * Uniflix amber accent.
 */
class TvMainActivity : MaActivity() {
    override val edgeToEdge: Boolean = false

    override fun onCreateBeforeContent() {
        // Same arming the phone does: catalog + artwork go through the
        // self-hosted proxy, streams come from user-supplied addon URLs.
        NetworkClient.init(this, TrustBundle.STANDARD)
    }

    @Composable
    override fun AppTheme(content: @Composable () -> Unit) {
        // Always dark, not dynamic: a TV has no wallpaper to derive a scheme from.
        MaTvTheme(TvAccent.Amber, content = content)
    }

    @Composable
    override fun Content() {
        TvNavigation()
    }
}
