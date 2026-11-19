package com.vayunmathur.pdf

import com.vayunmathur.pdf.ui.InitialScreen
import androidx.core.net.toUri

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.vayunmathur.library.util.DetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.pdf.ui.CutGlueScreen
import com.vayunmathur.pdf.ui.SafePdfViewerScreen
import com.vayunmathur.pdf.ui.captureEntries
import com.vayunmathur.pdf.platform.PdfViewModel

/**
 * Navigation host for the PDF app: home, the viewer, capture (with its
 * page-preview and crop destinations) and cut-and-glue.
 *
 * A document handed in by an external intent starts as the sole viewer
 * destination over home, and leaving it finishes the host (the caller owns
 * the document task); a document opened or captured in-app returns to home.
 */
@Composable
fun Navigation(
    viewModel: PdfViewModel,
    initialRoute: Route = Route.Home,
    externalRoute: Route? = null,
    onExternalRouteConsumed: () -> Unit = {},
    startedWithIntent: Boolean = false,
    onFinish: () -> Unit = {},
) {
    val backStack = if (initialRoute == Route.Home) {
        rememberNavBackStack<Route>(Route.Home)
    } else {
        rememberNavBackStack<Route>(Route.Home, initialRoute)
    }

    LaunchedEffect(externalRoute) {
        val route = externalRoute ?: return@LaunchedEffect
        when (route) {
            is Route.Viewer -> backStack.reset(Route.Home, route)
            else -> backStack.reset(Route.Home, route)
        }
        onExternalRouteConsumed()
    }

    // Leaving capture for home (or for the finished document) drops the
    // in-memory page list, as the old CapturePdfScreen dispose did.
    fun leaveCapture() {
        viewModel.clearCapturedImages()
        backStack.pop()
    }

    MainNavigation(backStack) {
        entry<Route.Home>(metadata = ListPage()) {
            val filePickerLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.OpenDocument(),
            ) { uri ->
                uri?.let { backStack.add(Route.Viewer(it.toString())) }
            }
            InitialScreen(
                onOpenPdf = { filePickerLauncher.launch(arrayOf("application/pdf")) },
                onCapturePdf = { backStack.add(Route.Capture) },
                onCutGlue = { backStack.add(Route.CutGlue) },
            )
        }
        entry<Route.Viewer>(metadata = DetailPage()) { route ->
            SafePdfViewerScreen(
                uri = route.uri.toUri(),
                onBack = {
                    if (startedWithIntent) onFinish() else backStack.pop()
                },
            )
        }
        captureEntries(viewModel, backStack, leaveCapture = { leaveCapture() })
        entry<Route.CutGlue>(metadata = DetailPage()) {
            CutGlueScreen(onBack = { backStack.pop() })
        }
    }
}


