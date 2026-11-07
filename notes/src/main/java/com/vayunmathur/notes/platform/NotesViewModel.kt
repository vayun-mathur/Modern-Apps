package com.vayunmathur.notes.platform

import android.app.Application
import android.net.Uri
import com.vayunmathur.library.log.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.vayunmathur.library.util.IntentHelper
import com.vayunmathur.library.util.parseMarkdown
import com.vayunmathur.notes.data.Note
import com.vayunmathur.notes.data.NotesRepository
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

class NotesViewModel(
    application: Application,
    private val repository: NotesRepository,
) : AndroidViewModel(application) {

    val notes: StateFlow<List<Note>> = repository.notes
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Ids deleted from the editor, so a dispose-time flush of a pending edit
    // cannot resurrect a note the user just deleted.
    private val deletedNoteIds = mutableSetOf<Long>()

    fun delete(note: Note) {
        synchronized(deletedNoteIds) { deletedNoteIds.add(note.id) }
        viewModelScope.launch(Dispatchers.IO) { repository.delete(note) }
    }

    fun upsertAll(notes: List<Note>) {
        viewModelScope.launch(Dispatchers.IO) { repository.upsertAll(notes) }
    }

    @OptIn(FlowPreview::class)
    @Composable
    fun editableNote(initialId: Long, default: () -> Note): MutableState<Note> {
        var currentId by remember { mutableLongStateOf(initialId) }

        val noteFlow = remember(currentId) { repository.noteByIdFlow(currentId) }
        val dbNote by noteFlow.collectAsStateWithLifecycle(initialValue = null)

        val localState = remember { mutableStateOf<Note?>(null) }
        val pendingWrites = remember { MutableStateFlow<Note?>(null) }

        // Only adopt the database copy when there is no unsaved local edit.
        // Unconditionally copying dbNote here reverted the title field to the
        // last saved value whenever a save landed mid-typing, dropping the
        // keystrokes typed since that save was enqueued.
        LaunchedEffect(dbNote, currentId) {
            val db = dbNote ?: return@LaunchedEffect
            if (localState.value == null || pendingWrites.value == null) {
                localState.value = db
            }
        }

        LaunchedEffect(Unit) {
            pendingWrites.filterNotNull().debounce(SAVE_DEBOUNCE_MS).collectLatest { newValue ->
                // A brand-new note keeps id 0 in local state until the flow
                // round-trips; save under the already-assigned id instead of
                // inserting a duplicate row per debounce window.
                val toSave = if (newValue.id == 0L && currentId != 0L) {
                    newValue.copy(id = currentId)
                } else {
                    newValue
                }
                val newId = withContext(Dispatchers.IO) { repository.upsert(toSave) }
                if (currentId == 0L) {
                    currentId = newId
                    if (localState.value?.id == 0L) {
                        localState.value = localState.value?.copy(id = newId)
                    }
                }
                if (pendingWrites.value == newValue) pendingWrites.value = null
            }
        }

        // Leaving the editor inside the debounce window must not lose the
        // last edit: flush any pending write from the ViewModel scope (which
        // outlives the composition), unless the note was just deleted.
        DisposableEffect(Unit) {
            onDispose {
                val pending = pendingWrites.value ?: return@onDispose
                val wasDeleted = synchronized(deletedNoteIds) { pending.id in deletedNoteIds }
                if (!wasDeleted) {
                    val toSave = if (pending.id == 0L && currentId != 0L) {
                        pending.copy(id = currentId)
                    } else {
                        pending
                    }
                    viewModelScope.launch(Dispatchers.IO) { repository.upsert(toSave) }
                }
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

    private val parsedCache = object : LinkedHashMap<ParsedKey, AnnotatedString>(32, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<ParsedKey, AnnotatedString>,
        ): Boolean = size > 32
    }

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

    fun searchResultsCount(content: String, searchText: String): Int {
        if (searchText.isEmpty()) return 0
        val text = parseDisplay(content).text
        return Regex(Regex.escape(searchText), RegexOption.IGNORE_CASE).findAll(text).count()
    }

    data class NoteShare(val uri: Uri, val markdown: String)

    private val _shareRequests = MutableSharedFlow<NoteShare>(extraBufferCapacity = 1)
    val shareRequests: SharedFlow<NoteShare> = _shareRequests.asSharedFlow()

    // Best-effort import of external files: any failure mode means "skip this
    // file" per this function's contract, and each failure is logged below.
    @Suppress("TooGenericExceptionCaught")
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
                        repository.upsert(Note(0, name, content))
                    }
                } catch (e: Exception) {
                    Log.error(TAG, "Error importing file: $uri", e)
                }
            }
        }
    }

    fun requestShare(note: Note) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val share = withContext(Dispatchers.IO) {
                val markdown = exportNoteMarkdown(ctx, note)
                val cachePath = File(ctx.cacheDir, "shared_notes")
                cachePath.mkdirs()
                val file = File(cachePath, shareMarkdownFileName(note.title))
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

    private val _externalOpens = MutableStateFlow<List<String>>(emptyList())
    val externalOpens: StateFlow<List<String>> = _externalOpens.asStateFlow()

    fun openExternal(uris: List<Uri>) {
        if (uris.isEmpty()) return
        _externalOpens.value = _externalOpens.value + uris.map { it.toString() }
    }

    fun consumeExternal(uri: String) {
        _externalOpens.value = _externalOpens.value.filterNot { it == uri }
    }

    data class ExternalNoteContent(val title: String, val content: String)

    // Best-effort read of an external note: any failure mode means null per
    // this function's contract, and the failure is logged below.
    @Suppress("TooGenericExceptionCaught")
    suspend fun readExternal(uriString: String): ExternalNoteContent? = withContext(Dispatchers.IO) {
        val ctx = getApplication<Application>()
        try {
            val uri = uriString.toUri()
            val content = ctx.contentResolver.openInputStream(uri)
                ?.bufferedReader()?.use { it.readText() } ?: return@withContext null
            val name = IntentHelper.getFileName(ctx, uri) ?: "Untitled"
            ExternalNoteContent(name.removeSuffix(".markdown").removeSuffix(".md"), content)
        } catch (e: Exception) {
            Log.error(TAG, "Error reading external note: $uriString", e)
            null
        }
    }

    // Best-effort save to an external note: any failure mode means false per
    // this function's contract, and the failure is logged below.
    @Suppress("TooGenericExceptionCaught")
    fun saveExternal(uriString: String, content: String, onResult: (Boolean) -> Unit) {
        val ctx = getApplication<Application>()
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    ctx.contentResolver.openOutputStream(uriString.toUri(), "wt")?.use {
                        it.write(content.toByteArray())
                    } ?: return@withContext false
                    true
                } catch (e: Exception) {
                    Log.error(TAG, "Error saving external note: $uriString", e)
                    false
                }
            }
            onResult(ok)
        }
    }

    fun addExternalToApp(title: String, content: String, onAdded: (Long) -> Unit) {
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) { repository.upsert(Note(0, title, content)) }
            onAdded(id)
        }
    }

    companion object {
        private const val TAG = "NotesViewModel"
        private const val SAVE_DEBOUNCE_MS = 300L
    }
}

/** Factory for constructing [NotesViewModel] with the repository. */
class NotesViewModelFactory(
    private val application: Application,
    private val repository: NotesRepository,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(NotesViewModel::class.java)) { "Unexpected ViewModel class: $modelClass" }
        return NotesViewModel(application, repository) as T
    }
}
