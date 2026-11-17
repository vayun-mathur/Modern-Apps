package com.vayunmathur.pdf.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.ui.DrawerValue
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.rememberDrawerState
import com.vayunmathur.pdf.ui.InitialScreen
import com.vayunmathur.pdf.platform.PdfPrimitive
import com.vayunmathur.pdf.platform.SafeOutlineItem
import com.vayunmathur.pdf.platform.SafePdfPage

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/** Expanded desktop window for the wide viewer+tools layout. */
private const val EXPANDED = "spec:width=1280dp,height=800dp,dpi=420"

// --- Sample document -------------------------------------------------------
//
// Pages are built as literal [SafePdfPage]s. That works because a decoded page is plain
// data — a list of text runs, fills and strokes in PDF page space — and only *producing*
// it needs the Rust renderer, which Layoutlib cannot load. Drawing it is ordinary Compose,
// so these previews show real pages rather than a placeholder.

/** US Letter, in PDF points. Page space has its origin at the bottom-left. */
private const val PAGE_W = 612f
private const val PAGE_H = 792f

private val INK = 0xFF1B1B1B.toInt()
private val MUTED = 0xFF6E6E6E.toInt()
private val ACCENT = 0xFF2E5AAC.toInt()
private val RULE = 0xFFC4C4C4.toInt()

private fun line(
    y: Float,
    text: String,
    size: Float = 11f,
    color: Int = INK,
    bold: Boolean = false,
    x: Float = 64f,
) = PdfPrimitive.Text(Offset(x, y), size, color, text, isBold = bold)

private fun rule(y: Float, x0: Float = 64f, x1: Float = 548f) =
    PdfPrimitive.StrokePath(RULE, 1f, FloatArray(0), 0f, listOf(Offset(x0, y), Offset(x1, y)))

private fun bar(x0: Float, y0: Float, x1: Float, y1: Float, color: Int) =
    PdfPrimitive.FillPath(
        color,
        false,
        listOf(listOf(Offset(x0, y0), Offset(x1, y0), Offset(x1, y1), Offset(x0, y1))),
    )

private fun textPage(title: String, folio: String, body: List<String>): SafePdfPage {
    val prims = mutableListOf<PdfPrimitive>()
    prims += line(716f, title, size = 21f, bold = true)
    prims += rule(702f)
    var y = 670f
    for (paragraph in body) {
        prims += line(y, paragraph)
        y -= 19f
    }
    prims += line(64f, folio, size = 9f, color = MUTED)
    return SafePdfPage(PAGE_W, PAGE_H, prims)
}

private val Page1 = textPage(
    title = "Annual Field Report",
    folio = "1",
    body = listOf(
        "Prepared for the regional survey committee.",
        "",
        "This report summarises the observations collected over the",
        "2025 season across the fourteen monitored sites, together",
        "with the analysis method used to reconcile the readings",
        "taken by the two independent teams.",
        "",
        "Section 1 states the scope and the definitions that the rest",
        "of the document depends on. Section 2 describes collection",
        "and analysis. Section 3 presents the results, and Section 4",
        "sets out the recommendations arising from them.",
        "",
        "All figures are reported to two decimal places. Where a",
        "site was inaccessible for part of the season, the affected",
        "interval is marked in the summary tables rather than",
        "interpolated.",
    ),
)

private val Page2 = run {
    val page = textPage(
        title = "2. Method",
        folio = "2",
        body = listOf(
            "Readings were taken at fortnightly intervals. Each site",
            "was visited by both teams within the same 48 hours so",
            "that the paired measurements remain comparable.",
        ),
    )
    val chart = mutableListOf<PdfPrimitive>()
    chart += line(560f, "Readings per site", size = 12f, bold = true)
    val heights = listOf(96f, 148f, 132f, 178f, 118f, 164f, 88f)
    heights.forEachIndexed { i, h ->
        val x = 74f + i * 66f
        chart += bar(x, 300f, x + 44f, 300f + h, if (i % 2 == 0) ACCENT else 0xFF7FA1DE.toInt())
    }
    chart += rule(300f, x0 = 64f, x1 = 548f)
    SafePdfPage(PAGE_W, PAGE_H, page.primitives + chart)
}

private val Page3 = textPage(
    title = "3. Results",
    folio = "3",
    body = listOf(
        "Site coverage reached 94% of the planned visits.",
        "",
        "Variance between the two teams stayed inside the agreed",
        "tolerance at every site except site 11, which is discussed",
        "separately below.",
    ),
)

private val Page4 = textPage(
    title = "Appendix A — Raw readings",
    folio = "4",
    body = listOf(
        "Site      Team A     Team B     Status",
        "01        12.37      12.41      within tolerance",
        "02        12.74      12.82      within tolerance",
        "03        13.11      13.23      within tolerance",
        "04        13.48      13.64      within tolerance",
        "05        13.85      14.05      within tolerance",
        "06        14.22      14.46      within tolerance",
        "07        14.59      14.87      within tolerance",
        "08        14.96      15.28      within tolerance",
        "09        15.33      15.69      within tolerance",
        "10        15.70      16.10      within tolerance",
        "11        16.07      18.94      see section 3.2",
        "12        16.44      16.92      within tolerance",
        "13        16.81      17.33      within tolerance",
        "14        17.18      17.74      within tolerance",
    ),
)

private val Page5 = textPage(
    title = "References",
    folio = "5",
    body = listOf(
        "[1] Regional Survey Handbook, 4th edition.",
        "[2] Measurement Reconciliation Guidelines.",
        "[3] Site Access Protocol, revision 9.",
    ),
)

private val Page6 = textPage(
    title = "Cover sheet",
    folio = "6",
    body = listOf("Annual Field Report", "Regional survey committee", "Season 2025"),
)

private val SampleOutline = listOf(
    SafeOutlineItem(0, 0, "Cover"),
    SafeOutlineItem(0, 1, "1. Introduction"),
    SafeOutlineItem(1, 1, "1.1 Scope"),
    SafeOutlineItem(1, 2, "1.2 Definitions"),
    SafeOutlineItem(0, 3, "2. Method"),
    SafeOutlineItem(1, 3, "2.1 Data collection"),
    SafeOutlineItem(1, 5, "2.2 Analysis"),
    SafeOutlineItem(0, 7, "3. Results"),
    SafeOutlineItem(1, 8, "3.1 Summary tables"),
    SafeOutlineItem(1, 9, "3.2 Site 11"),
    SafeOutlineItem(0, 11, "4. Recommendations"),
    SafeOutlineItem(0, 13, "Appendix A — Raw readings"),
    SafeOutlineItem(0, 17, "References"),
)

/**
 * Store listing images for `:pdf`. See `common-conventions-preview-metadata`.
 *
 * The reader itself ([SafePdfViewerScreen]) cannot be previewed: it opens a document
 * through the native `pdf_render` library, which Layoutlib cannot load, and its ~40 pieces
 * of edit state are welded to that handle. What it is built from can be previewed, so these
 * images use the pieces that were split out of it — [PdfOutlineDrawer] and
 * [SafePdfPageCanvas] — over the literal document above, plus the two screens that were
 * already free of the native handle.
 *
 * Each preview needs @PreviewTest as well as @Preview: @Preview alone renders in Studio but
 * is not collected as a screenshot test. Previews must also be class members, not top-level
 * functions. Order comes from the function names (Preview1…, Preview2…).
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-outline", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1Outline() {
        DynamicTheme(darkTheme = true) {
            PdfOutlineDrawer(
                outline = SampleOutline,
                drawerState = rememberDrawerState(DrawerValue.Open),
                onSelectPage = {},
            ) {
                // A LazyColumn of pages, as the reader lays them out: it measures each page
                // with an unbounded height so the fit-to-width aspect ratio is honoured.
                LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                    items(listOf(Page1, Page2)) { SafePdfPageCanvas(it) }
                }
            }
        }
    }

    @PreviewTest
    @Preview(name = "2-pages", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview2Pages() {
        DynamicTheme(darkTheme = true) {
            val pages = listOf(Page1, Page2, Page3, Page4, Page5, Page6)
            CutGlueContent(
                pageKeys = pages.indices.map { it.toLong() },
                renderPage = { pages.getOrNull(it) },
                initialPages = pages.withIndex().associate { (i, page) -> i.toLong() to page },
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-home", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview3Home() {
        DynamicTheme(darkTheme = true) {
            InitialScreen(onOpenPdf = {}, onCapturePdf = {}, onCutGlue = {})
        }
    }

    @PreviewTest
    @Preview(name = "4-expanded", device = EXPANDED, group = "large-tablet", showSystemUi = true)
    @Composable
    fun Preview4Expanded() {
        DynamicTheme(darkTheme = true) {
            PdfViewerWideLayout(
                viewer = {
                    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
                        items(listOf(Page1, Page2)) { SafePdfPageCanvas(it) }
                    }
                },
                tools = {
                    PdfOutlineDrawer(
                        outline = SampleOutline,
                        drawerState = rememberDrawerState(DrawerValue.Open),
                        onSelectPage = {},
                    ) {}
                },
            )
        }
    }
}
