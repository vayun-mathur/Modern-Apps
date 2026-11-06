package com.vayunmathur.communicate.whatsapp

import com.vayunmathur.communicate.data.whatsapp.WhatsAppProtocol
import com.vayunmathur.communicate.data.whatsapp.ephemeralBody
import com.vayunmathur.communicate.data.whatsapp.groupChangeBody
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 1 (w5): group-notification bodies (WhatsAppGroupBodies.kt). Pure JVM.
 */
class W5Slice1GroupBodiesTest {

    private fun node(tag: String, attrs: Map<String, String> = emptyMap(), data: String? = null) =
        WhatsAppProtocol.Node(
            tag = tag,
            attrs = attrs,
            data = data?.toByteArray(Charsets.UTF_8),
        )

    private fun memberNode(tag: String, vararg jids: String) = WhatsAppProtocol.Node(
        tag = tag,
        content = jids.map { WhatsAppProtocol.Node(tag = "participant", attrs = mapOf("jid" to it)) },
    )

    @Test
    fun subject_fromAttrAndFromData() {
        assertEquals(
            "[Group name changed to: New Name]",
            groupChangeBody(node("subject", mapOf("subject" to "New Name"))),
        )
        assertEquals(
            "[Group name changed to: Data Name]",
            groupChangeBody(node("subject", data = "Data Name")),
        )
        assertNull(groupChangeBody(node("subject")))
    }

    @Test
    fun description_usesDataOrEmpty() {
        assertEquals(
            "[Group description changed: hello]",
            groupChangeBody(node("description", data = "hello")),
        )
        assertEquals(
            "[Group description changed: ]",
            groupChangeBody(node("description")),
        )
    }

    @Test
    fun membership_listsParticipantsAndAction() {
        val body = groupChangeBody(
            memberNode("add", "1@s.whatsapp.net", "2@s.whatsapp.net"),
        )!!
        assertTrue(body.contains("1@s.whatsapp.net"), body)
        assertTrue(body.contains("added"), body)
        val removed = groupChangeBody(memberNode("remove", "3@s.whatsapp.net"))!!
        assertTrue(removed.contains("removeed"), removed)
        val promoted = groupChangeBody(memberNode("promote", "4@s.whatsapp.net"))!!
        assertTrue(promoted.contains("promoteed"), promoted)
    }

    @Test
    fun announce_locked_branches() {
        assertEquals(
            "[Only admins can send messages now]",
            groupChangeBody(node("announce", mapOf("announce" to "true"))),
        )
        assertEquals(
            "[Only admins can send messages now]",
            groupChangeBody(node("announce", mapOf("value" to "on"))),
        )
        assertEquals(
            "[All participants can send messages now]",
            groupChangeBody(node("announce")),
        )
        assertEquals(
            "[Only admins can edit group info now]",
            groupChangeBody(node("locked", mapOf("locked" to "true"))),
        )
        assertEquals(
            "[All participants can edit group info now]",
            groupChangeBody(node("locked", mapOf("locked" to "false"))),
        )
    }

    @Test
    fun link_unlink_andUnknown() {
        assertEquals(
            "[Group linked: community]",
            groupChangeBody(node("link", mapOf("link_type" to "community"))),
        )
        assertEquals(
            "[Group unlinked: subgroup]",
            groupChangeBody(node("unlink", mapOf("unlink_type" to "subgroup"))),
        )
        assertNull(groupChangeBody(node("bogus_tag")))
    }

    @Test
    fun ephemeral_offAndDurations() {
        assertEquals(
            "[Disappearing messages turned off]",
            ephemeralBody(node("ephemeral")),
        )
        assertEquals(
            "[Disappearing messages turned off]",
            ephemeralBody(node("ephemeral", mapOf("expiration" to "0"))),
        )
        assertEquals(
            "[Disappearing messages set to 90 seconds]",
            ephemeralBody(node("ephemeral", mapOf("expiration" to "90"))),
        )
        assertEquals(
            "[Disappearing messages set to 5 hour(s)]",
            ephemeralBody(node("ephemeral", mapOf("expiration" to "18000"))),
        )
        assertEquals(
            "[Disappearing messages set to 3 day(s)]",
            ephemeralBody(node("ephemeral", mapOf("expiration" to "259200"))),
        )
        assertEquals(
            "[Disappearing messages set to 1 week(s)]",
            ephemeralBody(node("ephemeral", mapOf("expiration" to "604800"))),
        )
    }
}
