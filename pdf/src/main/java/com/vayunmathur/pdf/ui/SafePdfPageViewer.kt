package com.vayunmathur.pdf.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.TransformableState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.ocr.OcrEngine
import com.vayunmathur.library.ui.CircularProgressIndicator
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.pdf.R
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Zoomable page viewer shared by the compact body and the Expanded viewer slot.
 *
 * One vertical truth: every page slot is always laid out, and [scroll] holds the
 * single document offset driven by finger drags, pinch pans, the scrubber and
 * jumps. Slots outside the viewport skip the native decode and draw a cheap
 * placeholder box instead, so large documents stay light without a lazy list.
 */
@Composable
internal fun SafePdfPageViewer(
    modifier: Modifier = Modifier,
    bundle: SafePdfDocumentBundle,
    state: SafePdfViewerState,
    zoom: SafePdfZoomState,
    transform: TransformableState,
    scroll: SafePdfScrollState,
    launchers: SafePdfLauncherSet,
) {
    val scope = rememberCoroutineScope()
    val document = bundle.document
    val ocr: OcrEngine = bundle.ocrEngine

    // Double-tap to zoom in/out with animation centered on the tap.
    val latestZoom by rememberUpdatedState(zoom.zoom)
    val latestPan by rememberUpdatedState(zoom.pan)
    val latestViewport by rememberUpdatedState(zoom.viewportSize)
    val latestEditMode by rememberUpdatedState(state.editMode)
    val latestTool by rememberUpdatedState(state.tool)

    scroll.pageCount = state.pageCount
    val doc = bundle.document
    scroll.ratioAt = { doc?.knownAspectRatio(it) }
    // Page-granular: recompose the list only when the visible set changes.
    val visible by remember { derivedStateOf { scroll.visibleRange(zoom.zoom) } }

    // Plain outer box: the zoom transform below must not scale the page
    // scrubber, so the scrubber lives here as an unzoomed overlay.
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .clipToBounds()
                .onSizeChanged {
                    zoom.viewportSize = it
                    scroll.viewportSize = it
                    scroll.scrollTo(scroll.scrollPx, zoom.zoom)
                }
                // Track finger count in the Initial pass (before scrolling reacts
                // in the Main pass) so multi-touch disables scroll.
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            val e = awaitPointerEvent(PointerEventPass.Initial)
                            zoom.multiTouch = e.changes.count { it.pressed } >= 2
                        }
                    }
                }
                // Double-tap to zoom in/out, centered on the tap with animation.
                // Disabled in edit mode (except SELECT) to avoid conflicting with annotation taps.
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { tapOffset ->
                            if (latestEditMode && latestTool != EditTool.SELECT) return@detectTapGestures
                            val vp = latestViewport
                            if (vp == IntSize.Zero) return@detectTapGestures
                            val z0 = latestZoom.coerceAtLeast(0.001f)
                            val targetZoom = if (latestZoom < DOUBLE_TAP_ZOOM_THRESHOLD) DOUBLE_TAP_ZOOM else 1f
                            val zoomDelta = targetZoom / z0
                            val maxX = maxPanX(vp.width, targetZoom)
                            val targetX = if (targetZoom <= 1f) {
                                0f
                            } else {
                                latestPan.x + (tapOffset.x - vp.width / 2f - latestPan.x) * (1f - zoomDelta)
                            }.coerceIn(-maxX, maxX)
                            scope.launch {
                                val startZoom = latestZoom
                                val startX = latestPan.x
                                val startScroll = scroll.scrollPx
                                val docY = startScroll + tapOffset.y / startZoom.coerceAtLeast(0.01f)
                                val anim = Animatable(0f)
                                anim.animateTo(1f, animationSpec = tween(durationMillis = 250)) {
                                    val t = value
                                    val z = startZoom + (targetZoom - startZoom) * t
                                    zoom.setZoomPan(z, Offset(startX + (targetX - startX) * t, 0f))
                                    scroll.scrollTo(docY - tapOffset.y / z.coerceAtLeast(0.01f), z)
                                }
                                zoom.setZoomPan(targetZoom, Offset(targetX, 0f))
                                scroll.scrollTo(docY - tapOffset.y / targetZoom.coerceAtLeast(0.01f), targetZoom)
                            }
                        }
                    )
                }
                // Above the graphicsLayer so gesture coordinates are plain viewport pixels.
                .transformable(transform, enabled = !state.editMode || state.tool == EditTool.SELECT)
                .scrollable(
                    orientation = Orientation.Vertical,
                    enabled = !zoom.multiTouch,
                    state = rememberScrollableState { delta -> scroll.scrollByScreen(delta, zoom.zoom) },
                )
                .graphicsLayer {
                    scaleX = zoom.zoom
                    scaleY = zoom.zoom
                    translationX = zoom.pan.x
                    // Issue #321: a document shorter than the viewport (e.g. a
                    // single page) used to pin to the top with dead space below.
                    // Center it vertically instead. This is purely visual: when
                    // there is slack, range() is 0 so scroll stays 0 and every
                    // scrollTo clamps to it; when content overflows, the slack
                    // is <= 0 and the offset vanishes.
                    val slack = size.height - scroll.contentHeightPx() * zoom.zoom
                    translationY = -scroll.scrollPx * zoom.zoom + (slack / 2f).coerceAtLeast(0f)
                    // Pivot on the top edge: keeps the first visible content anchored
                    // to the top of the screen across zoom changes.
                    transformOrigin = TransformOrigin(0.5f, 0f)
                }
                // Lay the full document out at its content height inside a
                // viewport-sized box; the graphicsLayer above pans across it.
                .layout { measurable, constraints ->
                    val h = scroll.contentHeightPx().roundToInt().coerceAtLeast(0)
                    val placeable = if (h > 0) {
                        measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                    } else {
                        measurable.measure(constraints)
                    }
                    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
                },
        ) {
            when (val loadState = bundle.loadState) {
                LoadState.Loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                is LoadState.Error -> Text(
                    text = androidx.compose.ui.res.stringResource(
                        if (loadState.notPdf) R.string.safe_pdf_not_a_pdf else R.string.safe_pdf_error
                    ),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
                is LoadState.Loaded -> Column(Modifier.fillMaxWidth()) {
                    for (index in 0 until state.pageCount) {
                        if (index in visible) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .onSizeChanged { scroll.setMeasuredHeight(index, it.height) }
                            ) {
                                val pageHighlights = state.matches.filter { it.first == index }.map { it.second }
                                val current = state.matches.getOrNull(state.matchIndex)
                                val currentHighlight = if (current?.first == index) current.second else null
                                SafePdfPageItem(
                                    document = loadState.document,
                                    index = index,
                                    version = (state.pageVersions[index] ?: 0) + state.pageMgrVersion,
                                    ocr = ocr,
                                    editMode = state.editMode,
                                    tool = state.tool,
                                    shape = state.shape,
                                    markup = state.markup,
                                    color = state.drawColor,
                                    strokeWidth = state.strokeWidth,
                                    selected = state.selected?.takeIf { it.first == index }?.second,
                                    highlights = pageHighlights,
                                    currentHighlight = currentHighlight,
                                    scope = state.scope,
                                    onSelect = { annotId -> state.selected = annotId?.let { index to it } },
                                    onEdited = { state.markEdited(index) },
                                    onCreated = { id -> state.registerCreated(index, id) },
                                    onMoved = { id, oldR, newR -> state.registerMoved(index, id, oldR, newR) },
                                    onFormEdited = { state.markEdited(index); state.nonUndoDirty = true },
                                    onPageWidth = { w -> zoom.onPageWidth(w) },
                                    onLinkPage = { p ->
                                        scroll.scrollToPage(p.coerceIn(0, (state.pageCount - 1).coerceAtLeast(0)), zoom.zoom)
                                    },
                                    textSession = state.textSession?.takeIf { it.page == index },
                                    onStartText = { s -> state.startText(document, s) },
                                    onTextChange = { v -> state.textSession = state.textSession?.copy(value = v) },
                                    onCommitText = { state.commitTextAndClear(document) },
                                    onRequestImage = { pt -> state.imageTarget = index to pt; launchers.pickImage() },
                                    onRequestNote = { pt -> state.pendingNote = index to pt },
                                    onRequestCallout = { a, b -> state.pendingCallout = Triple(index, a, b) },
                                    polyDraft = state.polyDraft?.takeIf { it.page == index },
                                    onAddPolyPoint = { pt -> state.addPolyPoint(index, pt) },
                                )
                            }
                        } else {
                            // Cheap placeholder: same footprint, no native decode.
                            val r = loadState.document.knownAspectRatio(index)?.takeIf { it > 0f }
                                ?: FALLBACK_PAGE_RATIO
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .onSizeChanged { scroll.setMeasuredHeight(index, it.height) }
                                    .padding(4.dp)
                                    .aspectRatio(r)
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            )
                        }
                    }
                }
            }
        }
        // Draggable page-number scrubber on the right edge; always visible.
        if (state.pageCount > 1) {
            PdfPageScroller(
                scroll = scroll,
                zoom = zoom,
                pageCount = state.pageCount,
            )
        }
    }
}
