package com.vayunmathur.cast.platform

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.vayunmathur.cast.domain.ClientPhase
import com.vayunmathur.cast.ui.CastPairDialog
import com.vayunmathur.library.ui.DynamicTheme

/**
 * Takes the six digits for a route selected in Settings.
 *
 * A route row has no text field, so until now the pairing state was only answerable from the app's
 * own screen: Settings sat at CONNECTING while the thing it was waiting for was somewhere the user
 * had no reason to look. This is that same prompt, put over Settings by
 * [com.vayunmathur.cast.platform.remotedisplay.MaRemoteDisplayProvider].
 *
 * **It pairs nothing itself.** It calls [CastController.submitPairCode], the same entry point the
 * in-app card uses, so the device key is stored by the same path - and a later session with the same
 * TV pairs silently and never launches this at all.
 *
 * `MirrorConsentActivity` is the precedent for being started from a background context, and the same
 * `START_ACTIVITIES_FROM_BACKGROUND` grant is what allows it.
 *
 * State is read straight off [CastController] rather than through [CastViewModel], whose `uiState` is
 * a `stateIn` starting at an empty [CastUiState]: built from that, the first frame would carry no TV
 * name and would read as "not awaiting a code", which is the very condition this dismisses itself on.
 */
class CastPairActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The window is transparent, so the scrim is Compose's and has to reach the system bars.
        enableEdgeToEdge()
        val appContext = applicationContext
        setContent {
            DynamicTheme {
                val session by CastController.sessionState.collectAsState()
                val device by CastController.device.collectAsState()
                LaunchedEffect(session.phase) {
                    // Paired, refused, or torn down under us: nothing left to type either way.
                    if (session.phase != ClientPhase.AwaitingCode) finish()
                }
                CastPairDialog(
                    state = CastUiState(
                        connectedDevice = device,
                        connection = CastConnection.AwaitingCode,
                        pairAttemptsLeft = session.attemptsLeft,
                        pairCodeChanged = session.codeChanged,
                    ),
                    actions = remember { PairPromptActions(appContext) },
                )
            }
        }
    }

    /**
     * Backing out is a decision not to pair, and it has to reach the session.
     *
     * Without this the session would stay in [ClientPhase.AwaitingCode] with nothing on screen left
     * to answer it, and the Settings row would keep spinning until the provider's timeout. Every way
     * out of the prompt finishes this activity, so this one place covers all of them.
     */
    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing && CastController.sessionState.value.phase == ClientPhase.AwaitingCode) {
            CastController.disconnect(this)
        }
    }

        /** Factory for the intent that opens the pairing prompt. */
    companion object {
        /** `NEW_TASK` because the caller is a bound provider service with no task of its own. */
        fun intent(context: Context): Intent =
            Intent(context, CastPairActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}

/**
 * The one action the prompt offers.
 *
 * A [CastActions] rather than a lambda so the pair-code card can be reused exactly as the app screen
 * uses it. Everything else it might call keeps the interface's no-op default, because a pair prompt
 * has no list to scan and no mirror to start.
 */
private class PairPromptActions(private val context: Context) : CastActions {
    override fun submitPairCode(code: String) = CastController.submitPairCode(context, code)
}
