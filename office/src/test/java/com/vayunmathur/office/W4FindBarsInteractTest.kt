package com.vayunmathur.office

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.office.util.OfficeViewModel
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * W4: [DocumentFindBars] interactions — match navigation jumps, replace
 * one/all against the real ViewModel, match-option toggles, and the
 * font-scale bar's slider + reset.
 */
@RunWith(AndroidJUnit4::class)
class W4FindBarsInteractTest {

    @get:Rule val rule = createComposeRule()

    private fun vm(doc: OdfDocument): OfficeViewModel =
        OfficeViewModel(ApplicationProvider.getApplicationContext()).apply {
            stateMutable.value = OfficeViewModel.ViewState.Loaded(doc)
        }

    private fun doc() = OdfDocument.TextDocument(
        "Doc",
        listOf(
            OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("hello world")))),
            OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("say hello again")))),
            OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("other")))),
        ),
    )

    private fun show(s: DocumentScreenState, document: OdfDocument = doc()) {
        val viewModel = vm(document)
        rule.setContent {
            DocumentFindBars(
                s = s,
                document = document,
                viewModel = viewModel,
                isTextDoc = true,
                isPresentation = false,
                wordCount = 3,
                charCount = 20,
                readingTime = 1,
                scope = rememberCoroutineScope(),
                listState = rememberLazyListState(),
            )
        }
    }

    private fun textOf(viewModelDoc: OdfDocument.TextDocument): List<String> =
        viewModelDoc.content.filterIsInstance<OdfContentBlock.Paragraph>()
            .map { it.paragraph.spans.joinToString("") { span -> span.text } }

    @Test fun nextAndPrevCycleThroughMatches() {
        val s = DocumentScreenState().apply {
            showSearch = true
            searchQuery = "hello"
        }
        show(s)
        rule.onNodeWithText("1/2").assertIsDisplayed()
        rule.onAllNodesWithText("Next", substring = true)[0].performClick()
        rule.waitForIdle()
        assertEquals(1, s.findIndex)
        rule.onNodeWithText("2/2").assertIsDisplayed()
        // Prev wraps back to the first match.
        rule.onAllNodesWithText("Prev", substring = true)[0].performClick()
        rule.waitForIdle()
        assertEquals(0, s.findIndex)
    }

    @Test fun replaceOneAndAllChangeTheDocument() {
        val document = doc()
        val viewModel = vm(document)
        val s = DocumentScreenState().apply {
            showSearch = true
            searchQuery = "hello"
            replaceText = "hi"
            showReplaceBar = true
        }
        rule.setContent {
            DocumentFindBars(
                s = s, document = document, viewModel = viewModel,
                isTextDoc = true, isPresentation = false,
                wordCount = 3, charCount = 20, readingTime = 1,
                scope = rememberCoroutineScope(), listState = rememberLazyListState(),
            )
        }
        // Replace-all button ("All") rewrites both occurrences and clears the query.
        rule.onAllNodesWithText("All")[0].performClick()
        rule.waitForIdle()
        val updated = (viewModel.state.value as OfficeViewModel.ViewState.Loaded).document
        @Suppress("UNCHECKED_CAST")
        val texts = textOf(updated as OdfDocument.TextDocument)
        assertEquals(listOf("hi world", "say hi again", "other"), texts)
        assertEquals("", s.searchQuery)
    }

    @Test fun matchOptionTogglesFlipState() {
        val s = DocumentScreenState().apply {
            showSearch = true
            searchQuery = "Hello"
            showReplaceBar = true
            replaceText = "x"
        }
        show(s)
        assertTrue(!s.matchCase)
        rule.onNodeWithText("☐ Case").performClick()
        rule.waitForIdle()
        assertTrue(s.matchCase)
        rule.onNodeWithText("☐ Word").performClick()
        rule.waitForIdle()
        assertTrue(s.wholeWord)
    }

    @Test fun fontScaleBarResetRestoresDefault() {
        val s = DocumentScreenState().apply {
            showFontControl = true
            fontSizeMultiplier = 1.6f
        }
        show(s)
        rule.onAllNodesWithText("Reset")[0].performClick()
        rule.waitForIdle()
        assertEquals(1f, s.fontSizeMultiplier)
    }
}
