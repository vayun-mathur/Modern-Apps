package com.vayunmathur.backup.service

import android.app.backup.BackupDataInput
import android.app.backup.BackupDataOutput
import android.app.backup.BackupTransport
import android.app.backup.RestoreDescription
import android.content.Context
import android.content.pm.PackageInfo
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.backup.DocsContentResolverShadow
import com.vayunmathur.backup.FakeAndroidKeyStore
import com.vayunmathur.backup.FakeDocumentsProvider
import com.vayunmathur.backup.data.BackupConfig
import com.vayunmathur.backup.domain.crypto.Bip39
import com.vayunmathur.backup.platform.crypto.KeyManager
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import java.io.File
import java.io.FileInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@org.robolectric.annotation.Config(shadows = [DocsContentResolverShadow::class, com.vayunmathur.backup.ShadowBackupDataInput::class, com.vayunmathur.backup.ShadowBackupDataOutput::class])
class TransportWithRepoTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var transport: ConfigurableBackupTransport

    @Before fun setUp() = runBlocking<Unit> {
        FakeAndroidKeyStore.install(); FakeAndroidKeyStore.clear()
        ctx.getSharedPreferences("backup_secure_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        val root = File(ctx.cacheDir, "transport-saf-${System.nanoTime()}").apply { mkdirs() }
        FakeDocumentsProvider.rootDir = root
        val provider = FakeDocumentsProvider()
        provider.attachInfo(ctx, android.content.pm.ProviderInfo().apply {
            authority = FakeDocumentsProvider.AUTHORITY; exported = true; grantUriPermissions = true
            readPermission = android.Manifest.permission.MANAGE_DOCUMENTS
            writePermission = android.Manifest.permission.MANAGE_DOCUMENTS
        })
        try {
            val m = Class.forName("org.robolectric.shadows.ShadowContentResolver")
                .getMethod("registerProviderInternal", String::class.java, android.content.ContentProvider::class.java)
            m.invoke(null, FakeDocumentsProvider.AUTHORITY, provider)
        } catch (ignored: Exception) {
            val shadow = Shadows.shadowOf(ctx.contentResolver)
            shadow.javaClass.methods.first { it.name.contains("registerProvider") }
                .invoke(shadow, FakeDocumentsProvider.AUTHORITY, provider)
        }
        FakeDocumentsProvider.instance = provider
        val config = BackupConfig(ctx); config.clear()
        runCatching { KeyManager(ctx).clear() }
        KeyManager(ctx).storeSeed(Bip39.mnemonicToSeed(Bip39.generate()))
        val treeUri = DocumentsContract.buildTreeDocumentUri(FakeDocumentsProvider.AUTHORITY, FakeDocumentsProvider.ROOT_ID)
        config.setSafBackend(treeUri.toString())
        transport = ConfigurableBackupTransport(ctx)
    }

    private fun pkg(name: String) = PackageInfo().apply { packageName = name }

    private fun dataOutput(fd: java.io.FileDescriptor): BackupDataOutput =
        BackupDataOutput::class.java.getDeclaredConstructor(java.io.FileDescriptor::class.java)
            .apply { isAccessible = true }.newInstance(fd)

    private fun dataInput(fd: java.io.FileDescriptor): BackupDataInput =
        BackupDataInput::class.java.getDeclaredConstructor(java.io.FileDescriptor::class.java)
            .apply { isAccessible = true }.newInstance(fd)

    private fun kvBackup(pkgName: String, entities: Map<String, ByteArray?>, flags: Int = 0): Int {
        val pipe = ParcelFileDescriptor.createPipe()
        val writer = dataOutput(pipe[1].fileDescriptor)
        for ((k, v) in entities) {
            if (v == null) { writer.writeEntityHeader(k, -1) }
            else { writer.writeEntityHeader(k, v.size); writer.writeEntityData(v, v.size) }
        }
        pipe[1].close()
        val result = if (flags == 0) transport.performBackup(pkg(pkgName), pipe[0])
            else transport.performBackup(pkg(pkgName), pipe[0], flags)
        pipe[0].close()
        return result
    }

    @Test fun kvAndFullBackupRestoreCycle() {
        assertEquals(BackupTransport.TRANSPORT_OK, transport.initializeDevice())
        assertTrue(transport.currentDestinationString().isNotEmpty())

        // KV backup with two entities.
        assertEquals(BackupTransport.TRANSPORT_OK, kvBackup("com.example.app", mapOf("k1" to "v1".toByteArray(), "k2" to "value2".toByteArray())))
        // Incremental delete of k1.
        assertEquals(BackupTransport.TRANSPORT_OK, kvBackup("com.example.app", mapOf("k1" to null)))
        // Non-incremental pass replaces the set.
        assertEquals(BackupTransport.TRANSPORT_OK, kvBackup("com.example.app", mapOf("only" to "one".toByteArray()), BackupTransport.FLAG_NON_INCREMENTAL))

        // Restore KV.
        assertEquals(BackupTransport.TRANSPORT_OK, transport.startRestore(1L, arrayOf(pkg("com.example.app"), pkg("com.absent"))))
        val desc = transport.nextRestorePackage()
        assertEquals("com.example.app", desc.packageName)
        assertEquals(RestoreDescription.TYPE_KEY_VALUE, desc.dataType)
        val pipe = ParcelFileDescriptor.createPipe()
        assertEquals(BackupTransport.TRANSPORT_OK, transport.getRestoreData(pipe[1]))
        pipe[1].close()
        val reader = dataInput(pipe[0].fileDescriptor)
        val restored = mutableMapOf<String, String>()
        while (reader.readNextHeader()) {
            val size = reader.dataSize
            val data = ByteArray(size); var off = 0
            while (off < size) { val n = reader.readEntityData(data, off, size - off); check(n > 0) { "short entity data" }; off += n }
            restored[reader.key] = data.decodeToString()
        }
        pipe[0].close()
        assertEquals(mapOf("only" to "one"), restored)
        assertEquals(RestoreDescription.NO_MORE_PACKAGES, transport.nextRestorePackage())
        transport.finishRestore()

        // Full backup: stream bytes through sendBackupData, then finish.
        assertEquals(BackupTransport.TRANSPORT_OK, transport.clearBackupData(pkg("com.example.app")))
        val fullPipe = ParcelFileDescriptor.createPipe()
        assertEquals(BackupTransport.TRANSPORT_OK, transport.performFullBackup(pkg("com.example.app"), fullPipe[0]))
        val payload = "full-tar-payload".toByteArray()
        val out = android.os.ParcelFileDescriptor.AutoCloseOutputStream(fullPipe[1])
        out.write(payload); out.flush()
        assertEquals(BackupTransport.TRANSPORT_OK, transport.sendBackupData(payload.size))
        assertEquals(BackupTransport.TRANSPORT_OK, transport.finishBackup())
        // finishBackup again with nothing pending is OK.
        assertEquals(BackupTransport.TRANSPORT_OK, transport.finishBackup())

        // Full restore.
        assertEquals(BackupTransport.TRANSPORT_OK, transport.startRestore(1L, arrayOf(pkg("com.example.app"))))
        val fdesc = transport.nextRestorePackage()
        assertEquals(RestoreDescription.TYPE_FULL_STREAM, fdesc.dataType)
        val restorePipe = ParcelFileDescriptor.createPipe()
        val n = transport.getNextFullRestoreDataChunk(restorePipe[1])
        assertEquals(payload.size, n)
        val got = ByteArray(n)
        val input = android.os.ParcelFileDescriptor.AutoCloseInputStream(restorePipe[0])
        var read = 0; while (read < n) { val r = input.read(got, read, n - read); check(r >= 0) { "short full-restore stream" }; read += r }
        assertEquals("full-tar-payload", got.decodeToString())
        assertEquals(BackupTransport.NO_MORE_DATA, transport.getNextFullRestoreDataChunk(restorePipe[1]))
        assertEquals(BackupTransport.TRANSPORT_OK, transport.abortFullRestore())
        transport.finishRestore()

        // Cancel a fresh full backup, then wipe everything.
        val cancelPipe = ParcelFileDescriptor.createPipe()
        assertEquals(BackupTransport.TRANSPORT_OK, transport.performFullBackup(pkg("com.example.app"), cancelPipe[0], 0))
        transport.cancelFullBackup()
        assertEquals(BackupTransport.TRANSPORT_OK, transport.initializeDevice())
        assertEquals(BackupTransport.TRANSPORT_OK, transport.clearBackupData(pkg("com.example.app")))
    }
}
