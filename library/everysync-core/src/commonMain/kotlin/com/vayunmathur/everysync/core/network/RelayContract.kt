package com.vayunmathur.everysync.core.network

/** Relay service endpoints. Contract reference: the Android `RelaySync` transport. */
const val RELAY_BASE_URL = "https://api.maproj.org/personal"
const val RELAY_WS_URL = "wss://api.maproj.org/personal/ws"

/** Channel name for a vault's record log, e.g. `passwords:<vaultId>`. */
fun recordChannel(dataType: String, vaultId: String): String = "$dataType:$vaultId"

fun membersChannel(vaultId: String): String = "members:$vaultId"
fun keysChannel(vaultId: String): String = "keys:$vaultId"
fun ownerChannel(vaultId: String): String = "owner:$vaultId"
fun titleChannel(vaultId: String): String = "title:$vaultId"
fun inboxChannel(deviceId: String): String = "inbox:$deviceId"
fun blobChannel(vaultId: String): String = "notes-blobs:$vaultId"

/**
 * Vault access roles — enforced entirely client-side via signature checks (relay is a pure log).
 * Passwords vaults never grant VIEWER: read-only passwords still disclose every secret.
 */
object RelayRoles {
    const val OWNER = "owner"
    const val EDITOR = "editor"
    const val VIEWER = "viewer"
    const val REVOKED = "revoked"

    fun canEdit(role: String) = role == OWNER || role == EDITOR
    fun canRead(role: String) = role == OWNER || role == EDITOR || role == VIEWER

    /** Roles offerable for a data type. Passwords exclude viewer (see above). */
    fun offerable(dataType: String): List<String> =
        if (dataType == "passwords") listOf(EDITOR) else listOf(EDITOR, VIEWER)
}

/** Deep-link / share-link helpers for personal vaults (mirrors office://join + voxels://join). */
object RelayLinks {
    const val SCHEME = "everysync"
    const val HOST_JOIN = "join"
    const val SHARE_BASE = "https://maproj.org/es/share"

    /** `https://maproj.org/es/share#id=<vault>&owner=<device>&type=<dataType>` — ids in fragment. */
    fun shareLinkFor(vaultId: String, ownerDeviceId: String, dataType: String): String =
        "$SHARE_BASE#id=$vaultId&owner=$ownerDeviceId&type=$dataType"

    /** `everysync://join/<vaultId>?owner=<device>&type=<dataType>`; null for any other intent. */
    fun parseJoinLink(scheme: String?, host: String?, vaultId: String?, ownerId: String?, dataType: String?)
        : Triple<String, String, String>? {
        if (scheme != SCHEME || host != HOST_JOIN) return null
        if (vaultId.isNullOrBlank() || ownerId.isNullOrBlank()) return null
        return Triple(vaultId, ownerId, dataType.orEmpty())
    }
}
