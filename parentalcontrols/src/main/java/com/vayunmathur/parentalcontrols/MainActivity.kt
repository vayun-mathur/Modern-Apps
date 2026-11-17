package com.vayunmathur.parentalcontrols

import android.content.Context
import android.content.Intent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.parentalcontrols.platform.SupervisionViewModel

/**
 * The single Parental Controls host, launched from Settings (MAOS redirects
 * the platform supervision entry here - see the Settings patch in
 * vendor/modern-apps).
 *
 * Every former editor activity is an alias onto this host (see the
 * manifest): the Settings supervision rows for bedtime, app limits,
 * downtime, school time and the daily limit keep their own labels,
 * permissions, intent-filters and summary meta-data and point here, and
 * [routeFor] turns the arriving intent into the start destination (a gated
 * editor starts at its PIN gate, never at the editor). Intents that arrive
 * while the host is running are re-routed through [pendingRoute].
 * SetupActivity, PinGateActivity and the five lock activities stay
 * activities: they are system-facing surfaces with activity-result or
 * no-UI contracts outside this navigation graph.
 */
class MainActivity : MaActivity() {

    private val viewModel: SupervisionViewModel by viewModels()

    private var initialRoute: Route = Route.Dashboard
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
            onFinish = { finish() },
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingRoute = routeFor(intent)
    }

    /** Intent actions and routing helpers for the Parental Controls host activity. */
    companion object {
        const val ACTION_DASHBOARD = "com.vayunmathur.parentalcontrols.action.DASHBOARD"
        const val ACTION_BEDTIME = "com.vayunmathur.parentalcontrols.action.BEDTIME"
        const val ACTION_APP_LIMITS = "com.vayunmathur.parentalcontrols.action.APP_LIMITS"
        const val ACTION_DOWNTIME = "com.vayunmathur.parentalcontrols.action.DOWNTIME"
        const val ACTION_SCHOOL_TIME = "com.vayunmathur.parentalcontrols.action.SCHOOL_TIME"
        const val ACTION_DAILY_LIMIT = "com.vayunmathur.parentalcontrols.action.DAILY_LIMIT"

        private const val ACTION_INTERSTITIAL_SCREEN =
            "android.app.supervision.action.INTERSTITIAL_SCREEN"
        private const val ACTION_MANAGE_SUPERVISION_APP_SETTINGS =
            "android.settings.MANAGE_SUPERVISION_APP_SETTINGS"

        /** An explicit intent to the host at the PIN-setup destination. */
        fun pinSetupIntent(context: Context): Intent =
            Intent(context, MainActivity::class.java)
                .putExtra(EXTRA_PIN_SETUP, true)

        private const val EXTRA_PIN_SETUP = "com.vayunmathur.parentalcontrols.extra.PIN_SETUP"

        /** The route an entry intent names; the dashboard is the default landing. */
        fun routeFor(intent: Intent?): Route = when (intent?.action) {
            ACTION_BEDTIME, ACTION_MANAGE_SUPERVISION_APP_SETTINGS -> Route.Bedtime
            ACTION_APP_LIMITS -> Route.AppLimits
            ACTION_DOWNTIME -> Route.Downtime
            ACTION_SCHOOL_TIME -> Route.SchoolTime
            ACTION_DAILY_LIMIT -> Route.DailyLimit
            else -> if (intent?.getBooleanExtra(EXTRA_PIN_SETUP, false) == true) {
                Route.PinSetup
            } else {
                Route.Dashboard
            }
        }
    }
}
