package com.vayunmathur.passwords.sync

import android.util.Base64
import com.vayunmathur.passwords.data.Passkey
import com.vayunmathur.passwords.data.Password
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RelayJsonTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test fun passwordEnvelopeRoundTrips() {
        val pw = Password(
            id = 7, name = "GitHub", username = "octo", email = "o@x.com",
            password = "secret", note = "n", totpSecret = "ABC",
            websites = listOf("github.com"), syncId = "sync-a", updatedAt = 1234,
        )
        val relay = pw.toRelayJson()
        assertEquals(false, relay.isPasskey)
        val restored = relay.toPassword(id = 9)
        assertEquals(9L, restored.id)
        assertEquals("GitHub", restored.name)
        assertEquals("sync-a", restored.syncId)
        assertEquals(1234L, restored.updatedAt)
        assertEquals(listOf("github.com"), restored.websites)
    }

    @Test fun passkeyEnvelopeRoundTripsBytes() {
        val pk = Passkey(
            id = 3, rpId = "example.com", rpName = "Example", credentialId = "cred",
            userId = "u1", userName = "alice", privateKeyBytes = byteArrayOf(5, 6, 7),
            creationTime = 10, lastUsedTime = 20, signCount = 2, syncId = "pk-a", updatedAt = 99,
        )
        val relay = pk.toRelayJson()
        assertTrue(relay.isPasskey)
        assertEquals(
            Base64.encodeToString(byteArrayOf(5, 6, 7), Base64.NO_WRAP),
            relay.privateKeyB64,
        )
        val restored = relay.toPasskey(id = 4)
        assertEquals(byteArrayOf(5, 6, 7).toList(), restored.privateKeyBytes.toList())
        assertEquals("pk-a", restored.syncId)
        assertEquals(2, restored.signCount)
    }

    @Test fun passkeyPayloadCarrierRoundTrips() {
        val pk = Passkey(rpId = "a.com", credentialId = "c", privateKeyBytes = byteArrayOf(1), syncId = "s")
        val payload = PasswordPasskeyPayload.fromPasskey(pk)
        assertEquals(pk.rpId, payload.toPasskey().rpId)
        assertEquals(byteArrayOf(1).toList(), payload.toPasskey().privateKeyBytes.toList())
    }

    @Test fun badBase64FallsBackToEmptyKey() {
        val relay = RelayPasswordJson(uid = "x", isPasskey = true, privateKeyB64 = "!!!not-base64!!!")
        assertEquals(0, relay.toPasskey(0).privateKeyBytes.size)
    }

    @Test fun envelopeSerializesWithStableFieldNames() {
        val text = json.encodeToString(Password(name = "A", syncId = "u1").toRelayJson())
        assertTrue(text.contains("\"uid\":\"u1\""))
        // Default-valued fields are omitted by the relay Json configuration.
        val parsed = json.decodeFromString(RelayPasswordJson.serializer(), text)
        assertEquals("A", parsed.name)
    }

    @Test fun providerUriBuildersAndTypes() {
        assertEquals(
            "content://com.vayunmathur.passwords.relay/vault/v1/passwords",
            RelayPasswordProvider.passwordsUri("v1").toString(),
        )
        assertTrue(RelayPasswordProvider.passkeysUri("v1").toString().endsWith("/passkeys"))
        assertTrue(RelayPasswordProvider.snapshotsUri("v1").toString().endsWith("/snapshots"))
        assertEquals(5, RelayPasswordProvider.COLUMNS.size)
    }
}
