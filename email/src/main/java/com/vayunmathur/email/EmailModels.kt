package com.vayunmathur.email

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(primaryKeys = ["accountEmail", "fullName"])
data class EmailFolder(
    val accountEmail: String,
    val fullName: String,
    val name: String,
    val parentFullName: String? = null,
    val holdsMessages: Boolean = true,
    val delimiter: String = "/"
)

@Serializable
@Entity(primaryKeys = ["accountEmail", "folderName", "id"])
data class EmailMessage(
    val accountEmail: String,
    val folderName: String,
    val id: Long, // IMAP UID
    val serverId: String? = null, // Message-ID header
    val threadId: String? = null, // Gmail Thread ID or custom grouping
    val subject: String,
    val from: String,
    val to: String? = null,
    val cc: String? = null,
    val date: String,
    val body: String? = null,
    val isHtml: Boolean = false,
    val isRead: Boolean = false,
    val references: String? = null, // References/In-Reply-To for threading
    val hasAttachments: Boolean = false
)

@Serializable
@Entity(primaryKeys = ["accountEmail", "messageId", "partId"])
data class Attachment(
    val accountEmail: String,
    val folderName: String,
    val messageId: Long, // IMAP UID
    val partId: String,
    val fileName: String,
    val mimeType: String,
    val size: Long,
    val localUri: String? = null
)

@Serializable
@Entity
data class EmailAccount(
    @PrimaryKey val email: String,
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAt: Long = 0
) {
    fun getColor(): Long {
        val colors = listOf(
            0xFFF44336, // Red
            0xFFE91E63, // Pink
            0xFF9C27B0, // Purple
            0xFF673AB7, // Deep Purple
            0xFF3F51B5, // Indigo
            0xFF2196F3, // Blue
            0xFF03A9F4, // Light Blue
            0xFF00BCD4, // Cyan
            0xFF009688, // Teal
            0xFF4CAF50, // Green
            0xFF8BC34A, // Light Green
            0xFFCDDC39, // Lime
            0xFFFFEB3B, // Yellow
            0xFFFFC107, // Amber
            0xFFFF9800, // Orange
            0xFFFF5722, // Deep Orange
            0xFF795548, // Brown
            0xFF9E9E9E, // Grey
            0xFF607D8B  // Blue Grey
        )
        val hash = email.hashCode()
        return colors[Math.abs(hash) % colors.size]
    }
}
