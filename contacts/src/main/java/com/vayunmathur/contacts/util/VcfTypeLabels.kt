package com.vayunmathur.contacts.util

import android.provider.ContactsContract

/** A provider detail type paired with its custom label (blank when not custom). */
internal data class TypedLabel(val type: Int, val label: String)

private fun extractCustomLabel(tokens: List<String>): String? {
    for (t in tokens) {
        if (t.startsWith("X-", ignoreCase = true) && t.length > 2) {
            return t.substring(2)
        }
    }
    return null
}

internal fun detectPhoneTypeWithLabel(params: Map<String, List<String>>): TypedLabel {
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

internal fun detectEmailTypeWithLabel(params: Map<String, List<String>>): TypedLabel {
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

internal fun detectAddressTypeWithLabel(params: Map<String, List<String>>): TypedLabel {
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

internal fun detectEventTypeWithLabel(params: Map<String, List<String>>): TypedLabel {
    val allTokens = params.values.flatten()
    val customLabel = extractCustomLabel(allTokens)
    return if (customLabel != null) {
        TypedLabel(ContactsContract.CommonDataKinds.Event.TYPE_CUSTOM, customLabel)
    } else {
        TypedLabel(ContactsContract.CommonDataKinds.Event.TYPE_OTHER, "")
    }
}
