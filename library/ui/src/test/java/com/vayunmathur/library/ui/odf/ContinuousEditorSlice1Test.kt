package com.vayunmathur.library.ui.odf

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * ContinuousParagraphEditor interactions: rendering, typing, smart
 * enter/backspace delegation, checkbox toggles and caret requests.
 * Slice-1 coverage for library/ui (odf package).
 */
@RunWith(RobolectricTestRunner::class)
class ContinuousEditorSlice1Test {

    @get:Rule
    val rule = createComposeRule()

    private fun doc(vararg texts: String) = OdfDocument.TextDocument(
        title = "t",
        content = texts.map { OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(it)))) },
    )

    @Test
    fun `the editor renders the paragraph text`() {
        rule.setContent {
            ContinuousParagraphEditor(
                doc = doc("hello world"),
                start = 0,
                endInclusive = 0,
                fontSizeMultiplier = 1f,
                onSelectionChange = { _, _, _, _ -> },
                onTextChange = { _, _, _ -> },
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("hello world", substring = true).assertIsDisplayed()
    }

    @Test
    fun `typing reports the new text`() {
        var reported: String? = null
        rule.setContent {
            ContinuousParagraphEditor(
                doc = doc(""),
                start = 0,
                endInclusive = 0,
                fontSizeMultiplier = 1f,
                onSelectionChange = { _, _, _, _ -> },
                onTextChange = { _, _, text -> reported = text },
            )
        }
        rule.waitForIdle()
        val fields = rule.onAllNodes(hasSetTextAction())
        assertTrue(fields.fetchSemanticsNodes().isNotEmpty(), "expected an editable text field")
        fields[0].performTextInput("typed!")
        rule.waitForIdle()
        assertEquals("typed!", reported)
    }

    @Test
    fun `enter delegates to the smart handler`() {
        var enterAt: Int? = null
        val state = OdfTextEditorState(doc("hello"))
        rule.setContent {
            ContinuousParagraphEditor(
                doc = state.current,
                start = 0,
                endInclusive = 0,
                fontSizeMultiplier = 1f,
                onSelectionChange = { _, _, _, _ -> },
                onTextChange = { s, e, t -> state.updateParagraphRun(s, e, t) },
                onEnter = { gPos ->
                    enterAt = gPos
                    state.handleListEnter(0, 0, gPos)
                },
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("hello", substring = true).assertIsDisplayed()
        assertEquals(null, enterAt)
    }

    @Test
    fun `heading sizes scale by level`() {
        assertEquals(30f, headingSizeSp(ParagraphStyle.HEADING1))
        assertEquals(26f, headingSizeSp(ParagraphStyle.HEADING2))
        assertEquals(22f, headingSizeSp(ParagraphStyle.HEADING3))
        assertEquals(20f, headingSizeSp(ParagraphStyle.HEADING4))
        assertEquals(null, headingSizeSp(ParagraphStyle.BODY))
    }

    @Test
    fun `a checkbox run renders its marker`() {
        rule.setContent {
            ContinuousParagraphEditor(
                doc = OdfDocument.TextDocument(
                    title = "t",
                    content = listOf(
                        OdfContentBlock.Paragraph(
                            OdfParagraph(
                                listOf(OdfSpan("task")),
                                style = ParagraphStyle.LIST_ITEM,
                                listType = ListType.CHECKBOX,
                                listChecked = true,
                            )
                        )
                    ),
                ),
                start = 0,
                endInclusive = 0,
                fontSizeMultiplier = 1f,
                onSelectionChange = { _, _, _, _ -> },
                onTextChange = { _, _, _ -> },
                onToggleCheckbox = {},
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("task", substring = true).assertIsDisplayed()
    }

    @Test
    fun `remote carets render without crashing`() {
        rule.setContent {
            ContinuousParagraphEditor(
                doc = doc("shared text here"),
                start = 0,
                endInclusive = 0,
                fontSizeMultiplier = 1f,
                onSelectionChange = { _, _, _, _ -> },
                onTextChange = { _, _, _ -> },
                remoteCarets = listOf(RemoteCaret(offset = 3, color = 0xFFFF0000, name = "Ada")),
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("shared text here", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a caret request moves the caret`() {
        var handled = false
        var sel: Int? = null
        rule.setContent {
            ContinuousParagraphEditor(
                doc = doc("abcdef"),
                start = 0,
                endInclusive = 0,
                fontSizeMultiplier = 1f,
                onSelectionChange = { _, _, a, _ -> sel = a },
                onTextChange = { _, _, _ -> },
                caretRequest = 3,
                onCaretRequestHandled = { handled = true },
            )
        }
        rule.waitForIdle()
        assertTrue(handled)
        assertEquals(3, sel)
    }
}
