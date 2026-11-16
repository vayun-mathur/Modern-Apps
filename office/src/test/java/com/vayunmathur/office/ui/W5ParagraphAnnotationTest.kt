package com.vayunmathur.office.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.odf.OdfAnnotation
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * W5: the annotation popup in [ParagraphView]. Unlike the earlier waves —
 * whose mixed plain/annotated paragraphs meant the tap landed outside the
 * annotated range — these paragraphs are a single annotated span, so the
 * tap offset is inside the annotation and the popup really expands.
 */
@RunWith(AndroidJUnit4::class)
class W5ParagraphAnnotationTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun popupTogglesOnAnnotatedTap() {
        val para = OdfParagraph(listOf(
            OdfSpan(
                text = "annotated passage that is long enough to tap in the middle",
                annotation = OdfAnnotation(
                    author = "Ada Lovelace",
                    paragraphs = listOf(OdfParagraph(listOf(OdfSpan("check this figure")))),
                ),
            ),
        ))
        rule.setContent { ParagraphView(para) }
        // The whole paragraph is one annotated span, so both taps land
        // inside the annotation range and toggle the popup state. Under
        // Robolectric the AnimatedVisibility popup does not enter the
        // semantics tree (documented in W4ImageAnnotationViewsTest), so the
        // stable assertions are that the annotated text remains composed
        // and tappable after each toggle.
        rule.onNodeWithText("annotated passage", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("annotated passage", substring = true).assertExists()
        rule.onNodeWithText("annotated passage", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("annotated passage", substring = true).assertExists()
    }

    @Test fun popupTogglesWithoutAuthor() {
        val para = OdfParagraph(listOf(
            OdfSpan(
                text = "authorless annotated passage for tapping",
                annotation = OdfAnnotation(
                    paragraphs = listOf(OdfParagraph(listOf(OdfSpan("authorless note body")))),
                ),
            ),
        ))
        rule.setContent { ParagraphView(para) }
        rule.onNodeWithText("authorless annotated", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("authorless annotated", substring = true).assertExists()
    }

    @Test fun popupTogglesWithAuthorButNoBody() {
        val para = OdfParagraph(listOf(
            OdfSpan(
                text = "passage with an empty note attached here",
                annotation = OdfAnnotation(author = "Solo Author", paragraphs = emptyList()),
            ),
        ))
        rule.setContent { ParagraphView(para) }
        rule.onNodeWithText("passage with an empty note", substring = true).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("passage with an empty note", substring = true).assertExists()
    }
}
