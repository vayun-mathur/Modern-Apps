package com.vayunmathur.securemail

import com.vayunmathur.securemail.data.MailFolder
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [Route] values: defaults, equality, and the pending-route holder. */
class RouteTest {

    @Test fun `composer defaults and copy semantics`() {
        val empty = Route.Composer()
        assertEquals("", empty.to)
        assertEquals("", empty.subject)
        assertEquals("", empty.body)
        assertNull(empty.inReplyTo)
        assertNull(empty.draftId)
        assertEquals("", empty.cc)
        assertEquals("", empty.bcc)
        assertNull(empty.from)
        val full = Route.Composer(
            to = "ada@example.org", subject = "S", body = "B", inReplyTo = "m1",
            draftId = "d1", cc = "c@x.org", bcc = "b@x.org", from = "alias@maemail.org",
        )
        assertEquals("ada@example.org", full.component1())
        assertEquals("m1", full.component4())
        assertEquals("alias@maemail.org", full.component8())
        assertEquals(full, full.copy())
        assertNotEquals(full, full.copy(subject = "Other"))
        assertEquals(full.hashCode(), full.copy().hashCode())
        assertTrue(full.toString().contains("ada@example.org"))
    }

    @Test fun `reader and singleton routes are distinct values`() {
        assertEquals(Route.Reader("m1"), Route.Reader("m1"))
        assertNotEquals(Route.Reader("m1"), Route.Reader("m2"))
        assertEquals("m1", Route.Reader("m1").messageId)
        assertNotEquals(Route.Inbox() as Route, Route.Settings as Route)
        assertNotEquals(Route.Onboarding as Route, Route.Lock as Route)
        assertNotEquals(Route.Out as Route, Route.Inbox() as Route)
    }

    @Test fun `inbox route carries its folder and defaults to inbox`() {
        assertEquals(MailFolder.INBOX, Route.Inbox().folder)
        assertEquals(Route.Inbox(MailFolder.INBOX), Route.Inbox())
        assertEquals(MailFolder.SPAM, Route.Inbox(MailFolder.SPAM).folder)
        assertNotEquals(Route.Inbox(MailFolder.INBOX), Route.Inbox(MailFolder.SPAM))
        assertNotEquals(Route.Inbox(MailFolder.SPAM), Route.Inbox(MailFolder.TRASH))
        assertEquals(Route.Inbox(MailFolder.SPAM), Route.Inbox(MailFolder.SPAM).copy())
        assertEquals(Route.Inbox(MailFolder.TRASH), Route.Inbox(MailFolder.SPAM).copy(folder = MailFolder.TRASH))
    }

    @Test fun `routes serialize through their generated serializers`() {
        val json = Json { ignoreUnknownKeys = true }
        val composer = Route.Composer(to = "a@x.org", subject = "Hi", from = "me@maemail.org")
        val encoded = json.encodeToString(Route.Composer.serializer(), composer)
        assertEquals(composer, json.decodeFromString(Route.Composer.serializer(), encoded))
        val reader = Route.Reader("msg-9")
        assertEquals(reader, json.decodeFromString(Route.Reader.serializer(), json.encodeToString(Route.Reader.serializer(), reader)))
        val inbox = Route.Inbox(MailFolder.SPAM)
        assertEquals(
            inbox,
            json.decodeFromString(Route.Inbox.serializer(), json.encodeToString(Route.Inbox.serializer(), inbox)),
        )
        assertEquals(MailFolder.SPAM, json.decodeFromString(Route.Inbox.serializer(), json.encodeToString(Route.Inbox.serializer(), inbox)).folder)
    }

    @Test fun `sealed route serializer round trips every variant`() {
        val json = Json { ignoreUnknownKeys = true }
        val routes: List<Route> = listOf(
            Route.Onboarding, Route.Lock, Route.Inbox(), Route.Inbox(MailFolder.TRASH), Route.Out, Route.Settings,
            Route.Composer(to = "a@x.org", subject = "S", draftId = "d1"),
            Route.Reader("m-1"),
        )
        for (route in routes) {
            val encoded = json.encodeToString(Route.serializer(), route)
            assertEquals(route, json.decodeFromString(Route.serializer(), encoded), encoded)
        }
    }

    @Test fun `intent state holds and clears a pending route`() {
        IntentState.navigationRoute = Route.Reader("pending-1")
        assertEquals(Route.Reader("pending-1"), IntentState.navigationRoute)
        IntentState.navigationRoute = null
        assertNull(IntentState.navigationRoute)
    }
}
