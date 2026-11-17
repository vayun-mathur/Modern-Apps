package com.vayunmathur.pdf.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalTextToolbar
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.ocr.OcrEngine
import com.vayunmathur.pdf.R
import com.vayunmathur.pdf.platform.SafePdfPage
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Text selection over a page's embedded text plus (for scanned pages) OCR text:
 *
 *  - **Long-press** selects the word under the finger; continuing the drag in the
 *    same motion extends the selection glyph-by-glyph.
 *  - The two **endpoint handles** can be dragged directly (no long-press) to grow
 *    or shrink the selection.
 *  - Selecting no longer auto-copies; the standard Android selection context
 *    menu (Copy / Select all) is shown via [LocalTextToolbar] — the real OS
 *    floating [android.view.ActionMode]. A quick tap dismisses the selection.
 */
@Composable
internal fun TextSelectionLayer(page: SafePdfPage, ch: Float, scale: Float, ocr: OcrEngine?) {
    val textClipLabel = stringResource(R.string.text)
    val clipboard = androidx.compose.ui.platform.LocalClipboard.current
    val scope = rememberCoroutineScope()
    val textToolbar = LocalTextToolbar.current

    val embedded = remember(page, ch, scale) { buildEmbeddedGlyphs(page, ch, scale) }
    val needsOcr = remember(embedded) {
        embedded.count { it.glyph.ch.isNotBlank() } < MIN_EMBEDDED_GLYPHS_FOR_TEXT
    }
    var ocrGlyphs by remember(page, ch, scale) { mutableStateOf<List<OrderedGlyph>>(emptyList()) }
    LaunchedEffect(page, ch, scale, needsOcr, ocr) {
        ocrGlyphs = emptyList()
        if (ocr != null && needsOcr) {
            ocrGlyphs = runCatching { ocrPageGlyphs(page, ch, scale, ocr) }.getOrDefault(emptyList())
        }
    }

    val glyphs = remember(embedded, ocrGlyphs) {
        (embedded + ocrGlyphs).sortedWith(compareBy({ it.orderY }, { it.orderX })).map { it.glyph }
    }
    if (glyphs.isEmpty()) return

    var range by remember(page) { mutableStateOf<IntRange?>(null) }
    // True while a handle/word drag is in progress → the OS menu is hidden until
    // the gesture settles (mirrors native selection behavior).
    var isAdjusting by remember(page) { mutableStateOf(false) }
    // This layer's position in the composition root, tracked so the menu's anchor
    // rect follows scroll/zoom; recomputed whenever the layout is re-positioned.
    var selCoords by remember(page) { mutableStateOf<LayoutCoordinates?>(null) }
    var rootAnchor by remember(page) { mutableStateOf(Offset.Zero) }
    // Whether *this* page currently owns the (window-global) toolbar, so pages
    // without a selection never hide another page's menu during scroll.
    var showing by remember(page) { mutableStateOf(false) }
    val latestGlyphs by rememberUpdatedState(glyphs)

    DisposableEffect(Unit) {
        onDispose { if (showing) textToolbar.hide() }
    }

    // Drive the real OS floating ActionMode: show it anchored to the selection's
    // bounding rect (in root coordinates) whenever there's a settled selection.
    LaunchedEffect(range, isAdjusting, rootAnchor, glyphs) {
        val r = range
        val coords = selCoords
        if (r == null || isAdjusting || coords == null || !coords.isAttached ||
            r.first !in glyphs.indices || r.last !in glyphs.indices) {
            if (showing) { textToolbar.hide(); showing = false }
            return@LaunchedEffect
        }
        var left = Float.MAX_VALUE
        var top = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (i in r) {
            val g = glyphs[i]
            if (g.left < left) left = g.left
            if (g.top < top) top = g.top
            if (g.right > right) right = g.right
            if (g.bottom > bottom) bottom = g.bottom
        }
        val tl = coords.localToRoot(Offset(left, top))
        val br = coords.localToRoot(Offset(right, bottom))
        val rootRect = Rect(minOf(tl.x, br.x), minOf(tl.y, br.y), maxOf(tl.x, br.x), maxOf(tl.y, br.y))
        textToolbar.showMenu(
            rect = rootRect,
            onCopyRequested = {
                val text = selectionText(glyphs, r)
                if (text.isNotBlank()) {
                    scope.launch {
                        clipboard.setClipEntry(
                            androidx.compose.ui.platform.ClipEntry(
                                android.content.ClipData.newPlainText(textClipLabel, text)
                            )
                        )
                    }
                }
                range = null
            },
            onSelectAllRequested = { range = 0..glyphs.lastIndex },
        )
        showing = true
    }

    Canvas(
        // Keyed on page only so zoom/scale changes don't cancel an in-progress
        // selection; latest glyphs are read via rememberUpdatedState.
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { c -> selCoords = c; rootAnchor = c.localToRoot(Offset.Zero) }
            .pointerInput(page) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)

                    // (A) If a selection exists and the finger lands on a handle,
                    //     drag it immediately (no long-press needed).
                    val existing = range
                    if (existing != null) {
                        val which = handleAt(latestGlyphs, down.position, existing)
                        if (which != null) {
                            down.consume()
                            isAdjusting = true
                            val anchor = if (which == 0) existing.last else existing.first
                            drag(down.id) { change ->
                                nearestGlyph(latestGlyphs, change.position)?.let { i ->
                                    range = minOf(i, anchor)..maxOf(i, anchor)
                                }
                                change.consume()
                            }
                            isAdjusting = false
                            return@awaitEachGesture
                        }
                    }

                    // (B) Otherwise classify the gesture: a long-press starts a word
                    //     selection; a quick tap dismisses; movement is a scroll (we
                    //     don't consume, so the parent list/zoom handles it).
                    val slop = viewConfiguration.touchSlop
                    val outcome = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        var moved = 0f
                        var last = down.position
                        var res = "hold"
                        while (true) {
                            val ev = awaitPointerEvent()
                            // A second finger means a pinch/zoom: bail without consuming
                            // so the parent transformable handles it even when the first
                            // finger is resting on text.
                            if (ev.changes.count { it.pressed } > 1) { res = "multitouch"; break }
                            val cpc = ev.changes.firstOrNull { it.id == down.id }
                            if (cpc == null) { res = "cancel"; break }
                            if (!cpc.pressed) { res = "tap"; break }
                            moved += (cpc.position - last).getDistance()
                            last = cpc.position
                            if (moved > slop) { res = "scroll"; break }
                        }
                        res
                    }

                    when (outcome) {
                        null -> {
                            // Long-press: select the word, then allow drag-to-extend.
                            val i = nearestGlyph(latestGlyphs, down.position, SELECT_HIT_PX)
                            if (i != null) {
                                down.consume()
                                val w = wordRangeAt(latestGlyphs, i)
                                range = w
                                // Keep the whole first word selected; extend outward
                                // toward the finger in either direction.
                                val wLo = w.first
                                val wHi = w.last
                                isAdjusting = true
                                drag(down.id) { change ->
                                    nearestGlyph(latestGlyphs, change.position)?.let { j ->
                                        range = minOf(j, wLo)..maxOf(j, wHi)
                                    }
                                    change.consume()
                                }
                                isAdjusting = false
                            }
                        }
                        "tap" -> if (range != null) range = null
                        else -> { /* scroll / multitouch / cancel: don't consume, let the parent scroll or pinch-zoom */ }
                    }
                }
            },
    ) {
        val r = range ?: return@Canvas
        val g = latestGlyphs
        val quad = Path()
        for (i in r) {
            if (i !in g.indices) continue
            val gg = g[i]
            quad.reset()
            quad.moveTo(gg.p0.x, gg.p0.y)
            quad.lineTo(gg.p1.x, gg.p1.y)
            quad.lineTo(gg.p2.x, gg.p2.y)
            quad.lineTo(gg.p3.x, gg.p3.y)
            quad.close()
            drawPath(path = quad, color = Color(0x553F51B5))
        }
        if (r.first in g.indices && r.last in g.indices) {
            drawCircle(Color(0xFF3F51B5), radius = 16f, center = g[r.first].p3)
            drawCircle(Color(0xFF3F51B5), radius = 16f, center = g[r.last].p2)
        }
    }
}
