package com.vayunmathur.passwords.data

import androidx.room3.Entity
import androidx.room3.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem

/**
 * [Passkey.linkedPasswordSyncId] value meaning the user detached this passkey by hand. A syncId is
 * 32 lowercase hex chars (see [newSyncId]), so this can never collide with a real one.
 */
const val PASSKEY_LINK_DETACHED = "-"

@Entity
data class Passkey(
    @PrimaryKey(autoGenerate = true) override val id: Long = 0,
    val rpId: String = "",
    val rpName: String = "",
    val credentialId: String = "",
    val userId: String = "",
    val userName: String = "",
    val userDisplayName: String = "",
    val privateKeyBytes: ByteArray = ByteArray(0),
    val creationTime: Long = System.currentTimeMillis(),
    val lastUsedTime: Long = System.currentTimeMillis(),
    val signCount: Int = 0,
    /**
     * Which password this passkey belongs to: null/blank to match automatically, a password's
     * syncId to pin it there, or [PASSKEY_LINK_DETACHED] to keep it standalone. Decode it via
     * `PasskeyLink` rather than reading it directly. Holds the syncId and not the row id because
     * ids are per-device and the vault syncs.
     */
    val linkedPasswordSyncId: String? = null,
    val syncId: String = newSyncId(),
    val updatedAt: Long = System.currentTimeMillis(),
) : DatabaseItem {
    /**
     * Full-field equality with content (not referential) comparison of
     * [privateKeyBytes]. `viewModel.passkeys` is a StateFlow, which conflates by
     * equality: leaving a field out means a change to that field alone produces an
     * equal list and the emission is dropped, so the UI silently keeps showing
     * stale data (e.g. `signAssertion` persists only a `signCount`/`lastUsedTime`
     * bump, and the "last used" card would never update).
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Passkey) return false
        return id == other.id &&
            rpId == other.rpId &&
            rpName == other.rpName &&
            credentialId == other.credentialId &&
            userId == other.userId &&
            userName == other.userName &&
            userDisplayName == other.userDisplayName &&
            privateKeyBytes.contentEquals(other.privateKeyBytes) &&
            creationTime == other.creationTime &&
            lastUsedTime == other.lastUsedTime &&
            signCount == other.signCount &&
            linkedPasswordSyncId == other.linkedPasswordSyncId &&
            syncId == other.syncId &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + rpId.hashCode()
        result = 31 * result + rpName.hashCode()
        result = 31 * result + credentialId.hashCode()
        result = 31 * result + userId.hashCode()
        result = 31 * result + userName.hashCode()
        result = 31 * result + userDisplayName.hashCode()
        result = 31 * result + privateKeyBytes.contentHashCode()
        result = 31 * result + creationTime.hashCode()
        result = 31 * result + lastUsedTime.hashCode()
        result = 31 * result + signCount
        result = 31 * result + linkedPasswordSyncId.hashCode()
        result = 31 * result + syncId.hashCode()
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}
