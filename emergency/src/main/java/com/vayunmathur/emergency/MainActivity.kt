package com.vayunmathur.emergency

import android.content.Intent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vayunmathur.emergency.platform.EmergencyViewModel
import com.vayunmathur.library.ui.MaActivity

/**
 * The single info host, reached from Settings and the lock screen.
 *
 * Every former activity entry point is an alias onto this host (see the
 * manifest): the emergency-assistance action lands on [Route.ViewInfo]
 * and the edit actions on [Route.EditInfo], and [routeFor] turns the
 * arriving intent into the start destination. Intents that arrive while
 * the host is already running are re-routed through [pendingRoute]
 * instead of stacking a second activity. The host is showWhenLocked and
 * directBootAware so the view card keeps working over the lock screen
 * before unlock, exactly as ViewInfoActivity did; the editor is only
 * ever launched from Settings after unlock. The SOS countdown stays in
 * SosActivity: its singleInstance, turnScreenOn, noHistory and
 * excludeFromRecents semantics must not leak into this host, and this
 * host's singleTask Settings task must not leak into the countdown.
 */
class MainActivity : MaActivity() {

    private val viewModel: EmergencyViewModel by viewModels()

    private var initialRoute: Route = Route.ViewInfo
    private var pendingRoute by mutableStateOf<Route?>(null)

    override fun onCreateBeforeContent() {
        initialRoute = routeFor(intent)
    }

    @Composable
    override fun Content() {
        Navigation(
            viewModel = viewModel,
            initialRoute = initialRoute,
            externalRoute = pendingRoute,
            onExternalRouteConsumed = { pendingRoute = null },
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingRoute = routeFor(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh()
    }

    companion object {
        const val ACTION_EDIT_EMERGENCY_INFO = "android.settings.EDIT_EMERGENCY_INFO"
        const val ACTION_EDIT_MEDICAL_INFO = "android.emergency.EDIT_MEDICAL_INFO"
        const val ACTION_EMERGENCY_ASSISTANCE = "android.telephony.action.EMERGENCY_ASSISTANCE"

        /** The route an entry intent names; the view card is the default landing. */
        fun routeFor(intent: Intent?): Route = when (intent?.action) {
            ACTION_EDIT_EMERGENCY_INFO, ACTION_EDIT_MEDICAL_INFO -> Route.EditInfo
            else -> Route.ViewInfo
        }
    }
}
