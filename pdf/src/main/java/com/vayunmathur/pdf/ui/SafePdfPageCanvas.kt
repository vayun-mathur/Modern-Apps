package com.vayunmathur.pdf.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.vayunmathur.library.log.Log
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Text
import com.vayunmathur.pdf.R
import com.vayunmathur.pdf.platform.SafePdfPage

/**
 * One page drawn at fit-to-width scale on its white "paper", with [overlays] stacked on top
 * once the page is known — they get the measured canvas width/height and the page-space to
 * pixel [scale] they need to position themselves.
 *
 * Takes a decoded [SafePdfPage] rather than a document handle, so a `@Preview` can render a
 * hand-built page (the primitives are plain data; only producing them needs the native
 * renderer).
 *
 * A null [page] with a non-null [failureReason] is a page that will never arrive, and shows a
 * placeholder instead of a spinner. Without that distinction a decode failure, a caught native
 * panic and "still loading" all present as the same indefinite spinner.
 */
@Composable
fun SafePdfPageCanvas(
    page: SafePdfPage?,
    modifier: Modifier = Modifier,
    placeholderRatio: Float? = null,
    failureReason: String? = null,
    overlays: @Composable BoxWithConstraintsScope.(cw: Float, ch: Float, scale: Float) -> Unit = { _, _, _ -> },
) {
    // A wrong placeholder ratio makes the item change height the moment the page decodes,
    // which shifts every item below it. Use the page's real ratio when known (see
    // SafePdfDocument.knownAspectRatio) and only fall back to letter-portrait when it isn't.
    val ratio = when {
        page != null && page.height > 0f -> page.width / page.height
        placeholderRatio != null && placeholderRatio > 0f -> placeholderRatio
        else -> 612f / 792f
    }

    BoxWithConstraints(
        modifier
            .fillMaxWidth()
            .padding(4.dp)
            .aspectRatio(ratio)
            .background(if (page == null) MaterialTheme.colorScheme.surfaceVariant else Color.White)
            .clipToBounds()
    ) {
        if (page == null || page.width <= 0f) {
            if (failureReason != null) {
                // A page that will never arrive. A spinner here is a lie that never resolves,
                // and is why this whole class of failure went unreported: the reason is logged
                // for the bug report, the user just needs to know this page is not coming.
                Text(
                    text = stringResource(R.string.pdf_page_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )
            } else {
                Text("Loading\u2026", modifier = Modifier.align(Alignment.Center))
            }
            return@BoxWithConstraints
        }
        val decoded = page

        // Render the (static) page into its own graphics layer so that overlay
        // redraws while drawing/dragging don't replay every page primitive.
        Canvas(Modifier.fillMaxSize().graphicsLayer { clip = true }) {
            // One unrenderable primitive must not take down the frame, nor leave the live
            // canvas holding open save/saveLayer levels — that would corrupt every sibling
            // drawn after it. Restoring to the entry count unwinds clips, groups and
            // soft-mask layers alike, compositing each layer in inner-to-outer order.
            val base = drawContext.canvas.nativeCanvas.saveCount
            try {
                drawSafePage(decoded)
            } catch (expected: RuntimeException) {
                Log.status("SafePdfViewer", "drawSafePage failed", expected)
            } finally {
                drawContext.canvas.nativeCanvas.restoreToCount(base)
            }
        }

        val cw = constraints.maxWidth.toFloat()
        overlays(cw, constraints.maxHeight.toFloat(), cw / decoded.width)
    }
}
