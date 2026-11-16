package com.vayunmathur.passwords.sync

import android.content.ContentValues
import android.database.MatrixCursor
import android.net.Uri
import com.vayunmathur.passwords.FakeAndroidKeyStore
import com.vayunmathur.passwords.data.Passkey
import com.vayunmathur.passwords.data.Password
import com.vayunmathur.passwords.data.SyncSnapshot
import kotlinx.serialization.json.Json
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * [RelayPasswordProvider] dispatch beyond the inert routes.
 *
 * Valid routes reach the SQLCipher vault, whose native library is
 * unavailable under Robolectric: each CRUD verb therefore surfaces
 * [UnsatisfiedLinkError] from the DAO call it dispatches to. That pins the
 * routing (URI match, envelope decoding, sync-id extraction) as executing
 * before the vault is touched. The cursor builders and the content hash —
 * the exact bytes the relay merge hashes — are invoked directly and
 * asserted on their output.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RelayProviderDispatchTest {

    @Before fun setUp() = FakeAndroidKeyStore.install()

    private val provider: RelayPasswordProvider by lazy {
        Robolectric.buildContentProvider(RelayPasswordProvider::class.java)
            .create(RelayPasswordProvider.AUTHORITY).get()
    }

    private fun uri(path: String) = Uri.parse("content://${RelayPasswordProvider.AUTHORITY}/$path")

    private fun envelope(uid: String, passkey: Boolean = false, deleted: Boolean = false): ContentValues =
        ContentValues().apply {
            put(
                RelayPasswordProvider.COL_PAYLOAD_JSON,
                """{"uid":"$uid","isPasskey":$passkey,"deleted":$deleted,"name":"Mail",""" +
                    """"username":"alice","password":"pw","rpId":"example.com",""" +
                    """"credentialId":"cred-1","userName":"alice","updatedAt":1234}""",
            )
        }

    // ---- CRUD dispatch reaches the vault ----

    @Test fun queryRoutesReachVault() {
        assertFailsWith<UnsatisfiedLinkError> {
            provider.query(uri("vault/v/passwords"), null, null, null, null)
        }
        assertFailsWith<UnsatisfiedLinkError> {
            provider.query(uri("vault/v/passkeys"), null, null, null, null)
        }
        assertFailsWith<UnsatisfiedLinkError> {
            provider.query(uri("vault/v/snapshots"), null, null, null, null)
        }
        // querySince takes `since` from the query parameter, then selectionArgs.
        assertFailsWith<UnsatisfiedLinkError> {
            provider.query(uri("vault/v/passwords/querySince?since=500"), null, null, null, null)
        }
        assertFailsWith<UnsatisfiedLinkError> {
            provider.query(uri("vault/v/passkeys/querySince"), null, null, arrayOf("250"), null)
        }
        // A non-numeric since falls back to 0 and still dispatches.
        assertFailsWith<UnsatisfiedLinkError> {
            provider.query(uri("vault/v/passwords/querySince?since=abc"), null, null, null, null)
        }
    }

    @Test fun insertAndUpdateRoutesReachVault() {
        assertFailsWith<UnsatisfiedLinkError> {
            provider.insert(uri("vault/v/passwords"), envelope("pw-1"))
        }
        assertFailsWith<UnsatisfiedLinkError> {
            provider.insert(uri("vault/v/passwords/upsertRaw"), envelope("pw-2"))
        }
        assertFailsWith<UnsatisfiedLinkError> {
            provider.insert(uri("vault/v/passkeys"), envelope("pk-1", passkey = true))
        }
        // A deleted envelope still has to look the row up before deleting it.
        assertFailsWith<UnsatisfiedLinkError> {
            provider.insert(uri("vault/v/passkeys/upsertRaw"), envelope("pk-2", passkey = true, deleted = true))
        }
        assertFailsWith<UnsatisfiedLinkError> {
            provider.update(uri("vault/v/passwords"), envelope("pw-3"), null, null)
        }
        assertFailsWith<UnsatisfiedLinkError> {
            provider.update(uri("vault/v/passkeys/upsertRaw"), envelope("pk-3", passkey = true), null, null)
        }
        // Snapshot upserts carry their columns directly, not an envelope.
        val snapshot = ContentValues().apply {
            put(RelayPasswordProvider.COL_SYNC_ID, "snap-1")
            put("kind", SyncSnapshot.KIND_PASSWORD)
            put(RelayPasswordProvider.COL_CONTENT_HASH, "hash-1")
            put(RelayPasswordProvider.COL_UPDATED_AT, 99L)
            put("remoteModified", 55L)
        }
        assertFailsWith<UnsatisfiedLinkError> { provider.insert(uri("vault/v/snapshots"), snapshot) }
        assertFailsWith<UnsatisfiedLinkError> {
            provider.update(uri("vault/v/snapshots/upsertRaw"), snapshot, null, null)
        }
    }

    @Test fun deleteRoutesReachVault() {
        // Single delete by sync id.
        assertFailsWith<UnsatisfiedLinkError> {
            provider.delete(uri("vault/v/passwords"), "syncId = ?", arrayOf("pw-1"))
        }
        assertFailsWith<UnsatisfiedLinkError> {
            provider.delete(uri("vault/v/passkeys"), "syncId = ?", arrayOf("pk-1"))
        }
        assertFailsWith<UnsatisfiedLinkError> {
            provider.delete(uri("vault/v/snapshots"), null, arrayOf("snap-1"))
        }
        // Purge routes and selection-less base deletes.
        assertFailsWith<UnsatisfiedLinkError> {
            provider.delete(uri("vault/v/passwords/deleteByVault"), null, null)
        }
        assertFailsWith<UnsatisfiedLinkError> {
            provider.delete(uri("vault/v/passkeys/deleteByVault"), null, null)
        }
        assertFailsWith<UnsatisfiedLinkError> {
            provider.delete(uri("vault/v/snapshots/deleteByVault"), null, null)
        }
        assertFailsWith<UnsatisfiedLinkError> {
            provider.delete(uri("vault/v/passwords"), null, null)
        }
        assertFailsWith<UnsatisfiedLinkError> {
            provider.delete(uri("vault/v/snapshots"), null, null)
        }
    }

    // ---- Cursor builders + content hash (pure, asserted on output) ----

    @Suppress("UNCHECKED_CAST")
    private fun <T> invokePrivate(name: String, vararg args: Any?): T {
        val method = RelayPasswordProvider::class.java.declaredMethods
            .first { it.name == name && it.parameterCount == args.size }
        method.isAccessible = true
        return method.invoke(provider, *args) as T
    }

    @Test fun cursorBuildersEmitRelayColumnsAndPayloads() {
        val passwordsCursor: MatrixCursor = invokePrivate(
            "cursorFromPasswords",
            listOf(
                Password(id = 1, name = "Mail", username = "alice", password = "pw", syncId = "pw-1", updatedAt = 111),
                Password(id = 2, name = "Bank", username = "bob", password = "pw2", syncId = "pw-2", updatedAt = 222),
            ),
        )
        assertEquals(RelayPasswordProvider.COLUMNS.toList(), passwordsCursor.columnNames.toList())
        assertEquals(2, passwordsCursor.count)
        assertTrue(passwordsCursor.moveToFirst())
        assertEquals("pw-1", passwordsCursor.getString(0))
        assertEquals(111L, passwordsCursor.getLong(1))
        assertEquals(0, passwordsCursor.getInt(2))
        val payload = Json { ignoreUnknownKeys = true }
            .decodeFromString(RelayPasswordJson.serializer(), passwordsCursor.getString(3))
        assertEquals("Mail", payload.name)
        assertEquals("alice", payload.username)
        assertEquals(64, passwordsCursor.getString(4).length)

        val passkeysCursor: MatrixCursor = invokePrivate(
            "cursorFromPasskeys",
            listOf(
                Passkey(id = 3, rpId = "example.com", rpName = "Key", userName = "carol", syncId = "pk-1", updatedAt = 333),
            ),
        )
        assertEquals(1, passkeysCursor.count)
        assertTrue(passkeysCursor.moveToFirst())
        assertEquals("pk-1", passkeysCursor.getString(0))
        val pkPayload = Json { ignoreUnknownKeys = true }
            .decodeFromString(RelayPasswordJson.serializer(), passkeysCursor.getString(3))
        assertTrue(pkPayload.isPasskey)
        assertEquals("example.com", pkPayload.rpId)

        val snapshotsCursor: MatrixCursor = invokePrivate(
            "cursorFromSnapshots",
            listOf(SyncSnapshot("snap-1", SyncSnapshot.KIND_PASSWORD, "hash-9", 44, 55, 66)),
        )
        assertEquals(1, snapshotsCursor.count)
        assertTrue(snapshotsCursor.moveToFirst())
        assertEquals("snap-1", snapshotsCursor.getString(0))
        assertEquals(44L, snapshotsCursor.getLong(1))
        assertEquals("", snapshotsCursor.getString(3))
        assertEquals("hash-9", snapshotsCursor.getString(4))

        val emptyCursor: MatrixCursor = invokePrivate("cursorFromPasswords", emptyList<Password>())
        assertEquals(0, emptyCursor.count)
    }

    @Test fun contentHashIgnoresIdentityButNotContentOrDeletion() {
        val base = RelayPasswordJson(uid = "a", name = "Mail", password = "pw", updatedAt = 100)
        val reidentified = base.copy(uid = "b", updatedAt = 999)
        val hashA: String = invokePrivate("relayContentHash", base)
        val hashB: String = invokePrivate("relayContentHash", reidentified)
        // uid/updatedAt are stripped for the hash, so a re-identified copy hashes the same.
        assertEquals(hashA, hashB)
        assertEquals(64, hashA.length)
        // Content and the deleted flag both feed the hash.
        val changed: String = invokePrivate("relayContentHash", base.copy(password = "other"))
        val deleted: String = invokePrivate("relayContentHash", base.copy(deleted = true))
        assertNotEquals(hashA, changed)
        assertNotEquals(hashA, deleted)

        // SHA-256 of the empty string is a well-known constant.
        val emptyHash: String = invokePrivate("sha256Hex", "")
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            emptyHash,
        )
    }
}
