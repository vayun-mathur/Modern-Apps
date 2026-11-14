package com.vayunmathur.contacts.data

import android.content.Context
import kotlinx.serialization.Serializable

/**
 * Singleton per-contact preferred number + platform for the Call/Message/Video
 * header buttons, persisted as a custom MIME Data row like [Gender].
 * Persisted under a custom MIME type in DATA1 (number as stored) + DATA2
 * (platform storage key); never stored on SIM contacts.
 */
@Serializable
data class CommPref(
    override val id: Long,
    val number: String,
    val platform: CommPlatform = CommPlatform.SIM,
) : ContactDetail<CommPref> {
    override val type: Int = 0
    override val value: String
        get() = "${platform.storageKey};$number"

    override fun withType(type: Int) =
        throw UnsupportedOperationException("Cannot change type of comm pref")

    override fun withValue(value: String): CommPref {
        val key = value.substringBefore(";")
        val num = value.substringAfter(";", "")
        return copy(
            number = num,
            platform = CommPlatform.fromStorageKey(key) ?: CommPlatform.SIM,
        )
    }

    override fun withLabel(label: String): CommPref = this

    override fun typeString(context: Context) =
        throw UnsupportedOperationException("Comm pref doesn't have type")

    /** Provider MIME type under which a [CommPref] row is stored. */
    companion object {
        const val MIME_TYPE = "vnd.android.cursor.item/vnd.com.vayunmathur.contacts.comm_pref"
    }
}

/** Platform a [CommPref] can point at. Storage keys are stable; do not rename. */
@Serializable
enum class CommPlatform(val storageKey: String) {
    SIM("sim"),
    SIGNAL("signal"),
    WHATSAPP("whatsapp"),
    TELEGRAM("telegram"),
    MEET("meet");

    /** Lookup of a platform by its persisted storage key. */
    companion object {
        fun fromStorageKey(key: String): CommPlatform? =
            entries.firstOrNull { it.storageKey == key.trim().lowercase() }
    }
}
