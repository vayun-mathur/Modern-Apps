package com.vayunmathur.contacts

import android.provider.ContactsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.LocalDate
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset

object VcfUtils {
    suspend fun exportContacts(contacts: List<Contact>, outputStream: OutputStream) {
        withContext(Dispatchers.IO) {
            outputStream.bufferedWriter().use { writer ->
                for (contact in contacts) {
                    val details = contact.details
                    writeFolded(writer, "BEGIN:VCARD")
                    writeFolded(writer, "VERSION:3.0")

                    // Name (N) - family;given;additional;prefix;suffix
                    val name = details.names.firstOrNull()
                    val family = name?.lastName ?: ""
                    val given = name?.firstName ?: ""
                    val additional = name?.middleName ?: ""
                    val prefix = name?.namePrefix ?: ""
                    val suffix = name?.nameSuffix ?: ""
                    writeFolded(writer, "N:${escapeV(family)};${escapeV(given)};${escapeV(additional)};${escapeV(prefix)};${escapeV(suffix)}")

                    // FN
                    val fn = listOfNotNull(prefix.ifEmpty { null }, given.ifEmpty { null }, additional.ifEmpty { null }, family.ifEmpty { null }, suffix.ifEmpty { null })
                        .joinToString(" ")
                    val fnValue = if (fn.isNotBlank()) fn else (details.names.firstOrNull()?.value ?: "")
                    writeFolded(writer, "FN:${escapeV(fnValue)}")

                    // Phones
                    for (phone in details.phoneNumbers) {
                        val typeToken = when (phone.type) {
                            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "CELL"
                            ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "HOME"
                            ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "WORK"
                            ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK,
                            ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME -> "FAX"
                            else -> "VOICE"
                        }
                        writeFolded(writer, "TEL;TYPE=$typeToken:${escapeV(phone.number)}")
                    }

                    // Emails
                    for (email in details.emails) {
                        val typeToken = when (email.type) {
                            ContactsContract.CommonDataKinds.Email.TYPE_HOME -> "HOME"
                            ContactsContract.CommonDataKinds.Email.TYPE_WORK -> "WORK"
                            else -> "INTERNET"
                        }
                        writeFolded(writer, "EMAIL;TYPE=$typeToken:${escapeV(email.address)}")
                    }

                    // Addresses
                    for (addr in details.addresses) {
                        // Format into ADR components: PO Box;Extended;Street;City;Region;PostalCode;Country
                        val formatted = addr.formattedAddress
                        writeFolded(writer, "ADR;TYPE=HOME:;;${escapeV(formatted)};;;;")
                    }

                    // Organization
                    val org = details.orgs.firstOrNull()?.company ?: ""
                    if (org.isNotEmpty()) writeFolded(writer, "ORG:${escapeV(org)}")

                    // Birthday
                    val bday = details.dates.firstOrNull { it.type == ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY }
                    if (bday != null) {
                        writeFolded(writer, "BDAY:${bday.startDate}")
                    }

                    // Notes
                    for (note in details.notes) {
                        if (note.content.isNotEmpty()) writeFolded(writer, "NOTE:${escapeV(note.content)}")
                    }

                    // Photo (base64) - write as single line; large photos are written raw
                    val photo = details.photos.firstOrNull()
                    if (photo != null && photo.photo.isNotEmpty()) {
                        writeFolded(writer, "PHOTO;ENCODING=b:${photo.photo}")
                    }

                    writeFolded(writer, "END:VCARD")
                }
                writer.flush()
            }
        }
    }

    // New: parse vCard stream into a list of Contact objects without saving them to the Contacts provider.
    fun parseContacts(inputStream: InputStream): List<Contact> {
        val contactsToSave = mutableListOf<Contact>()
        val reader = inputStream.bufferedReader()

        // Read and unfold folded lines (lines starting with space or tab continue previous)
        val rawLines = reader.readLines()
        val unfolded = mutableListOf<String>()
        var bufferLine: String? = null
        for (ln in rawLines) {
            if (ln.startsWith(" ") || ln.startsWith("\t")) {
                bufferLine = (bufferLine ?: "") + ln.trimStart()
            } else {
                if (bufferLine != null) unfolded.add(bufferLine)
                bufferLine = ln
            }
        }
        if (bufferLine != null) unfolded.add(bufferLine)

        var currentContact: ContactBuilder? = null

        for (raw in unfolded) {
            val line = raw.trimEnd()
            if (line.isEmpty()) continue
            if (line.startsWith("BEGIN:VCARD", ignoreCase = true)) {
                currentContact = ContactBuilder()
                continue
            }
            if (line.startsWith("END:VCARD", ignoreCase = true)) {
                currentContact?.let { builder ->
                    val details = ContactDetails(
                        phoneNumbers = builder.phones.toList(),
                        emails = builder.emails.toList(),
                        addresses = builder.addresses.toList(),
                        dates = builder.dates.toList(),
                        photos = builder.photos.toList(),
                        names = builder.names.toList(),
                        orgs = builder.orgs.toList(),
                        notes = builder.notes.toList(),
                        nicknames = builder.nicknames.toList()
                    )
                    val newContact = Contact(
                        false,
                        id = 0L,
                        isFavorite = false,
                        details
                    )
                    contactsToSave.add(newContact)
                }
                currentContact = null
                continue
            }

            if (currentContact == null) continue

            // Parse property line: NAME[;PARAMS]:VALUE
            val colonIndex = line.indexOf(':')
            if (colonIndex == -1) continue
            val nameAndParams = line.substring(0, colonIndex)
            val valuePart = line.substring(colonIndex + 1)

            val segments = nameAndParams.split(';')
            val propName = segments.firstOrNull()?.uppercase() ?: continue
            val params = parseParams(segments.drop(1))

            // Handle QUOTED-PRINTABLE decoding
            val encodingVals = params["ENCODING"] ?: params["ENCOD"]
            val isQP = encodingVals?.any { it.equals("QUOTED-PRINTABLE", ignoreCase = true) } == true
            val charsetName = params["CHARSET"]?.firstOrNull() ?: params["CHARSET*"]?.firstOrNull()
            val value = if (isQP) decodeQuotedPrintable(valuePart, charsetName ?: "UTF-8") else valuePart

            when (propName) {
                "N" -> {
                    // family;given;additional;prefix;suffix
                    val comps = value.split(';')
                    val family = comps.getOrNull(0) ?: ""
                    val given = comps.getOrNull(1) ?: ""
                    val additional = comps.getOrNull(2) ?: ""
                    val prefix = comps.getOrNull(3) ?: ""
                    val suffix = comps.getOrNull(4) ?: ""
                    currentContact.names.clear()
                    currentContact.names.add(Name(0, prefix, given, additional, family, suffix))
                }
                "FN" -> {
                    if (currentContact.names.isEmpty()) {
                        val display = value
                        val first = display.split(" ").firstOrNull() ?: display
                        val last = display.split(" ").drop(1).joinToString(" ")
                        currentContact.names.add(Name(0, "", first, "", last, ""))
                    }
                }
                "TEL" -> {
                    val ttype = detectPhoneType(params)
                    currentContact.phones.add(PhoneNumber(0, value, ttype))
                }
                "EMAIL" -> {
                    val etype = detectEmailType(params)
                    currentContact.emails.add(Email(0, value, etype))
                }
                "ADR" -> {
                    // ADR components: POBox;Extended;Street;City;Region;PostalCode;Country
                    val comps = value.split(';')
                    val street = comps.getOrNull(2) ?: ""
                    val city = comps.getOrNull(3) ?: ""
                    val region = comps.getOrNull(4) ?: ""
                    val postal = comps.getOrNull(5) ?: ""
                    val country = comps.getOrNull(6) ?: ""
                    val formatted = listOfNotNull(street.ifEmpty { null }, city.ifEmpty { null }, region.ifEmpty { null }, postal.ifEmpty { null }, country.ifEmpty { null }).joinToString(", ")
                    val atype = if (params["TYPE"]?.any { it.equals("HOME", ignoreCase = true) } == true) ContactsContract.CommonDataKinds.StructuredPostal.TYPE_HOME else ContactsContract.CommonDataKinds.StructuredPostal.TYPE_WORK
                    currentContact.addresses.add(Address(0, formatted, atype))
                }
                "ORG" -> {
                    currentContact.orgs.clear()
                    currentContact.orgs.add(Organization(0, value))
                }
                "BDAY" -> {
                    var dv = value
                    if (dv.matches(Regex("^\\d{8}"))) {
                        dv = dv.substring(0,4) + "-" + dv.substring(4,6) + "-" + dv.substring(6,8)
                    }
                    try {
                        val date = LocalDate.parse(dv)
                        currentContact.dates.add(Event(0, date, ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY))
                    } catch (_: Exception) {
                        // ignore invalid date
                    }
                }
                "NOTE" -> {
                    currentContact.notes.add(Note(0, value))
                }
                "PHOTO" -> {
                    // Keep base64 string as-is
                    currentContact.photos.add(Photo(0, value))
                }
                "URL" -> {
                    // No dedicated website field - append URL to notes
                    currentContact.notes.add(Note(0, value))
                }
                else -> {
                    // ignore unknown properties
                }
            }
        }

        return contactsToSave
    }

    private class ContactBuilder {
        val phones: MutableList<PhoneNumber> = mutableListOf()
        val emails: MutableList<Email> = mutableListOf()
        val addresses: MutableList<Address> = mutableListOf()
        val dates: MutableList<Event> = mutableListOf()
        val photos: MutableList<Photo> = mutableListOf()
        val names: MutableList<Name> = mutableListOf()
        val orgs: MutableList<Organization> = mutableListOf()
        val notes: MutableList<Note> = mutableListOf()
        val nicknames: MutableList<Nickname> = mutableListOf()
    }

    private fun escapeV(value: String): String {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;")
    }

    private fun parseParams(parts: List<String>): Map<String, List<String>> {
        val out = mutableMapOf<String, MutableList<String>>()
        for (p in parts) {
            if (p.isEmpty()) continue
            val eq = p.indexOf('=')
            if (eq == -1) {
                // bare token, treat as TYPE
                val k = "TYPE"
                val vals = p.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                out.getOrPut(k) { mutableListOf() }.addAll(vals)
            } else {
                val k = p.substring(0, eq).uppercase()
                val v = p.substring(eq + 1)
                val vals = v.split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }
                out.getOrPut(k) { mutableListOf() }.addAll(vals)
            }
        }
        return out
    }

    private fun writeFolded(writer: java.io.BufferedWriter, line: String) {
        val maxLineLength = 75
        if (line.length <= maxLineLength) {
            writer.write(line)
            writer.write("\r\n")
            return
        }
        var idx = 0
        while (idx < line.length) {
            val end = kotlin.math.min(idx + maxLineLength, line.length)
            val part = line.substring(idx, end)
            if (idx == 0) {
                writer.write(part)
                writer.write("\r\n")
            } else {
                writer.write(" " + part)
                writer.write("\r\n")
            }
            idx = end
        }
    }

    private fun decodeQuotedPrintable(input: String, charsetName: String): String {
        val out = ByteArrayOutputStream()
        var i = 0
        while (i < input.length) {
            val c = input[i]
            if (c == '=') {
                // soft line break? '=' at end or followed by CRLF is handled earlier since we read unfolded lines
                if (i + 2 < input.length) {
                    val hex = input.substring(i + 1, i + 3)
                    val byteVal = hex.toIntOrNull(16)
                    if (byteVal != null) {
                        out.write(byteVal)
                        i += 3
                        continue
                    }
                }
                // If malformed, skip '='
                i++
            } else {
                // write literal byte using US-ASCII
                out.write(c.code)
                i++
            }
        }
        return String(out.toByteArray(), Charset.forName(charsetName))
    }

    private fun detectPhoneType(params: Map<String, List<String>>): Int {
        val tokens = params.values.flatten().joinToString(";")
        return when {
            tokens.contains("CELL", ignoreCase = true) -> ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
            tokens.contains("HOME", ignoreCase = true) -> ContactsContract.CommonDataKinds.Phone.TYPE_HOME
            tokens.contains("WORK", ignoreCase = true) -> ContactsContract.CommonDataKinds.Phone.TYPE_WORK
            tokens.contains("FAX", ignoreCase = true) -> ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK
            else -> ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE
        }
    }

    private fun detectEmailType(params: Map<String, List<String>>): Int {
        val tokens = params.values.flatten().joinToString(";")
        return when {
            tokens.contains("WORK", ignoreCase = true) -> ContactsContract.CommonDataKinds.Email.TYPE_WORK
            tokens.contains("HOME", ignoreCase = true) -> ContactsContract.CommonDataKinds.Email.TYPE_HOME
            else -> ContactsContract.CommonDataKinds.Email.TYPE_OTHER
        }
    }
}
