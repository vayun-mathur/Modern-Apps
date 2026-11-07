package com.vayunmathur.files.ui

import android.app.Application
import android.content.ClipData
import android.content.ClipDescription
import android.view.DragEvent
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.files.platform.FilesViewModel
import com.vayunmathur.files.platform.ShadowEnvironmentDeniedFix
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowEnvironmentDeniedFix::class], qualifiers = "w800dp-h1280dp")
class ShellNotGrantedTest {

    @get:Rule val rule = createComposeRule()

    @Test
    fun `shell shows grant gate when all-files access is missing`() {
        val app: Application = ApplicationProvider.getApplicationContext()
        val vm = FilesViewModel(app)
        ShadowLooper.idleMainLooper()
        assertFalse(vm.isFilesGranted.value)
        val actions = RecordingFilesActions()
        rule.setContent {
            FilesShell(vm, actions) {
                androidx.compose.material3.Text("should-not-show")
            }
        }
        rule.onNodeWithText("Grant All Files Access").assertIsDisplayed()
        // tapping launches the system settings intent via the activity-result
        // launcher; in the compose host that is a no-op result, but must not crash
        rule.onNodeWithText("Grant All Files Access").performClick()
        rule.waitForIdle()
    }
}

@RunWith(RobolectricTestRunner::class)
class DropTargetTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun dragEvent(action: Int, clipData: ClipData? = null): DragAndDropEvent {
        // DragEvent.obtain is a hidden API and DragAndDropEvent's constructor is
        // internal to Compose, so both are built reflectively (Robolectric runs
        // the real framework classes, so this exercises the real code path).
        val event = org.robolectric.util.ReflectionHelpers.callConstructor<DragEvent>(
            DragEvent::class.java,
        )
        org.robolectric.util.ReflectionHelpers.setField(event, "mAction", action)
        org.robolectric.util.ReflectionHelpers.setField(event, "mClipData", clipData)
        org.robolectric.util.ReflectionHelpers.setField(
            event, "mClipDescription", clipData?.description,
        )
        return org.robolectric.util.ReflectionHelpers.callConstructor(
            DragAndDropEvent::class.java,
            org.robolectric.util.ReflectionHelpers.ClassParameter.from(DragEvent::class.java, event),
        )
    }

    @Test
    fun `drop target reports state changes and delivers existing files`() {
        val states = mutableListOf<Boolean>()
        val dropped = mutableListOf<List<File>>()
        val target = dropTarget(
            onDragStateChange = { states += it },
            onDrop = { dropped += it },
        )
        target.onEntered(dragEvent(DragEvent.ACTION_DRAG_ENTERED))
        target.onExited(dragEvent(DragEvent.ACTION_DRAG_EXITED))
        target.onEnded(dragEvent(DragEvent.ACTION_DRAG_ENDED))
        assertEquals(listOf(true, false, false), states)

        val real = File(app.cacheDir, "dropped-${System.nanoTime()}.txt").apply { writeText("x") }
        val clip = ClipData(
            "label",
            arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN),
            ClipData.Item(real.absolutePath),
        )
        clip.addItem(ClipData.Item("/nonexistent/ghost.txt"))
        clip.addItem(ClipData.Item(android.net.Uri.parse("content://x/1")))
        assertTrue(target.onDrop(dragEvent(DragEvent.ACTION_DROP, clip)))
        assertEquals(listOf(listOf(real)), dropped)
        assertEquals(false, states.last())

        // empty and missing clip data are refused
        assertFalse(target.onDrop(dragEvent(DragEvent.ACTION_DROP, null)))
        val noFiles = ClipData(
            "label",
            arrayOf(ClipDescription.MIMETYPE_TEXT_PLAIN),
            ClipData.Item("/nonexistent/only.txt"),
        )
        assertFalse(target.onDrop(dragEvent(DragEvent.ACTION_DROP, noFiles)))
        real.delete()
    }
}
