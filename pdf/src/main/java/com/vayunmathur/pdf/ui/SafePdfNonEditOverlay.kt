package com.vayunmathur.pdf.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.vayunmathur.library.ocr.OcrEngine
import com.vayunmathur.pdf.platform.SafeLink
import com.vayunmathur.pdf.platform.SafePdfPage

/** Read-only overlays: text selection underneath so link Boxes get hit-test priority. */
@Composable
internal fun NonEditOverlay(
    page: SafePdfPage,
    links: List<SafeLink>,
    ch: Float,
    scale: Float,
    ocr: OcrEngine?,
    onLinkPage: (Int) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    // Text selection underneath so link Boxes get hit-test priority.
    TextSelectionLayer(page = page, ch = ch, scale = scale, ocr = ocr)
    for (link in links) {
        val leftDp = with(density) { (link.x0 * scale).toDp() }
        val topDp = with(density) { (ch - link.y1 * scale).toDp() }
        // A link rect can sit at/above the page top or off the left edge, making these
        // negative; use offset (which permits it) rather than padding (which throws).
        // Coerce the size non-negative in case a malformed link has x1<x0 or y1<y0.
        val wDp = with(density) { ((link.x1 - link.x0) * scale).toDp() }.coerceAtLeast(0.dp)
        val hDp = with(density) { ((link.y1 - link.y0) * scale).toDp() }.coerceAtLeast(0.dp)
        Box(
            Modifier
                .offset(x = leftDp, y = topDp)
                .size(wDp, hDp)
                .clickable {
                    if (link.uri.isNotEmpty()) {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_VIEW, link.uri.toUri())
                            )
                        }
                    } else if (link.destPage >= 0) {
                        onLinkPage(link.destPage)
                    }
                },
        )
    }
}
