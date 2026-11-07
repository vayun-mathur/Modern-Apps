package com.vayunmathur.logviewer.platform

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vayunmathur.logviewer.domain.LogDocument
import com.vayunmathur.logviewer.domain.LogKind
import com.vayunmathur.logviewer.domain.LogLevel
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

@RunWith(AndroidJUnit4::class)
class LogViewerControllerTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun activity(): ComponentActivity =
        Robolectric.buildActivity(ComponentActivity::class.java).setup().get()

    private fun document() = LogDocument(
        kind = LogKind.ErrorReport,
        sourcePackage = "com.example.app",
        title = "Crash title",
        headerLines = listOf("type: crash"),
        bodyLines = listOf("trace line"),
    )

    private fun loadedViewModel(): LogViewerViewModel {
        val vm = LogViewerViewModel(app)
        vm.start(loader = { LogLoadResult.Loaded(document()) }, chrome = { LogViewerViewModel.Chrome() })
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (!vm.state.value.loading) return vm
            Thread.sleep(5)
        }
        fail("view model never loaded")
    }

    private fun clipboardText(context: Context): String? {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        return clipboard.primaryClip?.getItemAt(0)?.text?.toString()
    }

    @Test
    fun `copy puts the fenced log on the clipboard`() {
        val activity = activity()
        val controller = LogViewerController(activity, loadedViewModel())
        controller.copy()
        assertEquals("```\ntype: crash\ntrace line\n```\n", clipboardText(activity))
    }

    @Test
    fun `copy with nothing loaded leaves the clipboard alone`() {
        val activity = activity()
        val controller = LogViewerController(activity, LogViewerViewModel(app))
        controller.copy()
        assertNull(clipboardText(activity))
    }

    @Test
    fun `report copies then opens the issue tracker`() {
        val activity = activity()
        val controller = LogViewerController(activity, loadedViewModel())
        controller.report()
        assertEquals("```\ntype: crash\ntrace line\n```\n", clipboardText(activity))
        val started = shadowOf(activity).nextStartedActivity
        assertNotNull(started)
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(
            "https://github.com/vayun-mathur/Modern-Apps/issues",
            started.data.toString(),
        )
    }

    @Test
    fun `share launches a chooser carrying the snapshot stream`() {
        val activity = activity()
        val vm = loadedViewModel()
        val controller = LogViewerController(activity, vm)
        controller.share()
        // share() launches in the activity lifecycle scope; let it run.
        val deadline = System.nanoTime() + 10_000_000_000L
        var chooser: Intent? = null
        while (System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            chooser = shadowOf(activity).nextStartedActivity
            if (chooser != null) break
            Thread.sleep(5)
        }
        assertNotNull(chooser)
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        assertNotNull(send)
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals(LogDocument.MIME_TYPE, send.type)
        assertEquals(vm.state.value.snapshotFileName, send.getStringExtra(Intent.EXTRA_SUBJECT))
        val stream = send.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        assertNotNull(stream)
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun `save and zoom delegate to the view model`() {
        val activity = activity()
        val vm = loadedViewModel()
        val controller = LogViewerController(activity, vm)
        val before = vm.state.value.fontSizeSp
        controller.zoom(2f)
        assertEquals(before * 2, vm.state.value.fontSizeSp)

        val file = Files.createTempFile("controller-save", ".txt").toFile()
        controller.save(Uri.fromFile(file))
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline && !file.readText().contains("trace line")) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertEquals("type: crash\ntrace line\n", file.readText())
    }

    @Test
    fun `filter and extra-action hooks are no-ops in the base controller`() {
        val activity = activity()
        val vm = loadedViewModel()
        val controller = LogViewerController(activity, vm)
        val before = vm.state.value
        controller.setBuffers(listOf("radio"))
        controller.setLevel(LogLevel.Assert)
        controller.setFilter("anything")
        controller.perform(ExtraAction.MoreInfo)
        controller.perform(ExtraAction.ShowAppLog)
        assertEquals(before, vm.state.value)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test
    fun `copyStackTrace copies only when a trace is showing`() {
        val activity = activity()
        val vm = loadedViewModel()
        val controller = LogViewerController(activity, vm)
        controller.copyStackTrace()
        assertNull(clipboardText(activity))

        // Force a save failure so the view model holds a stack trace.
        val dir = Files.createTempDirectory("not-a-file").toFile()
        vm.save(Uri.fromFile(dir))
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline && vm.state.value.stackTrace == null) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        val trace = vm.state.value.stackTrace
        assertNotNull(trace)
        controller.copyStackTrace()
        assertEquals(trace, clipboardText(activity))

        controller.dismissStackTrace()
        assertNull(vm.state.value.stackTrace)
        assertTrue(File(dir.absolutePath).isDirectory)
    }
}
