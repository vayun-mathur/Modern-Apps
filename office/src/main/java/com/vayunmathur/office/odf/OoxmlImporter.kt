package com.vayunmathur.office.odf

import com.vayunmathur.library.ui.odf.*

/**
 * Entry point for importing Microsoft OOXML packages (.docx / .xlsx / .pptx). Reads the package
 * once (retaining media bytes) and delegates to the per-format importers ([OoxmlDocx], [OoxmlXlsx],
 * [OoxmlPptx]). Best-effort: maps a wide range of OOXML features onto the ODF model, degrading
 * gracefully where the model can't represent something.
 */
object OoxmlImporter {

    /** Imports OOXML [bytes]; returns null if the package isn't a recognized docx/xlsx/pptx. */
    fun import(bytes: ByteArray, fileName: String): OdfDocument? {
        val pkg = OoxmlPackage.read(bytes)
        val entries = pkg.entries
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return when {
            ext == "docx" || entries.containsKey("word/document.xml") -> OoxmlDocx.import(pkg, fileName)
            ext == "xlsx" || entries.containsKey("xl/workbook.xml") -> OoxmlXlsx.import(pkg, fileName)
            ext == "pptx" || entries.keys.any { it.startsWith("ppt/slides/slide") } -> OoxmlPptx.import(pkg, fileName)
            else -> null
        }
    }

    /** True if [bytes] is a ZIP whose entries look like an OOXML package. */
    fun looksLikeOoxml(bytes: ByteArray): Boolean {
        if (bytes.size < 4 || bytes[0] != 'P'.code.toByte() || bytes[1] != 'K'.code.toByte()) return false
        val names = OoxmlPackage.read(bytes).entries.keys
        return names.any { it.startsWith("word/") || it.startsWith("xl/") || it.startsWith("ppt/") }
    }
}
