package com.vayunmathur.notes.ui

import android.app.Application
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.notes.FakeAndroidKeyStore
import com.vayunmathur.notes.data.Note
import com.vayunmathur.notes.platform.NotesViewModel
import com.vayunmathur.notes.seedNotesDb
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ViewModel's editable-note binding was one of the notes report's
 * largest missed classes: its adopt/save/flush logic only ran on device.
 * (The full-screen ink editor itself cannot be composed on a host JVM:
 * androidx.ink loads its native library when the first brush is created,
 * which is the same device-bound ceiling as :library:ink.)
 */
@RunWith(RobolectricTestRunner::class)
class InkEditorAndEditableNoteTest {

    @get:Rule val rule = createComposeRule()

    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val repo get() = seedNotesDb(app)

    @BeforeTest fun purge() {
        FakeAndroidKeyStore.install()
        runBlocking { repo.purge() }
    }

    private fun await(timeoutMs: Long = 8000, cond: () -> Boolean) {
        rule.waitUntil(timeoutMillis = timeoutMs) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            runCatching { cond() }.getOrDefault(false)
        }
        assertTrue(cond(), "condition not met within ${timeoutMs}ms")
    }

    @Test fun editableNoteAdoptsStoredNoteAndSavesEditsDebounced() {
        val storedId = runBlocking { repo.upsert(Note(title = "Stored Title", content = "body")) }
        val vm = NotesViewModel(app, repo)
        lateinit var state: MutableState<Note>
        rule.setContent { state = vm.editableNote(storedId) { Note(0, "", "") } }
        // The stored note is adopted once its flow emits
        await { state.value.id == storedId && state.value.title == "Stored Title" }
        // An edit is written back under the same id after the debounce window
        rule.runOnIdle { state.value = state.value.copy(title = "Edited Title") }
        await { runBlocking { repo.getAll() }.singleOrNull()?.title == "Edited Title" }
        assertEquals(storedId, runBlocking { repo.getAll() }.single().id)
    }

    @Test fun editableNoteFlushesPendingEditWhenEditorLeaves() {
        val vm = NotesViewModel(app, repo)
        lateinit var state: MutableState<Note>
        var include by androidx.compose.runtime.mutableStateOf(true)
        rule.setContent {
            if (include) state = vm.editableNote(0L) { Note(0, "", "") }
        }
        rule.runOnIdle { state.value = state.value.copy(title = "Unsent Draft") }
        // Leave composition well inside the 300ms debounce window: the
        // dispose-time flush, not the debounced writer, must save it.
        rule.runOnIdle { include = false }
        await { runBlocking { repo.getAll() }.map { it.title } == listOf("Unsent Draft") }
    }

    @Test fun editableNoteNewNoteGetsItsAssignedIdOnSave() {
        val vm = NotesViewModel(app, repo)
        lateinit var state: MutableState<Note>
        rule.setContent { state = vm.editableNote(0L) { Note(0, "", "") } }
        rule.runOnIdle { state.value = state.value.copy(title = "Brand New") }
        // First save inserts the note and the local state adopts the new id
        await { state.value.id != 0L }
        val saved = runBlocking { repo.getAll() }
        assertEquals(listOf("Brand New"), saved.map { it.title })
        assertEquals(state.value.id, saved.single().id)
    }
}
