package com.vayunmathur.everysync

import android.content.Intent
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.vayunmathur.everysync.network.remote.RelayLinks
import com.vayunmathur.everysync.platform.EverySyncViewModel
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.util.AppMessages
import com.vayunmathur.library.util.OfflineAware

/** Entry activity that wires the EverySync UI and join-link handling. */
class MainActivity : MaActivity() {
    private val viewModel: EverySyncViewModel by viewModels()

    override fun onCreateBeforeContent() {
        // everysync://join links request access to someone else's vault — fire a
        // sealed join request (public ids only) and confirm on the snackbar.
        parseJoinLink(intent)?.let { handleJoinLink(it) }
    }

    @Composable
    override fun Content() {
        // Paint the theme background behind everything (incl. the status bar strip the
        // offline banner leaves transparent) so no white window background shows through
        // when offline. See issue #488.
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            OfflineAware {
                Navigation(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTop: warm-start join links arrive here rather than through a fresh onCreate.
        setIntent(intent)
        parseJoinLink(intent)?.let { handleJoinLink(it) }
    }

    /** Fires a sealed join request to the owner and confirms with a snackbar message. */
    private fun handleJoinLink(link: Triple<String, String, String>) {
        val (vaultId, ownerId, dataType) = link
        AppMessages.show(getString(R.string.join_request_sending))
        viewModel.vaults.requestToJoin(vaultId, ownerId, dataType) { ok ->
            AppMessages.show(
                getString(if (ok) R.string.join_request_sent else R.string.join_request_failed),
                duration = AppMessages.Duration.Long,
            )
        }
    }

    /**
     * Parses `everysync://join/<vaultId>?owner=<ownerDeviceId>&type=<dataType>`;
     * null for any other intent. Mirrors voxels `MenuActivity.parseJoinLink` and
     * office `MainActivity.parseJoinLink`.
     */
    private fun parseJoinLink(intent: Intent?): Triple<String, String, String>? {
        val data = intent?.data ?: return null
        return RelayLinks.parseJoinLink(
            scheme = data.scheme,
            host = data.host,
            vaultId = data.lastPathSegment?.takeIf { it.isNotBlank() },
            ownerId = data.getQueryParameter("owner")?.takeIf { it.isNotBlank() },
            dataType = data.getQueryParameter("type"),
        )
    }
}
