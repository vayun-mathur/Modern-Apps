package com.vayunmathur.notes.wear.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.tooling.preview.Preview
import androidx.wear.compose.material.MaterialTheme
import com.android.tools.screenshot.PreviewTest
import com.vayunmathur.notes.wear.domain.WearNote

/** Round watch, 454x454px class (227dp at 320dpi). */
private const val ROUND = "spec:width=227dp,height=227dp,dpi=320"

private val fixtureNotes = listOf(
    WearNote(syncId = "n1", title = "Shopping list", body = "Oats, coffee, tomatoes, basil", updatedAt = 3),
    WearNote(syncId = "n2", title = "Trip ideas", body = "Lisbon in spring; book the Alfama flat", updatedAt = 2),
    WearNote(syncId = "n3", title = "Call notes", body = "Renew passport before December", updatedAt = 1),
)

/**
 * Store listing images for `:notes-wear` — every top-level screen/route
 * (list, note detail, editor; create and edit share the editor screen).
 * `./gradlew :notes-wear:metadata` copies renders into `metadata_data/photos/notes-wear/wear/`.
 */
class MetadataPreviews {

    @PreviewTest
    @Preview(name = "1-list-round", device = ROUND, group = "wear")
    @Composable
    fun Preview1ListRound() {
        RoundPreview {
            WearNotesListContent(notes = fixtureNotes, pendingCount = 0, onOpenNote = {}, onCreateNote = {}, onRequestSync = { true })
        }
    }

    @PreviewTest
    @Preview(name = "2-note-round", device = ROUND, group = "wear")
    @Composable
    fun Preview2NoteRound() {
        RoundPreview { WearNoteContent(note = fixtureNotes[0], onEdit = {}, onDelete = {}, onBack = {}) }
    }

    @PreviewTest
    @Preview(name = "3-editor-round", device = ROUND, group = "wear")
    @Composable
    fun Preview3EditorRound() {
        RoundPreview {
            WearNoteEditorContent(initialTitle = fixtureNotes[0].title, initialBody = fixtureNotes[0].body, onSave = { _, _ -> }, onDone = {})
        }
    }
}

/** Round-watch framing: clip Layoutlib's rectangular canvas to the watch shape. */
@Composable
private fun RoundPreview(content: @Composable () -> Unit) {
    MaterialTheme {
        Box(Modifier.fillMaxSize().clip(CircleShape).background(MaterialTheme.colors.background)) { content() }
    }
}
