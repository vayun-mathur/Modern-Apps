package com.vayunmathur.notes.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.notes.data.Note
import com.vayunmathur.notes.data.NoteBlock
import com.vayunmathur.notes.platform.NoteActions
import com.vayunmathur.notes.platform.NoteUiState
import com.vayunmathur.notes.platform.NotesListActions
import com.vayunmathur.notes.platform.NotesListUiState

/** Phone-shaped, roughly 1080x2340 at xxhdpi — comfortably above the F-Droid minimum. */
private const val PHONE = "spec:width=411dp,height=891dp,dpi=420"

/**
 * Store listing images for `:notes`. See `common-conventions-preview-metadata`.
 *
 * Each preview needs @PreviewTest as well as @Preview: @Preview alone renders in Studio but
 * is not collected as a screenshot test. Previews must also be class members, not top-level
 * functions. Order comes from the function names (Preview1…, Preview2…).
 *
 * Everything here is a literal — no ViewModel, no database, no device — which is also what
 * makes the images reproducible from a clean checkout. Block ids are spelled out for the
 * same reason: the default is a random UUID, which would change the `key()` identity (and
 * so the render) on every run.
 */
class MetadataPreviews {

    private val samples = listOf(
        Note(
            id = 1,
            title = "Weekend plans",
            content = "Saturday: farmers market + hike at Twin Peaks. Sunday: brunch with Sam, then finish the book.",
            position = 0.0,
            tags = listOf("Personal", "Plans"),
        ),
        Note(
            id = 2,
            title = "Groceries",
            content = "Oat milk, eggs, sourdough, spinach, cherry tomatoes, olive oil, dark chocolate.",
            position = 1.0,
            tags = listOf("Personal", "Lists"),
        ),
        Note(
            id = 3,
            title = "Project ideas",
            content = "1. Auto-generate app screenshots 2. Offline map tiles 3. A tiny synth in Compose.",
            position = 2.0,
            tags = listOf("Work", "Ideas"),
        ),
        Note(
            id = 4,
            title = "Meeting notes",
            content = "Ship the metadata pipeline. Add dark-mode shots. Review the release checklist.",
            position = 3.0,
            tags = listOf("Work"),
        ),
        Note(
            id = 5,
            title = "Books to read",
            content = "Project Hail Mary • The Pragmatic Programmer • Piranesi • Thinking in Systems.",
            position = 4.0,
        ),
    )

    @PreviewTest
    @Preview(name = "1-notes", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview1Notes() {
        DynamicTheme(darkTheme = true) {
            NotesListScreen(
                state = NotesListUiState(notes = samples),
                actions = NotesListActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "2-editor", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview2Editor() {
        DynamicTheme(darkTheme = true) {
            NoteScreen(
                state = NoteUiState(
                    title = "Weekend plans",
                    blocks = listOf(
                        NoteBlock.Text(
                            markdown = """
                                ## Saturday

                                - [x] Farmers market at 9
                                - [ ] Hike at Twin Peaks
                                - [ ] Pick up the framed print

                                ## Sunday

                                Brunch with **Sam** at 11, then finish *Piranesi*.

                                Bring: water, sunscreen, the good camera.
                            """.trimIndent(),
                            id = "weekend-body",
                        ),
                    ),
                    tags = listOf("Personal", "Plans"),
                    knownTags = listOf("Personal", "Work", "Plans", "Lists", "Ideas"),
                ),
                actions = NoteActions.Noop,
            )
        }
    }

    @PreviewTest
    @Preview(name = "3-search", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview3Search() {
        DynamicTheme(darkTheme = true) {
            NotesListScreen(
                state = NotesListUiState(notes = samples),
                actions = NotesListActions.Noop,
                initialSearchQuery = "book",
            )
        }
    }

    /** The tag filter (#913): "Work" selected, so only work notes show and reordering is paused. */
    @PreviewTest
    @Preview(name = "4-tags", device = PHONE, group = "phone", showSystemUi = true)
    @Composable
    fun Preview4Tags() {
        DynamicTheme(darkTheme = true) {
            NotesListScreen(
                state = NotesListUiState(notes = samples),
                actions = NotesListActions.Noop,
                initialTagFilter = "Work",
            )
        }
    }
}
