package com.vayunmathur.notes.ui

import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.vayunmathur.library.util.NavBackStack
import com.vayunmathur.notes.R
import com.vayunmathur.notes.Route
import com.vayunmathur.notes.data.Note
import com.vayunmathur.notes.data.NoteBlock
import com.vayunmathur.notes.data.NoteBody
import com.vayunmathur.notes.data.body
import com.vayunmathur.notes.data.randomBlockId
import com.vayunmathur.notes.data.withBody
import com.vayunmathur.notes.domain.canonicalTag
import com.vayunmathur.notes.domain.normalizeTags
import com.vayunmathur.notes.domain.withoutTag
import com.vayunmathur.notes.platform.NoteActions
import com.vayunmathur.notes.platform.NoteImageStore
import com.vayunmathur.notes.platform.NoteUiState
import com.vayunmathur.notes.platform.NotesViewModel
import com.vayunmathur.notes.platform.exportNoteMarkdown
import com.vayunmathur.notes.platform.markdownCacheUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Binds the note with [noteID] to the stateless [NoteScreen]: owns the editable row, the
 * block list, and everything that needs a real device (image picker, clipboard, share).
 */
@Composable
fun NotePage(
    backStack: NavBackStack<Route>,
    notesViewModel: NotesViewModel,
    noteID: Long,
) {
    var note by notesViewModel.editableNote(noteID) { Note(0, "", "") }

    if (noteID != 0L && note.id == 0L) return

    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val shareNoteLabel = stringResource(R.string.share_note)
    val noteClipLabel = stringResource(R.string.note)
    val tagCounts by notesViewModel.tagCounts.collectAsStateWithLifecycle()
    val knownTags = remember(tagCounts) { tagCounts.map { it.name } }

    // Local, editable copy of the note body: an ordered list of inline blocks
    // (text / image / ink). Every change is written back to [note], which persists
    // via the ViewModel's debounced upsert.
    val blocks = remember(noteID) { mutableStateListOf<NoteBlock>().apply { addAll(note.body().blocks) } }
    fun commit() { note = note.withBody(NoteBody(blocks.toList())) }

    var editingInk by remember { mutableStateOf<NoteBlock.Ink?>(null) }
    // Where inserted media lands: the text block that had focus when the picker or the
    // drawing editor was opened. Kept here because the insert completes after a trip out
    // of the app, by which time focus is gone.
    var insertAfterId by remember { mutableStateOf<String?>(null) }

    fun insertBlock(block: NoteBlock) {
        val at = blocks.indexOfFirst { it.id == insertAfterId }.takeIf { it >= 0 }?.plus(1) ?: blocks.size
        blocks.add(at, block)
        // Keep a text block after inserted media so the user can keep typing below it.
        if (block !is NoteBlock.Text && (at + 1 > blocks.lastIndex || blocks[at + 1] !is NoteBlock.Text)) {
            blocks.add(at + 1, NoteBlock.Text("", randomBlockId()))
        }
        commit()
    }

    fun importImage(uri: Uri) {
        scope.launch {
            val name = withContext(Dispatchers.IO) { NoteImageStore.import(context, uri) }
            if (name != null) insertBlock(NoteBlock.Image(name))
        }
    }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) importImage(uri)
    }

    LaunchedEffect(notesViewModel) {
        notesViewModel.shareRequests.collect { share ->
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/markdown"
                putExtra(Intent.EXTRA_STREAM, share.uri)
                // Only inline the markdown as text when it is small. Notes with images
                // embed base64 data URIs, which can be many MB and would overflow the
                // Binder transaction limit (TransactionTooLargeException). In that case
                // the .md file (EXTRA_STREAM) is the payload.
                if (share.markdown.length < 100_000) {
                    putExtra(Intent.EXTRA_TEXT, share.markdown)
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, shareNoteLabel))
        }
    }

    // The drawing editor takes over the whole screen while open.
    val ink = editingInk
    if (ink != null) {
        BackHandler { editingInk = null }
        InkEditor(
            initialStrokes = ink.strokes,
            onDone = { newStrokes ->
                val i = blocks.indexOfFirst { it.id == ink.id }
                if (i >= 0) {
                    blocks[i] = ink.copy(strokes = newStrokes)
                    commit()
                } else {
                    insertBlock(ink.copy(strokes = newStrokes))
                }
                editingInk = null
            },
            onCancel = { editingInk = null },
        )
        return
    }

    NoteScreen(
        state = NoteUiState(title = note.title, blocks = blocks.toList(), tags = note.tags, knownTags = knownTags),
        sharedTextKey = "note-title-$noteID",
        actions = object : NoteActions {
            override fun back() = backStack.pop()

            override fun setTitle(title: String) { note = note.copy(title = title) }

            // Tags save through the same debounced editableNote write as the title,
            // so they get the same updatedAt bump and sync as any other edit.
            override fun addTag(tag: String) {
                val clean = canonicalTag(tag, knownTags) ?: return
                note = note.copy(tags = normalizeTags(note.tags + clean))
            }

            override fun removeTag(tag: String) { note = note.copy(tags = withoutTag(note.tags, tag)) }

            override fun setBlockMarkdown(id: String, markdown: String) {
                val i = blocks.indexOfFirst { it.id == id }
                if (i >= 0) {
                    blocks[i] = NoteBlock.Text(markdown, id)
                    commit()
                }
            }

            override fun addImage(afterBlockId: String?) {
                insertAfterId = afterBlockId
                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }

            override fun addDrawing(afterBlockId: String?) {
                insertAfterId = afterBlockId
                editingInk = NoteBlock.Ink(emptyList(), id = randomBlockId())
            }

            override fun editDrawing(block: NoteBlock.Ink) { editingInk = block }

            override fun resizeImage(id: String, widthFraction: Float) {
                val i = blocks.indexOfFirst { it.id == id }
                val block = blocks.getOrNull(i) as? NoteBlock.Image ?: return
                blocks[i] = block.copy(widthFraction = widthFraction)
                commit()
            }

            override fun moveBlock(id: String, delta: Int) {
                val i = blocks.indexOfFirst { it.id == id }
                val j = i + delta
                if (i < 0 || j < 0 || j > blocks.lastIndex) return
                val moved = blocks.removeAt(i)
                blocks.add(j, moved)
                commit()
            }

            override fun deleteBlock(id: String) {
                val i = blocks.indexOfFirst { it.id == id }
                if (i < 0) return
                val block = blocks[i]
                if (block is NoteBlock.Image) NoteImageStore.delete(context, block.fileName)
                blocks.removeAt(i)
                if (blocks.isEmpty()) blocks.add(NoteBlock.Text("", randomBlockId()))
                commit()
            }

            override fun copyNote() {
                scope.launch {
                    val clip = withContext(Dispatchers.IO) {
                        val markdown = exportNoteMarkdown(context, note)
                        // Big base64 images blow past Binder's ~1MB clip limit
                        // (TransactionTooLargeException), so for large notes copy a
                        // URI to the exported .md instead of the raw text.
                        if (markdown.length < 100_000) {
                            ClipData.newPlainText(noteClipLabel, markdown)
                        } else {
                            val uri = markdownCacheUri(context, note, markdown)
                            ClipData.newUri(context.contentResolver, "note", uri)
                        }
                    }
                    clipboard.setClipEntry(ClipEntry(clip))
                }
            }

            override fun shareNote() = notesViewModel.requestShare(note)

            override fun deleteNote() {
                notesViewModel.delete(note)
                backStack.pop()
            }
        },
    )
}
