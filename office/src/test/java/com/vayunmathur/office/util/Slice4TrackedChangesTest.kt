package com.vayunmathur.office.util

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.library.ui.odf.OdfAnnotation
import com.vayunmathur.library.ui.odf.OdfChange
import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 4/4 tracked-changes tests: [transformChangeSpans] is a pure internal
 * helper, and accept/reject run against a real [OfficeViewModel] under
 * Robolectric with state seeded directly (no I/O, no network).
 */
@RunWith(AndroidJUnit4::class)
class Slice4TrackedChangesTest {

    private fun vm(): OfficeViewModel =
        OfficeViewModel(ApplicationProvider.getApplicationContext())

    private fun changeDoc(): OdfDocument.TextDocument {
        val ins = OdfSpan("new", changeId = "c1", changeKind = "insertion")
        val del = OdfSpan("old", changeId = "c2", changeKind = "deletion")
        return OdfDocument.TextDocument(
            "Doc",
            listOf(
                OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("keep "), ins))),
                OdfContentBlock.Paragraph(OdfParagraph(listOf(del, OdfSpan(" tail")))),
            ),
            changes = listOf(
                OdfChange("c1", "insertion", author = "me", date = "2024-05-01T10:00:00"),
                OdfChange("c2", "deletion", author = "you", date = "2024-05-02T10:00:00"),
            ),
        )
    }

    private fun OfficeViewModel.loaded(): OdfDocument.TextDocument {
        seed(changeDoc())
        return changeDoc()
    }

    private fun OfficeViewModel.seed(doc: OdfDocument.TextDocument) {
        stateMutable.value = OfficeViewModel.ViewState.Loaded(doc)
    }

    private fun OfficeViewModel.current(): OdfDocument.TextDocument =
        (state.value as OfficeViewModel.ViewState.Loaded).document as OdfDocument.TextDocument

    private fun textOf(doc: OdfDocument.TextDocument): String =
        doc.content.filterIsInstance<OdfContentBlock.Paragraph>()
            .joinToString("|") { p -> p.paragraph.spans.joinToString("") { it.text } }

    // ---- transformChangeSpans (pure) ----

    @Test fun transformAcceptInsertionKeepsTextClearsMarker() {
        val vm = vm()
        val out = vm.transformChangeSpans(changeDoc(), "c1", removeSpans = false)
        assertEquals("keep new|old tail", textOf(out))
        assertTrue(out.content.filterIsInstance<OdfContentBlock.Paragraph>()
            .flatMap { it.paragraph.spans }.none { it.changeId == "c1" })
        assertEquals(listOf("c2"), out.changes.map { it.id })
    }

    @Test fun transformRejectInsertionRemovesText() {
        val vm = vm()
        val out = vm.transformChangeSpans(changeDoc(), "c1", removeSpans = true)
        assertEquals("keep |old tail", textOf(out))
        assertEquals(listOf("c2"), out.changes.map { it.id })
    }

    @Test fun transformAcceptDeletionRemovesText() {
        val vm = vm()
        val out = vm.transformChangeSpans(changeDoc(), "c2", removeSpans = true)
        assertEquals("keep new| tail", textOf(out))
        assertEquals(listOf("c1"), out.changes.map { it.id })
    }

    @Test fun transformRejectDeletionKeepsText() {
        val vm = vm()
        val out = vm.transformChangeSpans(changeDoc(), "c2", removeSpans = false)
        assertEquals("keep new|old tail", textOf(out))
        assertEquals(listOf("c1"), out.changes.map { it.id })
    }

    @Test fun transformUnknownIdLeavesDocUntouched() {
        val vm = vm()
        val doc = changeDoc()
        val out = vm.transformChangeSpans(doc, "nope", removeSpans = true)
        assertEquals(textOf(doc), textOf(out))
        assertEquals(listOf("c1", "c2"), out.changes.map { it.id })
    }

    @Test fun transformEmptySpanListLeavesPlaceholder() {
        val vm = vm()
        val doc = OdfDocument.TextDocument(
            "D",
            listOf(
                OdfContentBlock.Paragraph(
                    OdfParagraph(listOf(OdfSpan("gone", changeId = "c9", changeKind = "insertion"))))),
            changes = listOf(OdfChange("c9", "insertion")),
        )
        val out = vm.transformChangeSpans(doc, "c9", removeSpans = true)
        val spans = (out.content[0] as OdfContentBlock.Paragraph).paragraph.spans
        assertEquals(1, spans.size)
        assertEquals("", spans[0].text)
    }

    // ---- accept/reject via ViewModel ----

    @Test fun acceptInsertionThroughViewModel() {
        val vm = vm()
        vm.seed(changeDoc())
        vm.acceptChange("c1")
        val cur = vm.current()
        assertEquals("keep new|old tail", textOf(cur))
        assertEquals(listOf("c2"), cur.changes.map { it.id })
        assertTrue(vm.hasUnsavedChangesMutable.value)
    }

    @Test fun rejectInsertionThroughViewModel() {
        val vm = vm()
        vm.seed(changeDoc())
        vm.rejectChange("c1")
        assertEquals("keep |old tail", textOf(vm.current()))
    }

    @Test fun acceptDeletionThroughViewModel() {
        val vm = vm()
        vm.seed(changeDoc())
        vm.acceptChange("c2")
        assertEquals("keep new| tail", textOf(vm.current()))
    }

    @Test fun rejectDeletionThroughViewModel() {
        val vm = vm()
        vm.seed(changeDoc())
        vm.rejectChange("c2")
        assertEquals("keep new|old tail", textOf(vm.current()))
        assertEquals(listOf("c1"), vm.current().changes.map { it.id })
    }

    @Test fun acceptUnknownIdIsNoop() {
        val vm = vm()
        vm.seed(changeDoc())
        vm.acceptChange("nope")
        assertEquals("keep new|old tail", textOf(vm.current()))
    }

    @Test fun acceptAllAndRejectAll() {
        val vm = vm()
        vm.seed(changeDoc())
        vm.acceptAllChanges()
        assertEquals("keep new| tail", textOf(vm.current()))
        assertTrue(vm.current().changes.isEmpty())

        vm.seed(changeDoc())
        vm.rejectAllChanges()
        assertEquals("keep |old tail", textOf(vm.current()))
        assertTrue(vm.current().changes.isEmpty())
    }

    @Test fun changeOpsNoopWithoutLoadedDoc() {
        val vm = vm()
        // Empty state: none of these may crash.
        vm.acceptChange("c1")
        vm.rejectChange("c1")
        vm.acceptAllChanges()
        vm.rejectAllChanges()
        assertNull(vm.state.value as? OfficeViewModel.ViewState.Loaded)
    }

    @Test fun officeRolesHelper() {
        assertTrue(OfficeRoles.canEdit(OfficeRoles.OWNER))
        assertTrue(OfficeRoles.canEdit(OfficeRoles.EDITOR))
        assertTrue(!OfficeRoles.canEdit(OfficeRoles.VIEWER))
        assertTrue(!OfficeRoles.canEdit(OfficeRoles.REVOKED))
        assertTrue(!OfficeRoles.canEdit("other"))
    }

    @Test fun resolveCommentRemovesAnnotatedSpan() {
        val vm = vm()
        val doc = OdfDocument.TextDocument(
            "D",
            listOf(
                OdfContentBlock.Paragraph(
                    OdfParagraph(
                        listOf(
                            OdfSpan("a", annotation = OdfAnnotation(author = "me")),
                            OdfSpan("b"))))),
        )
        vm.seed(doc)
        vm.resolveComment(0, 0)
        val spans = (vm.current().content[0] as OdfContentBlock.Paragraph).paragraph.spans
        // The annotated span is removed; the plain span survives.
        assertEquals(listOf("b"), spans.map { it.text })
    }

    @Test fun resolveCommentOnPlainSpanIsNoop() {
        val vm = vm()
        val doc = OdfDocument.TextDocument(
            "D",
            listOf(OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("b"))))),
        )
        vm.seed(doc)
        vm.resolveComment(0, 0)
        val spans = (vm.current().content[0] as OdfContentBlock.Paragraph).paragraph.spans
        assertEquals(listOf("b"), spans.map { it.text })
    }
}
