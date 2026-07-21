package com.vayunmathur.notes.util

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.IntentHelper
import com.vayunmathur.library.util.parseMarkdown
import com.vayunmathur.notes.data.Note
import com.vayunmathur.notes.data.NoteDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * ViewModel for the Notes app.
 *
 * Owns:
 *  - the notes [StateFlow] (collected by screens)
 *  - DB write helpers (upsert / delete / upsertAll) that dispatch on IO
 *  - per-note editable state for the editor screen
 *  - file import / drop handling (content-resolver + DB upsert)
 *  - share-URI generation (cache file write + FileProvider URI)
 *  - parsed-markdown cache (process-wide, keyed by content + search context)
 */
class NotesViewModel(
    application: Application,
    private val noteDao: NoteDao,
) : AndroidViewModel(application) {

    val notes: StateFlow<List<Note>> = noteDao.getAllFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(note: Note) {
        viewModelScope.launch(Dispatchers.IO) { noteDao.delete(note) }
    }

    fun upsertAll(notes: List<Note>) {
        viewModelScope.launch(Dispatchers.IO) { noteDao.upsertAll(notes) }
    }

    /**
     * Returns a [MutableState] backed by the DB row with the given id.
     *
     * On set, the new value is optimistically published locally and pushed to
     * the database off-thread, debounced so a burst of keystrokes collapses to a
     * single upsert. If [initialId] was 0L (a new row), the id is updated after
     * the first upsert returns so subsequent edits target the same row.
     */
    @OptIn(FlowPreview::class)
    @Composable
    fun editableNote(initialId: Long, default: () -> Note): MutableState<Note> {
        var currentId by remember { mutableLongStateOf(initialId) }

        val noteFlow = remember(currentId) { noteDao.getByIdFlow(currentId) }
        val dbNote by noteFlow.collectAsStateWithLifecycle(initialValue = null)

        val localState = remember { mutableStateOf<Note?>(null) }

        LaunchedEffect(dbNote, currentId) {
            dbNote?.let { localState.value = it }
        }

        // Debounce writes so a burst of keystrokes results in a single DB upsert
        // once the user pauses, instead of one write per character.
        val pendingWrites = remember { MutableStateFlow<Note?>(null) }
        LaunchedEffect(Unit) {
            pendingWrites.filterNotNull().debounce(300).collectLatest { newValue ->
                val newId = withContext(Dispatchers.IO) { noteDao.upsert(newValue) }
                if (currentId == 0L) currentId = newId
            }
        }

        return remember {
            object : MutableState<Note> {
                override var value: Note
                    get() = localState.value ?: default()
                    set(newValue) {
                        localState.value = newValue
                        pendingWrites.value = newValue
                    }

                override fun component1(): Note = value
                override fun component2(): (Note) -> Unit = { value = it }
            }
        }
    }

    private data class ParsedKey(
        val content: String,
        val searchQuery: String,
        val searchIndex: Int,
    )

    // Simple LRU cache for parsed markdown AnnotatedStrings. Capped to a small
    // size to avoid retaining every note ever opened in memory. The current note
    // is hot, and switching back and forth between a few notes stays cached.
    private val parsedCache = object : LinkedHashMap<ParsedKey, AnnotatedString>(32, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<ParsedKey, AnnotatedString>,
        ): Boolean = size > 32
    }

    /**
     * Returns the cached parsed AnnotatedString for [content] using the
     * "display" parameters (no markers, no soft-wrap, no preprocessing).
     */
    @Synchronized
    fun parseDisplay(
        content: String,
        searchQuery: String = "",
        searchIndex: Int = -1,
    ): AnnotatedString {
        val key = ParsedKey(content, searchQuery, searchIndex)
        parsedCache[key]?.let { return it }
        val parsed = parseMarkdown(
            content,
            showMarkers = false,
            process = false,
            softWrap = false,
            searchQuery = searchQuery,
            searchIndex = searchIndex,
        )
        parsedCache[key] = parsed
        return parsed
    }

    /** Counts case-insensitive occurrences of [searchText] in the parsed text of [content]. */
    fun searchResultsCount(content: String, searchText: String): Int {
        if (searchText.isEmpty()) return 0
        val text = parseDisplay(content).text
        return Regex(Regex.escape(searchText), RegexOption.IGNORE_CASE).findAll(text).count()
    }

    /** A ready-to-share note: the `.md` file [uri] plus its [markdown] (EXTRA_TEXT fallback). */
    data class NoteShare(val uri: Uri, val markdown: String)

    private val _shareRequests = MutableSharedFlow<NoteShare>(extraBufferCapacity = 1)
    /** Emits a [NoteShare] each time [requestShare] completes. */
    val shareRequests: SharedFlow<NoteShare> = _shareRequests.asSharedFlow()

    /**
     * Reads each [uris] entry off the main thread and upserts it as a new [Note]
     * via [noteDao]. Errors are logged per-file and do not abort the batch.
     */
    fun importFiles(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val ctx = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) {
            uris.forEach { uri ->
                try {
                    val content = ctx.contentResolver.openInputStream(uri)
                        ?.bufferedReader()?.use { it.readText() }
                    if (content != null) {
                        val name = IntentHelper.getFileName(ctx, uri) ?: "Imported Note"
                        noteDao.upsert(Note(0, name, content))
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error importing file: $uri", e)
                }
            }
        }
    }

    /**
     * Exports [note] to a single self-contained Markdown document (images inlined,
     * drawings as SVG), writes it to the share cache as a `.md` file off the main
     * thread, then emits a [NoteShare] on [shareRequests]. Composables collect this
     * flow and dispatch the actual ACTION_SEND intent.
     */
    fun requestShare(note: Note) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val share = withContext(Dispatchers.IO) {
                val markdown = exportNoteMarkdown(ctx, note)
                val cachePath = File(ctx.cacheDir, "shared_notes")
                cachePath.mkdirs()
                val file = File(cachePath, "${note.title.ifBlank { "note" }}.md")
                file.writeText(markdown)
                val uri = FileProvider.getUriForFile(
                    ctx,
                    "${ctx.packageName}.fileprovider",
                    file,
                )
                NoteShare(uri, markdown)
            }
            _shareRequests.emit(share)
        }
    }

    // Externally-opened files (VIEW/EDIT/SEND intents) waiting to be shown on the
    // ExternalNoteScreen. Unlike [importFiles], these are NOT added to the DB;
    // navigation drains this queue one URI at a time.
    private val _externalOpens = MutableStateFlow<List<String>>(emptyList())
    val externalOpens: StateFlow<List<String>> = _externalOpens.asStateFlow()

    /** Queue external [uris] to open on the ExternalNoteScreen (no DB write). */
    fun openExternal(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _externalOpens.value = _externalOpens.value + uris.map { it.toString() }
    }

    /** Remove [uri] from the external-open queue once navigation has handled it. */
    fun consumeExternal(uri: String) {
        _externalOpens.value = _externalOpens.value.filterNot { it == uri }
    }

    /** Title (file name without extension) + raw markdown content of an external note. */
    data class ExternalNoteContent(val title: String, val content: String)

    /** Reads an externally-opened markdown file. Returns null if it can't be read. */
    suspend fun readExternal(uriString: String): ExternalNoteContent? = withContext(Dispatchers.IO) {
        val ctx = getApplication<Application>()
        try {
            val uri = Uri.parse(uriString)
            val content = ctx.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() } ?: return@withContext null
            val name = IntentHelper.getFileName(ctx, uri) ?: "Untitled"
            ExternalNoteContent(name.removeSuffix(".markdown").removeSuffix(".md"), content)
        } catch (e: Exception) {
            Log.e(TAG, "Error reading external note: $uriString", e)
            null
        }
    }

    /**
     * Writes [content] back to the external file [uriString]. Invokes [onResult]
     * on the main thread with whether the write succeeded (read-only URIs fail).
     */
    fun saveExternal(uriString: String, content: String, onResult: (Boolean) -> Unit) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.openOutputStream(Uri.parse(uriString), "wt")?.use {
                        it.write(content.toByteArray())
                    } ?: return@withContext false
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "Error saving external note: $uriString", e)
                    false
                }
            }
            onResult(ok)
        }
    }

    /**
     * Imports an external note into the app DB as a new [Note] and invokes
     * [onAdded] on the main thread with the new row id.
     */
    fun addExternalToApp(title: String, content: String, onAdded: (Long) -> Unit) {
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) { noteDao.upsert(Note(0, title, content)) }
            onAdded(id)
        }
    }

    companion object {
        private const val TAG = "NotesViewModel"
    }
}

/** Factory for constructing [NotesViewModel] with the [NoteDao]. */
class NotesViewModelFactory(
    private val application: Application,
    private val noteDao: NoteDao,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(NotesViewModel::class.java)) {
            "Unexpected ViewModel class: $modelClass"
        }
        return NotesViewModel(application, noteDao) as T
    }
}
