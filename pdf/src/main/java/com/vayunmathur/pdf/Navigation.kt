package com.vayunmathur.pdf

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vayunmathur.library.util.DetailPage
import com.vayunmathur.library.util.FullscreenPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.pdf.ui.CapturePdfScreen
import com.vayunmathur.pdf.ui.CropScreen
import com.vayunmathur.pdf.ui.CutGlueScreen
import com.vayunmathur.pdf.ui.SafePdfViewerScreen
import com.vayunmathur.pdf.util.PdfViewModel

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
                uri = Uri.parse(route.uri),
                onBack = {
                    if (startedWithIntent) onFinish() else backStack.pop()
                },
            )
        }
        entry<Route.Capture>(metadata = DetailPage()) {
            CapturePdfScreen(
                viewModel = viewModel,
                selectedIndex = null,
                onBack = { leaveCapture() },
                onPdfCreated = { uri ->
                    viewModel.clearCapturedImages()
                    backStack.reset(Route.Home, Route.Viewer(uri.toString()))
                },
                onSelectPage = { index ->
                    if (index != null) backStack.add(Route.CapturePage(index))
                },
                onOpenCrop = { index -> backStack.add(Route.Crop(index)) },
            )
        }
        entry<Route.CapturePage>(metadata = DetailPage()) { route ->
            CapturePdfScreen(
                viewModel = viewModel,
                selectedIndex = route.index,
                onBack = { backStack.pop() },
                onPdfCreated = { uri ->
                    viewModel.clearCapturedImages()
                    backStack.reset(Route.Home, Route.Viewer(uri.toString()))
                },
                onSelectPage = { index ->
                    if (index == null) {
                        backStack.pop()
                    } else if (index != route.index) {
                        backStack.setLast(Route.CapturePage(index))
                    }
                },
                onOpenCrop = { index -> backStack.add(Route.Crop(index)) },
            )
        }
        entry<Route.Crop>(metadata = FullscreenPage()) { route ->
            val images by viewModel.capturedImages.collectAsState()
            val image = images.getOrNull(route.index)
            if (image != null) {
                CropScreen(
                    uri = image.uri,
                    initialQuadrilateral = image.quadrilateral,
                    onCropDone = { newQuadrilateral ->
                        viewModel.updateQuadrilateral(route.index, newQuadrilateral)
                        backStack.pop()
                    },
                    onBack = { backStack.pop() },
                )
            } else {
                LaunchedEffect(route.index) { backStack.pop() }
            }
        }
        entry<Route.CutGlue>(metadata = DetailPage()) {
            CutGlueScreen(onBack = { backStack.pop() })
        }
    }
}
