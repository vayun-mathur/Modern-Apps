package com.vayunmathur.logviewer.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogDocumentExtraTest {

    private fun doc(
        headerLines: List<String> = emptyList(),
        bodyLines: List<String> = listOf("body"),
        kind: LogKind = LogKind.ErrorReport,
    ) = LogDocument(
        kind = kind,
        sourcePackage = "com.example.app",
        title = "Title",
        headerLines = headerLines,
        bodyLines = bodyLines,
    )

    @Test
    fun `blank-only header is dropped so no separator is rendered`() {
        val d = doc(headerLines = listOf("", "   "), bodyLines = listOf("x", "y"))
        assertTrue(d.headerLines.isEmpty())
        assertEquals(listOf("x", "y"), d.lines)
        assertEquals(2, d.lines.size)
    }

    @Test
    fun `lines view indexes header separator then body`() {
        val d = doc(headerLines = listOf("h1", "h2"), bodyLines = listOf("b1", "b2", "b3"))
        assertEquals(6, d.lines.size)
        assertEquals("h1", d.lines[0])
        assertEquals("h2", d.lines[1])
        assertEquals("", d.lines[2])
        assertEquals("b1", d.lines[3])
        assertEquals("b3", d.lines[5])
        assertEquals(listOf("h1", "h2", "", "b1", "b2", "b3"), d.lines.toList())
    }

    @Test
    fun `string constructor splits and drops trailing blank lines only`() {
        val d = LogDocument(LogKind.Logcat, null, "t", header = "h\n\n", body = "\nmid\n\n")
        assertEquals(listOf("h"), d.headerLines)
        assertEquals(listOf("", "mid"), d.bodyLines)
        val empty = LogDocument(LogKind.Logcat, null, "t", header = "", body = "")
        assertTrue(empty.headerLines.isEmpty())
        assertTrue(empty.bodyLines.isEmpty())
        assertTrue(empty.lines.isEmpty())
    }

    @Test
    fun `splitLines handles empty and newline only`() {
        assertEquals(emptyList(), LogDocument.splitLines(""))
        assertEquals(emptyList(), LogDocument.splitLines("\n\n"))
        assertEquals(listOf("a", "", "b"), LogDocument.splitLines("a\n\nb\n"))
    }

    @Test
    fun `clip with multi-line header adds blank line and fences, logcat copy trims`() {
        val d = doc(
            headerLines = listOf("h1", "h2"),
            bodyLines = listOf("  padded\tline  "),
            kind = LogKind.Logcat,
        )
        val clip = d.clipText()
        assertFalse(clip.truncated)
        assertEquals("```\nh1\nh2\n\npadded\tline\n```\n", clip.text)
    }

    @Test
    fun `clip with single header has no extra blank line`() {
        val d = doc(headerLines = listOf("only"), bodyLines = listOf("b"))
        assertEquals("```\nonly\nb\n```\n", d.clipText().text)
    }

    @Test
    fun `clip of empty document is just fences`() {
        val d = doc(bodyLines = emptyList())
        assertEquals("```\n```\n", d.clipText().text)
        val sb = StringBuilder()
        d.writeSnapshot(sb)
        assertEquals("", sb.toString())
    }

    @Test
    fun `snapshot separates multi-line header but not single-line`() {
        val multi = StringBuilder()
        doc(headerLines = listOf("h1", "h2"), bodyLines = listOf("b")).writeSnapshot(multi)
        assertEquals("h1\nh2\n\nb\n", multi.toString())
        val single = StringBuilder()
        doc(headerLines = listOf("h1"), bodyLines = listOf("b")).writeSnapshot(single)
        assertEquals("h1\nb\n", single.toString())
    }

    @Test
    fun `snapshot file name appends twelve hex chars and txt extension`() {
        val name = LogDocument.snapshotFileName("My Log")
        assertTrue(name.startsWith("My Log "))
        assertTrue(name.endsWith(".txt"))
        val suffix = name.removePrefix("My Log ").removeSuffix(".txt")
        assertEquals(12, suffix.length)
        assertTrue(suffix.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals("application/octet-stream", LogDocument.MIME_TYPE)
    }

    @Test
    fun `snapshot file name truncates long titles to 200 chars`() {
        val name = LogDocument.snapshotFileName("a".repeat(300))
        // 200 chars of title + space + 12 hex + ".txt"
        assertEquals(200 + 1 + 12 + 4, name.length)
        assertTrue(name.startsWith("a".repeat(200)))
    }

    @Test
    fun `snapshot file name budget is in utf-8 bytes not chars`() {
        // 300 three-byte chars: a 200-char budget would emit a 600-byte filename,
        // over the 255-byte limit of the filesystems saves land on.
        val name = LogDocument.snapshotFileName("一".repeat(300))
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 200 + 1 + 12 + 4)
        assertTrue(name.endsWith(".txt"))
        // 66 chars fit in 200 bytes (198); the 67th does not, and nothing is mangled.
        assertEquals(66, name.count { it == '一' })
    }

    @Test
    fun `snapshot file name does not split a surrogate pair at the boundary`() {
        // Char 200 (index 199) is a high surrogate: trimming must drop it too.
        val title = "a".repeat(199) + "\uD83D\uDE00" + "tail"
        val name = LogDocument.snapshotFileName(title)
        assertEquals(199 + 1 + 12 + 4, name.length)
        assertTrue(name.startsWith("a".repeat(199) + " "))
    }

    @Test
    fun `clip budget counts supplementary characters as four bytes`() {
        // 50k emoji = 200k UTF-8 bytes, over budget on bytes though 100k chars.
        val d = doc(bodyLines = listOf("\uD83D\uDE00".repeat(50_000), "tail"))
        val clip = d.clipText()
        assertTrue(clip.truncated)
        assertTrue(clip.text.contains("tail"))
    }

    @Test
    fun `clip keeps header even when body is truncated`() {
        val body = (1..20_000).map { "line $it with enough padding to fill" }
        val clip = doc(headerLines = listOf("hdr"), bodyLines = body).clipText()
        assertTrue(clip.truncated)
        assertTrue(clip.text.startsWith("```\nhdr\n[[TRUNCATED]]\n"))
        assertTrue(clip.text.contains("line 20000"))
    }
}
