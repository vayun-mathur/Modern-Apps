package com.vayunmathur.contacts.util

import android.provider.ContactsContract
import com.vayunmathur.contacts.data.Address
import com.vayunmathur.contacts.data.CommPlatform
import com.vayunmathur.contacts.data.CommPref
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.Email
import com.vayunmathur.contacts.data.Event
import com.vayunmathur.contacts.data.Gender
import com.vayunmathur.contacts.data.GroupMembership
import com.vayunmathur.contacts.data.Name
import com.vayunmathur.contacts.data.Nickname
import com.vayunmathur.contacts.data.Note
import com.vayunmathur.contacts.data.Organization
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.data.Photo
import com.vayunmathur.library.log.Log
import kotlinx.datetime.LocalDate
import java.io.ByteArrayOutputStream
import java.io.InputStream

private const val DATE_PREFIX_LENGTH = 4
private const val DATE_MONTH_END = 6
private const val DATE_DAY_END = 8
private const val NO_YEAR_SENTINEL = "1604"
private const val QP_HEX_PAIR_LENGTH = 2
private const val QP_ESCAPE_LENGTH = 3

private val VCF_BASIC_DATE_REGEX = Regex("^\\d{8}")

/**
 * Streaming vCard parser used by [VcfUtils.parseContacts]. Kept in its own
 * file so VcfUtils stays under the function cap (see TooManyFunctions).
 */
internal fun parseVcfContacts(
    inputStream: InputStream,
    resolveGroups: (List<String>) -> List<GroupMembership>,
): List<Contact> {
    val parser = VcfParser(resolveGroups)
    // Streaming unfold: process line-by-line, keeping only a small pending
    // buffer for folded (continuation) lines instead of loading the file.
    inputStream.bufferedReader(Charsets.UTF_8).use { br ->
        var pending: String? = null
        var line: String?
        while (br.readLine().also { line = it } != null) {
            val ln = line!!
            if (ln.startsWith(" ") || ln.startsWith("\t")) {
                pending = (pending ?: "") + ln.trimStart()
            } else {
                if (pending != null) parser.handleUnfolded(pending)
                pending = ln
            }
        }
        if (pending != null) parser.handleUnfolded(pending)
    }

    return parser.contacts
}

private class VcfParser(
    private val resolveGroups: (List<String>) -> List<GroupMembership>,
) {
    val contacts = mutableListOf<Contact>()
    private var currentContact: ContactBuilder? = null

    fun handleUnfolded(raw: String) {
        val line = raw.trimEnd()
        if (line.isEmpty()) return
        if (handleBoundary(line)) return
        val builder = currentContact ?: return
        handlePropertyLine(builder, line)
    }

    /** Handles BEGIN/END lines. Returns true when the line was a boundary. */
    private fun handleBoundary(line: String): Boolean {
        if (line.startsWith("BEGIN:VCARD", ignoreCase = true)) {
            currentContact = ContactBuilder()
            return true
        }
        if (line.startsWith("END:VCARD", ignoreCase = true)) {
            currentContact?.let { finishContact(it) }
            currentContact = null
            return true
        }
        return false
    }

    private fun finishContact(builder: ContactBuilder) {
        val details = ContactDetails(
            phoneNumbers = builder.phones.toList(),
            emails = builder.emails.toList(),
            addresses = builder.addresses.toList(),
            dates = builder.dates.toList(),
            photos = builder.photos.toList(),
            names = builder.names.toList(),
            orgs = builder.orgs.toList(),
            notes = builder.notes.toList(),
            nicknames = builder.nicknames.toList(),
            groups = builder.groups.toList(),
            genders = builder.genders.toList(),
            commPrefs = builder.commPrefs.toList()
        )
        val newContact = Contact(
            id = 0L,
            null,
            null,
            isFavorite = builder.isFavorite,
            details
        )
        contacts.add(newContact)
    }

    private fun handlePropertyLine(builder: ContactBuilder, line: String) {
        // Parse property line: NAME[;PARAMS]:VALUE
        val colonIndex = line.indexOf(':')
        if (colonIndex == -1) return
        val nameAndParams = line.take(colonIndex)
        val valuePart = line.substring(colonIndex + 1)

        val segments = nameAndParams.split(';')
        val propName = segments.firstOrNull()?.uppercase() ?: return
        val params = parseParams(segments.drop(1))
        val decodedValue = decodeValue(params, valuePart)

        if (!dispatchIdentityField(builder, propName, params, decodedValue)) {
            dispatchMetadataField(builder, propName, decodedValue)
        }
    }

    /** Applies name/phone/address/date identity fields. True when handled. */
    private fun dispatchIdentityField(
        builder: ContactBuilder,
        propName: String,
        params: Map<String, List<String>>,
        decodedValue: String,
    ): Boolean {
        when (propName) {
            "N" -> applyName(builder, decodedValue)
            "FN" -> applyFullName(builder, decodedValue)
            "TEL" -> applyPhone(builder, params, decodedValue)
            "EMAIL" -> applyEmail(builder, params, decodedValue)
            "ADR" -> applyAddress(builder, params, decodedValue)
            "X-EVENT" -> applyEvent(builder, params, decodedValue)
            "ORG" -> applyOrg(builder, decodedValue)
            "BDAY" -> applyBirthday(builder, decodedValue)
            else -> return false
        }
        return true
    }

    /** Applies notes/nicknames/gender/comm-pref/groups/photo/flag metadata fields. */
    private fun dispatchMetadataField(
        builder: ContactBuilder,
        propName: String,
        decodedValue: String,
    ) {
        when (propName) {
            "NOTE" -> applyNote(builder, decodedValue)
            "NICKNAME" -> applyNickname(builder, decodedValue)
            "GENDER" -> applyGender(builder, decodedValue)
            "X-COMM-PREF" -> applyCommPref(builder, decodedValue)
            "CATEGORIES" -> applyCategories(builder, decodedValue)
            "X-STARRED" -> applyStarred(builder, decodedValue)
            "PHOTO" -> applyPhoto(builder, decodedValue)
            "URL" -> {
                // Do not create Note rows from URL lines.
            }
            else -> {}
        }
    }

    private fun applyName(builder: ContactBuilder, decodedValue: String) {
        // Split on unescaped semicolons first: a literal ";" inside a name
        // arrives escaped ("\;") and must not split the components.
        val comps = splitUnescaped(decodedValue, ';').map(::unescapeV)
        val family = comps.getOrNull(0) ?: ""
        val given = comps.getOrNull(1) ?: ""
        val additional = comps.getOrNull(2) ?: ""
        val prefix = comps.getOrNull(3) ?: ""
        val suffix = comps.getOrNull(4) ?: ""
        builder.names.clear()
        builder.names.add(Name(0, prefix, given, additional, family, suffix))
    }

    private fun applyFullName(builder: ContactBuilder, decodedValue: String) {
        val value = unescapeV(decodedValue)
        val existing = builder.names.firstOrNull()
        val isBlankDefault = existing != null &&
            existing.firstName.isBlank() && existing.lastName.isBlank() &&
            existing.middleName.isBlank() && existing.namePrefix.isBlank() &&
            existing.nameSuffix.isBlank()
        if (existing == null || isBlankDefault) {
            val first = value.split(" ").firstOrNull() ?: value
            val last = value.split(" ").drop(1).joinToString(" ")
            builder.names.clear()
            builder.names.add(Name(0, "", first, "", last, ""))
        }
    }

    private fun applyPhone(
        builder: ContactBuilder,
        params: Map<String, List<String>>,
        decodedValue: String,
    ) {
        val value = unescapeV(decodedValue)
        val (ttype, tlabel) = detectPhoneTypeWithLabel(params)
        builder.phones.add(PhoneNumber(0, value, ttype, tlabel))
    }

    private fun applyEmail(
        builder: ContactBuilder,
        params: Map<String, List<String>>,
        decodedValue: String,
    ) {
        val value = unescapeV(decodedValue)
        val (etype, elabel) = detectEmailTypeWithLabel(params)
        builder.emails.add(Email(0, value, etype, elabel))
    }

    private fun applyAddress(
        builder: ContactBuilder,
        params: Map<String, List<String>>,
        decodedValue: String,
    ) {
        // Same escaped-split rule as applyName: commas and semicolons inside
        // a street or city arrive escaped and must not split the components.
        val comps = splitUnescaped(decodedValue, ';').map(::unescapeV)
        val street = comps.getOrNull(2) ?: ""
        val city = comps.getOrNull(3) ?: ""
        val region = comps.getOrNull(4) ?: ""
        val postal = comps.getOrNull(5) ?: ""
        val country = comps.getOrNull(6) ?: ""
        val formatted = listOfNotNull(
            street.ifEmpty { null },
            city.ifEmpty { null },
            region.ifEmpty { null },
            postal.ifEmpty { null },
            country.ifEmpty { null }
        ).joinToString(", ")
        val (atype, alabel) = detectAddressTypeWithLabel(params)
        builder.addresses.add(Address(0, formatted, atype, alabel))
    }

    private fun applyEvent(
        builder: ContactBuilder,
        params: Map<String, List<String>>,
        decodedValue: String,
    ) {
        val date = parseVcfDate(unescapeV(decodedValue)) ?: return
        val (dtype, dlabel) = detectEventTypeWithLabel(params)
        builder.dates.add(Event(0, date, dtype, dlabel))
    }

    private fun applyOrg(builder: ContactBuilder, decodedValue: String) {
        val value = unescapeV(decodedValue)
        builder.orgs.clear()
        builder.orgs.add(Organization(0, value))
    }

    private fun applyBirthday(builder: ContactBuilder, decodedValue: String) {
        // Only the first BDAY per vCard wins.
        val hasBirthday =
            builder.dates.any { it.type == ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY }
        if (hasBirthday) return
        val date = parseVcfDate(unescapeV(decodedValue)) ?: return
        builder.dates.add(
            Event(0, date, ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY)
        )
    }

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    private fun applyPhoto(builder: ContactBuilder, decodedValue: String) {
        // Photo.photo is Base64 by contract (Contact.completePhotoOperation
        // Base64-decodes it while building the provider batch, outside
        // save()'s error handling). Foreign cards also carry PHOTO as a
        // URI (PHOTO;VALUE=URI:https://...) or a data: URI; storing those
        // verbatim made the later decode throw IllegalArgumentException
        // and abort the whole import. Keep only decodable Base64.
        val normalized = decodedValue.filterNot { it.isWhitespace() }
        if (normalized.isEmpty()) return
        val decodable = try {
            kotlin.io.encoding.Base64.decode(normalized)
            true
        } catch (e: IllegalArgumentException) {
            try {
                kotlin.io.encoding.Base64.UrlSafe.decode(normalized)
                true
            } catch (e2: IllegalArgumentException) {
                false
            }
        }
        if (!decodable) {
            Log.status("VcfParser", "Dropping non-Base64 PHOTO value")
            return
        }
        builder.photos.add(Photo(0, normalized))
    }

    private fun applyNote(builder: ContactBuilder, decodedValue: String) {
        val value = unescapeV(decodedValue)
        if (builder.notes.size == 1 && builder.notes[0].content.isBlank()) {
            builder.notes.clear()
        }
        builder.notes.add(Note(0, value))
    }

    private fun applyNickname(builder: ContactBuilder, decodedValue: String) {
        val value = unescapeV(decodedValue)
        // NICKNAME may hold a comma-separated list; split on unescaped commas.
        val parts = splitUnescaped(value, ',').map { it.trim() }.filter { it.isNotBlank() }
        val names = parts.ifEmpty { listOf(value).filter { it.isNotBlank() } }
        for (n in names) {
            if (builder.nicknames.size == 1 && builder.nicknames[0].nickname.isBlank()) {
                builder.nicknames.clear()
            }
            builder.nicknames.add(
                Nickname(0, n, ContactsContract.CommonDataKinds.Nickname.TYPE_DEFAULT)
            )
        }
    }

    private fun applyGender(builder: ContactBuilder, decodedValue: String) {
        // GENDER:letter[;free text] per RFC 6350 §6.2.7; singleton, last one wins.
        // Split on unescaped semicolons first: a literal ";" inside free text
        // arrives escaped ("\;") and must not split the components.
        val comps = splitUnescaped(decodedValue, ';').map(::unescapeV)
        val code = Gender.canonicalCode(comps.getOrNull(0).orEmpty()) ?: return
        val text = comps.drop(1).joinToString(";").trim()
            .takeIf { code == Gender.OTHER && it.isNotEmpty() }.orEmpty()
        builder.genders.clear()
        builder.genders.add(Gender(0, code, text))
    }

    private fun applyCommPref(builder: ContactBuilder, decodedValue: String) {
        // X-COMM-PREF:platform;number; singleton, last one wins. Invalid lines dropped.
        val comps = splitUnescaped(decodedValue, ';').map(::unescapeV)
        val platform = CommPlatform.fromStorageKey(comps.getOrNull(0).orEmpty()) ?: return
        val number = comps.drop(1).joinToString(";").trim()
        if (number.isEmpty()) return
        builder.commPrefs.clear()
        builder.commPrefs.add(CommPref(0, number, platform))
    }

    // Broad catch is deliberate: group resolution runs caller-supplied code
    // during import and its failures must never crash the import.
    @Suppress("TooGenericExceptionCaught")
    private fun applyCategories(builder: ContactBuilder, decodedValue: String) {
        val value = unescapeV(decodedValue)
        val names = splitUnescaped(value, ',').map { it.trim() }.filter { it.isNotBlank() }
        if (names.isEmpty()) return
        try {
            builder.groups.addAll(resolveGroups(names))
        } catch (e: Exception) {
            // Default no-op / resolver failures must never crash import.
            Log.status("VcfParser", "Group resolution failed", e)
        }
    }

    private fun applyStarred(builder: ContactBuilder, decodedValue: String) {
        val v = decodedValue.trim()
        val isStarred = v == "1" ||
            v.equals("true", ignoreCase = true) ||
            v.equals("yes", ignoreCase = true)
        if (isStarred) {
            builder.isFavorite = true
        }
    }

    private fun decodeValue(params: Map<String, List<String>>, valuePart: String): String {
        // Handle QUOTED-PRINTABLE decoding; unknown CHARSET skips just this value.
        val encodingVals = params["ENCODING"] ?: params["ENCOD"]
        val isQP = encodingVals?.any { it.equals("QUOTED-PRINTABLE", ignoreCase = true) } == true
        val charsetName = params["CHARSET"]?.firstOrNull() ?: params["CHARSET*"]?.firstOrNull()
        return try {
            if (isQP) decodeQuotedPrintable(valuePart, charsetName ?: "UTF-8") else valuePart
        } catch (e: java.io.UnsupportedEncodingException) {
            Log.status("VcfParser", "Unsupported charset for QP value", e)
            valuePart
        } catch (e: IllegalArgumentException) {
            Log.status("VcfParser", "Bad QP value", e)
            valuePart
        }
    }

    /** Normalizes "YYYYMMDD" and "--MM-DD" forms to an ISO date. Null when unparseable. */
    private fun parseVcfDate(raw: String): LocalDate? {
        var dv = raw
        if (dv.matches(VCF_BASIC_DATE_REGEX)) {
            dv = dv.take(DATE_PREFIX_LENGTH) + "-" +
                dv.substring(DATE_PREFIX_LENGTH, DATE_MONTH_END) + "-" +
                dv.substring(DATE_MONTH_END, DATE_DAY_END)
        } else if (dv.startsWith("--")) {
            // Both "--MM-DD" and the compact "--MMDD" no-year forms occur
            // in foreign vCards; only the dashed one parsed before, so
            // compact birthdays were silently dropped on import.
            val rest = dv.substring(2)
            val monthDay = if (rest.length == 4 && rest.all { it.isDigit() }) {
                rest.substring(0, 2) + "-" + rest.substring(2)
            } else {
                rest
            }
            dv = "$NO_YEAR_SENTINEL-$monthDay"
        }
        return try {
            LocalDate.parse(dv)
        } catch (e: IllegalArgumentException) {
            Log.status("VcfParser", "Unparseable VCF date", e)
            null
        }
    }

    private fun detectPhoneTypeWithLabel(params: Map<String, List<String>>): TypedLabel {
        val allTokens = params.values.flatten()
        val customLabel = extractCustomLabel(allTokens)
        val tokenStr = allTokens.joinToString(";")
        val type = when {
            customLabel != null -> ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM
            tokenStr.contains("CELL", ignoreCase = true) ->
                ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
            tokenStr.contains("HOME", ignoreCase = true) ->
                ContactsContract.CommonDataKinds.Phone.TYPE_HOME
            tokenStr.contains("WORK", ignoreCase = true) ->
                ContactsContract.CommonDataKinds.Phone.TYPE_WORK
            tokenStr.contains("FAX", ignoreCase = true) ->
                ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK
            else -> ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
        }
        return TypedLabel(type, customLabel ?: "")
    }

    private fun detectEmailTypeWithLabel(params: Map<String, List<String>>): TypedLabel {
        val allTokens = params.values.flatten()
        val customLabel = extractCustomLabel(allTokens)
        val tokenStr = allTokens.joinToString(";")
        val type = when {
            customLabel != null -> ContactsContract.CommonDataKinds.Email.TYPE_CUSTOM
            tokenStr.contains("WORK", ignoreCase = true) ->
                ContactsContract.CommonDataKinds.Email.TYPE_WORK
            tokenStr.contains("HOME", ignoreCase = true) ->
                ContactsContract.CommonDataKinds.Email.TYPE_HOME
            else -> ContactsContract.CommonDataKinds.Email.TYPE_OTHER
        }
        return TypedLabel(type, customLabel ?: "")
    }

    private fun detectAddressTypeWithLabel(params: Map<String, List<String>>): TypedLabel {
        val allTokens = params.values.flatten()
        val customLabel = extractCustomLabel(allTokens)
        val tokenStr = allTokens.joinToString(";")
        val type = when {
            customLabel != null -> ContactsContract.CommonDataKinds.StructuredPostal.TYPE_CUSTOM
            tokenStr.contains("HOME", ignoreCase = true) ->
                ContactsContract.CommonDataKinds.StructuredPostal.TYPE_HOME
            tokenStr.contains("WORK", ignoreCase = true) ->
                ContactsContract.CommonDataKinds.StructuredPostal.TYPE_WORK
            else -> ContactsContract.CommonDataKinds.StructuredPostal.TYPE_HOME
        }
        return TypedLabel(type, customLabel ?: "")
    }

    private fun detectEventTypeWithLabel(params: Map<String, List<String>>): TypedLabel {
        val allTokens = params.values.flatten()
        val customLabel = extractCustomLabel(allTokens)
        return if (customLabel != null) {
            TypedLabel(ContactsContract.CommonDataKinds.Event.TYPE_CUSTOM, customLabel)
        } else {
            TypedLabel(ContactsContract.CommonDataKinds.Event.TYPE_OTHER, "")
        }
    }
}

private class ContactBuilder {
    val phones: MutableList<PhoneNumber> = mutableListOf()
    val emails: MutableList<Email> = mutableListOf()
    val addresses: MutableList<Address> = mutableListOf()
    val dates: MutableList<Event> = mutableListOf()
    val photos: MutableList<Photo> = mutableListOf()
    val names: MutableList<Name> = mutableListOf(Name(0, "", "", "", "", ""))
    val orgs: MutableList<Organization> = mutableListOf(Organization(0, ""))
    val notes: MutableList<Note> = mutableListOf(Note(0, ""))
    val nicknames: MutableList<Nickname> = mutableListOf(
        Nickname(0, "", ContactsContract.CommonDataKinds.Nickname.TYPE_DEFAULT)
    )
    val genders: MutableList<Gender> = mutableListOf()
    val commPrefs: MutableList<CommPref> = mutableListOf()
    val groups: MutableList<GroupMembership> = mutableListOf()
    var isFavorite: Boolean = false
}

private data class TypedLabel(val type: Int, val label: String)

private fun extractCustomLabel(tokens: List<String>): String? {
    for (t in tokens) {
        if (t.startsWith("X-", ignoreCase = true) && t.length > 2) {
            return t.substring(2)
        }
    }
    return null
}

private fun unescapeV(value: String): String {
    // Unescape backslash LAST to avoid double-processing.
    return value
        .replace("\\n", "\n")
        .replace("\\N", "\n")
        .replace("\\,", ",")
        .replace("\\;", ";")
        .replace("\\\\", "\\")
}

private fun splitUnescaped(value: String, delimiter: Char): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c == '\\' && i + 1 < value.length) {
            // Keep the escape pair intact; unescapeV runs afterwards per-part.
            cur.append(c).append(value[i + 1])
            i += 2
        } else if (c == delimiter) {
            out.add(cur.toString())
            cur.clear()
            i++
        } else {
            cur.append(c)
            i++
        }
    }
    out.add(cur.toString())
    return out
}

private fun parseParams(parts: List<String>): Map<String, List<String>> {
    val out = mutableMapOf<String, MutableList<String>>()
    for (p in parts) {
        if (p.isEmpty()) continue
        val eq = p.indexOf('=')
        if (eq == -1) {
            val k = "TYPE"
            val vals = p.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            out.getOrPut(k) { mutableListOf() }.addAll(vals)
        } else {
            val k = p.take(eq).uppercase()
            val v = p.substring(eq + 1)
            val vals = v.split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }
            out.getOrPut(k) { mutableListOf() }.addAll(vals)
        }
    }
    return out
}

private fun decodeQuotedPrintable(input: String, charsetName: String): String {
    val out = ByteArrayOutputStream()
    var i = 0
    while (i < input.length) {
        val c = input[i]
        if (c == '=') {
            if (i + QP_HEX_PAIR_LENGTH < input.length) {
                val hex = input.substring(i + 1, i + QP_ESCAPE_LENGTH)
                val byteVal = hex.toIntOrNull(16)
                if (byteVal != null) {
                    out.write(byteVal)
                    i += QP_ESCAPE_LENGTH
                    continue
                }
            }
            i++
        } else {
            out.write(c.code)
            i++
        }
    }
    return String(out.toByteArray(), charset(charsetName))
}
