package com.vayunmathur.office.data.odf

import org.xmlpull.v1.XmlPullParser

/**
 * Best-effort text extraction from SmartArt diagrams (Group 12 out-of-scope partial). SmartArt is
 * not modeled structurally; we pull the visible text out of the diagram data part (`data1.xml`) so
 * it isn't lost, returning one string per text paragraph.
 */
internal object OoxmlDiagram {

    private const val A_NS = "http://schemas.openxmlformats.org/drawingml/2006/main"

    /** Extracts diagram text paragraphs from the data part at [dataPartPath], or empty. */
    fun extractText(pkg: OoxmlPackage, dataPartPath: String?): List<String> {
        val xml = dataPartPath?.let { pkg.entries[it] } ?: return emptyList()
        val parser = OoxmlXml.newParser(xml)
        val out = mutableListOf<String>()
        val buf = StringBuilder()
        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG && parser.name == "t" && parser.namespace == A_NS) {
                buf.append(OoxmlXml.readElementText(parser, "t"))
            } else if (e == XmlPullParser.END_TAG && parser.name == "p" && parser.namespace == A_NS) {
                if (buf.isNotBlank()) out.add(buf.toString())
                buf.clear()
            }
            e = parser.next()
        }
        if (buf.isNotBlank()) out.add(buf.toString())
        return out
    }
}
