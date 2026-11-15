package com.vayunmathur.passwords.sync

import android.content.Context
import android.net.Uri
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.vayunmathur.library.util.DatabaseHelper
import com.vayunmathur.passwords.FakeAndroidKeyStore
import com.vayunmathur.passwords.data.PasswordDatabase
import com.vayunmathur.passwords.data.PasswordRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RunKdbxSyncFullPathTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() = FakeAndroidKeyStore.install()

    private fun configureMissingDocument() = runBlocking {
        KdbxSyncSettings.setEnabled(ctx, true)
        KdbxSyncSettings.setDocumentUri(ctx, "content://missing/vault.kdbx")
        KdbxPasswordHelper(ctx).storePassphrase("vault-pw")
    }

    @Test fun dbOverloadWithMissingDocumentRecordsFailure() = runBlocking {
        configureMissingDocument()
        val db = Room.inMemoryDatabaseBuilder(ctx, PasswordDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            // The SAF stream cannot be opened under Robolectric, so the sync
            // aborts as an Error and the failure is recorded for the UI.
            val result = runKdbxSync(ctx, db)
            assertTrue(result is KdbxSyncResult.Error, "expected Error, got $result")
            assertEquals(KdbxSyncSettings.STATUS_ERROR, KdbxSyncSettings.statusFlow(ctx).first())
        } finally {
            db.close()
        }
    }

    @Test fun repositoryOverloadWithMissingDocumentRecordsFailure() = runBlocking {
        configureMissingDocument()
        val result = runKdbxSync(ctx, PasswordRepository.get(ctx))
        assertTrue(result is KdbxSyncResult.Error, "expected Error, got $result")
        assertEquals(KdbxSyncSettings.STATUS_ERROR, KdbxSyncSettings.statusFlow(ctx).first())
    }

    @Test fun dbOverloadWithoutVaultKeyIsNotConfigured() = runBlocking {
        // SharedPrefs survive between tests in this sandbox: clear any key a
        // sibling test stored so "no vault key" really holds here.
        KdbxPasswordHelper(ctx).deleteKey()
        KdbxSyncSettings.setEnabled(ctx, true)
        KdbxSyncSettings.setDocumentUri(ctx, "content://vault.kdbx")
        // No KdbxPasswordHelper passphrase has been stored in this sandbox.
        val db = Room.inMemoryDatabaseBuilder(ctx, PasswordDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            assertEquals(KdbxSyncResult.NotConfigured, runKdbxSync(ctx, db))
        } finally {
            db.close()
        }
    }

    @Test fun safDocumentWriteToMissingUriThrows() {
        val doc = SafKdbxDocument(ctx, Uri.parse("content://missing/vault.kdbx"))
        assertFailsWith<java.io.FileNotFoundException> { doc.write(byteArrayOf(1, 2, 3)) }
    }

    @Test fun repositoryBackedEngineFailsClosedBeforeTouchingDaos() = runBlocking {
        // FileMissing and WrongPassword are decided before any DAO is read, so
        // the repository-backed engine can be exercised without the SQLCipher DB.
        val codec = object : KdbxCodec {
            override fun read(password: String, bytes: ByteArray): List<Map<String, String>>? = null
            override fun write(password: String, entries: List<Map<String, String>>): ByteArray = byteArrayOf(1)
        }
        val engine = KdbxSyncEngine(PasswordRepository.get(ctx), codec)
        val missing = object : KdbxDocument {
            override fun read(): ByteArray = throw FileNotFoundException("gone")
            override fun write(bytes: ByteArray) = Unit
        }
        assertEquals(KdbxSyncResult.FileMissing, engine.sync(missing, "pw"))
        val present = object : KdbxDocument {
            override fun read(): ByteArray = byteArrayOf(9, 9, 9)
            override fun write(bytes: ByteArray) = Unit
        }
        assertEquals(KdbxSyncResult.WrongPassword, engine.sync(present, "pw"))
    }

    @Test fun workerWithUnlockedVaultAndSyncDisabledSucceeds() = runBlocking {
        DatabaseHelper(ctx).storePassphrase("db-passphrase")
        KdbxSyncSettings.setEnabled(ctx, false)
        val worker = TestListenableWorkerBuilder<KdbxSyncWorker>(ctx).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
    }

    @Test fun settingsFlowsReflectStoredValues() = runBlocking {
        KdbxSyncSettings.setDocumentUri(ctx, "content://a/b.kdbx")
        assertEquals("content://a/b.kdbx", KdbxSyncSettings.documentUriFlow(ctx).first())
        // No successful sync has run in this class, so the timestamp stays at its default.
        assertEquals(0L, KdbxSyncSettings.lastSyncFlow(ctx).first())
        KdbxSyncSettings.recordFailure(ctx, "verify_failed")
        assertEquals("verify_failed", KdbxSyncSettings.errorFlow(ctx).first())
    }

    @Test fun safDocumentRoundTripsThroughContentResolver() {
        val uri = Uri.parse("content://docs/vault.kdbx")
        val out = ByteArrayOutputStream()
        org.robolectric.Shadows.shadowOf(ctx.contentResolver).registerOutputStream(uri, out)
        val doc = SafKdbxDocument(ctx, uri)
        doc.write(byteArrayOf(5, 6, 7))
        assertEquals(listOf(5.toByte(), 6.toByte(), 7.toByte()), out.toByteArray().toList())

        org.robolectric.Shadows.shadowOf(ctx.contentResolver)
            .registerInputStream(uri, ByteArrayInputStream(byteArrayOf(9, 8)))
        assertEquals(listOf(9.toByte(), 8.toByte()), doc.read().toList())
    }

    @Test fun workerRetriesWhenSyncErrors() = runBlocking {
        DatabaseHelper(ctx).storePassphrase("db-passphrase")
        configureMissingDocument()
        val worker = TestListenableWorkerBuilder<KdbxSyncWorker>(ctx).build()
        // The document cannot be opened, so the sync errors and WorkManager
        // is asked to retry rather than drop the sync on the floor.
        assertEquals(ListenableWorker.Result.retry(), worker.doWork())
    }
}
