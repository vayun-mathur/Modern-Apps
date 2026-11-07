package com.vayunmathur.logviewer.platform

import com.vayunmathur.logviewer.domain.LogKind
import com.vayunmathur.logviewer.domain.LogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogViewerUiContractTest {

    @Test
    fun `default ui state is loading error-report at report font size`() {
        val state = LogViewerUiState()
        assertTrue(state.loading)
        assertFalse(state.refreshing)
        assertFalse(state.unavailable)
        assertNull(state.unavailableMessageRes)
        assertEquals("", state.title)
        assertNull(state.sourcePackage)
        assertTrue(state.lines.isEmpty())
        assertEquals(LogKind.ErrorReport, state.kind)
        assertEquals(12f, state.fontSizeSp)
        assertFalse(state.showReportButton)
        assertEquals("", state.snapshotFileName)
        assertNull(state.logcat)
        assertTrue(state.extraActions.isEmpty())
        assertNull(state.stackTrace)
    }

    @Test
    fun `state copy preserves and replaces fields`() {
        val filters = LogcatFilters(listOf("main", "system"), LogLevel.Warn, "regex.*")
        val state = LogViewerUiState(
            loading = false,
            title = "T",
            sourcePackage = "com.example",
            lines = listOf("a"),
            kind = LogKind.Logcat,
            fontSizeSp = 9f,
            showReportButton = true,
            snapshotFileName = "f.txt",
            logcat = filters,
            extraActions = listOf(ExtraAction.MoreInfo, ExtraAction.ShowAppLog),
            stackTrace = "trace",
        )
        assertEquals(LogLevel.Warn, state.logcat?.level)
        assertEquals("regex.*", state.logcat?.filterRegex)
        assertEquals(listOf("main", "system"), state.logcat?.buffers)
        val copied = state.copy(refreshing = true, stackTrace = null)
        assertTrue(copied.refreshing)
        assertNull(copied.stackTrace)
        assertEquals("T", copied.title)
        assertEquals(listOf(ExtraAction.MoreInfo, ExtraAction.ShowAppLog), copied.extraActions)
    }

    @Test
    fun `load results carry document or optional message`() {
        val unavailable = LogLoadResult.Unavailable()
        assertNull(unavailable.messageRes)
        assertEquals(123, LogLoadResult.Unavailable(123).messageRes)
    }
}
