package com.vayunmathur.office.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.ui.DrawerValue
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.ui.IconDownload
import com.vayunmathur.library.ui.IconHome
import com.vayunmathur.library.ui.ModalNavigationDrawer
import com.vayunmathur.library.ui.PagerTab
import com.vayunmathur.library.ui.TabStyle
import com.vayunmathur.library.ui.TabbedPagerScaffold
import com.vayunmathur.library.ui.MaterialTheme
import com.vayunmathur.library.ui.Surface
import com.vayunmathur.library.ui.rememberDrawerState
import com.vayunmathur.library.ui.odf.OdfCell
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfFrame
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfRow
import com.vayunmathur.library.ui.odf.OdfSheet
import com.vayunmathur.library.ui.odf.OdfSlide
import com.vayunmathur.library.ui.odf.OdfSlideElement
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.ParagraphStyle
import com.vayunmathur.office.ui.DocumentChrome
import com.vayunmathur.office.ui.DrawerOutline
import com.vayunmathur.office.ui.HomeScreen
import com.vayunmathur.office.ui.OfficeLightTheme

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/** Expanded desktop window for the wide outline+document layout. */
private const val EXPANDED = "spec:width=1280dp,height=800dp,dpi=420"

// --- Sample documents ------------------------------------------------------
//
// The text document is the real metadata_data/assets/sample1.docx, run through the app's own
// importer offline (see SampleDocx.kt). The spreadsheet and presentation stay hand-built: the
// spreadsheet's values would otherwise need the native formula engine, which Layoutlib cannot
// load, and no sample .pptx renders meaningfully without its media.

/**
 * `metadata_data/assets/sample1.docx`, imported by the app's own OOXML importer — so the
 * listing image is the app opening a real Word document, not a model built to look like one.
 *
 * The import runs offline and its result is checked in as [SampleDocxBlocks]; see that file
 * for why a preview cannot parse the .docx itself.
 */
private val SampleTextDocument = OdfDocument.TextDocument(
    title = "sample1.docx",
    content = SampleDocxBlocks,
)

private fun sheetCell(
    text: String,
    bold: Boolean = false,
    align: TextAlign? = null,
    background: Long? = null,
) = OdfCell(text = text, bold = bold, alignment = align, backgroundColor = background)

private val HeaderFill = 0xFFE3E9F5L

private val SampleSpreadsheet = OdfDocument.Spreadsheet(
    title = "Budget.ods",
    sheets = listOf(
        OdfSheet(
            name = "Q3 Budget",
            rows = listOf(
                OdfRow(listOf(
                    sheetCell("Department", bold = true, background = HeaderFill),
                    sheetCell("Planned", bold = true, align = TextAlign.End, background = HeaderFill),
                    sheetCell("Actual", bold = true, align = TextAlign.End, background = HeaderFill),
                    sheetCell("Variance", bold = true, align = TextAlign.End, background = HeaderFill),
                )),
                OdfRow(listOf(sheetCell("Research"), sheetCell("48,000"), sheetCell("46,120"), sheetCell("-1,880"))),
                OdfRow(listOf(sheetCell("Fieldwork"), sheetCell("62,500"), sheetCell("64,310"), sheetCell("1,810"))),
                OdfRow(listOf(sheetCell("Equipment"), sheetCell("18,750"), sheetCell("17,940"), sheetCell("-810"))),
                OdfRow(listOf(sheetCell("Travel"), sheetCell("12,400"), sheetCell("13,850"), sheetCell("1,450"))),
                OdfRow(listOf(sheetCell("Outreach"), sheetCell("6,150"), sheetCell("5,980"), sheetCell("-170"))),
                OdfRow(listOf(
                    sheetCell("Total", bold = true, background = HeaderFill),
                    // Shown as the formula engine would evaluate it; the preview's value source
                    // returns the literal text, so the cell reads the same either way.
                    OdfCell(
                        text = "147,800",
                        bold = true,
                        formula = "of:=SUM([.B2:.B6])",
                        backgroundColor = HeaderFill),
                    OdfCell(
                        text = "148,200",
                        bold = true,
                        formula = "of:=SUM([.C2:.C6])",
                        backgroundColor = HeaderFill),
                    OdfCell(text = "400", bold = true, formula = "of:=C7-B7", backgroundColor = HeaderFill),
                )),
            ),
        ),
        OdfSheet(name = "Sites", rows = listOf(OdfRow(listOf(sheetCell("Site"), sheetCell("Lead"))))),
        OdfSheet(name = "Notes", rows = listOf(OdfRow(listOf(sheetCell("Reviewed 2025-09-30"))))),
    ),
)

/**
 * The value source the real view gets from the native formula engine. Here every cell is
 * already an evaluated literal, so this just reads it back.
 */
private class LiteralSpreadsheetValues(
    private val doc: OdfDocument.Spreadsheet,
) : SpreadsheetValues {
    override fun display(sheet: Int, row: Int, col: Int): String =
        doc.sheets.getOrNull(sheet)?.rows?.getOrNull(row)?.cells?.getOrNull(col)?.text ?: ""

    override fun isNumeric(sheet: Int, row: Int, col: Int): Boolean =
        display(sheet, row, col).replace(",", "").toDoubleOrNull() != null
}

private fun slideParagraph(text: String, style: ParagraphStyle = ParagraphStyle.BODY, size: Float? = null) =
    OdfParagraph(listOf(OdfSpan(text, fontSize = size)), style)

// Slide coordinates are px@96 inside the default 1058x794 ODF page.
private fun titleFrame(title: String) = OdfSlideElement.Frame(
    OdfFrame(80f, 90f, 900f, 120f, listOf(slideParagraph(title, ParagraphStyle.HEADING1, size = 40f)))
)

private fun bulletsFrame(lines: List<String>) = OdfSlideElement.Frame(
    OdfFrame(80f, 260f, 900f, 420f, lines.map { slideParagraph("•  $it", size = 22f) })
)

private val SamplePresentation = OdfDocument.Presentation(
    title = "Season Review.odp",
    slides = listOf(
        OdfSlide(
            name = "Title",
            elements = listOf(
                titleFrame("Season 2025 in review"),
                bulletsFrame(
                    listOf(
                        "Fourteen sites monitored fortnightly",
                        "94% of planned visits completed",
                        "Two independent teams, paired readings",
                        "One site flagged for follow-up",
                    )
                ),
            ),
        ),
        OdfSlide(
            name = "Method",
            elements = listOf(
                titleFrame("How the readings were paired"),
                bulletsFrame(
                    listOf(
                        "Both teams visited within 48 hours",
                        "Instruments recalibrated before each visit",
                        "Variance checked against the agreed tolerance",
                    )
                ),
            ),
        ),
        OdfSlide(
            name = "Results",
            elements = listOf(
                titleFrame("Results"),
                bulletsFrame(
                    listOf(
                        "Coverage 94%, up four points on 2024",
                        "Variance within tolerance at 13 of 14 sites",
                        "Site 11 scheduled for re-survey",
                    )
                ),
            ),
        ),
    ),
)

/**
 * Store listing images for `:office`. See `common-conventions-preview-metadata`.
 *
 * The old generator pushed three real sample files onto a device and opened each through an
 * `ACTION_VIEW` intent. These render the same three document kinds from the literal models
 * above, in the same order (text = 1, spreadsheet = 2, presentation = 3).
 *
 * The document body is wrapped in [OfficeLightTheme] exactly as `DocumentScreen` wraps it:
 * the app chrome is dark, but the rendered "paper" stays light. The editor's own chrome (top
 * bar, menu bar, format toolbar) is not here — it is welded to the OfficeViewModel and to
 * the native `office_engine`, which Layoutlib cannot load.
 *
 * Each preview needs @PreviewTest as well as @Preview: @Preview alone renders in Studio but
 * is not collected as a screenshot test. Previews must also be class members, not top-level
 * functions. Order comes from the function names (Preview1…, Preview2…).
 *
 * Coverage: `OfflineHome`, the three offline editor bodies (text/sheet/slides,
 * the renderable form of `OfflineEditor`), the outline drawer, and
 * `OnlineHome` in its disabled state on phone, plus the wide editor layout on
 * large-tablet (main/editor wide variant).
 * Exceptions: the enabled `OnlineHome` list and `OnlineEditor` need the E2EE
 * relay session + OfficeViewModel, and the full editor chrome needs the native
 * `office_engine` (above) — Layoutlib cannot load that native library, so only
 * the document bodies are shown, exactly as the header above describes.
 */
class MetadataPreviews {

    /** The "paper" wrapper DocumentScreen puts around every rendered document. */
    @Composable
    private fun Paper(content: @Composable () -> Unit) {
        DynamicTheme(darkTheme = true) {
            OfficeLightTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    content()
                }
            }
        }
    }

    @PreviewTest
    @Preview(name = "1-document", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1Document() {
        Paper { TextDocumentView(doc = SampleTextDocument) }
    }

    @PreviewTest
    @Preview(name = "2-spreadsheet", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview2Spreadsheet() {
        Paper {
            SpreadsheetView(
                doc = SampleSpreadsheet,
                values = LiteralSpreadsheetValues(SampleSpreadsheet),
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-presentation", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview3Presentation() {
        Paper { PresentationView(doc = SamplePresentation) }
    }

    @PreviewTest
    @Preview(name = "4-home", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview4Home() {
        ChromePreview()
    }

    @PreviewTest
    @Preview(name = "8-home-expanded", device = EXPANDED, group = "large-tablet", showSystemUi = true)
    @Composable
    fun Preview8HomeExpanded() {
        ChromePreview()
    }

    @PreviewTest
    @Preview(name = "7-online-disabled", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview7OnlineDisabled() {
        DynamicTheme(darkTheme = true) {
            OnlineDisabledScreen(onEnable = {})
        }
    }

    @PreviewTest
    @Preview(name = "5-expanded", device = EXPANDED, group = "large-tablet", showSystemUi = true)
    @Composable
    fun Preview5Expanded() {
        Paper {
            OfficeEditorWideLayout(
                outline = { TextDocumentView(doc = SampleTextDocument) },
                document = { TextDocumentView(doc = SampleTextDocument) },
            )
        }
    }

    @PreviewTest
    @Preview(name = "6-outline-drawer", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview6OutlineDrawer() {
        Paper {
            val drawerState = rememberDrawerState(DrawerValue.Open)
            val listState = rememberLazyListState()
            val scope = rememberCoroutineScope()
            val chrome = remember(SampleTextDocument) {
                DocumentChrome(
                    isEditMode = false,
                    hasUnsavedChanges = false,
                    isOnline = false,
                    onlineEnabled = false,
                    isSaving = false,
                    canUndo = false,
                    canRedo = false,
                    nightMode = false,
                    documentDarkMode = false,
                    isTextDoc = true,
                    isSpreadsheet = false,
                    isPresentation = false,
                    canEdit = false,
                    saveAsName = "sample1.odt",
                    headings = extractHeadings(SampleTextDocument),
                    wordCount = 0,
                    charCount = 0,
                    readingTime = 0,
                    bookmarks = emptyList(),
                )
            }
            ModalNavigationDrawer(
                drawerState = drawerState,
                gesturesEnabled = chrome.isTextDoc &&
                    (chrome.headings.isNotEmpty() || chrome.bookmarks.isNotEmpty()),
                drawerContent = { DrawerOutline(chrome, listState, drawerState, scope) },
            ) {
                TextDocumentView(doc = SampleTextDocument, listState = listState)
            }
        }
    }
}

/**
 * The app's real tab chrome around the home content: the same [TabbedPagerScaffold]
 * with the same Offline/Online tabs the home host declares. The Online tab is
 * ViewModel-coupled, so it shows an empty page (never visible — the shot stays on
 * Offline); Offline is the real stateless [HomeScreen].
 */
@Composable
private fun ChromePreview() {
    DynamicTheme(darkTheme = true) {
        val tabs = listOf(
            PagerTab("Offline", { IconHome() }) { HomeScreen() },
            PagerTab("Online", { IconDownload() }) { Box(Modifier.fillMaxSize()) },
        )
        TabbedPagerScaffold(
            tabs = tabs,
            pagerState = rememberPagerState(initialPage = 0, pageCount = { tabs.size }),
            tabStyle = TabStyle.BottomNav,
        )
    }
}
