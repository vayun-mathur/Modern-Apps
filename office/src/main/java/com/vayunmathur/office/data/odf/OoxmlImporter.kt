package com.vayunmathur.office.data.odf

import com.vayunmathur.library.log.Log
import com.vayunmathur.library.ui.odf.OdfDocument

/**
 * Entry point for importing Microsoft OOXML packages (.docx / .xlsx / .pptx). Reads the package
 * once (retaining media bytes) and delegates to the per-format importers ([OoxmlDocx], [OoxmlXlsx],
 * [OoxmlPptx]). Best-effort: maps a wide range of OOXML features onto the ODF model, degrading
 * gracefully where the model can't represent something.
 */
object OoxmlImporter {

    private const val ZIP_SIG_SIZE = 4
    private const val ZIP_SIG_0 = 0
    private const val ZIP_SIG_1 = 1
    private const val OLE_SIG_SIZE = 8
    private const val OLE_SIG_0 = 0
    private const val OLE_SIG_1 = 1
    private const val OLE_SIG_2 = 2
    private const val OLE_SIG_3 = 3
    private const val OLE_SIG_B0 = 0xD0.toByte()
    private const val OLE_SIG_B1 = 0xCF.toByte()
    private const val OLE_SIG_B2 = 0x11.toByte()
    private const val OLE_SIG_B3 = 0xE0.toByte()

    /** Imports OOXML [bytes]; returns null if the package isn't a recognized docx/xlsx/pptx. */
    fun import(bytes: ByteArray, fileName: String): OdfDocument? {
        require(!isEncryptedOfficeFile(bytes)) { "This Office file is password-protected." +
            "Remove the password and try again." }
        val pkg = OoxmlPackage.read(bytes)
        require(!pkg.entries.containsKey("EncryptionInfo")) {
            "This Office file is password-protected. Remove the password and try again."
        }
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return runCatching {
            resolveDocKind(ext, pkg, fileName)
        }.getOrElse {
            // Best-effort: never crash the app on a malformed but recognized package.
            // Dev-gated so JVM unit tests (Android stubs) stay silent-safe; on a dev
            // device this surfaces the swallowed cause for the next malformed file.
            Log.dev(TAG, "best-effort fallback for $fileName", it)
            fallbackDoc(ext, pkg, fileName)
        }
    }

    private fun resolveDocKind(ext: String, pkg: OoxmlPackage, fileName: String): OdfDocument? {
        val entries = pkg.entries
        return when {
            ext in DOCX_EXTS || entries.containsKey("word/document.xml") -> OoxmlDocx.import(pkg, fileName)
            ext in XLSX_EXTS || entries.containsKey("xl/workbook.xml") -> OoxmlXlsx.import(pkg, fileName)
            ext in PPTX_EXTS || entries.keys.any { it.startsWith("ppt/slides/slide") } -> OoxmlPptx.import(
                pkg,
                fileName)
            else -> null
        }
    }

    private fun fallbackDoc(ext: String, pkg: OoxmlPackage, fileName: String): OdfDocument? {
        val entries = pkg.entries
        return when {
            ext in DOCX_EXTS || entries.containsKey("word/document.xml") -> OdfDocument.TextDocument(
                fileName,
                emptyList())
            ext in XLSX_EXTS || entries.containsKey("xl/workbook.xml") -> OdfDocument.Spreadsheet(
                fileName,
                listOf())
            ext in PPTX_EXTS || entries.keys.any { it.startsWith("ppt/slides/slide") } -> OdfDocument.Presentation(
                fileName,
                listOf())
            else -> null
        }
    }

    /** True if [bytes] is a ZIP whose entries look like an OOXML package. */
    fun looksLikeOoxml(bytes: ByteArray): Boolean {
        if (!hasZipSignature(bytes)) return false
        val names = OoxmlPackage.read(bytes).entries.keys
        return names.any { it.startsWith("word/") || it.startsWith("xl/") || it.startsWith("ppt/") }
    }

    /** True when [bytes] starts with the ZIP local-file signature. */
    private fun hasZipSignature(bytes: ByteArray): Boolean =
        bytes.size >= ZIP_SIG_SIZE &&
            bytes[ZIP_SIG_0] == 'P'.code.toByte() &&
            bytes[ZIP_SIG_1] == 'K'.code.toByte()

    /** True if [bytes] is an OLE/CFB compound file — the container used by password-protected Office files. */
    fun isEncryptedOfficeFile(bytes: ByteArray): Boolean =
        bytes.size >= OLE_SIG_SIZE && bytes[OLE_SIG_0] == OLE_SIG_B0 && bytes[OLE_SIG_1] == OLE_SIG_B1 &&
            bytes[OLE_SIG_2] == OLE_SIG_B2 && bytes[OLE_SIG_3] == OLE_SIG_B3

    private val DOCX_EXTS = setOf("docx", "docm", "dotx", "dotm")
    private val XLSX_EXTS = setOf("xlsx", "xlsm", "xltx", "xltm")
    private val PPTX_EXTS = setOf("pptx", "pptm", "potx", "potm", "ppsx", "ppsm")

    private const val TAG = "OoxmlImporter"
}
