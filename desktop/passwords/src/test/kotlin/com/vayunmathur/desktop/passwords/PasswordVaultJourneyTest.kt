package com.vayunmathur.desktop.passwords

import com.vayunmathur.everysync.core.data.RemotePassword
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Desktop-passwords core journey at the vault layer (JVM CLI shell, no UI,
 * no androidTest source set; existing tests are kotlin.test JVM tests):
 * add a password -> it persists across a vault reopen -> edit -> delete
 * hard-removes the row, and a deleted-envelope upsert also removes it.
 */
class PasswordVaultJourneyTest {

    @Test
    fun `add reopen edit and delete journey`() = runBlocking {
        val dir = File(System.getProperty("java.io.tmpdir"), "pwvault-${System.nanoTime()}").apply { mkdirs() }
        val entry = RemotePassword(uid = "pw1", name = "Bank", username = "ada", password = "s3cret", updatedAt = 1_700_000_000_000L)

        PasswordVault(dir, "v1").upsert("v1", entry)
        assertEquals(listOf(entry), PasswordVault(dir, "v1").listLocal("v1"))

        val edited = entry.copy(password = "s3cret-v2", updatedAt = entry.updatedAt + 1)
        PasswordVault(dir, "v1").upsert("v1", edited)
        assertEquals("s3cret-v2", PasswordVault(dir, "v1").listLocal("v1").single().password)

        val vault = PasswordVault(dir, "v1")
        vault.delete("v1", "pw1")
        assertTrue(vault.listLocal("v1").isEmpty())

        // A relay tombstone envelope removes the row instead of storing it.
        vault.upsert("v1", entry)
        vault.upsert("v1", entry.copy(deleted = true))
        assertTrue(vault.listLocal("v1").isEmpty())
    }
}
