package com.vayunmathur.contacts

import android.content.ContentValues
import android.content.Intent
import android.provider.ContactsContract
import android.provider.ContactsContract.Intents.Insert
import androidx.core.content.IntentCompat
import com.vayunmathur.contacts.data.CDKEmail
import com.vayunmathur.contacts.data.CDKNickname
import com.vayunmathur.contacts.data.CDKNote
import com.vayunmathur.contacts.data.CDKOrg
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.CDKSName
import com.vayunmathur.contacts.data.CDKStructuredPostal
import com.vayunmathur.contacts.data.ContactPrefill
import com.vayunmathur.contacts.data.PrefillValue

internal fun isVcfIntent(intent: Intent): Boolean {
    val type = intent.type ?: ""
    return type.contains("vcard") ||
        type.contains("vcf") ||
        intent.data?.path?.endsWith(".vcf", ignoreCase = true) == true
}

/**
 * Reads a CommonDataKinds TYPE extra that may be an Int, a numeric String, or a custom label.
 * Returns (type, customLabel); a non-numeric String becomes TYPE_CUSTOM with that label.
 */
internal fun readType(intent: Intent, key: String): Pair<Int?, String?> {
    val extras = intent.extras ?: return null to null
    if (!extras.containsKey(key)) return null to null
    // Typed reads instead of the deprecated Bundle.get(String): CharSequence covers String
    // and styled text (numeric text parses, anything else becomes a TYPE_CUSTOM label),
    // Int covers the numeric form. Anything else is unknown, as before.
    extras.getCharSequence(key)?.let { raw ->
        val s = raw.toString()
        return s.toIntOrNull()?.let { it to null }
            ?: (ContactsContract.CommonDataKinds.BaseTypes.TYPE_CUSTOM to s)
    }
    val intValue = extras.getInt(key, Int.MIN_VALUE)
    if (intValue != Int.MIN_VALUE) return intValue to null
    return null to null
}

/** Extracts a phone number from a `tel:` or `phone_lookup` data URI (not from extras). */
internal fun uriPhoneNumber(intent: Intent): String? {
    val uri = intent.data ?: return null
    if (uri.scheme == "tel") {
        return uri.schemeSpecificPart
    }
    if (uri.authority == ContactsContract.AUTHORITY && uri.path?.contains("phone_lookup") == true) {
        return uri.lastPathSegment
    }
    return null
}

internal class PrefillNames(
    var name: String? = null,
    var company: String? = null,
    var notes: String? = null,
    var nickname: String? = null,
)

/**
 * Parses everything an ACTION_INSERT-style intent can carry into a [ContactPrefill].
 *
 * Reads the scalar [ContactsContract.Intents.Insert] extras via [Intent.getCharSequenceExtra]
 * (dialers/call logs pass values as `CharSequence`/`Spannable`, so `getStringExtra` returns
 * null for them) plus the [ContactsContract.Intents.Insert.DATA] `ArrayList<ContentValues>`.
 */
internal fun buildInsertPrefill(intent: Intent): ContactPrefill {
    fun text(key: String): String? =
        intent.getCharSequenceExtra(key)?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    val phones = collectPrefillPhones(intent, ::text)
    val emails = collectPrefillEmails(intent, ::text)
    val postals = collectPrefillPostals(intent, ::text)
    val names = collectPrefillNames(::text)
    applyPrefillDataRows(intent, phones, emails, postals, names)

    return ContactPrefill(
        names.name,
        names.company,
        names.notes,
        names.nickname,
        phones,
        emails,
        postals,
    )
}

internal fun collectPrefillPhones(
    intent: Intent,
    text: (String) -> String?,
): MutableList<PrefillValue> {
    val phones = mutableListOf<PrefillValue>()
    val (phoneType, phoneLabel) = readType(intent, Insert.PHONE_TYPE)
    text(Insert.PHONE)?.let { phones += PrefillValue(it, phoneType, phoneLabel) }
    text(Insert.SECONDARY_PHONE)?.let { phones += PrefillValue(it) }
    text(Insert.TERTIARY_PHONE)?.let { phones += PrefillValue(it) }
    return phones
}

internal fun collectPrefillEmails(
    intent: Intent,
    text: (String) -> String?,
): MutableList<PrefillValue> {
    val emails = mutableListOf<PrefillValue>()
    val (emailType, emailLabel) = readType(intent, Insert.EMAIL_TYPE)
    text(Insert.EMAIL)?.let { emails += PrefillValue(it, emailType, emailLabel) }
    text(Insert.SECONDARY_EMAIL)?.let { emails += PrefillValue(it) }
    text(Insert.TERTIARY_EMAIL)?.let { emails += PrefillValue(it) }
    return emails
}

internal fun collectPrefillPostals(
    intent: Intent,
    text: (String) -> String?,
): MutableList<PrefillValue> {
    val postals = mutableListOf<PrefillValue>()
    val (postalType, postalLabel) = readType(intent, Insert.POSTAL_TYPE)
    text(Insert.POSTAL)?.let { postals += PrefillValue(it, postalType, postalLabel) }
    return postals
}

internal fun collectPrefillNames(
    text: (String) -> String?,
): PrefillNames = PrefillNames(
    name = text(Insert.NAME),
    company = text(Insert.COMPANY),
    notes = text(Insert.NOTES),
)

internal fun applyPrefillDataRows(
    intent: Intent,
    phones: MutableList<PrefillValue>,
    emails: MutableList<PrefillValue>,
    postals: MutableList<PrefillValue>,
    names: PrefillNames,
) {
    // Insert.DATA: caller-provided rows, one ContentValues per data kind (typed).
    val dataRows =
        IntentCompat.getParcelableArrayListExtra(intent, Insert.DATA, ContentValues::class.java)
    dataRows?.forEach { cv ->
        fun value(key: String) = cv.getAsString(key)?.trim()?.takeIf { it.isNotEmpty() }
        when (cv.getAsString(ContactsContract.Data.MIMETYPE)) {
            CDKPhone.CONTENT_ITEM_TYPE ->
                applyPrefillPhoneRow(cv, ::value, phones)
            CDKEmail.CONTENT_ITEM_TYPE ->
                applyPrefillEmailRow(cv, ::value, emails)
            CDKStructuredPostal.CONTENT_ITEM_TYPE ->
                applyPrefillPostalRow(cv, ::value, postals)
            CDKSName.CONTENT_ITEM_TYPE ->
                if (names.name == null) names.name = value(CDKSName.DISPLAY_NAME)
            CDKOrg.CONTENT_ITEM_TYPE ->
                if (names.company == null) names.company = value(CDKOrg.COMPANY)
            CDKNote.CONTENT_ITEM_TYPE ->
                if (names.notes == null) names.notes = value(CDKNote.NOTE)
            CDKNickname.CONTENT_ITEM_TYPE ->
                if (names.nickname == null) names.nickname = value(CDKNickname.NAME)
        }
    }
}

internal fun applyPrefillPhoneRow(
    cv: ContentValues,
    value: (String) -> String?,
    phones: MutableList<PrefillValue>,
) {
    val number = value(CDKPhone.NUMBER) ?: return
    phones += PrefillValue(number, cv.getAsInteger(CDKPhone.TYPE), cv.getAsString(CDKPhone.LABEL))
}

internal fun applyPrefillEmailRow(
    cv: ContentValues,
    value: (String) -> String?,
    emails: MutableList<PrefillValue>,
) {
    val address = value(CDKEmail.ADDRESS) ?: return
    emails += PrefillValue(address, cv.getAsInteger(CDKEmail.TYPE), cv.getAsString(CDKEmail.LABEL))
}

internal fun applyPrefillPostalRow(
    cv: ContentValues,
    value: (String) -> String?,
    postals: MutableList<PrefillValue>,
) {
    val formatted = value(CDKStructuredPostal.FORMATTED_ADDRESS) ?: return
    postals += PrefillValue(
        formatted,
        cv.getAsInteger(CDKStructuredPostal.TYPE),
        cv.getAsString(CDKStructuredPostal.LABEL),
    )
}
