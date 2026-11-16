package com.vayunmathur.pdf

import android.content.Intent
import android.net.Uri
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import com.vayunmathur.library.ui.Button
import com.vayunmathur.library.ui.MaActivity
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.vayunmathur.pdf.R
import androidx.compose.ui.unit.dp
import com.vayunmathur.pdf.util.PdfViewModel

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

@Composable
fun InitialScreen(onOpenPdf: () -> Unit, onCapturePdf: () -> Unit, onCutGlue: () -> Unit) {
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Button(onClick = onOpenPdf, Modifier.padding(16.dp)) {
                Text(stringResource(R.string.open_pdf))
            }
            Button(onClick = onCapturePdf, Modifier.padding(16.dp)) {
                Text(stringResource(R.string.capture_pdf_title))
            }
            Button(onClick = onCutGlue, Modifier.padding(16.dp)) {
                Text(stringResource(R.string.pdf_cut_and_glue))
            }
        }
    }
}
