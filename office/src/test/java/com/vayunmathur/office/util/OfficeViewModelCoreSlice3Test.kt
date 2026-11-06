package com.vayunmathur.office.util

import com.vayunmathur.library.ui.odf.OdfContentBlock
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfParagraph
import com.vayunmathur.library.ui.odf.OdfSpan
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Slice 3/4: core [OfficeViewModel] state — undo/redo, toggles, auto-save, settings persistence,
 * theme mode, roles/invite metadata, plain-text extraction and caret remapping.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class OfficeViewModelCoreSlice3Test : OfficeViewModelSlice3Harness() {

    private fun textDoc(vararg texts: String) = OdfDocument.TextDocument(
        title = "Doc",
        content = texts.map { OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan(it)))) },
    )

    @Test
    fun `starts empty with defaults`() = runTest {
        val vm = idleVm()
        assertTrue(vm.state.value is OfficeViewModel.ViewState.Empty)
        assertFalse(vm.isEditMode.value)
        assertFalse(vm.hasUnsavedChanges.value)
        assertFalse(vm.canUndo.value)
        assertFalse(vm.canRedo.value)
        assertFalse(vm.nightMode.value)
        assertEquals(OfficeViewModel.DocumentThemeMode.UNCHANGED, vm.documentThemeMode.value)
        assertEquals(60_000L, vm.autoSaveIntervalMs)
    }

    @Test
    fun `undo redo walk document history`() = runTest {
        val vm = idleVm()
        val first = textDoc("one")
        val second = textDoc("two")
        vm.stateMutable.value = OfficeViewModel.ViewState.Loaded(first)
        vm.updateDocument(second)
        assertEquals(second, (vm.state.value as OfficeViewModel.ViewState.Loaded).document)
        assertTrue(vm.canUndo.value)
        assertTrue(vm.hasUnsavedChanges.value)

        vm.undo()
        assertEquals(first, (vm.state.value as OfficeViewModel.ViewState.Loaded).document)
        assertFalse(vm.canUndo.value)
        assertTrue(vm.canRedo.value)

        vm.redo()
        assertEquals(second, (vm.state.value as OfficeViewModel.ViewState.Loaded).document)
        assertTrue(vm.canUndo.value)
        assertFalse(vm.canRedo.value)
    }

    @Test
    fun `undo with empty state is a no-op`() = runTest {
        val vm = idleVm()
        vm.undo()
        vm.redo()
        assertTrue(vm.state.value is OfficeViewModel.ViewState.Empty)
        assertFalse(vm.canUndo.value)
        assertFalse(vm.canRedo.value)
    }

    @Test
    fun `pushUndo caps the stack at MAX_UNDO and clears redo`() = runTest {
        val vm = idleVm()
        vm.stateMutable.value = OfficeViewModel.ViewState.Loaded(textDoc("base"))
        repeat(MAX_UNDO + 5) { i -> vm.updateDocument(textDoc("v$i")) }
        // Drained by undoing MAX_UNDO times; one more undo is a no-op.
        repeat(MAX_UNDO) { vm.undo() }
        assertTrue(vm.canRedo.value)
        val docBefore = (vm.state.value as OfficeViewModel.ViewState.Loaded).document
        vm.undo()
        assertEquals(docBefore, (vm.state.value as OfficeViewModel.ViewState.Loaded).document)
    }

    @Test
    fun `updateDocument without loaded doc is a no-op`() = runTest {
        val vm = idleVm()
        vm.updateDocument(textDoc("x"))
        assertTrue(vm.state.value is OfficeViewModel.ViewState.Empty)
    }

    @Test
    fun `toggles flip their flows`() = runTest {
        val vm = idleVm()
        vm.toggleNightMode()
        assertTrue(vm.nightMode.value)
        vm.toggleEditMode()
        assertTrue(vm.isEditMode.value)
        vm.toggleDocumentDarkMode()
        assertEquals(OfficeViewModel.DocumentThemeMode.FOLLOW_SYSTEM, vm.documentThemeMode.value)
        assertTrue(vm.documentDarkMode.value)
        vm.toggleDocumentDarkMode()
        assertEquals(OfficeViewModel.DocumentThemeMode.UNCHANGED, vm.documentThemeMode.value)
    }

    @Test
    fun `setAutoSave stores interval and gates the flag`() = runTest {
        val vm = idleVm()
        vm.setAutoSave(true, 30)
        assertEquals(30_000L, vm.autoSaveIntervalMs)
        assertTrue(vm.autoSaveEnabled.value)
        vm.setAutoSave(false)
        assertFalse(vm.autoSaveEnabled.value)
        dispatcher.scheduler.runCurrent()
    }

    @Test
    fun `saveSettings persists and restores`() = runTest {
        val vm = idleVm()
        vm.saveSettings(app, autoSave = true, autoSaveInterval = 45, defaultFontSize = 18f)
        assertTrue(vm.autoSaveEnabled.value)
        assertEquals(45_000L, vm.autoSaveIntervalMs)
        assertEquals(18f, vm.getDefaultFontSize(app))
        assertEquals(45, vm.getAutoSaveInterval(app))
        assertTrue(vm.getAutoSaveEnabled(app))

        val vm2 = idleVm()
        vm2.loadSettings(app)
        assertTrue(vm2.autoSaveEnabled.value)
        assertEquals(45_000L, vm2.autoSaveIntervalMs)
        // Cancel both auto-save loops: runTest's teardown advanceUntilIdle would chase
        // their infinite while(true){delay()} forever.
        vm.setAutoSave(false)
        vm2.setAutoSave(false)
    }

    @Test
    fun `theme mode persists through settings`() = runTest {
        val vm = idleVm()
        vm.setDocumentThemeMode(app, OfficeViewModel.DocumentThemeMode.FOLLOW_SYSTEM)
        assertEquals(OfficeViewModel.DocumentThemeMode.FOLLOW_SYSTEM, vm.getDocumentThemeMode(app))
        // Legacy dark-mode key migrates to the new mode on load.
        prefs.edit().clear()
            .putBoolean("document_dark_mode", true)
            .apply()
        val vm2 = idleVm()
        vm2.loadSettings(app)
        assertEquals(OfficeViewModel.DocumentThemeMode.FOLLOW_SYSTEM, vm2.documentThemeMode.value)
    }

    @Test
    fun `DocumentThemeMode parse and toggle`() {
        assertEquals(
            OfficeViewModel.DocumentThemeMode.UNCHANGED,
            OfficeViewModel.DocumentThemeMode.parse(null),
        )
        assertEquals(
            OfficeViewModel.DocumentThemeMode.UNCHANGED,
            OfficeViewModel.DocumentThemeMode.parse("bogus"),
        )
        assertEquals(
            OfficeViewModel.DocumentThemeMode.FOLLOW_SYSTEM,
            OfficeViewModel.DocumentThemeMode.parse("FOLLOW_SYSTEM"),
        )
        assertEquals(
            OfficeViewModel.DocumentThemeMode.FOLLOW_SYSTEM,
            OfficeViewModel.DocumentThemeMode.UNCHANGED.toggled(),
        )
        assertEquals(
            OfficeViewModel.DocumentThemeMode.UNCHANGED,
            OfficeViewModel.DocumentThemeMode.FOLLOW_SYSTEM.toggled(),
        )
    }

    @Test
    fun `OfficeRoles canEdit`() {
        assertTrue(OfficeRoles.canEdit(OfficeRoles.OWNER))
        assertTrue(OfficeRoles.canEdit(OfficeRoles.EDITOR))
        assertFalse(OfficeRoles.canEdit(OfficeRoles.VIEWER))
        assertFalse(OfficeRoles.canEdit(OfficeRoles.REVOKED))
        assertFalse(OfficeRoles.canEdit("bogus"))
    }

    @Test
    fun `officeDocMetaFromInvite carries role and owner key`() {
        val inv = OfficeSync.Invite(
            docId = "d1", key = "keyB64", title = "Shared",
            charMode = true, role = OfficeRoles.VIEWER, ownerKey = "ownerB64", charKind = "text",
        )
        val meta = officeDocMetaFromInvite(inv)
        assertEquals("d1", meta.docId)
        assertEquals("Shared", meta.title)
        assertEquals("keyB64", meta.keyB64)
        assertEquals(false, meta.owner)
        assertEquals(true, meta.charMode)
        assertEquals(OfficeRoles.VIEWER, meta.role)
        assertEquals("ownerB64", meta.ownerKeyB64)
        assertEquals("text", meta.charKind)
    }

    @Test
    fun `docPlainText joins paragraph spans`() = runTest {
        val vm = idleVm()
        val doc = OdfDocument.TextDocument(
            title = "t",
            content = listOf(
                OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("a"), OdfSpan("b")))),
                OdfContentBlock.Paragraph(OdfParagraph(listOf(OdfSpan("c")))),
            ),
        )
        assertEquals("ab\nc", vm.docPlainText(doc))
        assertNull(vm.docPlainText(OdfDocument.Spreadsheet(title = "s", sheets = emptyList())))
    }

    @Test
    fun `remapRemoteCarets shifts carets on text change`() = runTest {
        val vm = idleVm()
        val before = textDoc("hello world")
        val after = textDoc("hello brave world")
        vm.stateMutable.value = OfficeViewModel.ViewState.Loaded(before)
        vm.remotePresenceMutable.value = listOf(
            // Caret at the insertion point stays left (change is at/after it).
            OfficePresence(id = "p1", name = "N", typing = false, ts = 0L, caret = 6),
            // Caret after the insertion shifts by the delta (+6).
            OfficePresence(id = "p3", name = "O", typing = false, ts = 0L, caret = 8),
            OfficePresence(id = "p2", name = "M", typing = false, ts = 0L, caret = null),
        )
        vm.updateDocument(after)
        val updated = vm.remotePresenceMutable.value
        assertEquals(6, updated.first { it.id == "p1" }.caret)
        assertEquals(14, updated.first { it.id == "p3" }.caret)
        assertNull(updated.first { it.id == "p2" }.caret)
    }

    @Test
    fun `remapRemoteCarets is a no-op without carets`() = runTest {
        val vm = idleVm()
        vm.stateMutable.value = OfficeViewModel.ViewState.Loaded(textDoc("a"))
        vm.updateDocument(textDoc("b"))
        assertTrue(vm.remotePresenceMutable.value.isEmpty())
    }

    @Test
    fun `ViewState variants hold their payloads`() {
        val doc = textDoc("x")
        val loaded = OfficeViewModel.ViewState.Loaded(doc)
        assertEquals(doc, loaded.document)
        val err = OfficeViewModel.ViewState.Error("boom")
        assertEquals("boom", err.message)
        assertTrue(OfficeViewModel.ViewState.Empty is OfficeViewModel.ViewState.Empty)
        assertTrue(OfficeViewModel.ViewState.Loading is OfficeViewModel.ViewState.Loading)
    }
}
