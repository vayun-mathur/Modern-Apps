package com.vayunmathur.library.util

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.shadows.BackupDataEntity
import org.robolectric.shadows.BackupDataInputBuilder
import org.robolectric.shadows.BackupDataOutputFactory

/**
 * BaseBackupAgent onBackup/onRestore round-trip through Robolectric's backup
 * data streams (the platform FD streams are shadow no-ops, so entities are
 * carried through the shadow factories the way the backup manager does).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LibraryBackupAgentTest {

    class TestAgent : BaseBackupAgent() {
        override val extraFiles: List<File> get() = listOf(extraFile)
        fun attach(ctx: Context) = attachBaseContext(ctx)
        companion object { lateinit var extraFile: File }
    }

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun agent(): TestAgent = TestAgent().apply { attach(ctx) }

    private fun pfd(file: File): ParcelFileDescriptor {
        if (!file.exists()) file.writeBytes(ByteArray(0))
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
    }

    @Test fun backupThenRestoreRoundTripsExtraFile() {
        val agent = agent()
        val extra = File(ctx.cacheDir, "agent-extra.txt").apply { writeText("agent-body") }
        TestAgent.extraFile = extra

        val newStateFile = File(ctx.cacheDir, "backup-state.bin").apply { delete() }
        val output = BackupDataOutputFactory.newInstance()
        agent.onBackup(null, output, pfd(newStateFile))

        val entities = Shadows.shadowOf(output).entities
        assertEquals(1, entities.size)
        val entity = entities.single()
        assertEquals("full_backup", entity.key())
        assertTrue(entity.dataSize() > 0, "zip entity written")
        // The entity payload is the backup zip (local file header signature).
        assertEquals('P'.code.toByte(), entity.data()[0])
        assertEquals('K'.code.toByte(), entity.data()[1])
        assertEquals(8, newStateFile.length(), "state timestamp written")

        extra.delete()
        assertTrue(!extra.exists())
        val restoreState = File(ctx.cacheDir, "restore-state.bin").apply { delete() }
        val input = BackupDataInputBuilder.newBuilder()
            .addEntity(BackupDataEntity.create("unrelated_key", "junk"))
            .addEntity(entity)
            .build()
        agent.onRestore(input, 1, pfd(restoreState))
        assertEquals("agent-body", extra.readText())
        assertEquals(8, restoreState.length())
    }

    @Test fun secondBackupWithPriorStateStillWritesState() {
        val agent = agent()
        val extra = File(ctx.cacheDir, "agent-extra2.txt").apply { writeText("v1") }
        TestAgent.extraFile = extra
        val state1 = File(ctx.cacheDir, "state-a.bin").apply { delete() }
        val first = BackupDataOutputFactory.newInstance()
        agent.onBackup(null, first, pfd(state1))
        assertEquals(1, Shadows.shadowOf(first).entities.size)
        assertEquals(8, state1.length())

        // Re-running with the prior state as oldState exercises the state-read
        // path (rewrite-or-copy, either way a state file must come out).
        val state2 = File(ctx.cacheDir, "state-b.bin").apply { delete() }
        val second = BackupDataOutputFactory.newInstance()
        agent.onBackup(pfd(state1), second, pfd(state2))
        assertEquals(8, state2.length())
    }

    @Test fun restoreWithOnlyUnrelatedEntitiesChangesNothing() {
        val agent = agent()
        val extra = File(ctx.cacheDir, "agent-extra3.txt").apply { writeText("keep") }
        TestAgent.extraFile = extra
        val restoreState = File(ctx.cacheDir, "restore-state3.bin").apply { delete() }
        val input = BackupDataInputBuilder.newBuilder()
            .addEntity(BackupDataEntity.create("something_else", "junk"))
            .build()
        agent.onRestore(input, 1, pfd(restoreState))
        assertEquals("keep", extra.readText())
        // No full_backup entity seen, so no new state is written.
        assertEquals(0, restoreState.length())
    }

    @Test fun backupWithFutureStateCopiesStateUnchanged() {
        val agent = agent()
        val extra = File(ctx.cacheDir, "agent-extra4.txt").apply { writeText("v") }
        TestAgent.extraFile = extra
        // Old state claims a far-future backup time, so nothing is newer: the
        // agent must copy the old state forward instead of writing an entity.
        val oldState = File(ctx.cacheDir, "state-old.bin")
        oldState.writeBytes(java.nio.ByteBuffer.allocate(8).putLong(Long.MAX_VALUE).array())
        val newState = File(ctx.cacheDir, "state-new.bin").apply { delete() }
        val output = BackupDataOutputFactory.newInstance()
        agent.onBackup(pfd(oldState), output, pfd(newState))
        assertTrue(Shadows.shadowOf(output).entities.isEmpty())
        assertEquals(8, newState.length())
        assertEquals(
            Long.MAX_VALUE,
            java.nio.ByteBuffer.wrap(newState.readBytes()).long,
        )
    }

    @Test fun databaseHelperPassphraseLifecycleWithoutKey() {
        val helper = DatabaseHelper(ctx)
        // No passphrase has been stored in this helper's prefs.
        helper.deleteKey() // must not throw even with nothing stored
        try {
            helper.getPassphrase()
            throw AssertionError("expected passphrase lookup to fail with no key")
        } catch (expected: Exception) {
            // IllegalStateException (no IV/passphrase) or a keystore failure on this platform.
            assertTrue(
                expected is IllegalStateException ||
                    expected is java.security.KeyStoreException ||
                    expected.cause is IllegalStateException,
                "$expected",
            )
        }
    }
}
