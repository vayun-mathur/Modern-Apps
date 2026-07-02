package com.vayunmathur.office.odf

import org.xmlpull.v1.XmlPullParser
import java.util.zip.ZipInputStream

/**
 * A parsed OOXML package (Phases 0A/0B/0D). Retains xml/rels parts as decoded text ([entries])
 * and binary media (png/jpeg/gif/emf/wmf/bmp/svg/tiff) as bytes ([media]). Provides relationship
 * resolution with path normalization and a content-types lookup.
 */
internal class OoxmlPackage(
    val entries: Map<String, String>,
    val media: Map<String, ByteArray>
) {
    /** A single relationship: absolute (normalized) [target], plus its type and target mode. */
    data class Rel(val id: String, val target: String, val type: String?, val external: Boolean)

    private val relsCache = HashMap<String, Map<String, Rel>>()
    private val contentTypes: ContentTypes by lazy {
        entries["[Content_Types].xml"]?.let { parseContentTypes(it) } ?: ContentTypes(emptyMap(), emptyMap())
    }

    /**
     * Relationships for the given [partPath] (e.g. "word/document.xml"), keyed by rId. Targets are
     * normalized to absolute package paths unless the relationship is external (TargetMode="External").
     */
    fun relsFor(partPath: String): Map<String, Rel> = relsCache.getOrPut(partPath) {
        val relsPath = relsPathFor(partPath)
        val xml = entries[relsPath] ?: return@getOrPut emptyMap()
        val map = LinkedHashMap<String, Rel>()
        val parser = OoxmlXml.newParser(xml)
        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG && parser.name == "Relationship") {
                val id = OoxmlXml.attr(parser, "Id")
                val target = OoxmlXml.attr(parser, "Target")
                val type = OoxmlXml.attr(parser, "Type")
                val external = OoxmlXml.attr(parser, "TargetMode").equals("External", true)
                if (id != null && target != null) {
                    val resolved = if (external) target else normalize(partPath, target)
                    map[id] = Rel(id, resolved, type, external)
                }
            }
            e = parser.next()
        }
        map
    }

    /** Resolves a rId against [partPath]'s relationships to an absolute package path (or external URL). */
    fun resolve(partPath: String, rId: String?): String? = rId?.let { relsFor(partPath)[it]?.target }

    /** Media bytes for an absolute package path, tolerating a leading slash. */
    fun mediaBytes(path: String?): ByteArray? {
        if (path == null) return null
        return media[path] ?: media[path.removePrefix("/")] ?: media["/$path".removePrefix("/")]
    }

    /** All parts declared (by override or default extension) with the given content-type substring. */
    fun partsOfType(typeContains: String): List<String> {
        val out = mutableListOf<String>()
        for ((part, ct) in contentTypes.overrides) if (ct.contains(typeContains, true)) out.add(part.removePrefix("/"))
        return out
    }

    private fun relsPathFor(partPath: String): String {
        val slash = partPath.lastIndexOf('/')
        val dir = if (slash >= 0) partPath.substring(0, slash) else ""
        val file = if (slash >= 0) partPath.substring(slash + 1) else partPath
        return if (dir.isEmpty()) "_rels/$file.rels" else "$dir/_rels/$file.rels"
    }

    private data class ContentTypes(val defaults: Map<String, String>, val overrides: Map<String, String>)

    private fun parseContentTypes(xml: String): ContentTypes {
        val defaults = HashMap<String, String>()
        val overrides = HashMap<String, String>()
        val parser = OoxmlXml.newParser(xml)
        var e = parser.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            if (e == XmlPullParser.START_TAG) when (parser.name) {
                "Default" -> {
                    val ext = OoxmlXml.attr(parser, "Extension"); val ct = OoxmlXml.attr(parser, "ContentType")
                    if (ext != null && ct != null) defaults[ext.lowercase()] = ct
                }
                "Override" -> {
                    val part = OoxmlXml.attr(parser, "PartName"); val ct = OoxmlXml.attr(parser, "ContentType")
                    if (part != null && ct != null) overrides[part] = ct
                }
            }
            e = parser.next()
        }
        return ContentTypes(defaults, overrides)
    }

    companion object {
        private val MEDIA_EXTS = setOf("png", "jpg", "jpeg", "gif", "bmp", "emf", "wmf", "svg", "tif", "tiff", "ico", "webp")

        /** Reads an OOXML zip into text entries + binary media. Never throws on malformed input. */
        fun read(bytes: ByteArray): OoxmlPackage {
            val entries = LinkedHashMap<String, String>()
            val media = LinkedHashMap<String, ByteArray>()
            try {
                ZipInputStream(bytes.inputStream()).use { zip ->
                    var e = zip.nextEntry
                    while (e != null) {
                        val n = e.name
                        if (!e.isDirectory) {
                            val ext = n.substringAfterLast('.', "").lowercase()
                            when {
                                n.endsWith(".xml") || n.endsWith(".rels") ->
                                    entries[n] = zip.readBytes().toString(Charsets.UTF_8)
                                ext in MEDIA_EXTS -> media[n] = zip.readBytes()
                            }
                        }
                        e = zip.nextEntry
                    }
                }
            } catch (_: Exception) {}
            return OoxmlPackage(entries, media)
        }

        /**
         * Normalizes a relationship [target] (possibly relative with ../ or ./ or absolute /...)
         * against the directory of [partPath] into an absolute package path.
         */
        fun normalize(partPath: String, target: String): String {
            if (target.startsWith("/")) return target.removePrefix("/")
            val baseDir = partPath.substringBeforeLast('/', "")
            val stack = ArrayDeque<String>()
            if (baseDir.isNotEmpty()) baseDir.split('/').forEach { stack.addLast(it) }
            for (seg in target.split('/')) {
                when (seg) {
                    "", "." -> {}
                    ".." -> if (stack.isNotEmpty()) stack.removeLast()
                    else -> stack.addLast(seg)
                }
            }
            return stack.joinToString("/")
        }
    }
}
