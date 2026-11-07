package com.vayunmathur.contacts.util
import android.provider.ContactsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.vayunmathur.contacts.data.Address
import com.vayunmathur.contacts.data.Contact
import com.vayunmathur.contacts.data.ContactDetails
import com.vayunmathur.contacts.data.Email
import com.vayunmathur.contacts.data.Gender
import com.vayunmathur.contacts.data.GroupMembership
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.data.toExternalDateString
import java.io.InputStream
import java.io.OutputStream
import java.io.Writer

object VcfUtils {
    private const val VCF_FOLD_LENGTH = 75

    suspend fun exportContacts(
        contacts: List<Contact>,
        outputStream: OutputStream,
        groupNames: Map<Long, String> = emptyMap()
    ) {
        withContext(Dispatchers.IO) {
            outputStream.bufferedWriter(Charsets.UTF_8).use { writer ->
                for (contact in contacts) {
                    writeContact(writer, contact, groupNames)
                }
                writer.flush()
            }
        }
    }

    private fun writeContact(
        writer: Writer,
        contact: Contact,
        groupNames: Map<Long, String>,
    ) {
        val details = contact.details
        writeFolded(writer, "BEGIN:VCARD")
        writeFolded(writer, "VERSION:3.0")
        writeNameLines(writer, details)
        writePhoneLines(writer, details)
        writeEmailLines(writer, details)
        writeAddressLines(writer, details)
        writeEventLines(writer, details)
        writeOrgLine(writer, details)
        writeBirthdayLine(writer, details)
        writeNoteLines(writer, details)
        writeNicknameLines(writer, details)
        writeGenderLine(writer, details)
        writeCommPrefLine(writer, details)
        writeCategoriesLine(writer, details, groupNames)

        // Favorite marker
        if (contact.isFavorite) writeFolded(writer, "X-STARRED:1")
        writePhotoLine(writer, details)

        writeFolded(writer, "END:VCARD")
    }

    private fun writeNameLines(writer: Writer, details: ContactDetails) {
        // Name (N) - family;given;additional;prefix;suffix
        val name = details.names.firstOrNull()
        val family = name?.lastName ?: ""
        val given = name?.firstName ?: ""
        val additional = name?.middleName ?: ""
        val prefix = name?.namePrefix ?: ""
        val suffix = name?.nameSuffix ?: ""
        writeFolded(
            writer,
            "N:${escapeV(family)};${escapeV(given)};" +
                "${escapeV(additional)};${escapeV(prefix)};${escapeV(suffix)}"
        )

        // FN
        val fn = listOfNotNull(
            prefix.ifEmpty { null },
            given.ifEmpty { null },
            additional.ifEmpty { null },
            family.ifEmpty { null },
            suffix.ifEmpty { null }
        ).joinToString(" ")
        val fnValue = fn.ifBlank { (details.names.firstOrNull()?.value ?: "") }
        writeFolded(writer, "FN:${escapeV(fnValue)}")
    }

    private fun writePhoneLines(writer: Writer, details: ContactDetails) {
        // Phones - preserve custom label as X- token for roundtrip
        for (phone in details.phoneNumbers) {
            val typeToken = phoneTypeToken(phone)
            writeFolded(writer, "TEL;TYPE=$typeToken:${escapeV(phone.number)}")
        }
    }

    private fun phoneTypeToken(phone: PhoneNumber): String =
        when (phone.type) {
            ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE -> "CELL"
            ContactsContract.CommonDataKinds.Phone.TYPE_HOME -> "HOME"
            ContactsContract.CommonDataKinds.Phone.TYPE_WORK -> "WORK"
            ContactsContract.CommonDataKinds.Phone.TYPE_FAX_WORK,
            ContactsContract.CommonDataKinds.Phone.TYPE_FAX_HOME -> "FAX"
            ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM ->
                customTypeToken(phone.label)
            else -> "VOICE"
        }

    private fun customTypeToken(label: String): String {
        val sanitized = label.ifBlank { "CUSTOM" }
            .replace(",", "").replace(";", "").replace(":", "")
        return "X-$sanitized"
    }

    private fun writeEmailLines(writer: Writer, details: ContactDetails) {
        // Emails
        for (email in details.emails) {
            val typeToken = emailTypeToken(email)
            writeFolded(writer, "EMAIL;TYPE=$typeToken:${escapeV(email.address)}")
        }
    }

    private fun emailTypeToken(email: Email): String =
        when (email.type) {
            ContactsContract.CommonDataKinds.Email.TYPE_HOME -> "HOME"
            ContactsContract.CommonDataKinds.Email.TYPE_WORK -> "WORK"
            ContactsContract.CommonDataKinds.Email.TYPE_CUSTOM ->
                customTypeToken(email.label)
            else -> "INTERNET"
        }

    private fun writeAddressLines(writer: Writer, details: ContactDetails) {
        // Addresses
        for (addr in details.addresses) {
            val formatted = addr.formattedAddress
            val typeToken =
                when (addr.type) {
                    ContactsContract.CommonDataKinds.StructuredPostal.TYPE_CUSTOM ->
                        customTypeToken(addr.label)
                    ContactsContract.CommonDataKinds.StructuredPostal.TYPE_WORK -> "WORK"
                    else -> "HOME"
                }
            writeFolded(writer, "ADR;TYPE=$typeToken:;;${escapeV(formatted)};;;;")
        }
    }

    private fun writeEventLines(writer: Writer, details: ContactDetails) {
        // Other dates (non-birthday) with custom label support
        val otherDates = details.dates.filter {
            it.type != ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY
        }
        for (event in otherDates) {
            val typeToken =
                if (event.type == ContactsContract.CommonDataKinds.Event.TYPE_CUSTOM) {
                    customTypeToken(event.label)
                } else {
                    "OTHER"
                }
            writeFolded(writer, "X-EVENT;TYPE=$typeToken:${escapeV(event.startDate.toExternalDateString())}")
        }
    }

    private fun writeOrgLine(writer: Writer, details: ContactDetails) {
        // Organization
        val org = details.orgs.firstOrNull()?.company ?: ""
        if (org.isNotEmpty()) writeFolded(writer, "ORG:${escapeV(org)}")
    }

    private fun writeBirthdayLine(writer: Writer, details: ContactDetails) {
        // Birthday, in the portable external form ("--MM-DD" when the year
        // is unknown).
        val bday = details.dates.firstOrNull {
            it.type == ContactsContract.CommonDataKinds.Event.TYPE_BIRTHDAY
        }
        if (bday != null) {
            writeFolded(writer, "BDAY:${bday.startDate.toExternalDateString()}")
        }
    }

    private fun writeNoteLines(writer: Writer, details: ContactDetails) {
        // Notes
        for (note in details.notes) {
            if (note.content.isNotEmpty()) writeFolded(writer, "NOTE:${escapeV(note.content)}")
        }
    }

    private fun writeNicknameLines(writer: Writer, details: ContactDetails) {
        // Nicknames
        for (nn in details.nicknames) {
            if (nn.nickname.isNotBlank()) writeFolded(writer, "NICKNAME:${escapeV(nn.nickname)}")
        }
    }

    private fun writeGenderLine(writer: Writer, details: ContactDetails) {
        // Gender (RFC 6350 §6.2.7): single letter, optional free text for Other.
        // Emitted as a vCard-4.0 extension; the parser is version-agnostic.
        // The ";" between letter and text is a component separator (like N's),
        // so each side is escaped separately rather than escaping the whole value.
        val gender = details.genders.firstOrNull { it.code.isNotBlank() } ?: return
        val code = Gender.canonicalCode(gender.code) ?: Gender.UNSPECIFIED
        val text = gender.text.trim().takeIf { code == Gender.OTHER && it.isNotEmpty() }
        val raw = if (text != null) "${escapeV(code)};${escapeV(text)}" else escapeV(code)
        writeFolded(writer, "GENDER:$raw")
    }

    private fun writeCommPrefLine(writer: Writer, details: ContactDetails) {
        // Preferred number + platform for the Call/Message/Video buttons.
        // Extension property; the parser is version-agnostic like GENDER.
        val pref = details.commPref ?: return
        if (pref.number.isBlank()) return
        writeFolded(writer, "X-COMM-PREF:${escapeV(pref.platform.storageKey)};${escapeV(pref.number)}")
    }

    private fun writeCategoriesLine(
        writer: Writer,
        details: ContactDetails,
        groupNames: Map<Long, String>,
    ) {
        // Group memberships as CATEGORIES (names via id->name map)
        if (details.groups.isEmpty()) return
        val catNames = details.groups.mapNotNull { gm ->
            val mapped = groupNames[gm.groupId]
            when {
                mapped != null && mapped.isNotBlank() -> mapped
                else -> gm.groupId.toString()
            }
        }.filter { it.isNotBlank() }.distinct()
        if (catNames.isNotEmpty()) {
            writeFolded(writer, "CATEGORIES:${catNames.joinToString(",") { escapeV(it) }}")
        }
    }

    private fun writePhotoLine(writer: Writer, details: ContactDetails) {
        // Photo (base64) - write as single line; large photos are written raw
        val photo = details.photos.firstOrNull()
        if (photo != null && photo.photo.isNotEmpty()) {
            writeFolded(writer, "PHOTO;ENCODING=b:${photo.photo}")
        }
    }

    fun parseContacts(
        inputStream: InputStream,
        resolveGroups: (List<String>) -> List<GroupMembership> = { emptyList() }
    ): List<Contact> = parseVcfContacts(inputStream, resolveGroups)

    private fun escapeV(value: String): String {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace(",", "\\,").replace(";", "\\;")
    }

    private fun writeFolded(writer: Writer, line: String) {
        if (line.length <= VCF_FOLD_LENGTH) {
            writer.write(line)
            writer.write("\r\n")
            return
        }
        var idx = 0
        while (idx < line.length) {
            val end = kotlin.math.min(idx + VCF_FOLD_LENGTH, line.length)
            val part = line.substring(idx, end)
            if (idx == 0) {
                writer.write(part)
                writer.write("\r\n")
            } else {
                writer.write(" $part")
                writer.write("\r\n")
            }
            idx = end
        }
    }
}
