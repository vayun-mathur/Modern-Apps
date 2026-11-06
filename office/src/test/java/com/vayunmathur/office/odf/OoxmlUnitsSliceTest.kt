package com.vayunmathur.office.odf

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 1/4 (Ooxml*): unit tests for the pure helpers — [OoxmlUnits] conversions/color math and
 * [OoxmlXml] parsing primitives. These underpin every docx/xlsx/pptx importer.
 */
class OoxmlUnitsSliceTest {

    // ---- Length conversions ----

    @Test fun emuToPxAndPt() {
        assertEquals(1f, OoxmlUnits.emuToPx(9525), 0.001f)
        assertEquals(2f, OoxmlUnits.emuToPx(19_050), 0.001f)
        assertEquals(0f, OoxmlUnits.emuToPx(0), 0.001f)
        assertEquals(1f, OoxmlUnits.emuToPt(12_700), 0.001f)
    }

    @Test fun twipsConversions() {
        assertEquals(1f, OoxmlUnits.twipsToPt(20), 0.001f)
        assertEquals(12f, OoxmlUnits.twipsToPt(240), 0.001f)
        // 1440 twips = 1 inch = 96 px.
        assertEquals(96f, OoxmlUnits.twipsToPx(1440), 0.01f)
        assertEquals(12f, OoxmlUnits.halfPtToPt(24), 0.001f)
        assertEquals(12f, OoxmlUnits.hundredthPtToPt(1200), 0.001f)
    }

    @Test fun angleAndExcelSizes() {
        assertEquals(90f, OoxmlUnits.angle60000ToDeg(5_400_000), 0.001f)
        assertEquals(0f, OoxmlUnits.angle60000ToDeg(0), 0.001f)
        // 8.43 default Excel column width ~= 64px.
        assertEquals(64.01f, OoxmlUnits.excelColWidthToPx(8.43f), 0.05f)
        assertEquals(16f, OoxmlUnits.ptToPx(12f), 0.001f)
    }

    // ---- Hex colors ----

    @Test fun hexColorForms() {
        assertEquals(0xFFFF0000L, OoxmlUnits.hexColor("#FF0000"))
        assertEquals(0xFF00FF00L, OoxmlUnits.hexColor("00FF00"))
        assertEquals(0x800000FFL, OoxmlUnits.hexColor("800000FF"))
        assertNull(OoxmlUnits.hexColor(null))
        assertNull(OoxmlUnits.hexColor(""))
        assertNull(OoxmlUnits.hexColor("auto"))
        assertNull(OoxmlUnits.hexColor("AUTO"))
        assertNull(OoxmlUnits.hexColor("xyz"))
        assertNull(OoxmlUnits.hexColor("12345"))
        assertNull(OoxmlUnits.hexColor("red"))
    }

    @Test fun highlightColors() {
        assertEquals(0xFFFFFF00L, OoxmlUnits.highlightColor("yellow"))
        assertEquals(0xFFFF0000L, OoxmlUnits.highlightColor("RED"))
        assertEquals(0xFFA9A9A9L, OoxmlUnits.highlightColor("darkGray"))
        assertNull(OoxmlUnits.highlightColor(null))
        assertNull(OoxmlUnits.highlightColor("chartreuse"))
    }

    @Test fun sysColors() {
        assertEquals(0xFF000000L, OoxmlUnits.sysColor("windowText"))
        assertEquals(0xFFFFFFFFL, OoxmlUnits.sysColor("window"))
        assertEquals(0xFF3399FFL, OoxmlUnits.sysColor("highlight"))
        assertNull(OoxmlUnits.sysColor("notacolor"))
        assertNull(OoxmlUnits.sysColor(null))
    }

    // ---- Color transforms ----

    @Test fun shadeAndTint() {
        // 50% shade of white -> mid-grey.
        val shaded = OoxmlUnits.applyTransforms(0xFFFFFFFF, shade = 50000)
        assertEquals(127, ((shaded ushr 16) and 0xFF).toInt())
        // Tint mixes toward white: 50% tint of black -> mid-grey.
        val tinted = OoxmlUnits.applyTransforms(0xFF000000, tint = 50000)
        assertEquals(127, ((tinted ushr 16) and 0xFF).toInt())
        // Full keep-factor (100000) -> identity.
        assertEquals(0xFF000000L, OoxmlUnits.applyTransforms(0xFF000000, tint = 100000))
        // No transforms -> identity.
        assertEquals(0xFF123456L, OoxmlUnits.applyTransforms(0xFF123456))
    }

    @Test fun lumAndSatMods() {
        // Halving luminance of pure red darkens it.
        val dark = OoxmlUnits.applyTransforms(0xFFFF0000, lumMod = 50000)
        val r = ((dark ushr 16) and 0xFF).toInt()
        assertTrue(r in 120..140, "red channel $r should roughly halve")
        // Zero saturation turns red grey.
        val grey = OoxmlUnits.applyTransforms(0xFFFF0000, satMod = 0)
        val gr = ((grey ushr 16) and 0xFF).toInt()
        val gg = ((grey ushr 8) and 0xFF).toInt()
        val gb = (grey and 0xFF).toInt()
        assertEquals(gr, gg)
        assertEquals(gg, gb)
        // Luminance offset pushes black toward grey.
        val lifted = OoxmlUnits.applyTransforms(0xFF000000, lumOff = 50000)
        assertTrue(((lifted ushr 16) and 0xFF).toInt() > 100)
    }

    @Test fun alphaTransform() {
        val half = OoxmlUnits.applyTransforms(0xFFFF0000, alpha = 50000)
        assertEquals(127, ((half ushr 24) and 0xFF).toInt())
        // Alpha clamps at both ends.
        assertEquals(0, ((OoxmlUnits.applyTransforms(0xFFFF0000, alpha = -1000) ushr 24) and 0xFF).toInt())
        assertEquals(255, ((OoxmlUnits.applyTransforms(0xFFFF0000, alpha = 200000) ushr 24) and 0xFF).toInt())
    }

    // ---- OoxmlXml primitives ----

    @Test fun newParserAndAttr() {
        val p = OoxmlXml.newParser("<a:x xmlns:a=\"u\" b:c=\"1\" d=\"2\" xmlns:b=\"v\"/>")
        var e = p.eventType
        while (e != org.xmlpull.v1.XmlPullParser.START_TAG) e = p.next()
        assertEquals("1", OoxmlXml.attr(p, "c"))
        assertEquals("2", OoxmlXml.attr(p, "d"))
        assertNull(OoxmlXml.attr(p, "missing"))
    }

    @Test fun attrNsDisambiguates() {
        // Same local name "id" in two namespaces: attrNs picks by namespace URI.
        val q = OoxmlXml.newParser("<root xmlns:r=\"urn:r\" xmlns:w=\"urn:w\"><e r:id=\"R\" w:id=\"W\"/></root>")
        var e = q.eventType
        while (!(e == org.xmlpull.v1.XmlPullParser.START_TAG && q.name == "e")) e = q.next()
        assertEquals("R", OoxmlXml.attrNs(q, "urn:r", "id"))
        assertEquals("W", OoxmlXml.attrNs(q, "urn:w", "id"))
        assertNull(OoxmlXml.attrNs(q, "urn:other", "id"))
    }

    @Test fun boolAttrSemantics() {
        assertTrue(OoxmlXml.boolAttr(null))
        assertTrue(OoxmlXml.boolAttr("1"))
        assertTrue(OoxmlXml.boolAttr("true"))
        assertTrue(OoxmlXml.boolAttr("on"))
        assertTrue(!OoxmlXml.boolAttr("0"))
        assertTrue(!OoxmlXml.boolAttr("false"))
        assertTrue(!OoxmlXml.boolAttr("off"))
    }

    @Test fun cellRefIndices() {
        assertEquals(0, OoxmlXml.colIndex("A1"))
        assertEquals(25, OoxmlXml.colIndex("Z1"))
        assertEquals(26, OoxmlXml.colIndex("AA1"))
        assertEquals(27, OoxmlXml.colIndex("AB12"))
        assertEquals(0, OoxmlXml.rowIndex("A1"))
        assertEquals(11, OoxmlXml.rowIndex("AB12"))
        assertEquals(-1, OoxmlXml.rowIndex("ABC"))
    }

    @Test fun readElementTextConcatenates() {
        val p = OoxmlXml.newParser("<r><t>Hello</t><t> world</t></r>")
        var e = p.eventType
        while (!(e == org.xmlpull.v1.XmlPullParser.START_TAG && p.name == "r")) e = p.next()
        // readElementText only stops at the matching end tag at the same depth.
        val sb = StringBuilder()
        val depth = p.depth
        var ev = p.next()
        while (!(ev == org.xmlpull.v1.XmlPullParser.END_TAG && p.depth == depth && p.name == "r")) {
            if (ev == org.xmlpull.v1.XmlPullParser.TEXT) sb.append(p.text)
            ev = p.next()
        }
        assertEquals("Hello world", sb.toString())
    }

    @Test fun skipAndForEachChild() {
        val p = OoxmlXml.newParser("<root><a>1</a><c>2</c></root>")
        var e = p.eventType
        while (!(e == org.xmlpull.v1.XmlPullParser.START_TAG && p.name == "root")) e = p.next()
        val seen = mutableListOf<String>()
        OoxmlXml.forEachChild(p, "root") { seen.add(it) }
        assertEquals(listOf("a", "c"), seen)

        val q = OoxmlXml.newParser("<root><skip><x>1<y>2</y></x></skip><keep>v</keep></root>")
        var f = q.eventType
        while (!(f == org.xmlpull.v1.XmlPullParser.START_TAG && q.name == "skip")) f = q.next()
        OoxmlXml.skipElement(q)
        // After skipping, the next start tag should be <keep>.
        var g = q.next()
        while (g != org.xmlpull.v1.XmlPullParser.START_TAG && g != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) g = q.next()
        assertEquals("keep", q.name)
        assertNotNull(q)
    }
}
