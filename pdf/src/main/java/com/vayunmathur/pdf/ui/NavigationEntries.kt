package com.vayunmathur.pdf.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.vayunmathur.library.util.DetailPage
import com.vayunmathur.library.util.EntryProviderScope
import com.vayunmathur.library.util.FullscreenPage
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.pdf.Route
import com.vayunmathur.pdf.platform.PdfViewModel

/** Capture, captured-page and crop destinations. */
internal fun EntryProviderScope<Route>.captureEntries(
    viewModel: PdfViewModel,
    backStack: NavBackStack<Route>,
    leaveCapture: () -> Unit,
) {
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
}
