package com.vayunmathur.office.data.odf

import com.vayunmathur.library.ui.odf.OdfMetadata
import org.xmlpull.v1.XmlPullParser

/**
 * Parses OOXML document properties (Phase C3): docProps/core.xml (Dublin Core + cp), app.xml
 * (application/company/counts/editing time), and custom.xml (user-defined properties) into
 * [OdfMetadata].
 */
internal object OoxmlMetadata {

    private class MetaAcc(
        var title: String? = null,
        var creator: String? = null,
        var subject: String? = null,
        var description: String? = null,
        var keywords: List<String> = emptyList(),
        var created: String? = null,
        var modified: String? = null,
        var generator: String? = null,
        var company: String? = null,
        var words: Int? = null,
        var chars: Int? = null,
        var pages: Int? = null,
        var paragraphs: Int? = null,
        var editingCycles: Int? = null,
        val userDefined: LinkedHashMap<String, String> = LinkedHashMap(),
        val userDefinedTypes: LinkedHashMap<String, String> = LinkedHashMap(),
    )

    fun parse(pkg: OoxmlPackage): OdfMetadata {
        val acc = MetaAcc()
        pkg.entries["docProps/core.xml"]?.let { parseCore(it, acc) }
        pkg.entries["docProps/app.xml"]?.let { parseApp(it, acc) }
        pkg.entries["docProps/custom.xml"]?.let { parseCustom(it, acc) }
        return OdfMetadata(
            title = acc.title, creator = acc.creator, author = acc.creator, subject = acc.subject,
            description = acc.description, keywords = acc.keywords,
            creationDate = acc.created, modifiedDate = acc.modified,
            generator = acc.generator, wordCount = acc.words, charCount = acc.chars,
            pageCount = acc.pages, paragraphCount = acc.paragraphs, editingCycles = acc.editingCycles,
            userDefined = acc.userDefined, userDefinedTypes = acc.userDefinedTypes
        ).let { if (acc.company != null) it.copy(userDefined = it.userDefined + ("Company" to acc.company!!)) else it }
    }

    private fun parseCore(xml: String, acc: MetaAcc) {
        val p = OoxmlXml.newParser(xml)
        var e = p.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG) applyCoreTag(p, acc)
            e = p.next()
        }
    }

    private fun applyCoreTag(p: XmlPullParser, acc: MetaAcc) {
        when (p.name) {
            "title" -> acc.title = OoxmlXml.readElementText(p, "title").ifBlank { null }
            "creator" -> acc.creator = OoxmlXml.readElementText(p, "creator").ifBlank { null }
            "subject" -> acc.subject = OoxmlXml.readElementText(p, "subject").ifBlank { null }
            "description" -> acc.description = OoxmlXml.readElementText(p, "description").ifBlank { null }
            "keywords" -> applyKeywordsTag(p, acc)
            "created" -> acc.created = OoxmlXml.readElementText(p, "created").ifBlank { null }
            "modified" -> acc.modified = OoxmlXml.readElementText(p, "modified").ifBlank { null }
        }
    }

    private fun applyKeywordsTag(p: XmlPullParser, acc: MetaAcc) {
        OoxmlXml.readElementText(p, "keywords").ifBlank { null }
            ?.let { acc.keywords = it.split(',', ';').map(String::trim).filter(String::isNotEmpty) }
    }

    private fun parseApp(xml: String, acc: MetaAcc) {
        val p = OoxmlXml.newParser(xml)
        var e = p.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG) applyAppTag(p, acc)
            e = p.next()
        }
    }

    private fun applyAppTag(p: XmlPullParser, acc: MetaAcc) {
        when (p.name) {
            "Application" -> acc.generator = OoxmlXml.readElementText(p, "Application").ifBlank { null }
            "Company" -> acc.company = OoxmlXml.readElementText(p, "Company").ifBlank { null }
            "Words" -> acc.words = OoxmlXml.readElementText(p, "Words").trim().toIntOrNull()
            "Characters" -> acc.chars = OoxmlXml.readElementText(p, "Characters").trim().toIntOrNull()
            "Pages" -> acc.pages = OoxmlXml.readElementText(p, "Pages").trim().toIntOrNull()
            "Paragraphs" -> acc.paragraphs = OoxmlXml.readElementText(p, "Paragraphs").trim().toIntOrNull()
            "TotalTime" -> acc.editingCycles = OoxmlXml.readElementText(p, "TotalTime").trim().toIntOrNull()
        }
    }

    private fun parseCustom(xml: String, acc: MetaAcc) {
        val p = OoxmlXml.newParser(xml)
        var e = p.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG && p.name == "property") parseCustomProperty(p, acc)
            e = p.next()
        }
    }

    private fun parseCustomProperty(p: XmlPullParser, acc: MetaAcc) {
        val name = OoxmlXml.attr(p, "name") ?: ""
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
            acc.userDefined[name] = value
            type?.let { acc.userDefinedTypes[name] = it }
        }
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
