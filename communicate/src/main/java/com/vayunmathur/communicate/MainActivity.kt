package com.vayunmathur.communicate

import android.app.PictureInPictureParams
import android.content.Intent
import android.util.Rational
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.vayunmathur.communicate.data.call.InAppCallPhase
import com.vayunmathur.communicate.data.call.InAppCallRegistry
import com.vayunmathur.communicate.intents.DeepLink
import com.vayunmathur.communicate.intents.DeepLinks
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.util.OfflineAware

/** The single activity hosting the communicate UI: navigation, deep links and line setup. */
class MainActivity : MaActivity() {
    /** The deep link the app was opened with, or the most recent one delivered to a running instance. */
    private var pendingDeepLink by mutableStateOf<DeepLink?>(null)

    override fun onCreateBeforeContent() {
        pendingDeepLink = DeepLinks.parse(intent)
        com.vayunmathur.library.wearlink.WearLinkPermissions.requestIfNeeded(this)
        // Wear companion sync lives and dies with this process (WearLink has
        // no system wakeup); the watch reads its cached snapshot offline.
        com.vayunmathur.communicate.data.WearCommunicateSync.get(this).start()
    }

    @Composable
    override fun Content() {
        OfflineAware {
            Navigation(initialDeepLink = pendingDeepLink)
        }
    }

    /**
     * A deep link that arrived while the activity was already running. Held in state so the composition picks
     * it up, rather than being lost — the app declared `sms:`/`tel:` filters for a long time and ignored them.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingDeepLink = DeepLinks.parse(intent)
    }

    /**
     * Shrink a video call into picture-in-picture when leaving the app, so the far end stays visible.
     *
     * Only for video: an audio call has nothing to show, and the ongoing-call notification already covers it.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val call = InAppCallRegistry.state.value
        val videoActive = call.remoteVideoEnabled || call.localVideoEnabled
        if (!videoActive || call.phase == InAppCallPhase.Idle || call.phase == InAppCallPhase.Ended) return
        runCatching {
            enterPictureInPictureMode(
                PictureInPictureParams.Builder()
                    .setAspectRatio(Rational(PORTRAIT_WIDTH, PORTRAIT_HEIGHT))
                    .setAutoEnterEnabled(true)
                    .setSourceRectHint(
                        android.graphics.Rect().also { window.decorView.getGlobalVisibleRect(it) },
                    )
                    .build(),
            )
        }
    }

    /** Splash aspect-ratio constants. */
    companion object {
        private const val PORTRAIT_WIDTH = 9
        private const val PORTRAIT_HEIGHT = 16
    }
}
