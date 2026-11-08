package com.vayunmathur.todo.domain.format

import com.vayunmathur.todo.data.Todo
import com.vayunmathur.todo.data.TodoList
import com.vayunmathur.todo.domain.DueDates
import com.vayunmathur.todo.domain.Recurrence
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FormatsTest {
    private val zone = TimeZone.of("America/Los_Angeles")
    private fun day(m: Int, d: Int) = DueDates.startOfDay(LocalDate(2026, m, d), zone)
    private val work = TodoList(syncId = "w", name = "Work Stuff")
    private val parent = Todo(
        syncId = "p1",
        title = "Ship release, then \"celebrate\"",
        notes = "line one\nline two",
        dueAt = day(3, 5),
        priority = 1,
        listSyncId = "w",
        tags = listOf("release"),
        recurrence = Recurrence.Weekly(1).serialize(),
        createdAt = day(3, 1),
    )
    private val timed = Todo(
        syncId = "t1",
        title = "Call",
        dueAt = DueDates.at(LocalDate(2026, 3, 6), LocalTime(17, 30), zone),
        dueAllDay = false,
        createdAt = day(3, 1),
    )
    private val done = Todo(syncId = "d1", title = "Done thing", done = true, completedAt = day(3, 2), createdAt = day(3, 1))
    private val sub = Todo(syncId = "s1", title = "Sub step", parentSyncId = "p1", createdAt = day(3, 1))
    private val source = ExportSource(listOf(parent, timed, done, sub), listOf(work))

    @Test
    fun `todo txt round trips the fields it can carry`() {
        val text = TodoTxt.export(source, zone)
        assertTrue(text.lines().first().startsWith("(A) 2026-03-01 Ship release"), text)
        assertTrue("+Work_Stuff" in text && "@release" in text && "due:2026-03-05" in text && "rec:+1w" in text, text)
        val back = TodoTxt.parse(text, zone)
        assertEquals(3, back.size, "subtasks are not exported to todo.txt")
        val p = back[0]
        assertEquals("Ship release, then \"celebrate\"", p.title)
        assertEquals(1, p.priority)
        assertEquals("Work Stuff", p.listName)
        assertEquals(listOf("release"), p.tags)
        assertEquals(day(3, 5), p.dueAt)
        assertEquals(Recurrence.Weekly(1), Recurrence.parse(p.recurrence))
        assertTrue(back[2].done)
        assertEquals(day(3, 2), back[2].completedAt)
    }

    @Test
    fun `todo txt understands completion-based repeats and thresholds`() {
        val t = TodoTxt.parse("water plants rec:3d t:2026-03-04", zone).single()
        assertEquals(Recurrence.AfterCompletion(3), Recurrence.parse(t.recurrence))
        assertEquals(day(3, 4), t.startAt)
        assertEquals("water plants", t.title)
    }

    @Test
    fun `csv round trips everything including quotes, newlines and subtasks`() {
        val back = TodoCsv.parse(TodoCsv.export(source, zone), zone)
        assertEquals(4, back.size)
        val p = back[0]
        assertEquals(parent.title, p.title)
        assertEquals(parent.notes, p.notes)
        assertEquals("Work Stuff", p.listName)
        assertEquals(parent.recurrence, p.recurrence)
        assertEquals(timed.dueAt, back[1].dueAt)
        assertEquals(false, back[1].dueAllDay)
        assertEquals("p1", back[3].parentRef)
        assertEquals("p1", p.ref)
    }

    @Test
    fun `csv reader handles quoted commas and CRLF`() {
        val rows = TodoCsv.readRows("a,\"b,c\",\"d\"\"e\"\r\n1,2,3\r\n")
        assertEquals(listOf(listOf("a", "b,c", "d\"e"), listOf("1", "2", "3")), rows)
    }

    @Test
    fun `icalendar round trips with folding, escaping and relations`() {
        val text = VTodo.export(source)
        assertTrue(text.lines().all { it.toByteArray().size <= 76 }, "lines are folded")
        assertTrue("DUE;VALUE=DATE:" in text && "RELATED-TO;RELTYPE=PARENT:p1@" in text, text)
        val back = VTodo.parse(text, TimeZone.currentSystemDefault())
        assertEquals(4, back.size)
        assertEquals(parent.title, back[0].title)
        assertEquals(parent.notes, back[0].notes)
        assertEquals(1, back[0].priority)
        assertEquals(listOf("release"), back[0].tags)
        assertEquals("Work Stuff", back[0].listName)
        assertEquals(timed.dueAt, back[1].dueAt)
        assertTrue(back[2].done)
        assertEquals("p1", back[3].parentRef)
    }

    @Test
    fun `icalendar round trips literal backslashes before escapable chars`() {
        val tricky = Todo(
            syncId = "x1",
            title = "C:\\new docs",
            notes = "a\\nb and c\\,d",
            createdAt = day(3, 1),
        )
        val back = VTodo.parse(VTodo.export(ExportSource(listOf(tricky), emptyList())), zone).single()
        assertEquals(tricky.title, back.title)
        assertEquals(tricky.notes, back.notes)
    }

    @Test
    fun `icalendar from other apps maps priority and dates`() {        val ics = """
            BEGIN:VCALENDAR
            BEGIN:VTODO
            UID:abc
            SUMMARY:Pay\, rent
            DUE;TZID=America/Los_Angeles:20260305T090000
            PRIORITY:5
            STATUS:NEEDS-ACTION
            END:VTODO
            END:VCALENDAR
        """.trimIndent()
        val t = VTodo.parse(ics, zone).single()
        assertEquals("Pay, rent", t.title)
        assertEquals(3, t.priority)
        assertEquals(DueDates.at(LocalDate(2026, 3, 5), LocalTime(9, 0), zone), t.dueAt)
        assertEquals(false, t.dueAllDay)
    }
}
