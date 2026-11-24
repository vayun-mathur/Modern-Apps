package com.vayunmathur.notes.wear.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.wear.compose.material.MaterialTheme
import com.vayunmathur.notes.wear.data.WearNotesStore
import com.vayunmathur.notes.wear.sync.WearLinkBridge

/**
 * Watch notes entry: recent list, note view and quick-note creation,
 * rendered only from the locally stored snapshot so it works standalone
 * (phone absent, cold start). Watch-originated changes queue in the
 * store and flow back over WearLink; nothing here claims a phone write.
 */
class WearMainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.vayunmathur.library.wearlink.WearLinkPermissions.requestIfNeeded(this)
        val store = WearNotesStore.get(this)
        WearLinkBridge.get(this)
        WearLinkBridge.publishWatchState(this)
        setContent {
            MaterialTheme {
                var route by remember { mutableStateOf<WearRoute>(WearRoute.NoteList) }
                when (val current = route) {
                    WearRoute.NoteList -> WearNotesListScreen(
                        store = store,
                        onOpenNote = { route = WearRoute.NoteView(it) },
                        onCreateNote = { route = WearRoute.CreateNote },
                    )
                    is WearRoute.NoteView -> WearNoteScreen(
                        store = store,
                        syncId = current.syncId,
                        onEdit = { route = WearRoute.EditNote(it) },
                        onBack = { route = WearRoute.NoteList },
                    )
                    WearRoute.CreateNote -> WearNoteEditorScreen(
                        store = store,
                        syncId = null,
                        onDone = { route = WearRoute.NoteList },
                    )
                    is WearRoute.EditNote -> WearNoteEditorScreen(
                        store = store,
                        syncId = current.syncId,
                        onDone = { route = WearRoute.NoteView(current.syncId) },
                    )
                }
            }
        }
    }
}
