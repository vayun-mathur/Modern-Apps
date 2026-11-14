package com.vayunmathur.contacts.util

import com.vayunmathur.contacts.data.Address
import com.vayunmathur.contacts.data.CDKEmail
import com.vayunmathur.contacts.data.CDKPhone
import com.vayunmathur.contacts.data.CDKStructuredPostal
import com.vayunmathur.contacts.data.Email
import com.vayunmathur.contacts.data.PhoneNumber
import com.vayunmathur.contacts.data.PrefillValue

/** Appends prefilled phones that aren't already present (compared by normalized number). */
internal fun ContactViewModel.mergePhones(
    existing: List<PhoneNumber>,
    prefill: List<PrefillValue>
): List<PhoneNumber> {
    if (prefill.isEmpty()) return existing
    val result = existing.toMutableList()
    val known = result.mapTo(mutableSetOf()) { normalizePhoneForCompare(it.number) }
    for (p in prefill) {
        val norm = normalizePhoneForCompare(p.value)
        if (norm.isEmpty() || !known.add(norm)) continue
        result += PhoneNumber(0, p.value, p.type ?: CDKPhone.TYPE_MOBILE, p.label ?: "")
    }
    return result
}

/** Appends prefilled emails that aren't already present (case-insensitive address match). */
internal fun ContactViewModel.mergeEmails(
    existing: List<Email>,
    prefill: List<PrefillValue>
): List<Email> {
    if (prefill.isEmpty()) return existing
    val result = existing.toMutableList()
    val known = result.mapTo(mutableSetOf()) { it.address.trim().lowercase() }
    for (e in prefill) {
        val v = e.value.trim()
        if (v.isEmpty() || !known.add(v.lowercase())) continue
        result += Email(0, e.value, e.type ?: CDKEmail.TYPE_HOME, e.label ?: "")
    }
    return result
}

/** Appends prefilled postal addresses that aren't already present (case-insensitive match). */
internal fun ContactViewModel.mergeAddresses(
    existing: List<Address>,
    prefill: List<PrefillValue>
): List<Address> {
    if (prefill.isEmpty()) return existing
    val result = existing.toMutableList()
    val known = result.mapTo(mutableSetOf()) { it.formattedAddress.trim().lowercase() }
    for (a in prefill) {
        val v = a.value.trim()
        if (v.isEmpty() || !known.add(v.lowercase())) continue
        result += Address(0, a.value, a.type ?: CDKStructuredPostal.TYPE_HOME, a.label ?: "")
    }
    return result
}
