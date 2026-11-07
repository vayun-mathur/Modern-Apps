package com.vayunmathur.office

import android.content.Context
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfFrame
import com.vayunmathur.library.ui.odf.OdfImage
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfRow
import com.vayunmathur.library.ui.odf.OdfCell
import com.vayunmathur.library.ui.odf.OdfSheet
import com.vayunmathur.library.ui.odf.OdfSlide
import com.vayunmathur.library.ui.odf.OdfSlideElement
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.office.util.OfficeViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Slice 4 pass 2: slide/sheet/text image crop overlays through DocumentOverlays. */
@RunWith(AndroidJUnit4::class)
class Slice4Pass2CropOverlaysTest {

    @get:Rule val rule = createComposeRule()
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private fun str(id: Int) = ctx.getString(id)

    private fun vm(doc: OdfDocument): OfficeViewModel =
        OfficeViewModel(ApplicationProvider.getApplicationContext()).apply {
            stateMutable.value = OfficeViewModel.ViewState.Loaded(doc)
        }

    private fun overlays(state: DocumentOverlayState, document: OdfDocument, viewModel: OfficeViewModel) {
        rule.setContent {
            DocumentOverlays(
                state = state,
                document = document,
                viewModel = viewModel,
                isPresentation = document is OdfDocument.Presentation,
                isOnline = false,
                focusedPara = 0,
                saveAsName = "Doc",
                activeCell = remember { mutableStateOf(null) },
                activeSlide = remember { mutableStateOf(0) },
                activeSlideEl = remember { mutableStateOf(0) },
                activeRunStart = remember { mutableStateOf(-1) },
                activeRunEnd = remember { mutableStateOf(-1) },
                selStart = remember { mutableStateOf(0) },
                selEnd = remember { mutableStateOf(0) },
                pendingReplace = remember { mutableStateOf(null) },
                listState = rememberLazyListState(),
                scope = rememberCoroutineScope(),
                launchers = rememberDocumentLaunchers(viewModel, DocumentScreenState(), onPickImageBytes = { _, _ -> }),
                onBecameOnline = {},
                onBack = {},
            )
        }
    }

    private fun imageFrame() = OdfSlideElement.Frame(
        OdfFrame(
            0f, 0f, 100f, 80f, emptyList(),
            image = OdfImage(
                path = "p.png", imageData = ByteArray(0),
                cropLeftPct = 0.1f, cropTopPct = 0.2f,
            ),
        )
    )

    private fun deckDoc() = OdfDocument.Presentation("Deck.odp", listOf(OdfSlide("S1", listOf(imageFrame()))))

    private fun sheetDoc() = OdfDocument.Spreadsheet(
        "Book.ods",
        listOf(
            OdfSheet(
                "S",
                listOf(OdfRow(listOf(OdfCell("a")))),
                floating = listOf(imageFrame()),
            )
        ),
    )

    private fun textDoc() = OdfDocument.TextDocument(
        "Doc.odt",
        listOf(
            OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("body")))),
            OdfContentBlock.Image(OdfImage(path = "p.png", imageData = ByteArray(0), cropTopPct = 0.3f)),
        ),
    )

    @Test fun slideCropOverlayApplyDismissesAndResetClears() {
        val viewModel = vm(deckDoc())
        val state = DocumentOverlayState().apply { cropSlideTarget = 0 to 0 }
        overlays(state, deckDoc(), viewModel)
        rule.onNodeWithText(str(R.string.edit_image)).assertExists()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.apply)).performClick()
        assertNull(state.cropSlideTarget)
        val frame = ((viewModel.state.value as OfficeViewModel.ViewState.Loaded).document
            as OdfDocument.Presentation).slides[0].elements[0] as OdfSlideElement.Frame
        assertEquals(0.1f, frame.frame.image!!.cropLeftPct)
    }

    @Test fun slideCropOverlayMissingImageClearsTarget() {
        val doc = OdfDocument.Presentation(
            "Deck.odp",
            listOf(
                OdfSlide(
                    "S1",
                    listOf(
                        OdfSlideElement.Frame(
                            OdfFrame(0f, 0f, 10f, 10f, listOf(OdfParagraph(listOf(OdfSpan("t")))))
                        )
                    ),
                )
            ),
        )
        val state = DocumentOverlayState().apply { cropSlideTarget = 0 to 0 }
        overlays(state, doc, vm(doc))
        rule.waitForIdle()
        assertNull(state.cropSlideTarget, "target without an image dismisses itself")
    }

    @Test fun sheetCropOverlayRotateAndCancel() {
        val viewModel = vm(sheetDoc())
        val state = DocumentOverlayState().apply { cropSheetTarget = 0 to 0 }
        overlays(state, sheetDoc(), viewModel)
        rule.onNodeWithText(str(R.string.edit_image)).assertExists()
        rule.onNodeWithText(str(R.string.rotate_90)).performClick()
        val floating = ((viewModel.state.value as OfficeViewModel.ViewState.Loaded).document
            as OdfDocument.Spreadsheet).sheets[0].floating[0] as OdfSlideElement.Frame
        assertEquals(90f, floating.frame.image!!.rotationDegrees)
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.cancel)).performClick()
        assertNull(state.cropSheetTarget)
    }

    @Test fun sheetCropOverlayResetZeroesCrop() {
        val viewModel = vm(sheetDoc())
        val state = DocumentOverlayState().apply { cropSheetTarget = 0 to 0 }
        overlays(state, sheetDoc(), viewModel)
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.reset)).performClick()
        assertNull(state.cropSheetTarget)
        val floating = ((viewModel.state.value as OfficeViewModel.ViewState.Loaded).document
            as OdfDocument.Spreadsheet).sheets[0].floating[0] as OdfSlideElement.Frame
        assertEquals(0f, floating.frame.image!!.cropLeftPct)
        assertEquals(0f, floating.frame.image!!.cropTopPct)
    }

    @Test fun textImageCropOverlayViaSlideOverlays() {
        val viewModel = vm(textDoc())
        val state = DocumentOverlayState().apply { cropImageBlock = 1 }
        overlays(state, textDoc(), viewModel)
        rule.onNodeWithText(str(R.string.edit_image)).assertExists()
        rule.onNodeWithText(str(com.vayunmathur.library.ui.R.string.apply)).performClick()
        assertEquals(-1, state.cropImageBlock)
        val block = ((viewModel.state.value as OfficeViewModel.ViewState.Loaded).document
            as OdfDocument.TextDocument).content[1] as OdfContentBlock.Image
        assertEquals(0.3f, block.image.cropTopPct)
    }
}
