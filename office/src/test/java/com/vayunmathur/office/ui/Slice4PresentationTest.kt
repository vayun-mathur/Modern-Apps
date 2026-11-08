package com.vayunmathur.office.ui

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfFrame
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfShape
import com.vayunmathur.library.ui.odf.OdfSlide
import com.vayunmathur.library.ui.odf.OdfSlideElement
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.office.DocumentOverlayState
import com.vayunmathur.office.SlideOverlays
import com.vayunmathur.office.rememberDocumentLaunchers
import com.vayunmathur.office.DocumentScreenState
import com.vayunmathur.office.util.OfficeViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice 4/4 presentation tests: [PresentationView] edit-mode controls, empty
 * deck, slideshow/go-to dialogs, plus [SlideOverlays] wiring (notes,
 * background, transition) against a real ViewModel.
 */
@RunWith(AndroidJUnit4::class)
class Slice4PresentationTest {

    @get:Rule val rule = createComposeRule()

    private fun frame(vararg texts: String) = OdfSlideElement.Frame(
        OdfFrame(0f, 0f, 200f, 100f, texts.map { OdfParagraph(listOf(OdfSpan(it))) }))

    private fun deck() = OdfDocument.Presentation(
        "Deck",
        listOf(
            OdfSlide("Intro", listOf(frame("welcome"))),
            OdfSlide("More", listOf(frame("second"))),
        ),
    )

    private fun vm(doc: OdfDocument): OfficeViewModel =
        OfficeViewModel(ApplicationProvider.getApplicationContext()).apply {
            stateMutable.value = OfficeViewModel.ViewState.Loaded(doc)
        }

    @Test fun emptyPresentationPlaceholder() {
        rule.setContent { PresentationView(doc = OdfDocument.Presentation("E", emptyList())) }
        rule.onNodeWithText("Empty presentation").assertIsDisplayed()
    }

    @Test fun editModeSlideControls() {
        var added = -1
        var dup = -1
        var box = -1
        rule.setContent {
            PresentationView(
                doc = deck(),
                isEditMode = true,
                onAddSlide = { added = it },
                onDuplicateSlide = { dup = it },
                onAddTextBox = { box = it },
            )
        }
        rule.onNodeWithText("+ Slide").performClick()
        assertEquals(0, added)
        rule.onNodeWithText("Dup").performClick()
        assertEquals(0, dup)
        rule.onNodeWithText("+ Text").performClick()
        assertEquals(0, box)
    }

    @Test fun navBarNextPrev() {
        val changed = mutableListOf<Int>()
        rule.setContent {
            PresentationView(doc = deck(), onSlideChange = { changed.add(it) })
        }
        rule.onNodeWithText("Slide 1 of 2").assertIsDisplayed()
        rule.onNodeWithText("Next", substring = true).performClick()
        rule.onNodeWithText("Slide 2 of 2").assertIsDisplayed()
        rule.onNodeWithText("Prev", substring = true).performClick()
        rule.onNodeWithText("Slide 1 of 2").assertIsDisplayed()
        assertTrue(changed.contains(1))
    }

    @Test fun slideshowDialogShowsAndCloses() {
        rule.setContent { PresentationView(doc = deck()) }
        rule.onNodeWithText("Play", substring = true).performClick()
        // Slideshow renders the slide canvas plus a position counter.
        rule.onNodeWithText("1 / 2").assertIsDisplayed()
        rule.onNodeWithText("Close").performClick()
        rule.onNodeWithText("1 / 2").assertDoesNotExist()
    }

    @Test fun goToSlideJumps() {
        rule.setContent { PresentationView(doc = deck()) }
        rule.onNodeWithText("Slide 1 of 2", substring = true).performClick()
        rule.onNode(hasSetTextAction()).performTextInput("2")
        rule.onNodeWithText("Go").performClick()
        rule.onNodeWithText("Slide 2 of 2").assertIsDisplayed()
    }

    @Test fun deleteSlideInEditMode() {
        var deleted = -1
        rule.setContent {
            PresentationView(
                doc = deck(),
                isEditMode = true,
                onDeleteSlide = { deleted = it },
            )
        }
        rule.onNodeWithText("Delete").performClick()
        assertEquals(0, deleted)
    }

    @Test fun slideNotesExpandInCard() {
        val doc = OdfDocument.Presentation(
            "Deck",
            listOf(
                OdfSlide(
                    "Intro",
                    listOf(frame("welcome")),
                    notes = listOf(OdfParagraph(listOf(OdfSpan("secret"))))),
            ),
        )
        rule.setContent { PresentationView(doc = doc) }
        rule.onNodeWithText("Speaker notes").performClick()
        rule.onNodeWithText("secret").assertIsDisplayed()
    }

    // ---- SlideOverlays wiring ----

    private fun slideOverlays(
        state: DocumentOverlayState,
        document: OdfDocument,
        viewModel: OfficeViewModel,
    ) {
        rule.setContent {
            SlideOverlays(
                state = state,
                document = document,
                viewModel = viewModel,
                isPresentation = true,
                activeCell = remember { mutableStateOf(null) },
                activeSlide = remember { mutableStateOf(0) },
                pendingReplace = remember { mutableStateOf(null) },
                launchers = rememberDocumentLaunchers(
                    viewModel,
                    DocumentScreenState(),
                    onPickImageBytes = { _, _ -> }),
                focusedPara = 0,
            )
        }
    }

    @Test fun slideNotesSave() {
        val state = DocumentOverlayState().apply { showSlideNotes = true }
        val viewModel = vm(deck())
        slideOverlays(state, deck(), viewModel)
        rule.onNodeWithText("Speaker notes").assertIsDisplayed()
        rule.onNode(hasSetTextAction()).performTextInput("remember this")
        rule.onNodeWithText("Save").performClick()
        val cur = (viewModel.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.Presentation
        assertEquals("remember this", cur.slides[0].notes.single().spans.single().text)
        assertTrue(!state.showSlideNotes)
    }

    @Test fun slideBackgroundPickDismisses() {
        val state = DocumentOverlayState().apply { showSlideBackground = true }
        slideOverlays(state, deck(), vm(deck()))
        rule.onNodeWithText("Slide background").assertIsDisplayed()
        rule.onNodeWithText("∅").performClick()
        assertTrue(!state.showSlideBackground)
    }

    @Test fun slideTransitionFadeApplies() {
        val state = DocumentOverlayState().apply { showSlideTransition = true }
        val viewModel = vm(deck())
        slideOverlays(state, deck(), viewModel)
        rule.onNodeWithText("Slide transition").assertIsDisplayed()
        rule.onNodeWithText("None").performClick()
        rule.onNodeWithText("Fade", substring = true, useUnmergedTree = true).performClick()
        rule.onNodeWithText("Apply").performClick()
        val cur = (viewModel.state.value as OfficeViewModel.ViewState.Loaded)
            .document as OdfDocument.Presentation
        assertEquals("fade", cur.slides[0].transitionType)
        assertTrue(!state.showSlideTransition)
    }

    @Test fun slideOverlaysHiddenForNonPresentation() {
        val state = DocumentOverlayState().apply {
            showSlideNotes = true
            showSlideBackground = true
        }
        rule.setContent {
            SlideOverlays(
                state = state,
                document = OdfDocument.TextDocument(
                    "T",
                    listOf(OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("x")))))),
                viewModel = vm(deck()),
                isPresentation = false,
                activeCell = remember { mutableStateOf(null) },
                activeSlide = remember { mutableStateOf(0) },
                pendingReplace = remember { mutableStateOf(null) },
                launchers = rememberDocumentLaunchers(
                    vm(deck()),
                    DocumentScreenState(),
                    onPickImageBytes = { _, _ -> }),
                focusedPara = 0,
            )
        }
        rule.onNodeWithText("Speaker notes").assertDoesNotExist()
    }

    @Test fun slideShapeRenders() {
        val doc = OdfDocument.Presentation(
            "Shapes",
            listOf(
                OdfSlide(
                    "S",
                    listOf(
                        OdfSlideElement.Shape(
                            OdfShape.Rect(
                                x = 0f, y = 0f, width = 50f, height = 50f,
                                text = listOf(OdfParagraph(listOf(OdfSpan("box")))))),
                    ),
                ),
            ),
        )
        rule.setContent { PresentationView(doc = doc) }
        rule.onNodeWithText("box").assertIsDisplayed()
    }
}
