package com.vayunmathur.emergency.data

import android.graphics.Bitmap
import android.net.Uri
import androidx.core.net.toUri

/**
 * The owner's identity details plus their emergency contacts.
 *
 * Allergies and medications are deliberately absent: they are read live from Health Connect
 * (see [com.vayunmathur.emergency.platform.HealthConnectMedical]), never typed in by hand.
 * Contacts are stored as `|`-separated `CommonDataKinds.Phone.CONTENT_URI` strings exactly
 * like `EmergencyContactsPreference.serialize` (`|` separator, legacy string-set guard on
 * read).
 */
data class EmergencyInfo(
    val name: String = "",
    val address: String = "",
    val bloodType: String = "",
    val organDonor: String = "",
    val contacts: List<EmergencyContact> = emptyList(),
) {
    /** True once anything a first responder could use has been entered. */
    fun hasAnythingSet(): Boolean =
        name.isNotBlank() || address.isNotBlank() ||
            normalizeBloodType(bloodType).isNotBlank() ||
            normalizeOrganDonor(organDonor).isNotBlank() || contacts.isNotEmpty()
}

/** One emergency contact: the phone-table URI plus a snapshot for display. */
data class EmergencyContact(
    /** `ContactsContract.CommonDataKinds.Phone.CONTENT_URI` for the chosen number. */
    val phoneUri: Uri,
    val displayName: String,
    val phoneNumber: String,
    val phoneType: String,
    /** The contact's photo, or null when it has none. Never persisted; re-read onload. */
    val photo: Bitmap? = null,
)

/** Preference keys. Order matches the fields shown in the view screen. */
object EmergencyKeys {
    const val NAME = "name"
    const val ADDRESS = "address"
    const val BLOOD_TYPE = "blood_type"
    const val ORGAN_DONOR = "organ_donor"
    const val CONTACTS = "emergency_contacts"

    val VIEW_ORDER = listOf(ADDRESS, BLOOD_TYPE, ORGAN_DONOR)
}

/** Canonical stored value for a "yes" organ-donor dropdown selection. */
const val ORGAN_DONOR_YES = "yes"

/** Canonical stored value for a "no" organ-donor dropdown selection. */
const val ORGAN_DONOR_NO = "no"

/**
 * Blood-type dropdown options in display order. Unknown is stored as blank,
 * so [EmergencyInfo.hasAnythingSet] and the view screen's blank checks hide it.
 */
val BLOOD_TYPE_OPTIONS = listOf("A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-")

/**
 * Normalizes a stored blood type from the old free-text field: trims,
 * upper-cases, and maps a typed "unknown" to blank.
 */
fun normalizeBloodType(raw: String): String {
    val trimmed = raw.trim()
    if (trimmed.equals("unknown", ignoreCase = true)) return ""
    return trimmed.uppercase()
}

/**
 * Normalizes a stored organ-donor value from the old free-text field to its
 * canonical form; unknown (or blank) becomes blank.
 */
fun normalizeOrganDonor(raw: String): String =
    when (raw.trim().lowercase()) {
        "", "unknown" -> ""
        "yes" -> ORGAN_DONOR_YES
        "no" -> ORGAN_DONOR_NO
        else -> raw.trim()
    }

/** `|` separator from `EmergencyContactsPreference`; quoted for split. */
const val CONTACT_SEPARATOR = "|"

/** Serializes contact URIs the GrapheneOS way: `uri|uri` with no trailing separator. */
fun serializeContacts(uris: List<Uri>): String = uris.joinToString(CONTACT_SEPARATOR) { it.toString() }

/** Splits a serialized contact string into URIs, dropping blanks. */
fun parseContactUris(serialized: String): List<Uri> =
    serialized.split(CONTACT_SEPARATOR).mapNotNull {
        val trimmed = it.trim()
        if (trimmed.isEmpty()) null else runCatching { trimmed.toUri() }.getOrNull()
    }
