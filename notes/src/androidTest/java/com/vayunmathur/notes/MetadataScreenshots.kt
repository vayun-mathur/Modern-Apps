package com.vayunmathur.notes

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.vayunmathur.notes.data.Note
import com.vayunmathur.notes.util.NotesViewModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Screenshot generator driven by `:notes:metadata`. Seeds a few sample notes (via the
 * ViewModel, exactly like clock's alarms) so the list isn't empty.
 */
@RunWith(AndroidJUnit4::class)
class MetadataScreenshots {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    private val outDir: File by lazy {
        File(ctx.getExternalFilesDir(null), "metadata_screenshots").apply {
            deleteRecursively()
            mkdirs()
        }
    }

    private fun snap(index: Int) {
        composeRule.waitForIdle()
        val image = composeRule.onRoot().captureToImage()
        File(outDir, "$index.png").outputStream().use { out ->
            image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    private val samples = listOf(
        "Weekend plans" to "Saturday: farmers market + hike at Twin Peaks. Sunday: brunch with Sam, then finish the book.",
        "Groceries" to "Oat milk, eggs, sourdough, spinach, cherry tomatoes, olive oil, dark chocolate.",
        "Project ideas" to "1. Auto-generate app screenshots 2. Offline maps tiles 3. A tiny synth in Compose.",
        "Meeting notes" to "Ship the metadata pipeline. Add dark-mode shots. Review the release checklist.",
        "Books to read" to "Project Hail Mary • The Pragmatic Programmer • Piranesi • Thinking in Systems.",
    )

    @Test
    fun generateStoreScreenshots() {
        // The database is built asynchronously on launch; wait for the list to be ready.
        Thread.sleep(1500)
        val vm = composeRule.runOnUiThread<NotesViewModel> {
            ViewModelProvider(composeRule.activity)[NotesViewModel::class.java]
        }
        composeRule.runOnUiThread {
            vm.upsertAll(
                samples.mapIndexed { i, (title, content) ->
                    Note(title = title, content = content, position = i.toDouble())
                }
            )
        }
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText(samples[0].first).fetchSemanticsNodes().isNotEmpty()
        }
        snap(1) // populated notes list
    }
}
