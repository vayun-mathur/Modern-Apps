package com.vayunmathur.email.platform

import android.content.Context
import android.net.Uri
import com.vayunmathur.email.data.EmailMessage
import com.vayunmathur.email.network.imap.MimeParser
import java.io.File

/** An attachment extracted from a parsed .eml file. */
data class EmlAttachment(
    val fileName: String,
    val mimeType: String,
    val bytes: ByteArray,
)

/** A parsed .eml file as a message plus its attachments and inline images. */
data class ParsedEml(
    val message: EmailMessage,
    val emlAttachments: List<EmlAttachment>,
    val inlineCidMap: Map<String, File> = emptyMap(),
)

/** Parses .eml files into messages and extracted attachments. */
object EmlUtils {

    private const val EML_EXTENSION_LEN = 4
    private const val MAX_BASENAME_LEN = 60

    /**
     * Parse EML file via raw [MimeParser] (no Jakarta dependency).
     * Returns [ParsedEml] expected by EmlViewerScreen.
     */
    fun parseEml(context: Context, uri: Uri): ParsedEml {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Cannot open InputStream for $uri")
        val syntheticId = uri.toString().hashCode().toLong()
        return MimeParser.parseEmlToParsedMessage(bytes, syntheticId, context)
    }

    fun sanitizeFileName(input: String, fallback: String = "email"): String {
        var base = input.ifBlank { fallback }.trim()
        if (base.endsWith(".eml", ignoreCase = true)) base = base.dropLast(EML_EXTENSION_LEN)
        base = base.take(MAX_BASENAME_LEN).trim()
        // Dots are not kept: a trailing ".FINAL" would otherwise read as a
        // doubled extension ("Report.FINAL.eml"). The single ".eml" suffix is
        // re-added below, so no information the filesystem needs is lost.
        base = base.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        base = base.replace(Regex("_+"), "_")
        base = base.trim('_', '.', ' ')
        if (base.isBlank()) base = fallback
        return "$base.eml"
    }
}
