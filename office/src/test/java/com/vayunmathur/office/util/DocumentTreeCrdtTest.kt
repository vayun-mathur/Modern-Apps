package com.vayunmathur.office.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentTreeCrdtTest {

    private fun replicas(base: String): Pair<DocumentTreeCrdt, DocumentTreeCrdt> {
        val a = DocumentTreeCrdt("A")
        val ops = a.update(base)
        val b = DocumentTreeCrdt("B")
        b.apply(ops)
        return a to b
    }

    @Test
    fun xml_declaration_and_full_document_roundtrip() {
        // Mirrors real serializeFlat output: an <?xml?> declaration then the document element.
        val xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><office:document office:version=\"1.3\">" +
            "<office:body><office:text><text:p>Hi</text:p></office:text></office:body></office:document>"
        val a = DocumentTreeCrdt("A")
        a.update(xml)
        assertEquals(xml, a.render())
        // And a concurrent edit still merges + keeps the declaration + valid structure.
        val b = DocumentTreeCrdt("B"); b.apply(a.toState().nodes)
        val opsA = a.update(xml.replace("<text:p>Hi</text:p>", "<text:p>Hi there</text:p>"))
        val opsB = b.update(xml.replace("<text:p>Hi</text:p>", "<text:p>Oh Hi</text:p>"))
        a.apply(opsB); b.apply(opsA)
        assertEquals(a.render(), b.render())
        val r = a.render()
        assertTrue(r.startsWith("<?xml"))
        assertTrue(r.contains("there")); assertTrue(r.contains("Oh"))
        assertEquals(1, Regex("<office:document ").findAll(r).count())
    }

    @Test
    fun roundtrip_render_equals_input() {
        val a = DocumentTreeCrdt("A")
        val xml = "<office:text><text:p>Hello &amp; world</text:p><draw:frame svg:width=\"1>2\"/></office:text>"
        a.update(xml)
        assertEquals(xml, a.render())
    }

    @Test
    fun concurrent_char_edits_same_paragraph_merge() {
        val (a, b) = replicas("<t><p>Hello</p></t>")
        val opsA = a.update("<t><p>Hello World</p></t>")
        val opsB = b.update("<t><p>Hi Hello</p></t>")
        a.apply(opsB); b.apply(opsA)
        assertEquals(a.render(), b.render())
        val r = a.render()
        assertTrue("A's edit", r.contains("World"))
        assertTrue("B's edit", r.contains("Hi"))
        assertEquals("one paragraph", 1, Regex("<p>").findAll(r).count())
        assertEquals(1, Regex("</p>").findAll(r).count())
    }

    /** THE EDGE: A types into a paragraph while B deletes that paragraph. A's text must NOT strand. */
    @Test
    fun edit_inside_deleted_paragraph_cascades_no_stranding() {
        val (a, b) = replicas("<office:text><text:p>Hello</text:p></office:text>")
        val opsA = a.update("<office:text><text:p>Hello World</text:p></office:text>") // A appends " World"
        val opsB = b.update("<office:text></office:text>")                              // B deletes the paragraph
        a.apply(opsB); b.apply(opsA)
        assertEquals("replicas converge", a.render(), b.render())
        val r = a.render()
        assertEquals("paragraph (and A's concurrent text) fully removed — nothing stranded", "<office:text></office:text>", r)
        assertTrue(!r.contains("World"))
        assertTrue(!r.contains("<text:p>"))
    }

    @Test
    fun edit_one_paragraph_delete_another() {
        val (a, b) = replicas("<t><p>one</p><p>two</p></t>")
        val opsA = a.update("<t><p>one EDIT</p><p>two</p></t>") // A edits P1
        val opsB = b.update("<t><p>two</p></t>")               // B deletes P1
        a.apply(opsB); b.apply(opsA)
        assertEquals(a.render(), b.render())
        // P1 was deleted by B; A's edit to it cascades away. P2 ("two") survives.
        val r = a.render()
        assertTrue(r.contains("two"))
        assertEquals("only one paragraph remains", 1, Regex("<p>").findAll(r).count())
    }

    @Test
    fun concurrent_structural_inserts_stay_wellformed() {
        val (a, b) = replicas("<t><p>one</p></t>")
        val opsA = a.update("<t><p>one</p><p>A-para</p></t>")
        val opsB = b.update("<t><p>one</p><p>B-para</p></t>")
        a.apply(opsB); b.apply(opsA)
        assertEquals(a.render(), b.render())
        val r = a.render()
        assertTrue(r.contains("A-para")); assertTrue(r.contains("B-para")); assertTrue(r.contains("one"))
        assertEquals(3, Regex("<p>").findAll(r).count())
        assertEquals(3, Regex("</p>").findAll(r).count())
    }

    @Test
    fun merge_is_commutative_and_idempotent() {
        val (a, b) = replicas("<t><p>base</p></t>")
        val opsA = a.update("<t><p>baseA</p></t>")
        val opsB = b.update("<t><p>Bbase</p></t>")
        // apply in opposite orders on fresh replicas built from the same base
        val (c, d) = replicas("<t><p>base</p></t>")
        c.apply(opsA); c.apply(opsB); c.apply(opsB) // idempotent double-apply
        d.apply(opsB); d.apply(opsA)
        assertEquals(c.render(), d.render())
    }

    @Test
    fun state_serialization_roundtrip() {
        val (a, _) = replicas("<t><p>persist me</p></t>")
        val json = a.serialize()
        val restored = DocumentTreeCrdt("A")
        restored.loadState(json)
        assertEquals(a.render(), restored.render())
    }
}
