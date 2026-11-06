package com.vayunmathur.office.odf

import org.xmlpull.v1.XmlPullParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Slice 1/4 (Ooxml*): tests for theme resolution ([OoxmlTheme]), DrawingML color elements
 * ([OoxmlColor]), document properties ([OoxmlMetadata]) and SmartArt text fallback
 * ([OoxmlDiagram]).
 */
class OoxmlThemeColorSliceTest {

    private fun atTag(xml: String, tag: String): XmlPullParser {
        val p = OoxmlXml.newParser(xml)
        var e = p.eventType
        while (!(e == XmlPullParser.START_TAG && p.name == tag)) {
            e = p.next()
            if (e == XmlPullParser.END_DOCUMENT) throw IllegalArgumentException("no <$tag> in $xml")
        }
        return p
    }

    // ---- OoxmlTheme ----

    @Test fun themeDefaults() {
        val d = OoxmlTheme.DEFAULT
        assertEquals(0xFF4472C4L, d.schemeColor("accent1"))
        assertEquals("Calibri Light", d.majorFont)
        assertEquals("Calibri", d.minorFont)
        // tx/bg aliases fall back to the standard mapping.
        assertEquals(d.colors["dk1"], d.schemeColor("tx1"))
        assertEquals(d.colors["lt1"], d.schemeColor("bg1"))
        assertEquals(d.colors["dk2"], d.schemeColor("tx2"))
        assertEquals(d.colors["lt2"], d.schemeColor("bg2"))
        assertNull(d.schemeColor("phClr"))
        assertNull(d.schemeColor(null))
        assertNull(d.schemeColor("nope"))
    }

    @Test fun themeParseNullGivesDefault() {
        assertTrue(OoxmlTheme.parse(null) === OoxmlTheme.DEFAULT)
    }

    @Test fun themeParseFillsMissingSlots() {
        val theme = OoxmlTheme.parse(
            "<theme><themeElements><clrScheme name=\"X\">" +
                "<accent1><srgbClr val=\"123456\"/></accent1></clrScheme>" +
                "<fontScheme><majorFont><latin typeface=\"Arial\"/></majorFont>" +
                "<minorFont><latin typeface=\"Times\"/></minorFont></fontScheme>" +
                "</themeElements></theme>")
        assertEquals(0xFF123456L, theme.schemeColor("accent1"))
        assertEquals("Arial", theme.majorFont)
        assertEquals("Times", theme.minorFont)
        // Missing slots fall back to DEFAULT so lookups never return null unexpectedly.
        assertEquals(OoxmlTheme.DEFAULT.schemeColor("accent2"), theme.schemeColor("accent2"))
    }

    @Test fun themeParseSysClrSlot() {
        val theme = OoxmlTheme.parse(
            "<theme><themeElements><clrScheme name=\"X\">" +
                "<dk1><sysClr val=\"windowText\" lastClr=\"010203\"/></dk1>" +
                "<lt1><sysClr val=\"window\"/></lt1>" +
                "</clrScheme></themeElements></theme>")
        assertEquals(0xFF010203L, theme.schemeColor("dk1"))
        assertEquals(0xFFFFFFFFL, theme.schemeColor("lt1"))
    }

    @Test fun themeWithClrMapOverridesAliases() {
        val mapped = OoxmlTheme.DEFAULT.withClrMap(mapOf("tx1" to "lt1", "bg1" to "dk1"))
        assertEquals(OoxmlTheme.DEFAULT.colors["lt1"], mapped.schemeColor("tx1"))
        assertEquals(OoxmlTheme.DEFAULT.colors["dk1"], mapped.schemeColor("bg1"))
        // Empty map returns the same instance.
        assertTrue(OoxmlTheme.DEFAULT.withClrMap(emptyMap()) === OoxmlTheme.DEFAULT)
    }

    // ---- OoxmlColor ----

    @Test fun colorSrgb() {
        val c = OoxmlColor.parse(atTag("<root><srgbClr val=\"FF0000\"/></root>", "srgbClr"), OoxmlTheme.DEFAULT)
        assertEquals(0xFFFF0000L, c)
    }

    @Test fun colorSrgbWithShadeTransform() {
        val c = OoxmlColor.parse(
            atTag("<root><srgbClr val=\"FFFFFF\"><shade val=\"50000\"/></srgbClr></root>", "srgbClr"),
            OoxmlTheme.DEFAULT)
        assertEquals(127, (((c ?: 0L) ushr 16) and 0xFF).toInt())
    }

    @Test fun colorSysClrPrefersLastClr() {
        val c = OoxmlColor.parse(
            atTag("<root><sysClr val=\"windowText\" lastClr=\"112233\"/></root>", "sysClr"),
            OoxmlTheme.DEFAULT)
        assertEquals(0xFF112233L, c)
        // Without lastClr, falls back to the system color name.
        val c2 = OoxmlColor.parse(
            atTag("<root><sysClr val=\"window\"/></root>", "sysClr"),
            OoxmlTheme.DEFAULT)
        assertEquals(0xFFFFFFFFL, c2)
    }

    @Test fun colorSchemeClr() {
        val c = OoxmlColor.parse(
            atTag("<root><schemeClr val=\"accent1\"/></root>", "schemeClr"),
            OoxmlTheme.DEFAULT)
        assertEquals(0xFF4472C4L, c)
        assertNull(OoxmlColor.parse(
            atTag("<root><schemeClr val=\"phClr\"/></root>", "schemeClr"),
            OoxmlTheme.DEFAULT))
    }

    @Test fun colorPresetAndScrgb() {
        assertEquals(
            0xFFFF0000L,
            OoxmlColor.parse(atTag("<root><prstClr val=\"red\"/></root>", "prstClr"), OoxmlTheme.DEFAULT))
        assertNull(OoxmlColor.parse(
            atTag("<root><prstClr val=\"plaid\"/></root>", "prstClr"),
            OoxmlTheme.DEFAULT))
        assertEquals(
            0xFFFF0000L,
            OoxmlColor.parse(
                atTag("<root><scrgbClr r=\"100000\" g=\"0\" b=\"0\"/></root>", "scrgbClr"),
                OoxmlTheme.DEFAULT))
        assertNull(OoxmlColor.parse(
            atTag("<root><scrgbClr r=\"100000\" g=\"oops\"/></root>", "scrgbClr"),
            OoxmlTheme.DEFAULT))
    }

    @Test fun colorUnknownTagIsNull() {
        // Bare context color / unknown element: no resolvable base.
        assertNull(OoxmlColor.parse(
            atTag("<root><phClr/></root>", "phClr"),
            OoxmlTheme.DEFAULT))
    }

    @Test fun colorLumModTransform() {
        val c = OoxmlColor.parse(
            atTag("<root><srgbClr val=\"FF0000\"><lumMod val=\"50000\"/></srgbClr></root>", "srgbClr"),
            OoxmlTheme.DEFAULT)
        val r = (((c ?: 0L) ushr 16) and 0xFF).toInt()
        assertTrue(r in 120..140, "halved red channel was $r")
    }

    @Test fun colorSatModAndAlphaAndLumOff() {
        val grey = OoxmlColor.parse(
            atTag("<root><srgbClr val=\"FF0000\"><satMod val=\"0\"/></srgbClr></root>", "srgbClr"),
            OoxmlTheme.DEFAULT) ?: 0L
        assertEquals((grey ushr 16) and 0xFF, (grey ushr 8) and 0xFF)
        assertEquals((grey ushr 8) and 0xFF, grey and 0xFF)
        val half = OoxmlColor.parse(
            atTag("<root><srgbClr val=\"FF0000\"><alpha val=\"50000\"/></srgbClr></root>", "srgbClr"),
            OoxmlTheme.DEFAULT) ?: 0L
        assertEquals(127, ((half ushr 24) and 0xFF).toInt())
        val lifted = OoxmlColor.parse(
            atTag("<root><srgbClr val=\"000000\"><lumOff val=\"50000\"/></srgbClr></root>", "srgbClr"),
            OoxmlTheme.DEFAULT) ?: 0L
        assertTrue((((lifted ushr 16) and 0xFF).toInt()) > 100)
    }

    // ---- OoxmlMetadata ----

    private fun metaPkg() = OoxmlPackage(
        mapOf(
            "docProps/core.xml" to (
                "<coreProperties xmlns:dc=\"d\">" +
                    "<title>My Doc</title><creator>Ada</creator><subject>S</subject>" +
                    "<description>D</description><keywords>a, b;c </keywords>" +
                    "<created>2024-01-02T03:04:05Z</created><modified>2024-02-03T00:00:00Z</modified>" +
                    "</coreProperties>"),
            "docProps/app.xml" to (
                "<Properties><Application>Word</Application><Company>Acme</Company>" +
                    "<Words>10</Words><Characters>60</Characters><Pages>2</Pages>" +
                    "<Paragraphs>3</Paragraphs><TotalTime>7</TotalTime></Properties>"),
            "docProps/custom.xml" to (
                "<Properties><property name=\"Owner\"><lpwstr>Bo</lpwstr></property>" +
                    "<property name=\"Count\"><i4>42</i4></property>" +
                    "<property name=\"Flag\"><bool>true</bool></property>" +
                    "<property name=\"When\"><filetime>2024-01-01</filetime></property>" +
                    "</Properties>")
        ),
        emptyMap())

    @Test fun metadataParsesAllParts() {
        val m = OoxmlMetadata.parse(metaPkg())
        assertEquals("My Doc", m.title)
        assertEquals("Ada", m.creator)
        assertEquals("Ada", m.author)
        assertEquals("S", m.subject)
        assertEquals("D", m.description)
        assertEquals(listOf("a", "b", "c"), m.keywords)
        assertEquals("2024-01-02T03:04:05Z", m.creationDate)
        assertEquals("2024-02-03T00:00:00Z", m.modifiedDate)
        assertEquals("Word", m.generator)
        assertEquals(10, m.wordCount)
        assertEquals(60, m.charCount)
        assertEquals(2, m.pageCount)
        assertEquals(3, m.paragraphCount)
        assertEquals(7, m.editingCycles)
        assertEquals("Bo", m.userDefined["Owner"])
        assertEquals("Acme", m.userDefined["Company"])
        assertEquals("string", m.userDefinedTypes["Owner"])
        assertEquals("float", m.userDefinedTypes["Count"])
        assertEquals("boolean", m.userDefinedTypes["Flag"])
        assertEquals("date", m.userDefinedTypes["When"])
    }

    @Test fun metadataEmptyPackageGivesDefaults() {
        val m = OoxmlMetadata.parse(OoxmlPackage(emptyMap(), emptyMap()))
        assertNull(m.title)
        assertNull(m.creator)
        assertTrue(m.keywords.isEmpty())
        assertNull(m.wordCount)
        assertTrue(m.userDefined.isEmpty())
    }

    @Test fun metadataIgnoresBlankAndBadNumbers() {
        val m = OoxmlMetadata.parse(OoxmlPackage(
            mapOf(
                "docProps/core.xml" to "<coreProperties><title>   </title><keywords></keywords></coreProperties>",
                "docProps/app.xml" to "<Properties><Words>many</Words><Pages></Pages></Properties>"),
            emptyMap()))
        assertNull(m.title)
        assertTrue(m.keywords.isEmpty())
        assertNull(m.wordCount)
        assertNull(m.pageCount)
    }

    // ---- OoxmlDiagram ----

    private val diagramNs = "http://schemas.openxmlformats.org/drawingml/2006/main"

    @Test fun diagramExtractsParagraphText() {
        val pkg = OoxmlPackage(
            mapOf("diagrams/data1.xml" to (
                "<x xmlns:a=\"$diagramNs\"><a:p><a:r><a:t>hello </a:t></a:r>" +
                    "<a:r><a:t>world</a:t></a:r></a:p><a:p><a:t>second</a:t></a:p>" +
                    "<a:p></a:p></x>")),
            emptyMap())
        assertEquals(listOf("hello world", "second"), OoxmlDiagram.extractText(pkg, "diagrams/data1.xml"))
    }

    @Test fun diagramMissingPartGivesEmpty() {
        val pkg = OoxmlPackage(emptyMap(), emptyMap())
        assertTrue(OoxmlDiagram.extractText(pkg, "diagrams/data1.xml").isEmpty())
        assertTrue(OoxmlDiagram.extractText(pkg, null).isEmpty())
    }

    @Test fun diagramIgnoresOtherNamespaces() {
        val pkg = OoxmlPackage(
            mapOf("diagrams/data1.xml" to "<x><p><t>nope</t></p></x>"),
            emptyMap())
        assertTrue(OoxmlDiagram.extractText(pkg, "diagrams/data1.xml").isEmpty())
    }
}
