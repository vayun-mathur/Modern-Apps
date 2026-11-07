package com.vayunmathur.everysync.domain.format

import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import com.vayunmathur.everysync.data.RemoteContact
import com.vayunmathur.everysync.data.TypedValue

/**
 * Minimal vCard 3.0/4.0 reader/writer covering the fields EverySync maps into
 * ContactsContract. Self-contained so `:everysync` needs no cross-module
 * dependency on the contacts app's VcfUtils.
 */
object VCard {

    fun parse(text: String, fallbackUid: String? = null): RemoteContact {
        val builder = VCardBuilder(fallbackUid ?: "")
        for (line in unfold(text)) {
            val prop = splitProperty(line) ?: continue
            builder.apply(prop)
        }
        return builder.build()
    }

    private class VCardBuilder(private var uid: String) {
        private var fn = ""
        private var prefix = ""
        private var first = ""
        private var middle = ""
        private var last = ""
        private var suffix = ""
        private var org = ""
        private var note = ""
        private var bday: String? = null
        private val phones = mutableListOf<TypedValue>()
        private val emails = mutableListOf<TypedValue>()
        private val addresses = mutableListOf<TypedValue>()

        fun apply(prop: Prop) {
            when (prop.name.uppercase()) {
                "UID" -> uid = prop.value.ifBlank { uid }
                "FN" -> fn = unescape(prop.value)
                "N" -> readName(prop.value)
                "ORG" -> org = unescape(prop.value.split(";").firstOrNull() ?: prop.value)
                "NOTE" -> note = unescape(prop.value)
                "BDAY" -> bday = normalizeDate(prop.value)
                "TEL" -> phones += TypedValue(unescape(prop.value), phoneType(prop.params))
                "EMAIL" -> emails += TypedValue(unescape(prop.value), emailType(prop.params))
                "ADR" -> addresses += TypedValue(formatAddress(prop.value), addressType(prop.params))
            }
        }

        private fun readName(value: String) {
            val parts = value.split(";")
            last = unescape(parts.getOrElse(0) { "" })
            first = unescape(parts.getOrElse(1) { "" })
            middle = unescape(parts.getOrElse(2) { "" })
            prefix = unescape(parts.getOrElse(3) { "" })
            suffix = unescape(parts.getOrElse(4) { "" })
        }

        fun build(): RemoteContact {
            val display = fn.ifBlank {
                listOf(prefix, first, middle, last, suffix)
                    .filter { it.isNotBlank() }
                    .joinToString(" ")
            }
            return RemoteContact(
                uid = uid,
                displayName = display,
                prefix = prefix,
                firstName = first,
                middleName = middle,
                lastName = last,
                suffix = suffix,
                organization = org,
                note = note,
                phones = phones,
                emails = emails,
                addresses = addresses,
                birthday = bday,
            )
        }
    }

    fun serialize(c: RemoteContact): String = buildString {
        append("BEGIN:VCARD\r\n")
        append("VERSION:3.0\r\n")
        append("UID:${c.uid}\r\n")
        append("N:${esc(c.lastName)};${esc(c.firstName)};${esc(c.middleName)};${esc(c.prefix)};${esc(c.suffix)}\r\n")
        append("FN:${esc(c.displayName)}\r\n")
        if (c.organization.isNotBlank()) append("ORG:${esc(c.organization)}\r\n")
        for (p in c.phones) append("TEL;TYPE=${phoneTypeName(p.type)}:${esc(p.value)}\r\n")
        for (e in c.emails) append("EMAIL;TYPE=${emailTypeName(e.type)}:${esc(e.value)}\r\n")
        for (a in c.addresses) append("ADR;TYPE=${addressTypeName(a.type)}:;;${esc(a.value)};;;;\r\n")
        c.birthday?.let { append("BDAY:$it\r\n") }
        if (c.note.isNotBlank()) append("NOTE:${esc(c.note)}\r\n")
        append("END:VCARD\r\n")
    }

    // --- helpers ---

    /** RFC 6350 line unfolding: continuation lines start with space/tab. */
    private fun unfold(text: String): List<String> {
        val out = mutableListOf<String>()
        for (raw in text.replace("\r\n", "\n").split("\n")) {
            if ((raw.startsWith(" ") || raw.startsWith("\t")) && out.isNotEmpty()) {
                out[out.lastIndex] = out.last() + raw.trimStart()
            } else {
                out.add(raw)
            }
        }
        return out
    }

    private data class Prop(val name: String, val params: String, val value: String)

    private fun splitProperty(line: String): Prop? {
        val colon = line.indexOf(':')
        if (colon <= 0) return null
        val left = line.take(colon)
        val value = line.substring(colon + 1)
        val semi = left.indexOf(';')
        return if (semi > 0) Prop(left.take(semi), left.substring(semi + 1), value)
        else Prop(left, "", value)
    }

    private fun paramTypes(params: String): List<String> =
        params.split(";")
            .flatMap { it.removePrefix("TYPE=").removePrefix("type=").split(",") }
            .map { it.trim().uppercase() }
            .filter { it.isNotBlank() }

    private fun phoneType(params: String): Int = when {
        paramTypes(params).any { it == "CELL" || it == "MOBILE" } -> Phone.TYPE_MOBILE
        paramTypes(params).contains("WORK") -> Phone.TYPE_WORK
        paramTypes(params).contains("HOME") -> Phone.TYPE_HOME
        paramTypes(params).contains("FAX") -> Phone.TYPE_FAX_HOME
        else -> Phone.TYPE_OTHER
    }

    private fun phoneTypeName(type: Int): String = when (type) {
        Phone.TYPE_MOBILE -> "CELL"
        Phone.TYPE_WORK -> "WORK"
        Phone.TYPE_HOME -> "HOME"
        Phone.TYPE_FAX_HOME, Phone.TYPE_FAX_WORK -> "FAX"
        else -> "VOICE"
    }

    private fun emailType(params: String): Int = when {
        paramTypes(params).contains("WORK") -> Email.TYPE_WORK
        paramTypes(params).contains("HOME") -> Email.TYPE_HOME
        else -> Email.TYPE_OTHER
    }

    private fun emailTypeName(type: Int): String = when (type) {
        Email.TYPE_WORK -> "WORK"
        Email.TYPE_HOME -> "HOME"
        else -> "INTERNET"
    }

    private fun addressType(params: String): Int = when {
        paramTypes(params).contains("WORK") -> StructuredPostal.TYPE_WORK
        paramTypes(params).contains("HOME") -> StructuredPostal.TYPE_HOME
        else -> StructuredPostal.TYPE_OTHER
    }

    private fun addressTypeName(type: Int): String = when (type) {
        StructuredPostal.TYPE_WORK -> "WORK"
        StructuredPostal.TYPE_HOME -> "HOME"
        else -> "OTHER"
    }

    /** ADR is ;;street;city;region;code;country — flatten to a display string. */
    private fun formatAddress(value: String): String =
        value.split(";").map { unescape(it) }.filter { it.isNotBlank() }.joinToString(", ")

    private fun normalizeDate(value: String): String? {
        val v = value.trim()
        return when {
            v.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) -> v
            v.matches(Regex("\\d{8}")) -> "${v.take(4)}-${v.substring(4, 6)}-${v.substring(6, 8)}"
            else -> v.takeIf { it.isNotBlank() }
        }
    }

    private fun esc(s: String): String =
        s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")

    /**
     * Single-pass unescape. Sequential String.replace calls corrupt text
     * that itself contains a backslash: an escaped backslash followed by
     * 'n' ("\\n" on the wire, a literal backslash + n in the value) would
     * first become backslash + newline.
     */
    private fun unescape(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n', 'N' -> out.append('\n')
                    ',', ';', '\\' -> out.append(s[i + 1])
                    else -> out.append(s[i + 1])
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }
}
