package com.vayunmathur.desktop.notes

import com.vayunmathur.everysync.core.data.RemoteNote
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Desktop-notes core journey at the vault layer (this module is a JVM CLI
 * shell with no UI and no androidTest source set; its existing tests are
 * kotlin.test JVM tests, so the journey is the user-visible sync cycle):
 * create a note -> it persists across a vault reopen -> edit -> soft-delete
 * leaves a tombstone that still propagates, never a hard removal.
 */
class NoteVaultJourneyTest {

    @Test
    fun `create reopen edit and tombstone-delete journey`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "notevault-${System.nanoTime()}").apply { mkdirs() }
        val note = RemoteNote(uid = "n1", title = "Journey", content = "body", updatedAt = 1_700_000_000_000L)

        NoteVault(dir, "v1").upsert("v1", note)

        // Reopen (new vault instance over the same dir) sees the note.
        val reopened = NoteVault(dir, "v1")
        assertEquals(listOf(note), reopened.listLocal("v1"))

        // Edit keeps identity, bumps content.
        val edited = note.copy(content = "body v2", updatedAt = note.updatedAt + 1)
        reopened.upsert("v1", edited)
        assertEquals("body v2", NoteVault(dir, "v1").listLocal("v1").single().content)

        // Delete is a tombstone: still listed (so the merge sees the deletion), flagged deleted.
        val vault = NoteVault(dir, "v1")
        vault.delete("v1", "n1")
        val afterDelete = vault.listLocal("v1")
        assertEquals(1, afterDelete.size)
        assertTrue(afterDelete.single().deleted)
        assertTrue(vault.localChanges("v1", 0L).single().deleted)
    }
}
