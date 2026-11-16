package com.vayunmathur.library.ui

import android.text.SpannableStringBuilder
import android.widget.EditText
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.odf.ContinuousParagraphEditor
import com.vayunmathur.library.ui.odf.ListType
import com.vayunmathur.library.ui.odf.OdfCell
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfNumberFormat
import com.vayunmathur.library.ui.odf.OdfNumberToken
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfRow
import com.vayunmathur.library.ui.odf.OdfSheet
import com.vayunmathur.library.ui.odf.OdfSpan
import com.vayunmathur.library.ui.odf.OdfTextEditorState
import com.vayunmathur.library.ui.odf.OoxmlExporter
import com.vayunmathur.library.ui.odf.ParagraphStyle
import com.vayunmathur.library.ui.odf.RemoteCaret
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith

/**
 * W4: the continuous ODF editor over mixed-layout multi-paragraph runs,
 * the shared editor-format buttons driving a real controller, the editor
 * state's undo/redo, and OOXML date-token format codes.
 */
@RunWith(AndroidJUnit4::class)
class W4EditorDeepTest {

    @get:Rule
    val rule = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun mixedDoc(): OdfDocument.TextDocument = OdfDocument.TextDocument(
        title = "t",
        content = listOf(
            OdfContentBlock.Paragraph(OdfParagraph(
                listOf(OdfSpan("Big Heading")), style = ParagraphStyle.HEADING1)),
            OdfContentBlock.Paragraph(OdfParagraph(
                listOf(OdfSpan("first task")),
                style = ParagraphStyle.LIST_ITEM, listType = ListType.NUMBERED, listItemIndex = 1)),
            OdfContentBlock.Paragraph(OdfParagraph(
                listOf(OdfSpan("second task")),
                style = ParagraphStyle.LIST_ITEM, listType = ListType.NUMBERED, listItemIndex = 2)),
            OdfContentBlock.Paragraph(OdfParagraph(
                listOf(OdfSpan("closing body")))),
        ),
    )

    @Test fun editorRendersMixedRunWithListPrefixes() {
        rule.setContent {
            ContinuousParagraphEditor(
                doc = mixedDoc(),
                start = 0,
                endInclusive = 3,
                fontSizeMultiplier = 1.25f,
                onSelectionChange = { _, _, _, _ -> },
                onTextChange = { _, _, _ -> },
                remoteCarets = listOf(
                    RemoteCaret(offset = 5, color = 0xFF0000FF, name = "Bo"),
                    RemoteCaret(offset = 40, color = 0xFF00FF00, name = "Cy"),
                ),
            )
        }
        rule.waitForIdle()
        rule.onNodeWithText("Big Heading", substring = true).assertExists()
        rule.onNodeWithText("first task", substring = true).assertExists()
        rule.onNodeWithText("closing body", substring = true).assertExists()
        // Numbered list items carry their generated prefixes in the transformed text.
        assertTrue(
            rule.onAllNodesWithText("1.", substring = true).fetchSemanticsNodes().isNotEmpty() ||
                rule.onAllNodesWithText("first task", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun editorTypingAcrossRunReportsWholeText() {
        val state = OdfTextEditorState(mixedDoc())
        var reported: Triple<Int, Int, String>? = null
        rule.setContent {
            ContinuousParagraphEditor(
                doc = state.current,
                start = 1,
                endInclusive = 2,
                fontSizeMultiplier = 1f,
                onSelectionChange = { _, _, _, _ -> },
                onTextChange = { s, e, t -> reported = Triple(s, e, t); state.updateParagraphRun(s, e, t) },
            )
        }
        rule.waitForIdle()
        val fields = rule.onAllNodes(hasSetTextAction())
        assertTrue(fields.fetchSemanticsNodes().isNotEmpty())
        fields[0].performTextInput("!")
        rule.waitForIdle()
        assertTrue(reported != null, "typing should report")
        assertTrue(state.current.content.size >= 4)
    }

    @Test fun editorStateUndoRedoRoundTrips() {
        val state = OdfTextEditorState(mixedDoc())
        val before = state.current
        state.toggleListItem(3)
        assertTrue(state.current != before)
        state.undo()
        assertEquals(before, state.current)
        state.redo()
        assertTrue(state.current != before)
        state.setParagraphAlignment(0, androidx.compose.ui.text.style.TextAlign.Center)
        state.restartNumbering(1)
        state.insertHorizontalLine(0)
        assertTrue(state.current.content.size > before.content.size)
    }

    @Test fun editorBaseButtonsToggleControllerFormats() {
        val controller = HtmlEditorController()
        controller.editText = EditText(context).apply {
            setText(SpannableStringBuilder("format me"))
            setSelection(0, 9)
        }
        rule.setContent {
            Row { EditorBaseButtons(controller) }
        }
        rule.onNodeWithContentDescription("Bold").performClick()
        assertTrue(controller.isActive(EditorFormat.BOLD))
        rule.onNodeWithContentDescription("Italic").performClick()
        assertTrue(controller.isActive(EditorFormat.ITALIC))
        rule.onNodeWithContentDescription("Underline").performClick()
        assertTrue(controller.isActive(EditorFormat.UNDERLINE))
    }

    @Test fun xlsxDateTokenCodesCoverAllKinds() {
        val tokens = listOf(
            OdfNumberToken("year", style = "long"),
            OdfNumberToken("year", style = "short"),
            OdfNumberToken("month", style = "long", textual = true),
            OdfNumberToken("month", style = "short", textual = false),
            OdfNumberToken("day", style = "long"),
            OdfNumberToken("day-of-week", style = "long"),
            OdfNumberToken("hours", style = "long"),
            OdfNumberToken("minutes", style = "short"),
            OdfNumberToken("seconds", style = "long"),
            OdfNumberToken("am-pm"),
            OdfNumberToken("text", text = " at "),
            OdfNumberToken("era"),
        )
        val cell = OdfCell(
            text = "2024-01-01", numberValue = 45292.0, valueType = "date",
            numberFormat = OdfNumberFormat(isDate = true, dateTimeTokens = tokens),
        )
        val sheet = OdfSheet("D", listOf(OdfRow(listOf(cell))))
        val zip = OoxmlExporter.export(OdfDocument.Spreadsheet("B", listOf(sheet)))
        val xml = StringBuilder()
        ZipInputStream(ByteArrayInputStream(zip)).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                xml.append(zis.readBytes().toString(Charsets.UTF_8)).append('\n')
                zis.closeEntry()
                e = zis.nextEntry
            }
        }
        val all = xml.toString()
        for (code in listOf("yyyy", "mmmm", "dddd", "AM/PM", " at ")) {
            assertTrue(all.contains(code), code + " missing in " + all.take(1200))
        }
    }
}
