package com.vayunmathur.everysync.sync.adapters

import com.vayunmathur.everysync.data.RemoteContact
import com.vayunmathur.everysync.data.RemoteEvent
import com.vayunmathur.everysync.data.RemoteNote
import com.vayunmathur.everysync.data.RemoteTodoList
import com.vayunmathur.everysync.data.TypedValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KindsExtraTest {
    @Test fun calendarSyncIdIncludesRecurrence() {
        val master = RemoteEvent(uid="e1", calendarId="cal", summary="S", startMillis=1000, endMillis=2000)
        val inst = master.copy(recurrenceId="20240101T000000Z")
        assertEquals("e1", CalendarKind.syncId(master))
        assertTrue(CalendarKind.syncId(inst).startsWith("e1"))
        assertTrue(CalendarKind.syncId(inst) != "e1")
        val rebound = CalendarKind.rebind(inst, CalendarKind.syncId(inst))
        assertEquals("e1", rebound.uid); assertEquals("20240101T000000Z", rebound.recurrenceId)
        val plain = CalendarKind.rebind(master, "newid"); assertEquals("newid", plain.uid)
    }
    @Test fun calendarCanonicalIgnoresIdentityAndTransport() {
        val a = RemoteEvent(uid="a", etag="x", href="h", calendarId="c1", summary="Meet", startMillis=1700000000000, endMillis=1700003600000, updatedAt=5)
        val b = a.copy(uid="b", etag=null, href=null, calendarId="c2", updatedAt=99)
        assertEquals(CalendarKind.canonical(a), CalendarKind.canonical(b))
        assertEquals(CalendarKind.contentHash(a), CalendarKind.contentHash(b))
        assertTrue(CalendarKind.contentHash(a) != CalendarKind.contentHash(a.copy(deleted=true)))
        assertEquals("event", CalendarKind.kind); assertEquals(true, CalendarKind.localDeletesAreAuthoritative)
    }
    @Test fun calendarJsonRoundTripAndCorrupt() {
        val e = RemoteEvent(uid="e9", calendarId="c", summary="S", rrule="FREQ=DAILY", recurrenceId="r1", updatedAt=7)
        assertEquals(e, CalendarKind.fromJson(CalendarKind.toJson(e)))
        assertNull(CalendarKind.fromJson("nope")); assertNull(CalendarKind.fromJson(""))
        assertEquals(e, CalendarKind.fromJson("""{"uid":"e9","calendarId":"c","summary":"S","rrule":"FREQ=DAILY","recurrenceId":"r1","updatedAt":7,"future":1}"""))
    }
    @Test fun calendarContentKeyAndMutators() {
        val e = RemoteEvent(uid="e1", calendarId="cal", recurrenceId="r")
        assertTrue(CalendarKind.contentKey(e).contains("cal")); assertTrue(CalendarKind.contentKey(e).contains("r"))
        assertEquals(true, CalendarKind.withDeleted(e, true).deleted)
        assertEquals(42L, CalendarKind.withUpdatedAt(e, 42).updatedAt)
        assertEquals(42L, CalendarKind.updatedAt(e.copy(updatedAt=42)))
    }
    @Test fun contactKindCanonicalAndVCard() {
        val c = RemoteContact(uid="a", etag="e", href="h", displayName="Ann", phones=listOf(TypedValue("123", 2)), updatedAt=9)
        val b = c.copy(uid="b", etag=null, href=null, updatedAt=1)
        assertEquals(ContactKind.canonical(c), ContactKind.canonical(b))
        assertEquals(ContactKind.contentHash(c), ContactKind.contentHash(b))
        assertTrue(ContactKind.contentKey(c).contains("Ann")); assertTrue(ContactKind.contentKey(c).contains("123"))
        assertEquals("contact", ContactKind.kind)
        assertEquals(c, ContactKind.fromJson(ContactKind.toJson(c))); assertNull(ContactKind.fromJson("{"))
        val parsed = ContactKind.parseVCard("BEGIN:VCARD\nUID:v1\nFN:Vera\nEND:VCARD\n", "fb", updatedAt=33)
        assertEquals("Vera", parsed.displayName); assertEquals(33L, parsed.updatedAt)
        assertEquals("z", ContactKind.rebind(c, "z").uid); assertEquals(true, ContactKind.withDeleted(c, true).deleted)
    }
    @Test fun noteKind() {
        val n = RemoteNote(uid="n", title="T", content="C", position=2.0, updatedAt=5)
        assertEquals(NoteKind.canonical(n), NoteKind.canonical(n.copy(uid="x", position=9.0, updatedAt=9)))
        assertEquals(n, NoteKind.fromJson(NoteKind.toJson(n))); assertNull(NoteKind.fromJson("[]"))
        assertEquals("note", NoteKind.kind); assertTrue(NoteKind.contentKey(n).startsWith("note"))
        assertEquals(listOf("x.png"), NoteKind.imageNames("""{"blocks":[{"fileName": "x.png"},{"fileName":""}]}"""))
    }
    @Test fun todoListKind() {
        val l = RemoteTodoList(uid="l1", name="Work", color=123, sections=listOf("A"), createdAt=7, updatedAt=10)
        assertEquals(l, TodoListKind.fromJson(TodoListKind.toJson(l))); assertNull(TodoListKind.fromJson("bad"))
        assertEquals(TodoListKind.canonical(l), TodoListKind.canonical(l.copy(uid="z", position=5.0, updatedAt=99)))
        assertEquals("todolist", TodoListKind.kind); assertTrue(TodoListKind.contentKey(l).contains("Work"))
        assertEquals("q", TodoListKind.rebind(l, "q").uid); assertEquals(true, TodoListKind.withDeleted(l, true).deleted); assertEquals(3L, TodoListKind.withUpdatedAt(l,3).updatedAt)
    }
    @Test fun passwordFactories() {
        val p = PasswordKind.password(uid="p", name="Bank", username="u", email="e", password="pw", note="n", totpSecret="t", websites=listOf("w"), updatedAt=11)
        assertEquals(false, p.isPasskey); assertEquals("Bank", p.name); assertEquals(11L, p.updatedAt)
        val k = PasswordKind.passkey(uid="k", rpId="rp", credentialId="cred", rpName="RP", userId="uid", signCount=3, updatedAt=12)
        assertEquals(true, k.isPasskey); assertEquals("rp", k.rpId); assertEquals(3, k.signCount)
        assertEquals(false, PasswordKind.localDeletesAreAuthoritative)
        assertEquals(PasswordKind.canonical(p), PasswordKind.canonical(p.copy(uid="x", updatedAt=99)))
    }
}
