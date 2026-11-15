package com.vayunmathur.passwords.sync

import android.content.Context
import android.net.Uri
import androidx.room3.Room
import androidx.test.core.app.ApplicationProvider
import com.vayunmathur.passwords.FakeAndroidKeyStore
import com.vayunmathur.passwords.data.Password
import com.vayunmathur.passwords.data.PasswordDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Full `runKdbxSync` against a real (in-memory) Room database and real KDBX
 * bytes served through the content resolver: the whole settings -> SAF ->
 * engine -> native codec -> settings-recording path, minus SQLCipher.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RunKdbxSyncEndToEndTest {

    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private lateinit var db: PasswordDatabase
    private val uri: Uri = Uri.parse("content://docs/vault.kdbx")

    @Before fun setUp() {
        FakeAndroidKeyStore.install()
        db = Room.inMemoryDatabaseBuilder(ctx, PasswordDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    private fun serve(bytes: ByteArray): ByteArrayOutputStream {
        shadowOf(ctx.contentResolver).registerInputStream(uri, ByteArrayInputStream(bytes))
        val out = ByteArrayOutputStream()
        shadowOf(ctx.contentResolver).registerOutputStream(uri, out)
        return out
    }

    @Test fun fullSyncPushesLocalPullsRemoteAndRecordsSuccess() = runBlocking {
        db.passwordDao().upsertRaw(
            Password(name = "Local", username = "u-local", password = "lp", syncId = "local-1", updatedAt = 1000),
        )
        val foreign = listOf(
            mapOf(
                "Title" to "Remote", "UserName" to "bob", "Password" to "rp",
                EntryMapper.FIELD_SYNC_ID to "r1", EntryMapper.FIELD_MODIFIED to "500",
                EntryMapper.FIELD_TYPE to "password",
            ),
        )
        val vaultBytes = assertNotNull(NativeKdbxCodec.write("vault-pw", foreign))
        val written = serve(vaultBytes)

        KdbxSyncSettings.setEnabled(ctx, true)
        KdbxSyncSettings.setDocumentUri(ctx, uri.toString())
        KdbxPasswordHelper(ctx).storePassphrase("vault-pw")

        val result = runKdbxSync(ctx, db)
        assertEquals(KdbxSyncResult.Success(pushed = 1, pulled = 1, deletedLocal = 0, deletedRemote = 0), result)

        // Both rows are now in the local database, and the file was rewritten with both.
        val stored = db.passwordDao().getAll()
        assertEquals(setOf("Local", "Remote"), stored.map { it.name }.toSet())
        assertTrue(written.size() > 0)
        val merged = assertNotNull(NativeKdbxCodec.read("vault-pw", written.toByteArray()))
        assertEquals(setOf("Local", "Remote"), merged.map { it["Title"] }.toSet())

        // The pre-sync file was backed up inside the app sandbox.
        assertTrue(File(ctx.filesDir, "kdbx-sync-backup.kdbx").exists())

        // Outcome is recorded for the settings screen.
        assertEquals(KdbxSyncSettings.STATUS_OK, KdbxSyncSettings.statusFlow(ctx).first())
        assertTrue(KdbxSyncSettings.lastSyncFlow(ctx).first() > 0)

        // A second sync against the merged file is a steady-state no-op.
        serve(written.toByteArray())
        val second = runKdbxSync(ctx, db)
        assertEquals(KdbxSyncResult.Success(0, 0, 0, 0), second)
        assertEquals(2, db.passwordDao().getAll().size)
    }

    @Test fun engineWithDatabaseConstructorSyncsPasskeys() = runBlocking {
        val codec = object : KdbxCodec {
            private val vaults = mutableMapOf<String, List<Map<String, String>>>()
            private var counter = 0
            override fun read(password: String, bytes: ByteArray): List<Map<String, String>>? =
                vaults[String(bytes)]
            override fun write(password: String, entries: List<Map<String, String>>): ByteArray {
                val handle = "v-${counter++}"
                vaults[handle] = entries.map { it.toMap() }
                return handle.toByteArray()
            }
        }
        var remoteBytes = codec.write("pw", emptyList())
        val document = object : KdbxDocument {
            override fun read(): ByteArray = remoteBytes!!
            override fun write(bytes: ByteArray) { remoteBytes = bytes }
        }
        db.passkeyDao().upsertRaw(
            com.vayunmathur.passwords.data.Passkey(
                rpId = "example.com", rpName = "Example", credentialId = "cred-1",
                userName = "alice", privateKeyBytes = byteArrayOf(1, 2, 3),
                syncId = "pk-1", updatedAt = 42,
            ),
        )
        val engine = KdbxSyncEngine(db, codec)
        val result = engine.sync(document, "pw")
        assertEquals(KdbxSyncResult.Success(1, 0, 0, 0), result)
        val remote = codec.read("pw", remoteBytes!!)!!
        assertEquals("cred-1", remote.single()["KPEX_PASSKEY_CREDENTIAL_ID"])
        assertEquals(1, db.syncSnapshotDao().getAll().size)
    }
}
