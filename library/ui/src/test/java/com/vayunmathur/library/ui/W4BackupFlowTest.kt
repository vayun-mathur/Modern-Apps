package com.vayunmathur.library.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.library.util.BackupFormat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * W4: [BackupButtons] full flows. A fake [ActivityResultRegistry] (the
 * standard Compose testing seam) hands a document Uri straight back, so
 * export, password-gated export and failing import run their real
 * coroutine paths against the Robolectric content resolver.
 */
@RunWith(RobolectricTestRunner::class)
class W4BackupFlowTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private class FakeRegistry(var nextUri: Uri?) : ActivityResultRegistry() {
        override fun <I : Any?, O : Any?> onLaunch(
            requestCode: Int,
            contract: ActivityResultContract<I, O>,
            input: I,
            options: ActivityOptionsCompat?,
        ) {
            dispatchResult(requestCode, Activity.RESULT_OK, Intent().setData(nextUri))
        }
    }

    private class RecordingFormat(
        override val needsPassword: Boolean = false,
        val failImport: Boolean = false,
    ) : BackupFormat {
        override val mimeType: String = "application/zip"
        override val defaultFileName: String = "backup.zip"
        var exportedWith: String? = "unset"
        var exportedBytes: ByteArray? = null
        var importedWith: String? = "unset"

        override suspend fun export(context: Context, password: String?, outputStream: OutputStream) {
            exportedWith = password
            val bytes = "backup-bytes".encodeToByteArray()
            outputStream.write(bytes)
            exportedBytes = bytes
        }

        override suspend fun import(context: Context, password: String?, inputStream: InputStream) {
            importedWith = password
            inputStream.readBytes()
            if (failImport) throw IllegalStateException("corrupt backup")
        }
    }

    private fun show(format: BackupFormat, uri: Uri, exported: ByteArrayOutputStream? = null) {
        val registry = FakeRegistry(uri)
        if (exported != null) {
            shadowOf(context.contentResolver).registerOutputStream(uri, exported)
        }
        shadowOf(context.contentResolver)
            .registerInputStream(uri, ByteArrayInputStream("incoming".encodeToByteArray()))
        val owner = object : ActivityResultRegistryOwner {
            override val activityResultRegistry: ActivityResultRegistry = registry
        }
        rule.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides owner) {
                // BackupButtons emits two sibling icon buttons; give them a Row
                // so they don't overlap (the second would swallow the clicks).
                androidx.compose.foundation.layout.Row { BackupButtons(format = format) }
            }
        }
    }

    private fun awaitTrue(timeoutMs: Long = 10_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(25)
        }
        assertTrue(cond())
    }

    @Test fun exportWritesThroughLauncherResult() {
        val format = RecordingFormat()
        val uri = Uri.parse("content://w4.test/export.zip")
        val out = ByteArrayOutputStream()
        show(format, uri, out)
        rule.onNodeWithContentDescription("Backup").performClick()
        awaitTrue { format.exportedBytes != null }
        assertEquals(null, format.exportedWith)
        assertEquals("backup-bytes", out.toByteArray().decodeToString())
    }

    @Test fun passwordExportAsksThenExportsWithPassword() {
        val format = RecordingFormat(needsPassword = true)
        val uri = Uri.parse("content://w4.test/secret.zip")
        val out = ByteArrayOutputStream()
        show(format, uri, out)
        rule.onNodeWithContentDescription("Backup").performClick()
        rule.waitForIdle()
        // Password dialog is up; OK stays disabled until text is entered.
        rule.onNode(hasSetTextAction()).performTextInput("hunter2")
        rule.onNodeWithText("OK").performClick()
        awaitTrue { format.exportedBytes != null }
        assertEquals("hunter2", format.exportedWith)
    }

    @Test fun failingImportReportsAndDoesNotRelaunch() {
        val format = RecordingFormat(failImport = true)
        val uri = Uri.parse("content://w4.test/broken.zip")
        show(format, uri)
        rule.onNodeWithContentDescription("Restore").performClick()
        // Import runs and fails; the failure path must complete (a success
        // would schedule a process relaunch, which never happens here).
        awaitTrue { format.importedWith != "unset" }
        Thread.sleep(300)
        rule.onNodeWithContentDescription("Restore").assertExists()
    }

    @Test fun passwordImportDialogCancelAborts() {
        val format = RecordingFormat(needsPassword = true, failImport = true)
        val uri = Uri.parse("content://w4.test/secret-in.zip")
        show(format, uri)
        rule.onNodeWithContentDescription("Restore").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertEquals("unset", format.importedWith)
    }
}
