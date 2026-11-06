package com.vayunmathur.library.ui

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import com.vayunmathur.library.util.BackupFormat
import java.io.InputStream
import java.io.OutputStream
import kotlin.test.Test
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Slice 3/3: [BackupButtons] renders export/import affordances for a fake
 * format (launchers only fire on tap, so composition is the assertion).
 */
@RunWith(RobolectricTestRunner::class)
class BackupButtonsSliceTest {

    @get:Rule
    val rule = createComposeRule()

    private class FakeFormat(
        override val mimeType: String = "application/zip",
        override val defaultFileName: String = "backup.zip",
        override val needsPassword: Boolean = false,
    ) : BackupFormat {
        override suspend fun export(context: Context, password: String?, outputStream: OutputStream) {
            outputStream.write(1)
        }

        override suspend fun import(context: Context, password: String?, inputStream: InputStream) {
        }
    }

    @Test fun backupButtonsShowExportAndImport() {
        rule.setContent { BackupButtons(format = FakeFormat()) }
        rule.onNodeWithContentDescription("Backup").assertIsDisplayed()
        rule.onNodeWithContentDescription("Restore").assertIsDisplayed()
    }

    @Test fun backupButtonsDefaultOverloadRenders() {
        rule.setContent { BackupButtons() }
        rule.onNodeWithContentDescription("Backup").assertIsDisplayed()
        rule.onNodeWithContentDescription("Restore").assertIsDisplayed()
    }
}
