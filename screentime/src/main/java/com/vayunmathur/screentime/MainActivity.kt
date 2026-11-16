package com.vayunmathur.screentime

import android.content.Intent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.screentime.platform.ScreenTimeViewModel

/**
 * The single Screen Time host, reached from Settings (no launcher icon).
 *
 * Every former activity entry point is an alias onto this host (see the
 * manifest): the Settings homepage rows for the dashboard and Wind Down and
 * the per-app details action all resolve here, and [routeFor] turns the
 * arriving intent into the start destination. Intents that arrive while the
 * host is already running are re-routed through [pendingRoute] instead of
 * stacking a second activity. FrictionActivity stays a separate activity:
 * it is a system-triggered surface shown over the app being opened.
 */
class MainActivity : MaActivity() {

    private val viewModel: ScreenTimeViewModel by viewModels()

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
        const val EXTRA_DETAILS_PACKAGE = "com.vayunmathur.screentime.extra.DETAILS_PACKAGE"

        const val ACTION_DASHBOARD = "com.vayunmathur.screentime.action.DASHBOARD"
        const val ACTION_APP_DETAILS = "com.vayunmathur.screentime.action.APP_DETAILS"
        const val ACTION_BEDTIME_SETTINGS = "android.settings.BEDTIME_SETTINGS"

        // String literal, not Intent.ACTION_SHOW_SUSPENDED_APP_DETAILS: the constant is absent
        // from this SDK's Intent, and the action string is stable platform surface.
        const val ACTION_SHOW_SUSPENDED_APP_DETAILS =
            "android.intent.action.SHOW_SUSPENDED_APP_DETAILS"

        /** The route an entry intent names; the dashboard is the default landing. */
        fun routeFor(intent: Intent?): Route = when (intent?.action) {
            ACTION_BEDTIME_SETTINGS -> Route.WindDown
            ACTION_APP_DETAILS -> Route.AppDetails(detailsPackage(intent) ?: "")
            ACTION_SHOW_SUSPENDED_APP_DETAILS -> Route.AppDetails(detailsPackage(intent) ?: "")
            else -> detailsPackage(intent)?.let { Route.AppDetails(it) } ?: Route.Dashboard
        }

        private fun detailsPackage(intent: Intent?): String? {
            intent ?: return null
            if (intent.action == ACTION_SHOW_SUSPENDED_APP_DETAILS) {
                intent.data?.schemeSpecificPart?.removePrefix("package:")?.let { return it }
            }
            return intent.getStringExtra(EXTRA_DETAILS_PACKAGE)
        }
    }
}
