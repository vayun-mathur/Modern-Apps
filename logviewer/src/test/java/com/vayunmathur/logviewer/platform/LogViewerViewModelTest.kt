package com.vayunmathur.logviewer.platform

import android.app.Application
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.logviewer.domain.LogDocument
import com.vayunmathur.logviewer.domain.LogKind
import com.vayunmathur.logviewer.domain.LogLevel
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

@RunWith(AndroidJUnit4::class)
class LogViewerViewModelTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun viewModel() = LogViewerViewModel(app)

    private fun document(
        title: String = "System log",
        kind: LogKind = LogKind.Logcat,
        body: List<String> = listOf("first", "second"),
    ) = LogDocument(
        kind = kind,
        sourcePackage = "com.example.app",
        title = title,
        headerLines = listOf("type: logcat"),
        bodyLines = body,
    )

    /** Waits for the IO load to land on the main looper; fails rather than hanging. */
    private fun await(vm: LogViewerViewModel, condition: (LogViewerUiState) -> Boolean): LogViewerUiState {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            val state = vm.state.value
            if (condition(state)) return state
            Thread.sleep(5)
        }
        fail("state never matched; last=${vm.state.value}")
    }

    private fun loadedVm(doc: LogDocument = document()): LogViewerViewModel {
        val vm = viewModel()
        vm.start(loader = { LogLoadResult.Loaded(doc) }, chrome = { LogViewerViewModel.Chrome() })
        await(vm) { !it.loading }
        return vm
    }

    @Test
    fun `initial state is loading and nothing can be clipped shared or dismissed`() = runBlocking {
        val vm = viewModel()
        assertTrue(vm.state.value.loading)
        assertNull(vm.clipText())
        assertNull(vm.shareUri())
        vm.dismissStackTrace()
        assertNull(vm.state.value.stackTrace)
        // Saving with no document is a no-op (and must not throw).
        vm.save(Uri.parse("file:///tmp/never-written"))
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(vm.state.value.loading)
    }

    @Test
    fun `start loads document applies chrome and initial font size`() {
        val vm = viewModel()
        val filters = LogcatFilters(listOf("main"), LogLevel.Warn, "x")
        vm.start(
            loader = { LogLoadResult.Loaded(document()) },
            chrome = {
                LogViewerViewModel.Chrome(
                    showReportButton = true,
                    extraActions = listOf(ExtraAction.ShowAppLog),
                    logcat = filters,
                )
            },
        )
        val state = await(vm) { !it.loading }
        assertFalse(state.unavailable)
        assertEquals("System log", state.title)
        assertEquals("com.example.app", state.sourcePackage)
        assertEquals(listOf("type: logcat", "", "first", "second"), state.lines)
        assertEquals(LogKind.Logcat, state.kind)
        assertEquals(9f, state.fontSizeSp)
        assertTrue(state.showReportButton)
        assertEquals(listOf(ExtraAction.ShowAppLog), state.extraActions)
        assertEquals(filters, state.logcat)
        assertTrue(state.snapshotFileName.startsWith("System log "))
        assertTrue(state.snapshotFileName.endsWith(".txt"))
    }

    @Test
    fun `start is idempotent, later calls are ignored`() {
        val vm = viewModel()
        var firstLoads = 0
        var secondLoads = 0
        vm.start(loader = { firstLoads++; LogLoadResult.Loaded(document("First")) }, chrome = { LogViewerViewModel.Chrome() })
        await(vm) { !it.loading }
        vm.start(loader = { secondLoads++; LogLoadResult.Loaded(document("Second")) }, chrome = { LogViewerViewModel.Chrome() })
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, firstLoads)
        assertEquals(0, secondLoads)
        assertEquals("First", vm.state.value.title)
    }

    @Test
    fun `unavailable result carries its message and clears loading`() {
        val vm = viewModel()
        vm.start(loader = { LogLoadResult.Unavailable(77) }, chrome = { LogViewerViewModel.Chrome() })
        val state = await(vm) { !it.loading }
        assertTrue(state.unavailable)
        assertEquals(77, state.unavailableMessageRes)
        assertFalse(state.refreshing)

        val silent = viewModel()
        silent.start(loader = { LogLoadResult.Unavailable() }, chrome = { LogViewerViewModel.Chrome() })
        val silentState = await(silent) { !it.loading }
        assertTrue(silentState.unavailable)
        assertNull(silentState.unavailableMessageRes)
    }

    @Test
    fun `error report starts at report font size`() {
        val vm = loadedVm(document(kind = LogKind.ErrorReport))
        assertEquals(12f, vm.state.value.fontSizeSp)
        assertEquals(LogKind.ErrorReport, vm.state.value.kind)
    }

    @Test
    fun `zoom multiplies and clamps to the supported range`() {
        val vm = loadedVm()
        vm.zoom(2f)
        assertEquals(18f, vm.state.value.fontSizeSp)
        vm.zoom(100f)
        assertEquals(24f, vm.state.value.fontSizeSp)
        vm.zoom(0.0001f)
        assertEquals(2f, vm.state.value.fontSizeSp)
        vm.zoom(1f)
        assertEquals(2f, vm.state.value.fontSizeSp)
    }

    @Test
    fun `reload keeps the pinched font size and shows refreshing`() {
        val vm = loadedVm()
        vm.zoom(2f) // 18sp
        val filters = LogcatFilters(listOf("radio"), LogLevel.Error, "boom")
        vm.reload(
            filters = filters,
            loader = { LogLoadResult.Loaded(document(title = "Reloaded", body = listOf("new line"))) },
            chrome = { LogViewerViewModel.Chrome(logcat = filters) },
        )
        // The new filters are visible immediately, before the load lands.
        assertEquals(filters, vm.state.value.logcat)
        assertTrue(vm.state.value.refreshing)
        val state = await(vm) { it.title == "Reloaded" }
        assertFalse(state.refreshing)
        assertEquals(18f, state.fontSizeSp)
        assertEquals(listOf("type: logcat", "", "new line"), state.lines)
    }

    @Test
    fun `reload to unavailable marks the screen unavailable`() {
        val vm = loadedVm()
        vm.reload(
            filters = LogcatFilters(listOf("main"), LogLevel.Verbose, ""),
            loader = { LogLoadResult.Unavailable(55) },
            chrome = { LogViewerViewModel.Chrome() },
        )
        val state = await(vm) { it.unavailable }
        assertEquals(55, state.unavailableMessageRes)
        assertFalse(state.refreshing)
        assertFalse(state.loading)
    }

    @Test
    fun `clipText returns the loaded document clip`() {
        val vm = loadedVm()
        val clip = vm.clipText()
        assertNotNull(clip)
        assertFalse(clip.truncated)
        assertEquals("```\ntype: logcat\nfirst\nsecond\n```\n", clip.text)
    }

    @Test
    fun `shareUri registers a content uri named after the snapshot`() = runBlocking {
        val vm = loadedVm()
        val uri = vm.shareUri()
        assertNotNull(uri)
        assertEquals("content", uri.scheme)
        assertEquals("${app.packageName}.BlobProvider", uri.authority)
        assertTrue(uri.toString().contains(vm.state.value.snapshotFileName.replace(" ", "%20")))
        // The provider serves the snapshot bytes back, gzipped in between. Robolectric
        // emulates pipes with temp files, so unlike a real pipe a read does not block
        // for openPipeHelper's writer thread: a first read can catch the file still
        // empty. Reopen and reread until the snapshot arrives or a deadline passes.
        val expected = "type: logcat\nfirst\nsecond\n"
        val deadline = System.nanoTime() + 10_000_000_000L
        var text: String? = null
        while (System.nanoTime() < deadline) {
            text = app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            if (text == expected) break
            Thread.sleep(5)
        }
        assertEquals(expected, text)
    }

    @Test
    fun `save writes the snapshot to the picked uri`() {
        val vm = loadedVm()
        val file = Files.createTempFile("saved-log", ".txt").toFile()
        vm.save(Uri.fromFile(file))
        await(vm) { file.readText().contains("second") }
        assertEquals("type: logcat\nfirst\nsecond\n", file.readText())
        assertNull(vm.state.value.stackTrace)
    }

    @Test
    fun `save failure surfaces a stack trace that can be dismissed`() {
        val vm = loadedVm()
        val dir = Files.createTempDirectory("not-a-file").toFile()
        vm.save(Uri.fromFile(dir))
        val state = await(vm) { it.stackTrace != null }
        assertTrue(state.stackTrace!!.contains("Exception"))
        vm.dismissStackTrace()
        assertNull(vm.state.value.stackTrace)
    }

    @Test
    fun `chrome defaults when chrome lambda is not consulted for unavailable`() {
        val vm = viewModel()
        var chromeCalls = 0
        vm.start(
            loader = { LogLoadResult.Unavailable() },
            chrome = { chromeCalls++; LogViewerViewModel.Chrome() },
        )
        await(vm) { !it.loading }
        assertEquals(0, chromeCalls)
        assertEquals(LogViewerViewModel.Chrome(), LogViewerViewModel.Chrome())
        assertFalse(LogViewerViewModel.Chrome().showReportButton)
        assertNull(LogViewerViewModel.Chrome().logcat)
    }

    @Test
    fun `snapshot file for a document is stable within a state`() {
        val vm = loadedVm(document(title = "Fixed title"))
        assertTrue(vm.state.value.snapshotFileName.startsWith("Fixed title "))
        assertTrue(File(vm.state.value.snapshotFileName).extension == "txt")
    }
}
