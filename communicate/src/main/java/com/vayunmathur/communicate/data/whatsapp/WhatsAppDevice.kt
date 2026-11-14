package com.vayunmathur.communicate.data.whatsapp

import androidx.room3.Dao
import androidx.room3.Entity
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.PrimaryKey
import androidx.room3.Query

/**
 * Device information for WhatsApp Web companion.
 * Stores per-device metadata for multi-device support.
 */
@Entity(tableName = "whatsapp_devices")
data class WhatsAppDevice(
    @PrimaryKey
    val deviceId: String,
    val phoneNumber: String,
    val pushName: String,
    val platform: String,
    val lastSeen: Long,
    val jid: String = "", // Full JID including device suffix
    // From Go UserLoginMetadata
    val timezone: String = "",
    val loggedInAt: Long = 0L,
    val phoneLastSeen: Long = 0L,
    val phoneLastPinged: Long = 0L,
    val businessName: String = "",
)

/**
 * Signal Protocol session data for E2E encryption.
 * Each peer device gets its own session record.
 * Based on whatsmeow's signal session store.
 */
@Entity(tableName = "whatsapp_sessions")
data class WhatsAppSession(
    @PrimaryKey
    val jid: String, // e.g., "1234567890:0@s.whatsapp.net" (includes device ID)
    val sessionData: ByteArray, // Serialized Signal session record
    val timestamp: Long,
    val isPreKey: Boolean = false, // Whether this session was established via pre-key
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as WhatsAppSession
        if (jid != other.jid) return false
        if (!sessionData.contentEquals(other.sessionData)) return false
        return timestamp == other.timestamp
    }

    override fun hashCode(): Int {
        var result = jid.hashCode()
        result = 31 * result + sessionData.contentHashCode()
        result = 31 * result + timestamp.hashCode()
        return result
    }
}

/** Queries for linked WhatsApp devices. */
@Dao
interface WhatsAppDeviceDao {
    @Query("SELECT * FROM whatsapp_devices WHERE deviceId = :deviceId")
    suspend fun getDevice(deviceId: String): WhatsAppDevice?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDevice(device: WhatsAppDevice)

    @Query("DELETE FROM whatsapp_devices WHERE deviceId = :deviceId")
    suspend fun deleteDevice(deviceId: String)
}

/** Queries for stored WhatsApp protocol sessions. */
@Dao
interface WhatsAppSessionDao {
    @Query("SELECT * FROM whatsapp_sessions WHERE jid = :jid")
    suspend fun getSession(jid: String): WhatsAppSession?

    @Query("SELECT EXISTS(SELECT 1 FROM whatsapp_sessions WHERE jid = :jid)")
    suspend fun containsSession(jid: String): Boolean

    @Query("SELECT * FROM whatsapp_sessions")
    suspend fun getAllSessions(): List<WhatsAppSession>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: WhatsAppSession)

    @Query("DELETE FROM whatsapp_sessions WHERE jid = :jid")
    suspend fun deleteSession(jid: String)

    @Query("DELETE FROM whatsapp_sessions")
    suspend fun clearAllSessions()
}

/**
 * Message metadata matching Go's waid.MessageMetadata.
 * Stored as JSON alongside messages.
 */
data class MessageMetadata(
    val senderDeviceId: Int = 0,
    val error: MessageErrorType = MessageErrorType.NO_ERROR,
    val broadcastListJid: String? = null,
    val isMatrixPoll: Boolean = false,
    val edits: List<String> = emptyList(),
)

/** Why a WhatsApp message could not be processed (decryption, media, …). */
enum class MessageErrorType {
    NO_ERROR,
    DECRYPTION_FAILED,
    MEDIA_NOT_FOUND,
    UNDECRYPTABLE,
}

/**
 * Portal metadata matching Go's waid.PortalMetadata.
 */
data class PortalMetadata(
    val disappearingTimerSetAt: Long = 0L,
    val topicId: String = "",
    val lastSync: Long = 0L,
    val communityAnnouncementGroup: Boolean = false,
    val addressingMode: String = "",
)

/**
 * Ghost metadata matching Go's waid.GhostMetadata.
 */
data class GhostMetadata(
    val lastSync: Long = 0L,
)

/**
 * Reaction metadata matching Go's waid.ReactionMetadata.
 */
data class ReactionMetadata(
    val senderDeviceId: Int = 0,
)

/**
 * Group invite metadata matching Go's waid.GroupInviteMeta.
 */
data class GroupInviteMeta(
    val jid: String,
    val code: String,
    val expiration: Long,
    val inviter: String,
    val groupName: String,
    val isParentGroup: Boolean = false,
)
