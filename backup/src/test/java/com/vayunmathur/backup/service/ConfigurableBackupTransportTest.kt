package com.vayunmathur.backup.service

import android.app.backup.BackupTransport
import android.app.backup.RestoreDescription
import android.content.Context
import android.content.pm.PackageInfo
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.backup.FakeAndroidKeyStore
import com.vayunmathur.backup.FakeDocumentsProvider
import com.vayunmathur.backup.data.BackupConfig
import com.vayunmathur.backup.platform.crypto.KeyManager
import com.vayunmathur.backup.domain.crypto.Bip39
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class ConfigurableBackupTransportTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var transport: ConfigurableBackupTransport

    @Before fun setUp() = runBlocking<Unit> {
        FakeAndroidKeyStore.install(); FakeAndroidKeyStore.clear()
        ctx.getSharedPreferences("backup_secure_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        BackupConfig(ctx).clear(); runCatching { KeyManager(ctx).clear() }
        transport = ConfigurableBackupTransport(ctx)
    }

    private fun pkg(name: String) = PackageInfo().apply { packageName = name }

    @Test fun identityAndConstantsWithoutRepo() {
        assertEquals("com.vayunmathur.backup/.service.ConfigurableBackupTransportService", transport.name())
        assertEquals("com.vayunmathur.backup", transport.transportDirName())
        assertEquals(0, transport.transportFlagsCompat())
        assertEquals(0L, transport.requestBackupTime())
        assertEquals(0L, transport.requestFullBackupTime())
        assertEquals(Long.MAX_VALUE, transport.getBackupQuota("com.x", false))
        assertEquals(Long.MAX_VALUE, transport.getBackupQuota("com.x", true))
        assertEquals(BackupTransport.TRANSPORT_OK, transport.checkFullBackupSize(1_000_000L))
        assertEquals(BackupTransport.TRANSPORT_OK, transport.checkFullBackupSize(-1L))
        assertNotNull(transport.configurationIntent())
        assertNotNull(transport.dataManagementIntent())
        assertEquals(transport.configurationIntent().component, transport.dataManagementIntent().component)
        assertTrue(transport.dataManagementIntentLabel().isNotEmpty())
        assertTrue(transport.currentDestinationString().isNotEmpty())
        val sets = transport.availableRestoreSetsCompat()
        assertEquals(1, sets.size); assertEquals(transport.name(), sets[0].name)
        assertEquals(1L, transport.currentRestoreSetCompat())
    }

    @Test fun operationsFailGracefullyWithoutRepo() {
        assertEquals(BackupTransport.TRANSPORT_NOT_INITIALIZED, transport.initializeDevice())
        val (readFd, writeFd) = ParcelFileDescriptor.createPipe()
        assertEquals(BackupTransport.TRANSPORT_NOT_INITIALIZED, transport.performBackup(pkg("com.a"), readFd))
        assertEquals(BackupTransport.TRANSPORT_NOT_INITIALIZED, transport.clearBackupData(pkg("com.a")))
        assertEquals(BackupTransport.TRANSPORT_OK, transport.finishBackup()) // kv path: no full backup pending
        assertEquals(BackupTransport.TRANSPORT_NOT_INITIALIZED, transport.performFullBackup(pkg("com.a"), writeFd))
        assertEquals(BackupTransport.TRANSPORT_ERROR, transport.sendBackupData(10))
        transport.cancelFullBackup() // no-op, must not throw
        assertEquals(BackupTransport.TRANSPORT_NOT_INITIALIZED, transport.startRestore(1L, arrayOf(pkg("com.a"))))
        assertEquals(RestoreDescription.NO_MORE_PACKAGES, transport.nextRestorePackageCompat())
        val (r2, w2) = ParcelFileDescriptor.createPipe()
        assertEquals(BackupTransport.TRANSPORT_NOT_INITIALIZED, transport.getRestoreData(r2))
        assertEquals(BackupTransport.NO_MORE_DATA, transport.getNextFullRestoreDataChunk(w2))
        assertEquals(BackupTransport.TRANSPORT_OK, transport.abortFullRestore())
        transport.finishRestore() // no-op
        readFd.close(); writeFd.close(); r2.close(); w2.close()
    }

    @Test fun serviceBindsTransport() {
        val service = ConfigurableBackupTransportService()
        // attach a base context via Robolectric service controller
        val controller = org.robolectric.Robolectric.buildService(ConfigurableBackupTransportService::class.java)
        val svc = controller.create().get()
        val binder = svc.onBind(android.content.Intent())
        assertNotNull(binder)
        controller.destroy()
    }

    // Reflective wrappers for hidden-API-suffixed / overloaded transport methods where
    // the compile-time stub signatures differ; direct calls above cover most.
    private fun BackupTransport.transportFlagsCompat(): Int = getTransportFlags()
    private fun BackupTransport.availableRestoreSetsCompat() = getAvailableRestoreSets().toList()
    private fun BackupTransport.currentRestoreSetCompat(): Long = getCurrentRestoreSet()
    private fun BackupTransport.nextRestorePackageCompat(): RestoreDescription = nextRestorePackage()
}
