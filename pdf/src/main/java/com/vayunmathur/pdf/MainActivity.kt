package com.vayunmathur.pdf

import android.content.Intent
import android.net.Uri
import androidx.activity.viewModels
import com.vayunmathur.library.ui.MaActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.vayunmathur.pdf.platform.PdfViewModel

/**
 * The single PDF host.
 *
 * External VIEW intents arrive as a [Route.Viewer] start destination (see
 * [routeFor]); leaving that viewer finishes the host because the caller
 * owns the document task, while documents opened in-app return to home.
 * Intents that arrive while the host is running are re-routed through
 * [pendingRoute] instead of stacking a second activity.
 */
class MainActivity : MaActivity() {
    private val pdfViewModel: PdfViewModel by viewModels()
    private var intentData: Uri? = null

    private var initialRoute: Route = Route.Home
    private var pendingRoute by mutableStateOf<Route?>(null)
    private var startedWithIntent = false

    override fun onCreateBeforeContent() {
        intentData = intent.data
        startedWithIntent = intentData != null
        initialRoute = routeFor(intent)
    }

    @Composable
    override fun Content() {
        Navigation(
            viewModel = pdfViewModel,
            initialRoute = initialRoute,
            externalRoute = pendingRoute,
            onExternalRouteConsumed = { pendingRoute = null },
            startedWithIntent = startedWithIntent,
            onFinish = { finish() },
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let {
            startedWithIntent = true
            pendingRoute = routeFor(intent)
        }
    }

    companion object {
        /** The route an entry intent names; home is the default landing. */
        fun routeFor(intent: Intent?): Route =
            intent?.data?.let { Route.Viewer(it.toString()) } ?: Route.Home
    }
}
