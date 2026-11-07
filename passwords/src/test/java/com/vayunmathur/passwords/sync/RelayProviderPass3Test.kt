package com.vayunmathur.passwords.sync

import android.content.ContentValues
import android.net.Uri
import com.vayunmathur.passwords.data.Passkey
import com.vayunmathur.passwords.data.Password
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Pass-3: RelayPasswordProvider URI contract and relay-json conversions (DB-free paths). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RelayProviderPass3Test {

    @Test
    fun companionUrisAndColumns() {
        assertEquals(
            "content://com.vayunmathur.passwords.relay/vault/v1/passwords",
            RelayPasswordProvider.passwordsUri("v1").toString(),
        )
        assertEquals(
            "content://com.vayunmathur.passwords.relay/vault/v1/passkeys",
            RelayPasswordProvider.passkeysUri("v1").toString(),
        )
        assertEquals(
            "content://com.vayunmathur.passwords.relay/vault/xyz/snapshots",
            RelayPasswordProvider.snapshotsUri("xyz").toString(),
        )
        assertEquals(
            listOf("syncId", "updatedAt", "deleted", "payloadJson", "contentHash"),
            RelayPasswordProvider.COLUMNS.toList(),
        )
        assertEquals("com.vayunmathur.passwords.relay", RelayPasswordProvider.AUTHORITY)
    }

    @Test
    fun providerOnCreateAndGetType() {
        val provider = RelayPasswordProvider()
        assertTrue(provider.onCreate())
        assertEquals(
            "vnd.android.cursor.dir/vnd.com.vayunmathur.passwords.password",
            provider.getType(RelayPasswordProvider.passwordsUri("v1")),
        )
        assertEquals(
            "vnd.android.cursor.dir/vnd.com.vayunmathur.passwords.password",
            provider.getType(Uri.parse("content://${RelayPasswordProvider.AUTHORITY}/vault/v1/passwords/querySince")),
        )
        assertEquals(
            "vnd.android.cursor.dir/vnd.com.vayunmathur.passwords.passkey",
            provider.getType(RelayPasswordProvider.passkeysUri("v1")),
        )
        assertEquals(
            "vnd.android.cursor.dir/vnd.com.vayunmathur.passwords.snapshot",
            provider.getType(RelayPasswordProvider.snapshotsUri("v1")),
        )
        // Op-suffixed write URIs and unknown URIs have no MIME type
        assertNull(provider.getType(Uri.parse("content://${RelayPasswordProvider.AUTHORITY}/vault/v1/passwords/upsertRaw")))
        assertNull(provider.getType(Uri.parse("content://com.example/other")))
    }

    @Test
    fun providerRejectsNullAndUnknownWritesWithoutDb() {
        val provider = RelayPasswordProvider()
        val unknown = Uri.parse("content://com.example/other")
        assertNull(provider.insert(unknown, ContentValues()))
        assertNull(provider.insert(RelayPasswordProvider.passwordsUri("v1"), null))
        assertEquals(0, provider.update(unknown, ContentValues(), null, null))
        assertEquals(0, provider.update(RelayPasswordProvider.passwordsUri("v1"), null, null, null))
        assertNull(provider.query(unknown, null, null, null, null))
        // A passwords insert with no payload column is rejected before touching the vault
        assertNull(provider.insert(RelayPasswordProvider.passwordsUri("v1"), ContentValues()))
    }

    @Test
    fun relayJsonRoundTripsPasswordAndPasskey() {
        val password = Password(
            id = 3, name = "GitHub", username = "octocat", email = "o@x.io",
            password = "s3cret", note = "note", syncId = "sync-pw", updatedAt = 1234,
        )
        val backPw = password.toRelayJson().toPassword(id = 9)
        assertEquals(9, backPw.id)
        assertEquals("GitHub", backPw.name)
        assertEquals("octocat", backPw.username)
        assertEquals("s3cret", backPw.password)
        assertEquals("sync-pw", backPw.syncId)

        val passkey = Passkey(
            id = 4, rpId = "github.com", rpName = "GitHub", credentialId = "cred-1",
            userName = "octocat", syncId = "sync-pk", updatedAt = 99,
        )
        val relay = passkey.toRelayJson()
        assertTrue(relay.isPasskey)
        val backPk = relay.toPasskey(id = 7)
        assertEquals(7, backPk.id)
        assertEquals("github.com", backPk.rpId)
        assertEquals("cred-1", backPk.credentialId)
        assertEquals("sync-pk", backPk.syncId)
        assertNotEquals(password.toRelayJson(), relay)
    }
}
