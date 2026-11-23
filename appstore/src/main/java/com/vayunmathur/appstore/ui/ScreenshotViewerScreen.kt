package com.vayunmathur.appstore.ui
import com.vayunmathur.appstore.R

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntSize
import com.vayunmathur.library.image.compose.AsyncImage
import com.vayunmathur.library.image.compose.AsyncImageState
import com.vayunmathur.library.ui.AppScaffold
import com.vayunmathur.library.ui.FadeVisibility
import com.vayunmathur.library.ui.IconClose
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.OverlayAction
import com.vayunmathur.library.ui.R as UiR
import com.vayunmathur.library.ui.Spacing
import com.vayunmathur.library.ui.Text
import com.vayunmathur.library.ui.TopAppBarOverlay
import com.vayunmathur.library.ui.ZoomState
import com.vayunmathur.library.ui.zoomGestures

/**
 * Full-screen viewer for an app's listing screenshots (#918), opened from the strip on the
 * app page at the screenshot that was tapped.
 *
 * Swipe between screenshots; pinch, double-tap and drag to zoom and pan (the shared
 * [zoomGestures], same as the Photos viewer). A zoomed screenshot pans first and hands the
 * swipe to the pager once it reaches its edge, and it snaps back to fit when it leaves the
 * screen. Tap toggles the close button and counter; system back also closes.
 *
 * Images sit inside the system-bar insets, so nothing is drawn under a cutout or the
 * navigation bar at fit scale in either orientation, while the black backdrop runs edge to
 * edge. The URL is the same model the strip loaded, so the bitmap usually comes straight
 * from the memory cache.
 */
@Composable
fun ScreenshotViewerScreen(
    appName: String,
    urls: List<String>,
    initialIndex: Int,
    onClose: () -> Unit,
) {
    if (urls.isEmpty()) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    val pagerState = rememberPagerState(initialPage = initialIndex.coerceIn(urls.indices)) { urls.size }
    var chromeVisible by remember { mutableStateOf(true) }
    val closeLabel = stringResource(UiR.string.close)

    AppScaffold(topBar = {}) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize().testTag(VIEWER_PAGER_TAG),
                beyondViewportPageCount = 1,
            ) { page ->
                ZoomableScreenshot(
                    url = urls[page],
                    description = stringResource(
                        R.string.screenshot_description,
                        appName,
                        page + 1,
                        urls.size,
                    ),
                    isSettledPage = pagerState.settledPage == page,
                    padding = padding,
                    onTap = { chromeVisible = !chromeVisible },
                )
            }
            FadeVisibility(
                visible = chromeVisible,
                modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth(),
            ) {
                TopAppBarOverlay(
                    title = {
                        Text(
                            stringResource(
                                R.string.screenshot_counter,
                                pagerState.currentPage + 1,
                                urls.size,
                            ),
                            color = Color.White,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier
                                .padding(start = Spacing.lg)
                                .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(Spacing.lg))
                                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
                        )
                    },
                    actions = listOf(OverlayAction({ IconClose() }, closeLabel, onClose)),
                )
            }
        }
    }
}

/** One page: the screenshot fitted inside the insets, with its own zoom. */
@Composable
private fun ZoomableScreenshot(
    url: String,
    description: String,
    isSettledPage: Boolean,
    padding: PaddingValues,
    onTap: () -> Unit,
) {
    val zoom = remember { mutableStateOf(ZoomState()) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    var state by remember(url) { mutableStateOf<AsyncImageState>(AsyncImageState.Loading) }
    // A page swiped away comes back at fit, not where it was left zoomed in.
    LaunchedEffect(isSettledPage) {
        if (!isSettledPage) zoom.value = ZoomState()
    }
    Box(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .onSizeChanged { size = it }
            // On the page rather than the bitmap, which only appears once decoded.
            .semantics { contentDescription = description }
            .zoomGestures(
                zoom = zoom,
                containerSize = { size },
                onZoomChange = { zoom.value = it },
                onTap = { onTap() },
            ),
        contentAlignment = Alignment.Center,
    ) {
        AsyncImage(
            model = url,
            contentDescription = null,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // Read inside the layer block: a pinch re-runs only the transform.
                    val z = zoom.value
                    scaleX = z.scale
                    scaleY = z.scale
                    translationX = z.offset.x
                    translationY = z.offset.y
                },
            contentScale = ContentScale.Fit,
            onState = { state = it },
        )
        when (state) {
            // Image-loading placeholder: empty box, no spinner icon.
            AsyncImageState.Loading -> Box(Modifier.fillMaxSize())
            is AsyncImageState.Error -> Text(
                stringResource(R.string.screenshot_load_failed),
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> Unit
        }
    }
}

/** Test tag on the viewer's pager, for swipe tests. */
internal const val VIEWER_PAGER_TAG = "screenshot-viewer-pager"
