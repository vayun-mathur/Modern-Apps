package com.vayunmathur.office.odf

import com.vayunmathur.library.ui.odf.OdfMetadata
import org.xmlpull.v1.XmlPullParser

/**
 * Parses OOXML document properties (Phase C3): docProps/core.xml (Dublin Core + cp), app.xml
 * (application/company/counts/editing time), and custom.xml (user-defined properties) into
 * [OdfMetadata].
 */
internal object OoxmlMetadata {

    fun parse(pkg: OoxmlPackage): OdfMetadata {
        var title: String? = null
        var creator: String? = null
        var subject: String? = null
        var description: String? = null
        var keywords: List<String> = emptyList()
        var created: String? = null
        var modified: String? = null
        var generator: String? = null
        var company: String? = null
        var words: Int? = null
        var chars: Int? = null
        var pages: Int? = null
        var paragraphs: Int? = null
        var editingCycles: Int? = null
        val userDefined = LinkedHashMap<String, String>()
        val userDefinedTypes = LinkedHashMap<String, String>()

        pkg.entries["docProps/core.xml"]?.let { xml ->
            val p = OoxmlXml.newParser(xml)
            var e = p.eventType
            while (e != XmlPullParser.END_DOCUMENT) {
                if (e == XmlPullParser.START_TAG) when (p.name) {
                    "title" -> title = OoxmlXml.readElementText(p, "title").ifBlank { null }
                    "creator" -> creator = OoxmlXml.readElementText(p, "creator").ifBlank { null }
                    "subject" -> subject = OoxmlXml.readElementText(p, "subject").ifBlank { null }
                    "description" -> description = OoxmlXml.readElementText(p, "description").ifBlank { null }
                    "keywords" -> OoxmlXml.readElementText(p, "keywords").ifBlank { null }
                        ?.let { keywords = it.split(',', ';').map(String::trim).filter(String::isNotEmpty) }
                    "created" -> created = OoxmlXml.readElementText(p, "created").ifBlank { null }
                    "modified" -> modified = OoxmlXml.readElementText(p, "modified").ifBlank { null }
                }
                e = p.next()
            }
        }

        pkg.entries["docProps/app.xml"]?.let { xml ->
            val p = OoxmlXml.newParser(xml)
            var e = p.eventType
            while (e != XmlPullParser.END_DOCUMENT) {
                if (e == XmlPullParser.START_TAG) when (p.name) {
                    "Application" -> generator = OoxmlXml.readElementText(p, "Application").ifBlank { null }
                    "Company" -> company = OoxmlXml.readElementText(p, "Company").ifBlank { null }
                    "Words" -> words = OoxmlXml.readElementText(p, "Words").trim().toIntOrNull()
                    "Characters" -> chars = OoxmlXml.readElementText(p, "Characters").trim().toIntOrNull()
                    "Pages" -> pages = OoxmlXml.readElementText(p, "Pages").trim().toIntOrNull()
                    "Paragraphs" -> paragraphs = OoxmlXml.readElementText(p, "Paragraphs").trim().toIntOrNull()
                    "TotalTime" -> editingCycles = OoxmlXml.readElementText(p, "TotalTime").trim().toIntOrNull()
                }
                e = p.next()
            }
        }

        pkg.entries["docProps/custom.xml"]?.let { xml ->
            val p = OoxmlXml.newParser(xml)
            var e = p.eventType
            while (e != XmlPullParser.END_DOCUMENT) {
                if (e == XmlPullParser.START_TAG && p.name == "property") {
                    val name = OoxmlXml.attr(p, "name") ?: ""
                    // read the single typed child (vt:*) value
                    val depth = p.depth
                    var value: String? = null
                    var type: String? = null
                    var ev = p.next()
                    while (!(ev == XmlPullParser.END_TAG && p.depth == depth && p.name == "property")) {
                        if (ev == XmlPullParser.END_DOCUMENT) break
                        if (ev == XmlPullParser.START_TAG && p.name in VT_TAGS) {
                            type = vtType(p.name)
                            value = OoxmlXml.readElementText(p, p.name)
                        }
                        ev = p.next()
                    }
                    if (name.isNotBlank() && value != null) {
                        userDefined[name] = value!!
                        type?.let { userDefinedTypes[name] = it }
                    }
                }
                e = p.next()
            }
        }

        return OdfMetadata(
            title = title, creator = creator, author = creator, subject = subject,
            description = description, keywords = keywords,
            creationDate = created, modifiedDate = modified,
            generator = generator, wordCount = words, charCount = chars,
            pageCount = pages, paragraphCount = paragraphs, editingCycles = editingCycles,
            userDefined = userDefined, userDefinedTypes = userDefinedTypes
        ).let { if (company != null) it.copy(userDefined = it.userDefined + ("Company" to company)) else it }
    }

    private val VT_TAGS = setOf("lpwstr", "lpstr", "i4", "int", "r8", "bool", "filetime", "decimal")

    private fun vtType(tag: String): String = when (tag) {
        "i4", "int", "decimal" -> "float"
        "r8" -> "float"
        "bool" -> "boolean"
        "filetime" -> "date"
        else -> "string"
    }
}
