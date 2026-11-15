package com.vayunmathur.passwords.sync

import android.content.ContentValues
import android.net.Uri
import com.vayunmathur.passwords.data.Passkey
import com.vayunmathur.passwords.data.Password
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RelayProviderExtraTest {

    private val provider: RelayPasswordProvider by lazy {
        Robolectric.buildContentProvider(RelayPasswordProvider::class.java)
            .create(RelayPasswordProvider.AUTHORITY).get()
    }

    private fun uri(path: String) = Uri.parse("content://${RelayPasswordProvider.AUTHORITY}/$path")

    @Test fun onCreateAndGetTypeForEveryRoute() {
        assertTrue(provider.onCreate())
        assertEquals(
            "vnd.android.cursor.dir/vnd.com.vayunmathur.passwords.password",
            provider.getType(uri("vault/v/passwords")),
        )
        assertEquals(
            "vnd.android.cursor.dir/vnd.com.vayunmathur.passwords.password",
            provider.getType(uri("vault/v/passwords/querySince")),
        )
        assertEquals(
            "vnd.android.cursor.dir/vnd.com.vayunmathur.passwords.passkey",
            provider.getType(uri("vault/v/passkeys")),
        )
        assertEquals(
            "vnd.android.cursor.dir/vnd.com.vayunmathur.passwords.passkey",
            provider.getType(uri("vault/v/passkeys/querySince")),
        )
        assertEquals(
            "vnd.android.cursor.dir/vnd.com.vayunmathur.passwords.snapshot",
            provider.getType(uri("vault/v/snapshots")),
        )
        assertNull(provider.getType(uri("vault/v/unknown")))
        assertNull(provider.getType(uri("elsewhere/passwords")))
    }

    @Test fun unknownRoutesAreInert() {
        val values = ContentValues().apply { put(RelayPasswordProvider.COL_PAYLOAD_JSON, "{}") }
        assertNull(provider.query(uri("vault/v/unknown"), null, null, null, null))
        assertNull(provider.insert(uri("vault/v/unknown"), values))
        assertEquals(0, provider.update(uri("vault/v/unknown"), values, null, null))
        assertEquals(0, provider.delete(uri("vault/v/unknown"), null, null))
    }

    @Test fun nullAndMalformedPayloadsAreRejectedWithoutTouchingVault() {
        val passwords = uri("vault/v/passwords")
        assertNull(provider.insert(passwords, null))
        assertEquals(0, provider.update(passwords, null, null, null))
        // No payloadJson column at all.
        assertNull(provider.insert(passwords, ContentValues()))
        // Malformed JSON is not an envelope.
        val malformed = ContentValues().apply {
            put(RelayPasswordProvider.COL_PAYLOAD_JSON, "{not json")
        }
        assertNull(provider.insert(passwords, malformed))
        assertEquals(0, provider.update(passwords, malformed, null, null))
        assertNull(provider.insert(uri("vault/v/passkeys"), malformed))
        // Well-formed JSON whose uid is blank is rejected as foreign/corrupt.
        val blankUid = ContentValues().apply {
            put(RelayPasswordProvider.COL_PAYLOAD_JSON, """{"uid":"","name":"Ghost"}""")
        }
        assertNull(provider.insert(passwords, blankUid))
        // Snapshot insert without a syncId is rejected.
        assertNull(provider.insert(uri("vault/v/snapshots"), ContentValues()))
        assertEquals(
            0,
            provider.update(
                uri("vault/v/snapshots"),
                ContentValues().apply { put("kind", "password") },
                null, null,
            ),
        )
    }

    @Test fun envelopeDefaultsAndFullFieldMapping() {
        val defaults = RelayPasswordJson()
        assertEquals("", defaults.uid)
        assertEquals(false, defaults.isPasskey)
        assertEquals(false, defaults.deleted)
        assertEquals(emptyList(), defaults.websites)
        assertNull(defaults.totpSecret)
        assertNull(defaults.linkedPasswordSyncId)

        val full = RelayPasswordJson(
            uid = "u-9", name = "Mail", username = "alice", email = "a@x.com",
            password = "pw", note = "note", totpSecret = "SEED",
            websites = listOf("mail.example", "webmail.example"),
            deleted = true, updatedAt = 4242,
        )
        val asPassword = full.toPassword(id = 77)
        assertEquals(77L, asPassword.id)
        assertEquals("u-9", asPassword.syncId)
        assertEquals(4242L, asPassword.updatedAt)
        assertEquals(listOf("mail.example", "webmail.example"), asPassword.websites)
        assertEquals("SEED", asPassword.totpSecret)
        // toRelayJson of a Password never marks passkey/deleted: those are transport-only.
        val back = asPassword.toRelayJson()
        assertEquals(false, back.isPasskey)
        assertEquals(false, back.deleted)
        assertEquals(full.name, back.name)
        assertNotEquals(full.hashCode(), RelayPasswordJson(uid = "other").hashCode())
    }

    @Test fun passkeyEnvelopeAndPayloadCarriersPreserveEveryField() {
        val pk = Passkey(
            id = 12, rpId = "login.example", rpName = "Login", credentialId = "cred-9",
            userId = "uid-1", userName = "carol", userDisplayName = "Carol C",
            privateKeyBytes = byteArrayOf(3, 1, 4), creationTime = 100, lastUsedTime = 200,
            signCount = 9, linkedPasswordSyncId = "sync-pw", syncId = "pk-9", updatedAt = 300,
        )
        val payload = PasswordPasskeyPayload.fromPasskey(pk)
        val restoredPayload = payload.toPasskey()
        assertEquals(pk.rpId, restoredPayload.rpId)
        assertEquals(pk.credentialId, restoredPayload.credentialId)
        assertEquals(listOf(3.toByte(), 1.toByte(), 4.toByte()), restoredPayload.privateKeyBytes.toList())
        assertEquals("sync-pw", restoredPayload.linkedPasswordSyncId)
        assertEquals(9, restoredPayload.signCount)

        val relay = pk.toRelayJson()
        assertEquals(payload.privateKeyBase64, relay.privateKeyB64)
        val restored = relay.toPasskey(id = 99)
        assertEquals(99L, restored.id)
        assertEquals(100L, restored.creationTime)
        assertEquals(200L, restored.lastUsedTime)
        assertEquals("Carol C", restored.userDisplayName)

        // A payload with garbage base64 decodes to an empty key rather than throwing.
        val garbage = PasswordPasskeyPayload(privateKeyBase64 = "%%%")
        assertEquals(0, garbage.toPasskey().privateKeyBytes.size)
        // Serialised envelope round-trips through the provider's JSON shape.
        val json = Json { ignoreUnknownKeys = true }
        val text = json.encodeToString(relay)
        assertEquals(relay, json.decodeFromString(RelayPasswordJson.serializer(), text))
        assertTrue(text.contains("\"uid\":\"pk-9\""))
    }

    @Test fun providerConstantsAndUriBuilders() {
        assertEquals("com.vayunmathur.passwords.relay", RelayPasswordProvider.AUTHORITY)
        assertEquals("com.vayunmathur.passwords.permission.RELAY", RelayPasswordProvider.PERMISSION)
        assertEquals(
            listOf("syncId", "updatedAt", "deleted", "payloadJson", "contentHash"),
            RelayPasswordProvider.COLUMNS.toList(),
        )
        assertEquals(
            "content://com.vayunmathur.passwords.relay/vault/abc/passwords",
            RelayPasswordProvider.passwordsUri("abc").toString(),
        )
        // Vault ids with spaces are accepted verbatim (the segment is ignored server-side).
        assertTrue(RelayPasswordProvider.snapshotsUri("my vault").toString().contains("my"))
    }

    @Test fun upsertRawRoutesWithoutSyncIdAreRejected() {
        assertNull(
            provider.insert(
                uri("vault/v/snapshots/upsertRaw"),
                ContentValues().apply { put("kind", "password") },
            ),
        )
        assertNull(provider.getType(uri("vault/v/passwords/upsertRaw")))
        assertNull(provider.getType(uri("vault/v/passwords/deleteByVault")))
        assertEquals(
            "vnd.android.cursor.dir/vnd.com.vayunmathur.passwords.passkey",
            provider.getType(uri("vault/v/passkeys/querySince")),
        )
    }
}
