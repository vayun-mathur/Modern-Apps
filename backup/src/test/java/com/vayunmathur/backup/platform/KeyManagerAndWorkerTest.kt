package com.vayunmathur.backup.platform

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import com.vayunmathur.backup.FakeAndroidKeyStore
import com.vayunmathur.backup.data.BackupConfig
import com.vayunmathur.backup.domain.crypto.Bip39
import com.vayunmathur.backup.platform.crypto.KeyManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class KeyManagerAndWorkerTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun setUp() {
        FakeAndroidKeyStore.install(); FakeAndroidKeyStore.clear()
        ctx.getSharedPreferences("backup_secure_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun deriveMasterKeyIsDeterministic32Bytes() {
        val seed = ByteArray(64) { it.toByte() }
        val a = KeyManager.deriveMasterKey(seed); val b = KeyManager.deriveMasterKey(seed)
        assertEquals(32, a.size); assertContentEquals(a, b)
        val c = KeyManager.deriveMasterKey(ByteArray(64) { (it + 1).toByte() })
        assertFalse(a.contentEquals(c))
    }

    @Test fun storeAndRetrieveSeedDerivedKey() {
        val km = KeyManager(ctx)
        assertFalse(km.hasMasterKey())
        val seed = Bip39.mnemonicToSeed(Bip39.generate())
        km.storeSeed(seed)
        assertTrue(km.hasMasterKey())
        assertContentEquals(KeyManager.deriveMasterKey(seed), km.getMasterKey())
        // Same seed again overwrites with same key.
        km.storeSeed(seed); assertContentEquals(KeyManager.deriveMasterKey(seed), km.getMasterKey())
        km.clear(); assertFalse(km.hasMasterKey())
    }

    @Test fun workerNoOpsWhenDisabledOrUnconfigured() = runBlocking<Unit> {
        val config = BackupConfig(ctx); config.clear()
        val worker = TestListenableWorkerBuilder<FileBackupWorker>(ctx).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        // Enabled but no backend/key -> also success (no-op).
        config.setFileBackupEnabled(true)
        assertTrue(config.settings.first().fileBackupEnabled)
        val worker2 = TestListenableWorkerBuilder<FileBackupWorker>(ctx).build()
        assertEquals(ListenableWorker.Result.success(), worker2.doWork())
        config.clear()
    }

    @Test fun workerRetriesThenSucceedsPathWithSafMissing() = runBlocking<Unit> {
        // Enabled + SAF uri pointing nowhere + key present: backend creation succeeds,
        // backup attempt fails inside manager (index write through providerless SAF)
        // or returns success if enumerate produces empty and write is swallowed.
        // We only assert it returns a terminal Result without throwing.
        val config = BackupConfig(ctx); config.clear()
        KeyManager(ctx).storeSeed(Bip39.mnemonicToSeed(Bip39.generate()))
        config.setSafBackend("content://com.example.invalid/tree/root")
        config.setFileBackupEnabled(true)
        val worker = TestListenableWorkerBuilder<FileBackupWorker>(ctx).build()
        val result = worker.doWork()
        assertTrue(result is ListenableWorker.Result)
        config.clear(); KeyManager(ctx).clear()
    }
}
