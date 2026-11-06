package com.vayunmathur.communicate.whatsapp

import com.vayunmathur.communicate.data.whatsapp.mex.WhatsAppMexOps
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Slice 1 (w5): all remaining JVM-pure `variables` builders in WhatsAppMexOps
 * (groups batch, contacts sync, blocklist, presence, newsletter, messaging keys).
 * The builders use java.util.Base64 only — no Android runtime needed.
 */
class W5Slice1MexOpsBuildersTest {

    @Test
    fun participatingGroupsVariables_withAndWithoutUser() {
        assertEquals(
            """{"participating_groups_input":{"id":"u@s.whatsapp.net"}}""",
            WhatsAppMexOps.buildParticipatingGroupsVariables("u@s.whatsapp.net"),
        )
        assertEquals(
            """{"participating_groups_input":{}}""",
            WhatsAppMexOps.buildParticipatingGroupsVariables(null),
        )
    }

    @Test
    fun groupBatchQueryVariables_listsGroups() {
        assertEquals(
            """{"batch_query_input":{"groups":[{"group_jid":"1@g.us","query_context":"INTERACTIVE"},{"group_jid":"2@g.us","query_context":"INTERACTIVE"}]}}""",
            WhatsAppMexOps.buildGroupBatchQueryVariables(listOf("1@g.us", "2@g.us"), "INTERACTIVE"),
        )
    }

    @Test
    fun contactDiscoveryVariables_shape() {
        assertEquals(
            """{"input":{"contacts":[{"phone":{"raw_pn":"+15550001"}},{"phone":{"raw_pn":"+15550002"}}],"context":"SEARCH"}}""",
            WhatsAppMexOps.buildContactDiscoveryVariables(listOf("+15550001", "+15550002"), "SEARCH"),
        )
    }

    @Test
    fun primaryContactsFullSyncVariables_shape() {
        val out = WhatsAppMexOps.buildPrimaryContactsFullSyncVariables(
            listOf("+15550001"), "sess-1", 0, true, "REGISTRATION",
        )
        assertTrue(out.contains("\"session_id\":\"sess-1\""), out)
        assertTrue(out.contains("\"page_index\":0"), out)
        assertTrue(out.contains("\"last\":true,\"page_index\":0") || out.contains("\"last\":true"), out)
        assertTrue(out.contains("\"context\":\"REGISTRATION\""), out)
        assertTrue(out.contains("\"raw_pn\":\"+15550001\""), out)
    }

    @Test
    fun usernameCheckVariables_withAndWithoutSession() {
        assertEquals(
            """{"username":"jo","include_suggestions":true,"source":"USER_INPUT","session_id":"s1"}""",
            WhatsAppMexOps.buildUsernameCheckVariables("jo", true, "USER_INPUT", "s1"),
        )
        assertEquals(
            """{"username":"jo","include_suggestions":false,"source":"USER_INPUT"}""",
            WhatsAppMexOps.buildUsernameCheckVariables("jo", false, "USER_INPUT", null),
        )
    }

    @Test
    fun usernameSetVariables_withAndWithoutSession() {
        assertEquals(
            """{"username":"johndoe","source":"USER_INPUT","pin":"ABC123","session_id":"s9"}""",
            WhatsAppMexOps.buildUsernameSetVariables("johndoe", "ABC123", "USER_INPUT", "s9"),
        )
    }

    @Test
    fun blocklistVariables_shapes() {
        assertEquals("{}", WhatsAppMexOps.buildBlocklistGetVariables(null))
        assertEquals("""{"dhash":"h1"}""", WhatsAppMexOps.buildBlocklistGetVariables("h1"))
        val update = WhatsAppMexOps.buildUpdateBlocklistLidVariables(
            "lid1", "pn_jid", "1@s.whatsapp.net", "BLOCK", "d2",
        )
        assertEquals(
            """{"block_input":{"user":"lid1","identifier":{"pn_jid":"1@s.whatsapp.net"},"action":"BLOCK","dhash":"d2"}}""",
            update,
        )
        val updateNoDhash = WhatsAppMexOps.buildUpdateBlocklistLidVariables(
            "lid1", "username", "jo", "UNBLOCK", null,
        )
        assertTrue(!updateNoDhash.contains("dhash"), updateNoDhash)
    }

    @Test
    fun presenceVariables_withAndWithoutFilter() {
        assertEquals(
            """{"get_online_or_last_status_input":{"online_or_last_status_input":[{"jid":"a@lid"}],"last_active_filter":"LAST_HOUR"}}""",
            WhatsAppMexOps.buildOnlineOrLastStatusVariables(listOf("a@lid"), "LAST_HOUR"),
        )
        assertTrue(
            !WhatsAppMexOps.buildOnlineOrLastStatusVariables(listOf("a@lid"), null).contains("last_active_filter"),
        )
    }

    @Test
    fun newsletterVariables_shapes() {
        assertEquals(
            """{"input":{"key":"k1","type":"JID"}}""",
            WhatsAppMexOps.buildNewsletterVariables("k1", "JID", null),
        )
        assertEquals(
            """{"input":{"key":"k1","type":"INVITE","view_role":"GUEST"}}""",
            WhatsAppMexOps.buildNewsletterVariables("k1", "INVITE", "GUEST"),
        )
        assertEquals(
            """{"newsletter_id":"n1"}""",
            WhatsAppMexOps.buildNewsletterIdVariables("n1"),
        )
    }

    @Test
    fun messagingKeysVariables_shapes() {
        assertEquals(
            """{"input":{"users":["a@s.whatsapp.net","b@s.whatsapp.net"]}}""",
            WhatsAppMexOps.buildGetMessagingKeysVariables(listOf("a@s.whatsapp.net", "b@s.whatsapp.net")),
        )
        val setVars = WhatsAppMexOps.buildSetMessagingKeysVariables(
            identity = byteArrayOf(1, 2, 3),
            skeyId = 1,
            skeyValue = byteArrayOf(4),
            skeySignature = byteArrayOf(5, 6),
            prekeys = listOf(2 to byteArrayOf(7)),
        )
        // type = base64(0x05), ids are 3-byte big-endian base64
        assertTrue(setVars.contains("\"type\":\"" + Base64.getEncoder().encodeToString(byteArrayOf(0x05)) + "\""), setVars)
        assertTrue(setVars.contains("\"identity\":\"" + Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3)) + "\""), setVars)
        // ids are 3-byte big-endian base64: 1 -> AAAB, 2 -> AAAC
        assertTrue(setVars.contains("\"id\":\"AAAB\""), setVars)
        assertTrue(setVars.contains("\"id\":\"AAAC\""), setVars)
    }

    @Test
    fun opNames_areDocumentedConstants() {
        assertEquals("QueryGroupInfo", WhatsAppMexOps.OP_GROUP_QUERY)
        assertEquals("ContactUserInfoQuery", WhatsAppMexOps.OP_CONTACT_DISCOVERY)
        assertEquals("UsernameSetMutation", WhatsAppMexOps.OP_USERNAME_SET)
        assertEquals("GetMessagingKeys", WhatsAppMexOps.OP_GET_MESSAGING_KEYS)
        assertEquals("SetMessagingKeys", WhatsAppMexOps.OP_SET_MESSAGING_KEYS)
    }
}
