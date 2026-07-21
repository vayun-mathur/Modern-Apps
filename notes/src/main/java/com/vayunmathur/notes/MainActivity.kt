package com.vayunmathur.notes

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.vayunmathur.library.ui.DynamicTheme
import com.vayunmathur.library.util.IntentHelper
import com.vayunmathur.library.util.ListDetailPage
import com.vayunmathur.library.util.ListPage
import com.vayunmathur.library.util.MainNavigation
import com.vayunmathur.library.util.NavKey
import com.vayunmathur.library.room.buildDatabase
import com.vayunmathur.library.util.closeCachedDatabase
import com.vayunmathur.library.util.onFileDrop
import com.vayunmathur.library.util.rememberNavBackStack
import com.vayunmathur.notes.data.DB_NAME
import com.vayunmathur.notes.data.Note
import com.vayunmathur.notes.data.NoteDao
import com.vayunmathur.notes.data.NoteDatabase
import com.vayunmathur.notes.ui.NotePage
import com.vayunmathur.notes.ui.NotesListPage
import com.vayunmathur.notes.ui.ExternalNoteScreen
import com.vayunmathur.notes.util.NotesViewModel
import com.vayunmathur.notes.util.NotesViewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable

class MainActivity : ComponentActivity() {
    private lateinit var noteDao: NoteDao
    private val notesViewModel: NotesViewModel by viewModels {
        NotesViewModelFactory(application, noteDao)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val ready = mutableStateOf(false)
        lifecycleScope.launch(Dispatchers.IO) {
            val legacyNotes = readAndClearLegacyNotes()
            noteDao = buildDatabase<NoteDatabase>(dbName = DB_NAME).noteDao()
            if (legacyNotes.isNotEmpty()) noteDao.upsertAll(legacyNotes)
            withContext(Dispatchers.Main) {
                handleIntent(intent)
                ready.value = true
            }
        }

        setContent {
            DynamicTheme {
                Box(Modifier.fillMaxSize().onFileDrop { uris ->
                    notesViewModel.importFiles(uris)
                }) {
                    if (ready.value) Navigation(notesViewModel)
                }
            }
        }
    }

    /**
     * Reads notes from the legacy "passwords-db" (the notes app's former
     * database name) off the main thread, then removes it. Must run before the
     * current database is built because [buildDatabase] caches by class.
     */
    private suspend fun readAndClearLegacyNotes(): List<Note> {
        if (!getDatabasePath(LEGACY_DB_NAME).exists()) return emptyList()
        return try {
            buildDatabase<NoteDatabase>().noteDao().getAll()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read legacy notes database", e)
            emptyList()
        } finally {
            closeCachedDatabase<NoteDatabase>()
            deleteDatabase(LEGACY_DB_NAME)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (::noteDao.isInitialized) handleIntent(intent)
    }

    /**
     * External VIEW/EDIT/SEND opens are shown on their own [ExternalNoteScreen]
     * (a standalone markdown editor) instead of being auto-added to the DB.
     */
    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uris = IntentHelper.getUrisFromIntent(intent).ifEmpty { listOfNotNull(intent.data) }
        notesViewModel.openExternal(uris)
    }

    companion object {
        private const val TAG = "NotesMainActivity"
        private const val LEGACY_DB_NAME = "passwords-db"
    }
}

@Serializable
sealed interface Route: NavKey {
    @Serializable
    data object NotesList: Route
    @Serializable
    data class Note(val id: Long): Route
    @Serializable
    data class ExternalNote(val uri: String): Route
}

@Composable
fun Navigation(notesViewModel: NotesViewModel) {
    val backStack = rememberNavBackStack<Route>(Route.NotesList)
    val externalOpens by notesViewModel.externalOpens.collectAsStateWithLifecycle()
    LaunchedEffect(externalOpens) {
        externalOpens.firstOrNull()?.let { uri ->
            notesViewModel.consumeExternal(uri)
            backStack.add(Route.ExternalNote(uri))
        }
    }
    MainNavigation(backStack) {
        entry<Route.NotesList>(metadata = ListPage()) {
            NotesListPage(backStack, notesViewModel)
        }
        entry<Route.Note>(metadata = ListDetailPage()) {
            NotePage(backStack, notesViewModel, it.id)
        }
        entry<Route.ExternalNote>(metadata = ListDetailPage()) {
            ExternalNoteScreen(backStack, notesViewModel, it.uri)
        }
    }
}
