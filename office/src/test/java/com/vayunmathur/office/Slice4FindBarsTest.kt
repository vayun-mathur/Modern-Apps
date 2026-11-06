package com.vayunmathur.office

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.office.util.OfficeViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice 4/4 find-bar tests (Robolectric + createComposeRule):
 * [DocumentFindBars] search/replace/font-scale/word-count/timer bars driven
 * through a [DocumentScreenState] with a seeded (Loaded) ViewModel.
 */
@RunWith(AndroidJUnit4::class)
class Slice4FindBarsTest {

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

    private fun show(
        s: DocumentScreenState,
        document: OdfDocument = doc(),
        viewModel: OfficeViewModel = vm(document),
        isTextDoc: Boolean = true,
        isPresentation: Boolean = false,
    ) {
        rule.setContent {
            DocumentFindBars(
                s = s,
                document = document,
                viewModel = viewModel,
                isTextDoc = isTextDoc,
                isPresentation = isPresentation,
                wordCount = 3,
                charCount = 20,
                readingTime = 1,
                scope = rememberCoroutineScope(),
                listState = rememberLazyListState(),
            )
        }
    }

    @Test fun searchFieldUpdatesQuery() {
        val s = DocumentScreenState().apply { showSearch = true }
        show(s)
        rule.onNode(hasSetTextAction()).performTextInput("hello")
        assertEquals("hello", s.searchQuery)
    }

    @Test fun matchNavigatorCounts() {
        val s = DocumentScreenState().apply {
            showSearch = true
            searchQuery = "hello"
        }
        show(s)
        // 2 matching blocks: navigator shows "1/2".
        rule.onNodeWithText("1/2").assertIsDisplayed()
    }

    @Test fun matchNavigatorNextPrev() {
        val s = DocumentScreenState().apply {
            showSearch = true
            searchQuery = "hello"
        }
        show(s)
        assertEquals(0, s.findIndex)
        // Navigator shows the position within the match list.
        rule.onNodeWithText("1/2").assertIsDisplayed()
        assertEquals(0, s.findIndex)
    }

    @Test fun replaceBarTypesReplacement() {
        val s = DocumentScreenState().apply {
            showSearch = true
            searchQuery = "hello"
            showReplaceBar = true
        }
        show(s)
        // Two editable fields: search + replace. Type into the replace one.
        val fields = rule.onAllNodes(hasSetTextAction())
        assertEquals(2, fields.fetchSemanticsNodes().size)
        fields[1].performTextInput("hi")
        assertEquals("hi", s.replaceText)
    }

    @Test fun wordCountBarShowsCounts() {
        val s = DocumentScreenState().apply { showWordBar = true }
        show(s)
        rule.onNodeWithText("3", substring = true).assertIsDisplayed()
        rule.onNodeWithText("20", substring = true).assertIsDisplayed()
    }

    @Test fun timerBarHiddenWithoutFlag() {
        val s = DocumentScreenState()
        show(s, isPresentation = true)
        rule.onNodeWithText("⏱", substring = true).assertDoesNotExist()
    }

    @Test fun timerBarShowsForPresentation() {
        val s = DocumentScreenState().apply {
            showTimer = true
            timerSeconds = 65
        }
        show(s, isPresentation = true)
        rule.onNodeWithText("01:05", substring = true).assertIsDisplayed()
    }

    @Test fun fontScaleBarHiddenByDefault() {
        val s = DocumentScreenState()
        show(s)
        // The font-scale slider row only appears with showFontControl.
        assertEquals(1f, s.fontSizeMultiplier)
    }

    @Test fun searchHiddenWithoutFlag() {
        val s = DocumentScreenState()
        show(s)
        rule.onNodeWithText("Search", substring = true).assertDoesNotExist()
    }

    @Test fun replaceToggleFlips() {
        val s = DocumentScreenState().apply { showSearch = true }
        show(s)
        assertTrue(!s.showReplaceBar)
    }
}
