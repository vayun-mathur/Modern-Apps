package com.vayunmathur.code.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.code.domain.syntax.Language
import com.vayunmathur.code.platform.CodeActions
import com.vayunmathur.code.platform.CodeUiState
import com.vayunmathur.code.platform.TabUiState
import com.vayunmathur.code.platform.TreeRowUiState
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.code.data.EditorPrefs
import com.vayunmathur.code.data.FileFiles
import com.vayunmathur.code.platform.EditorViewModel

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/** Expanded desktop window for the wide tree+editor layout. */
private const val EXPANDED = "spec:width=1280dp,height=800dp,dpi=420"

/** The file in the foreground tab. Chosen to exercise every token kind the highlighter knows. */
private val SAMPLE_KOTLIN = """
    package com.vayunmathur.code.util

    import android.content.ContentResolver
    import android.net.Uri
    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.withContext

    /**
     * Reads and writes documents through the Storage Access Framework, so the
     * editor never has to ask for a storage permission.
     */
    class DocumentStore(private val resolver: ContentResolver) {

        // Display names are stable for the lifetime of a tree, so cache them.
        private val names = HashMap<String, String>()

        suspend fun read(uri: Uri): String = withContext(Dispatchers.IO) {
            resolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "cannot open document" }
                input.bufferedReader().readText()
            }
        }

        suspend fun write(uri: Uri, text: String) = withContext(Dispatchers.IO) {
            // "wt" truncates first, so shrinking a file leaves no stale bytes.
            resolver.openOutputStream(uri, "wt")?.use { output ->
                output.write(text.toByteArray(Charsets.UTF_8))
                output.flush()
            }
        }

        fun displayName(id: String, fallback: String = "untitled"): String =
            names[id] ?: fallback
    }
""".trimIndent()

/**
 * Store listing images for `:code`, rendered from Compose previews instead of from an
 * instrumented test on a device. See `common-conventions-preview-metadata`.
 *
 * `./gradlew :code:metadata` renders these and copies the PNGs into
 * `metadata_data/photos/code/`, where `release.sh` picks them up.
 *
 * Three things to keep in mind when editing:
 *
 *  - Order comes from the function names. The generated PNG filenames embed them, so
 *    `Preview1Editor`/`Preview2Find`/... sort into listing order. Renumber if you reorder.
 *  - Each preview needs @PreviewTest as well as @Preview. @Preview alone renders in Studio
 *    but is not collected as a screenshot test, which surfaces as "did not discover any
 *    tests". Previews must also be class members, not top-level functions, for the same
 *    reason: the engine discovers them as JUnit tests.
 *  - Everything is a literal. There is no ViewModel here and no SAF provider to browse, so
 *    the tree and the open tabs below are the whole input.
 */
class MetadataPreviews {

    /** The tab strip as it looks with a project open; the first tab is the one being edited. */
    private fun tabs() = listOf(
        TabUiState(
            name = "DocumentStore.kt",
            value = TextFieldValue(SAMPLE_KOTLIN),
            language = Language.KOTLIN,
            isDirty = true,
            canUndo = true,
        ),
        TabUiState(name = "EditorViewModel.kt", language = Language.KOTLIN),
        TabUiState(name = "build.gradle.kts", language = Language.KOTLIN),
    )

    private fun state() = CodeUiState(
        tabs = tabs(),
        currentIndex = 0,
        rootName = "code",
        folderOpen = true,
        nodes = listOf(
            TreeRowUiState("src", depth = 0, isDirectory = true, expanded = true),
            TreeRowUiState("main", depth = 1, isDirectory = true, expanded = true),
            TreeRowUiState("syntax", depth = 2, isDirectory = true),
            TreeRowUiState("ui", depth = 2, isDirectory = true, expanded = true),
            TreeRowUiState("CodeEditor.kt", depth = 3),
            TreeRowUiState("EditorScreen.kt", depth = 3),
            TreeRowUiState("FileTreePane.kt", depth = 3),
            TreeRowUiState("util", depth = 2, isDirectory = true, expanded = true),
            TreeRowUiState("DocumentStore.kt", depth = 3),
            TreeRowUiState("EditorPrefs.kt", depth = 3),
            TreeRowUiState("EditorViewModel.kt", depth = 3),
            TreeRowUiState("FileFiles.kt", depth = 3),
            TreeRowUiState("AndroidManifest.xml", depth = 2),
            TreeRowUiState("screenshotTest", depth = 1, isDirectory = true),
            TreeRowUiState("build.gradle.kts", depth = 0),
            TreeRowUiState("README.md", depth = 0),
        ),
    )

    @PreviewTest
    @Preview(name = "1-editor", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1Editor() {
        DynamicTheme(darkTheme = true) {
            EditorScreen(state = state(), actions = CodeActions.Noop)
        }
    }

    @PreviewTest
    @Preview(name = "2-find", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview2Find() {
        DynamicTheme(darkTheme = true) {
            EditorScreen(state = state(), actions = CodeActions.Noop, initialFind = "resolver")
        }
    }

    @PreviewTest
    @Preview(name = "3-files", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview3Files() {
        DynamicTheme(darkTheme = true) {
            EditorScreen(state = state(), actions = CodeActions.Noop, initialDrawerOpen = true)
        }
    }

    @PreviewTest
    @Preview(name = "4-expanded", device = EXPANDED, group = "large-tablet", showSystemUi = true)
    @Composable
    fun Preview4Expanded() {
        DynamicTheme(darkTheme = true) {
            val uiState = state()
            CodeWideLayout(
                tree = { FileTreePane(state = uiState, actions = CodeActions.Noop, onOpenFolder = {}, onOpenFile = {}, onFileOpened = {}) },
                editor = { EditorScreen(state = uiState, actions = CodeActions.Noop) },
                terminal = {},
            )
        }
    }
}
