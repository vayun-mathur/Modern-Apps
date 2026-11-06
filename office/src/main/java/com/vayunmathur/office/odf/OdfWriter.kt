package com.vayunmathur.office.odf

import android.content.Context
import android.net.Uri
import com.vayunmathur.library.ui.odf.OdfDocument
import com.vayunmathur.library.ui.odf.OdfPageSetup
import com.vayunmathur.library.ui.odf.OdfSerializer
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

object OdfWriter {

    /** Copies one zip entry verbatim from [zipIn] to [zipOut]. */
    private fun copyEntry(zipIn: ZipInputStream, zipOut: ZipOutputStream, name: String) {
        zipOut.putNextEntry(ZipEntry(name))
        zipIn.copyTo(zipOut)
        zipOut.closeEntry()
    }

    fun save(context: Context, sourceUri: Uri?, document: OdfDocument, targetUri: Uri) {
        val result = OdfSerializer.serializePackaged(document)
        val contentXml = result.contentXml
        val metaXml = OdfSerializer.serializeMeta(document.metadata)
        val settingsXml = OdfSerializer.serializeSettings(document)
        val docImages = imagesOf(document)
        val written = mutableSetOf<String>()
        // Media types for generated package parts (embedded chart objects, inline images).
        val extraManifest = LinkedHashMap<String, String>(result.manifest)

        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zipOut ->
            val save = SaveAcc(contentXml, metaXml, settingsXml, docImages, written, extraManifest)
            copySourceEntries(context, sourceUri, document, zipOut, save)
            writeMissingEntries(document, zipOut, save, result)
        }
        // AFTER the ZipOutputStream is closed (close writes the END header).
        val bytes = buffer.toByteArray()
        context.contentResolver.openOutputStream(targetUri)?.use { it.write(bytes) }
    }

    /** Save accumulation state. */
    private class SaveAcc(
        val contentXml: String,
        val metaXml: String,
        val settingsXml: String?,
        val docImages: Map<String, ByteArray>,
        val written: MutableSet<String>,
        val extraManifest: LinkedHashMap<String, String>,
    )

    /** Copy source package entries (or write mimetype for new docs). */
    private fun copySourceEntries(
        context: Context,
        sourceUri: Uri?,
        document: OdfDocument,
        zipOut: ZipOutputStream,
        save: SaveAcc,
    ) {
        val source = if (sourceUri != null) context.contentResolver.openInputStream(sourceUri) else null
        if (source != null) {
            source.use { input ->
                ZipInputStream(input).use { zipIn ->
                    var entry = zipIn.nextEntry
                    while (entry != null) {
                        copySourceEntry(zipIn, zipOut, entry, document, save)
                        entry = zipIn.nextEntry
                    }
                }
            }
        } else {
            // Brand-new document with no source package: write the special mimetype entry first,
            // uncompressed, as required by ODF. (Priority 1: enable saving new documents)
            writeStored(zipOut, "mimetype", documentMime(document).toByteArray(Charsets.US_ASCII))
            save.written.add("mimetype")
        }
    }

    /** Copy one source entry (regenerating managed parts). */
    private fun copySourceEntry(
        zipIn: ZipInputStream,
        zipOut: ZipOutputStream,
        entry: java.util.zip.ZipEntry,
        document: OdfDocument,
        save: SaveAcc,
    ) {
        val name = entry.name
        when {
            name == "content.xml" -> {
                writeEntry(
                    zipOut,
                    "content.xml",
                    save.contentXml.toByteArray(Charsets.UTF_8)); save.written.add(name)
            }
            name == "meta.xml" -> {
                writeEntry(
                    zipOut,
                    "meta.xml",
                    save.metaXml.toByteArray(Charsets.UTF_8)); save.written.add(name)
            }
            // When we have generated freeze-pane settings, replace; otherwise copy source as-is.
            // (C2)
            name == "settings.xml" -> if (save.settingsXml != null) {
                writeEntry(
                    zipOut,
                    "settings.xml",
                    save.settingsXml.toByteArray(Charsets.UTF_8)); save.written.add(name)
            } else {
                copyEntry(zipIn, zipOut, name)
                save.written.add(name)
            }
            // Regenerated below; never copy the old manifest.
            name == "META-INF/manifest.xml" -> {}
            // Patch page geometry into the copied styles.xml so Page Setup edits persist.
            // (Priority 7)
            name == "styles.xml" -> {
                val ps = (document as? OdfDocument.TextDocument)?.pageSetup
                val original = zipIn.readBytes().toString(Charsets.UTF_8)
                val out = if (ps != null) patchStylesXml(original, ps) else original
                writeEntry(zipOut, "styles.xml", out.toByteArray(Charsets.UTF_8)); save.written.add(name)
            }
            !entry.isDirectory -> {
                copyEntry(zipIn, zipOut, name)
                save.written.add(name)
            }
        }
    }

    /** Write entries missing from the source + images + manifest. */
    private fun writeMissingEntries(
        document: OdfDocument,
        zipOut: ZipOutputStream,
        save: SaveAcc,
        result: OdfSerializer.SerResult,
    ) {
        writeCoreEntries(document, zipOut, save)
        writeImageEntries(zipOut, save, result)
        writeObjectEntries(zipOut, save, result)
        // Regenerate META-INF/manifest.xml listing everything.
        val manifestXml = buildManifest(document, save.written, save.extraManifest)
        writeEntry(zipOut, "META-INF/manifest.xml", manifestXml.toByteArray(Charsets.UTF_8))
    }

    /** Core XML entries missing from the source. */
    private fun writeCoreEntries(document: OdfDocument, zipOut: ZipOutputStream, save: SaveAcc) {
        writeTextEntry(zipOut, save, "content.xml", save.contentXml)
        writeTextEntry(zipOut, save, "meta.xml", save.metaXml)
        // Minimal styles.xml when the package didn't already carry one. (Priority 1)
        if ("styles.xml" !in save.written) {
            writeTextEntry(zipOut, save, "styles.xml", OdfSerializer.serializeStyles(document))
        }
        // Freeze-pane settings for a source that had no settings.xml. (C2)
        if (save.settingsXml != null) {
            writeTextEntry(zipOut, save, "settings.xml", save.settingsXml)
        }
    }

    /** Writes a UTF-8 text entry unless already written. */
    private fun writeTextEntry(zipOut: ZipOutputStream, save: SaveAcc, name: String, text: String) {
        if (name in save.written) return
        writeEntry(zipOut, name, text.toByteArray(Charsets.UTF_8))
        save.written.add(name)
    }

    /** Document + serialized inline images. */
    private fun writeImageEntries(zipOut: ZipOutputStream, save: SaveAcc, result: OdfSerializer.SerResult) {
        // Document images (inserted via the editor) not already in the package. (A6)
        for ((path, bytes) in save.docImages) {
            if (isSkippableImage(path, bytes, save.written)) continue
            writeEntry(zipOut, path, bytes); save.written.add(path)
            save.extraManifest[path] = mediaTypeFor(path)
        }
        // Inline images promoted to the package during serialization. (A6)
        for ((path, bytes) in result.images) {
            if (path in save.written || bytes.isEmpty()) continue
            writeEntry(zipOut, path, bytes); save.written.add(path)
        }
    }

    /** Embedded chart objects. (A8) */
    private fun writeObjectEntries(zipOut: ZipOutputStream, save: SaveAcc, result: OdfSerializer.SerResult) {
        for ((path, xml) in result.objects) {
            if (path in save.written) continue
            writeEntry(zipOut, path, xml.toByteArray(Charsets.UTF_8))
            save.written.add(path)
        }
    }

    private fun writeEntry(zipOut: ZipOutputStream, name: String, bytes: ByteArray) {
        zipOut.putNextEntry(ZipEntry(name))
        zipOut.write(bytes)
        zipOut.closeEntry()
    }

    /** Writes an uncompressed (STORED) zip entry — required for the ODF `mimetype` member. */
    private fun writeStored(zipOut: ZipOutputStream, name: String, bytes: ByteArray) {
        val entry = ZipEntry(name)
        entry.method = ZipEntry.STORED
        entry.size = bytes.size.toLong()
        entry.compressedSize = bytes.size.toLong()
        val crc = java.util.zip.CRC32(); crc.update(bytes); entry.crc = crc.value
        zipOut.putNextEntry(entry)
        zipOut.write(bytes)
        zipOut.closeEntry()
    }

    /** Patches page geometry attributes into the first style:page-layout-properties of styles.xml. (Priority 7) */
    private const val PX_PER_CM = 37.795f

    private fun patchStylesXml(xml: String, ps: OdfPageSetup): String {
        val m = Regex("<style:page-layout-properties\\b[^>]*?(/?)>").find(xml) ?: return xml
        var tag = m.value
        fun cm(px: Float): String = String.format(java.util.Locale.US, "%.4fcm", px / PX_PER_CM)
        fun setAttr(name: String, value: String) {
            val attr = "$name=\"$value\""
            val r = Regex(Regex.escape(name) + "=\"[^\"]*\"")
            tag = if (r.containsMatchIn(tag)) r.replace(tag) { attr }
            else if (tag.endsWith("/>")) tag.dropLast(2) + " $attr/>"
            else tag.dropLast(1) + " $attr>"
        }
        setAttr("fo:page-width", cm(ps.widthPx))
        setAttr("fo:page-height", cm(ps.heightPx))
        setAttr("fo:margin-left", cm(ps.marginLeftPx))
        setAttr("fo:margin-right", cm(ps.marginRightPx))
        setAttr("fo:margin-top", cm(ps.marginTopPx))
        setAttr("fo:margin-bottom", cm(ps.marginBottomPx))
        setAttr("style:print-orientation", if (ps.isLandscape) "landscape" else "portrait")
        return xml.replaceRange(m.range, tag)
    }

    /** Rebuilds META-INF/manifest.xml from the final package contents so new images/objects are declared. (A6/A8) */
    private fun manifestEntry(path: String, type: String): String =
        """<manifest:file-entry manifest:full-path="$path" manifest:media-type="$type"/>"""

    private fun buildManifest(document: OdfDocument, written: Set<String>, extra: Map<String, String>): String {
        val mime = documentMime(document)
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.append("""<manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0"" +
            "manifest:version="1.3">""")
        sb.append("""<manifest:file-entry manifest:full-path="/"" +
            "manifest:version="1.3" manifest:media-type="$mime"/>""")
        // Embedded-object directory entries (declared first so readers register the sub-documents).
        for ((path, type) in extra) {
            if (path.endsWith("/")) {
                sb.append(manifestEntry(path, type))
            }
        }
        val seen = mutableSetOf("/")
        fun add(path: String, type: String) {
            if (path in seen) return
            seen.add(path)
            sb.append(manifestEntry(path, type))
        }
        for ((path, type) in extra) if (!path.endsWith("/")) add(path, type)
        for (path in written) {
            if (path == "mimetype" || path == "META-INF/manifest.xml") continue
            add(path, mediaTypeFor(path))
        }
        sb.append("</manifest:manifest>")
        return sb.toString()
    }

    private fun documentMime(document: OdfDocument): String = when (document) {
        is OdfDocument.TextDocument -> "application/vnd.oasis.opendocument.text"
        is OdfDocument.Spreadsheet -> "application/vnd.oasis.opendocument.spreadsheet"
        is OdfDocument.Presentation -> "application/vnd.oasis.opendocument.presentation"
        is OdfDocument.Drawing -> "application/vnd.oasis.opendocument.graphics"
    }

    /** Skip inline placeholders, blanks, already-written, or empty image entries. */
    private fun isSkippableImage(path: String, bytes: ByteArray, written: Set<String>): Boolean =
        path == "inline" || path.isBlank() || path in written || bytes.isEmpty()

    private fun mediaTypeFor(path: String): String {
        if (isOdfXml(path) || path.endsWith(".xml")) return "text/xml"
        return imageMediaType(path) ?: "application/octet-stream"
    }

    /** True for ODF package XML parts. */
    private fun isOdfXml(path: String): Boolean {
        if (isTopLevelXml(path)) {
            return true
        }
        return path.endsWith("/content.xml") || path.endsWith("/styles.xml")
    }

    /** True for top-level ODF XML parts. */
    private fun isTopLevelXml(path: String): Boolean {
        return path == "content.xml" || path == "styles.xml" ||
            path == "meta.xml" || path == "settings.xml"
    }

    /** Image media type by extension, or null. */
    private fun imageMediaType(path: String): String? = when {
        path.endsWith(".rdf") -> "application/rdf+xml"
        path.endsWith(".png") -> "image/png"
        path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
        path.endsWith(".gif") -> "image/gif"
        path.endsWith(".bmp") -> "image/bmp"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".webp") -> "image/webp"
        else -> null
    }

    private fun imagesOf(document: OdfDocument): Map<String, ByteArray> = when (document) {
        is OdfDocument.TextDocument -> document.images
        is OdfDocument.Spreadsheet -> document.images
        is OdfDocument.Presentation -> document.images
        is OdfDocument.Drawing -> document.images
    }

    fun saveAs(context: Context, sourceUri: Uri?, document: OdfDocument, targetUri: Uri) {
        save(context, sourceUri, document, targetUri)
    }
}
