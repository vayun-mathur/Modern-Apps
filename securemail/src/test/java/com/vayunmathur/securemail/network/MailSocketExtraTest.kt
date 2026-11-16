package com.vayunmathur.securemail.network

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [FederatedSendRequest] wire shape, [KeyLookupHttp] failure paths, socket JSON config. */
@RunWith(RobolectricTestRunner::class)
class MailSocketExtraTest {

    @Test fun `federated send request serializes with nullable bodies`() {
        val json = Json { ignoreUnknownKeys = true }
        val req = FederatedSendRequest(
            from = "me@maemail.org",
            toExternal = listOf("ada@example.org", "bob@example.org"),
            armoredBodies = listOf("ARMORED", null),
            subject = "Federated",
        )
        val encoded = json.encodeToString(FederatedSendRequest.serializer(), req)
        assertTrue(encoded.contains("\"from\":\"me@maemail.org\""), encoded)
        assertTrue(encoded.contains("\"armoredBodies\":[\"ARMORED\",null]"), encoded)
        assertEquals(req, json.decodeFromString(FederatedSendRequest.serializer(), encoded))
        assertEquals(req.copy(subject = "Other"), req.copy(subject = "Other"))
        assertEquals("me@maemail.org", req.component1())
        assertEquals(listOf("ada@example.org", "bob@example.org"), req.component2())
    }

    @Test fun `key lookup http returns null for unreachable urls instead of throwing`() = runBlocking {
        // Nothing is listening on the loopback discard port in the test env;
        // both helpers must degrade to null (the send path's no-key outcome).
        assertNull(KeyLookupHttp.fetchText("http://127.0.0.1:9/wkd", useSystemTrust = true))
        assertNull(KeyLookupHttp.fetchText("not a url", useSystemTrust = false))
        assertNull(KeyLookupHttp.fetchWkdKey("http://127.0.0.1:9/.well-known/openpgpkey/hu/x"))
        assertNull(KeyLookupHttp.fetchWkdKey("not a url"))
    }

    @Test fun `socket json ignores unknown keys`() {
        val parsed = MailSocket.json.decodeFromString<FederatedSendRequest>(
            """{"from":"a@x.org","toExternal":[],"armoredBodies":[],"subject":"s","future":42}""",
        )
        assertEquals("a@x.org", parsed.from)
        assertEquals("s", parsed.subject)
    }
}
