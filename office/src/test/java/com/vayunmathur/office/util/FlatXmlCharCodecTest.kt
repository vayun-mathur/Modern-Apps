package com.vayunmathur.office.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlatXmlCharCodecTest {

    @Test
    fun roundtrip_is_exact() {
        val xml = """<office:document office:mimetype="x"><office:body><text:p>Hello &amp; world</text:p><draw:frame svg:width="1>2"/></office:body></office:document>"""
        assertEquals(xml, FlatXmlCharCodec.fromCells(FlatXmlCharCodec.toCells(xml)))
    }

    @Test
    fun tag_with_gt_in_attribute_stays_one_cell() {
        val xml = """<a b="x>y">t</a>"""
        val cells = FlatXmlCharCodec.toCells(xml)
        // The opening tag (with the '>' inside the quoted attribute) must be a single tag cell.
        assertTrue(cells.any { it == "t\u0002<a b=\"x>y\">" })
        assertEquals(xml, FlatXmlCharCodec.fromCells(cells))
    }

    @Test
    fun binary_data_is_one_opaque_cell_not_char_split() {
        val b64 = "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo=" // stand-in for a large image blob
        val xml = "<draw:image><office:binary-data>$b64</office:binary-data></draw:image>"
        val cells = FlatXmlCharCodec.toCells(xml)
        assertTrue("base64 kept whole", cells.any { it == "b\u0002$b64" })
        assertTrue("not char-split", cells.none { it == "c\u0002Q" && b64.startsWith("Q") && cells.count { c -> c.startsWith("c\u0002") } > 5 })
        assertEquals(xml, FlatXmlCharCodec.fromCells(cells))
    }

    @Test
    fun concurrent_edits_same_text_run_merge_char_level() {
        val base = "<office:text><text:p>Hello</text:p></office:text>"
        val a = DocumentCrdt("A"); val baseOps = a.update(FlatXmlCharCodec.toCells(base))
        val b = DocumentCrdt("B"); b.apply(baseOps)
        val opsA = a.update(FlatXmlCharCodec.toCells("<office:text><text:p>Hello World</text:p></office:text>"))
        val opsB = b.update(FlatXmlCharCodec.toCells("<office:text><text:p>Hi Hello</text:p></office:text>"))
        a.apply(opsB); b.apply(opsA)
        assertEquals(a.render(), b.render())
        val merged = FlatXmlCharCodec.fromCells(a.render())
        assertTrue("has A's edit", merged.contains("World"))
        assertTrue("has B's edit", merged.contains("Hi"))
        // Tags stay intact and balanced (one paragraph, valid structure).
        assertEquals(1, Regex("<text:p>").findAll(merged).count())
        assertEquals(1, Regex("</text:p>").findAll(merged).count())
    }

    @Test
    fun concurrent_structural_inserts_stay_wellformed() {
        val base = "<office:text><text:p>one</text:p></office:text>"
        val a = DocumentCrdt("A"); val baseOps = a.update(FlatXmlCharCodec.toCells(base))
        val b = DocumentCrdt("B"); b.apply(baseOps)
        val opsA = a.update(FlatXmlCharCodec.toCells("<office:text><text:p>one</text:p><text:p>A-para</text:p></office:text>"))
        val opsB = b.update(FlatXmlCharCodec.toCells("<office:text><text:p>one</text:p><text:p>B-para</text:p></office:text>"))
        a.apply(opsB); b.apply(opsA)
        assertEquals(a.render(), b.render())
        val merged = FlatXmlCharCodec.fromCells(a.render())
        assertTrue(merged.contains("A-para")); assertTrue(merged.contains("B-para")); assertTrue(merged.contains("one"))
        // Every <text:p> is matched by a </text:p> (no interleaved/stranded tags).
        assertEquals(Regex("<text:p>").findAll(merged).count(), Regex("</text:p>").findAll(merged).count())
        assertEquals(3, Regex("<text:p>").findAll(merged).count())
    }
}
