package com.vayunmathur.library.ui.odf

/** An in-memory ODF document: a text document, spreadsheet, presentation or drawing. */
sealed class OdfDocument {
    abstract val title: String
    abstract val metadata: OdfMetadata

    /** A word-processing document: content blocks, images, notes and page setup. */
    data class TextDocument(
        override val title: String,
        val content: List<OdfContentBlock>,
        override val metadata: OdfMetadata = OdfMetadata(),
        val images: Map<String, ByteArray> = emptyMap(),
        val footnotes: List<OdfFootnote> = emptyList(),
        val headerParagraphs: List<OdfParagraph> = emptyList(),
        val footerParagraphs: List<OdfParagraph> = emptyList(),
        val bookmarks: List<OdfBookmark> = emptyList(),
        val changes: List<OdfChange> = emptyList(),
        val pageSetup: OdfPageSetup? = null
    ) : OdfDocument()

    /** A spreadsheet document: sheets plus named ranges and validations. */
    data class Spreadsheet(
        override val title: String,
        val sheets: List<OdfSheet>,
        override val metadata: OdfMetadata = OdfMetadata(),
        val images: Map<String, ByteArray> = emptyMap(),
        val namedRanges: List<OdfNamedRange> = emptyList(),
        val validations: List<OdfDataValidation> = emptyList()
    ) : OdfDocument()

    /** A presentation: an ordered list of slides. */
    data class Presentation(
        override val title: String,
        val slides: List<OdfSlide>,
        override val metadata: OdfMetadata = OdfMetadata(),
        val images: Map<String, ByteArray> = emptyMap()
    ) : OdfDocument()

    /** A drawing document: pages of placed frames and shapes. */
    data class Drawing(
        override val title: String,
        val pages: List<OdfSlide>,
        override val metadata: OdfMetadata = OdfMetadata(),
        val images: Map<String, ByteArray> = emptyMap()
    ) : OdfDocument()
}

/**
 * A tracked change region (text:changed-region, Priority 6). [type] is one of
 * "insertion", "deletion", "format-change". Body spans reference it via OdfSpan.changeId.
 */
data class OdfChange(
    val id: String,
    val type: String,
    val author: String? = null,
    val date: String? = null,
    val comment: String? = null
)

/**
 * Page geometry (style:page-layout-properties, Priority 7). All lengths in px@96
 * (cm = px / 37.795). Defaults are A4 portrait with 2cm margins.
 */
data class OdfPageSetup(
    val widthPx: Float = 793.7f,
    val heightPx: Float = 1122.5f,
    val marginLeftPx: Float = 75.6f,
    val marginRightPx: Float = 75.6f,
    val marginTopPx: Float = 75.6f,
    val marginBottomPx: Float = 75.6f
) {
    val isLandscape: Boolean get() = widthPx > heightPx
}

/** One block of text-document content: paragraph, table, image, chart or formula. */
sealed class OdfContentBlock {
    /** A text paragraph block. */
    data class Paragraph(val paragraph: OdfParagraph) : OdfContentBlock()
    /** A table block inside a text document. */
    data class Table(val table: OdfTable) : OdfContentBlock()
    /** An embedded image block. */
    data class Image(val image: OdfImage) : OdfContentBlock()
    /** An embedded chart block. */
    data class Chart(val chart: OdfChart) : OdfContentBlock()
    /** A MathML formula block. */
    data class Formula(val mathml: String) : OdfContentBlock()
    /** Live table of contents (text:table-of-content). Entries are the generated heading lines. */
    data class TableOfContents(val title: String, val entries: List<OdfParagraph>) : OdfContentBlock()
    /** Start of a text:section (round-trip). [columnCount] > 1 means a multi-column section. (Round 3) */
    data class SectionStart(val name: String, val columnCount: Int = 1) : OdfContentBlock()
    /** End of a text:section. */
    data object SectionEnd : OdfContentBlock()
    /** An explicit page break. */
    data object PageBreak : OdfContentBlock()
}

/** The chart kinds an [OdfChart] can render. */
enum class ChartType { BAR, LINE, PIE, AREA, DONUT, SCATTER, STACKED_BAR, RADAR, BUBBLE }

/** One named data series of an [OdfChart], with optional fill and label settings. */
data class OdfChartSeries(
    val name: String,
    val values: List<Float>,
    // Per-series fill color (chart:style-name -> fo:fill-color), preserved for round-trip. (Phase 5)
    val color: Long? = null,
    // Whether data labels are shown for this series (chart:data-label-*). (Phase 5)
    val dataLabels: Boolean = false
)

/** A chart: type, categories, series and axis/legend presentation. */
data class OdfChart(
    val type: ChartType,
    val categories: List<String>,
    val series: List<OdfChartSeries>,
    val title: String? = null,
    val subtitle: String? = null,
    val legend: Boolean = true,
    val xAxisTitle: String? = null,
    val yAxisTitle: String? = null,
    // Stacked/percent-stacked plotting (chart:stacked / chart:percentage). (Phase 5)
    val stacked: Boolean = false
)

@Suppress("ArrayInDataClass")
/** An embedded image: package path, bytes, geometry, crop and effects. */
data class OdfImage(
    val path: String,
    val imageData: ByteArray,
    val width: Float = 0f,
    val height: Float = 0f,
    val anchorType: String = "",
    // Rotation in degrees clockwise (E38).
    val rotationDegrees: Float = 0f,
    // Natural (intrinsic) pixel size of the source image, used to convert crop
    // fractions to absolute ODF fo:clip lengths. 0 = unknown. (A7)
    val naturalWidthPx: Float = 0f,
    val naturalHeightPx: Float = 0f,
    // Non-destructive crop insets as fractions of the source image [0,1). (Phase 5)
    val cropLeftPct: Float = 0f,
    val cropTopPct: Float = 0f,
    val cropRightPct: Float = 0f,
    val cropBottomPct: Float = 0f,
    // Image effects (Round 2 R7): opacity 0..100 (% from draw:image-opacity), color mode
    // (draw:color-mode: "standard"/"greyscale"/"mono"/"watermark").
    val opacityPercent: Float = 100f,
    val colorMode: String? = null,
    // Accessibility alt text from svg:title / svg:desc inside the frame. (Phase 2)
    val altTitle: String? = null,
    val altDesc: String? = null
)
