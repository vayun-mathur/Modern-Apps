package com.vayunmathur.messages.whatsapp

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Device information for WhatsApp Web companion.
 */
@Entity(tableName = "whatsapp_devices")
data class WhatsAppDevice(
    @PrimaryKey
    val deviceId: String,
    val phoneNumber: String,
    val pushName: String,
    val platform: String,
    val lastSeen: Long,
)

/**
 * Session keys for Noise protocol.
 */
@Entity(tableName = "whatsapp_sessions")
data class WhatsAppSession(
    @PrimaryKey
    val jid: String, // e.g., "1234567890@s.whatsapp.net"
    val sessionData: ByteArray,
    val timestamp: Long,
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

@Dao
interface WhatsAppDeviceDao {
    @Query("SELECT * FROM whatsapp_devices WHERE deviceId = :deviceId")
    suspend fun getDevice(deviceId: String): WhatsAppDevice?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDevice(device: WhatsAppDevice)

    @Query("DELETE FROM whatsapp_devices WHERE deviceId = :deviceId")
    suspend fun deleteDevice(deviceId: String)
}

@Dao
interface WhatsAppSessionDao {
    @Query("SELECT * FROM whatsapp_sessions WHERE jid = :jid")
    suspend fun getSession(jid: String): WhatsAppSession?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: WhatsAppSession)

    @Query("DELETE FROM whatsapp_sessions WHERE jid = :jid")
    suspend fun deleteSession(jid: String)

    @Query("DELETE FROM whatsapp_sessions")
    suspend fun clearAllSessions()
}
