package com.vayunmathur.logviewer.domain

import java.util.UUID

/**
 * One log or error report, ready to display, copy, save or share.
 *
 * Pure data: everything that needed a `Context` - reading logcat, resolving an app label, reading a
 * tombstone - has already happened by the time one of these exists. That is what lets the same
 * formatting be reused by the screen, the clipboard and the file writer without any of them
 * agreeing on it separately.
 *
 * Held as lines rather than one string: a full logcat is tens of megabytes, and the list, the
 * clipboard and the file writer all want it line by line. Keeping one copy and viewing it in each
 * shape is what stops a large log from sitting in the heap several times over.
 */
class LogDocument(
    val kind: LogKind,
    /** The app the log is about - the one that crashed, or the one logcat is filtered on. */
    val sourcePackage: String?,
    val title: String,
    headerLines: List<String>,
    val bodyLines: List<String>,
) {
    constructor(kind: LogKind, sourcePackage: String?, title: String, header: String, body: String) :
        this(kind, sourcePackage, title, splitLines(header), splitLines(body))

    /** Empty when there is no header at all, so the screen does not render a blank separator. */
    val headerLines: List<String> =
        if (headerLines.all(String::isBlank)) emptyList() else headerLines

    /** The rows the list shows - header, blank, body - as a view, not a copy. */
    val lines: List<String> = object : AbstractList<String>() {
        private val bodyStart =
            if (this@LogDocument.headerLines.isEmpty()) 0 else this@LogDocument.headerLines.size + 1

        override val size: Int get() = bodyStart + bodyLines.size

        override fun get(index: Int): String {
            val header = this@LogDocument.headerLines
            return when {
                index < header.size -> header[index]
                index < bodyStart -> ""
                else -> bodyLines[index - bodyStart]
            }
        }
    }

    /**
     * The clipboard text, and whether it had to be shortened.
     *
     * Fenced in triple backticks because the destination is an issue tracker. The tail is kept
     * rather than the head: the end of a log is what explains the crash. The 200 kB budget is the
     * binder transaction limit that `ClipboardManager.setPrimaryClip` has to fit inside - going
     * over it is a `TransactionTooLargeException`, not a truncated paste.
     */
    fun clipText(): ClipText {
        var sumSize = headerLines.sumOf { utf8Length(it) + 1 }
        var sumChars = 0
        var bodyStartIndex = 0

        for (i in bodyLines.indices.reversed()) {
            val line = bodyLines[i]
            sumChars += line.length + 1
            sumSize += utf8Length(line) + 1
            if (sumSize > MAX_CLIP_BYTES) {
                bodyStartIndex = i + 1
                break
            }
        }

        val sb = StringBuilder(sumChars + 1000)
        sb.append("```\n")

        for (line in headerLines) {
            sb.append(line)
            sb.append('\n')
        }
        if (headerLines.size > 1) sb.append('\n')

        if (bodyStartIndex != 0) sb.append("[[TRUNCATED]]\n")

        for (i in bodyStartIndex until bodyLines.size) {
            sb.append(kind.copyLine(bodyLines[i]))
            sb.append('\n')
        }

        sb.append("```\n")

        return ClipText(sb.toString(), truncated = bodyStartIndex != 0)
    }

    /** The whole thing, unfenced and untruncated: what Save writes and what Share hands over. */
    fun writeSnapshot(out: Appendable) {
        for (line in headerLines) {
            out.append(line)
            out.append('\n')
        }
        if (headerLines.size > 1) out.append('\n')

        for (line in bodyLines) {
            out.append(line)
            out.append('\n')
        }
    }

    data class ClipText(val text: String, val truncated: Boolean)

    companion object {
        private const val MAX_CLIP_BYTES = 200_000

        /**
         * Logs are plain text, but "text/plain" makes share targets do unwanted things to them -
         * link detection, and a preview in the share sheet that is no help for a stack trace.
         */
        const val MIME_TYPE = "application/octet-stream"

        /**
         * A name for the saved or shared file: the title, then twelve hex characters so two saves
         * of the same log do not collide.
         */
        fun snapshotFileName(title: String): String =
            trimToSize(title, 200) + ' ' + UUID.randomUUID().toString().substring(24) + ".txt"

        /**
         * Trailing empty strings are dropped, so a body ending in a newline does not gain a blank
         * last row (and so "scroll to the last line" lands on the last line).
         */
        internal fun splitLines(s: String): List<String> {
            val parts = s.split("\n")
            var end = parts.size
            while (end > 0 && parts[end - 1].isEmpty()) end--
            return parts.subList(0, end)
        }

        /** Counted rather than encoded: encoding every line just to measure it doubles the work. */
        private fun utf8Length(s: String): Int {
            var bytes = 0
            var i = 0
            while (i < s.length) {
                val c = s[i]
                bytes += when {
                    c.code < 0x80 -> 1
                    c.code < 0x800 -> 2
                    c.isHighSurrogate() && i + 1 < s.length && s[i + 1].isLowSurrogate() -> {
                        i++
                        4
                    }
                    else -> 3
                }
                i++
            }
            return bytes
        }

        /** [android.text.TextUtils.trimToSize], reimplemented so this file stays Android-free. */
        private fun trimToSize(s: String, maxLength: Int): String {
            if (s.length <= maxLength) return s
            val end = if (Character.isHighSurrogate(s[maxLength - 1])) maxLength - 1 else maxLength
            return s.substring(0, end)
        }
    }
}
