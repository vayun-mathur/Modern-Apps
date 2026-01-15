package com.vayunmathur.openassistant.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.vayunmathur.openassistant.GrokRequest
import com.vayunmathur.openassistant.ImageUrl
import com.vayunmathur.openassistant.ImageUrlContent
import com.vayunmathur.openassistant.TextContent
import com.vayunmathur.openassistant.ToolCall

@Entity(
    tableName = "messages",
    foreignKeys = [ForeignKey(
        entity = Conversation::class,
        parentColumns = ["id"],
        childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["conversationId"])]
)
@TypeConverters(Converters::class)
data class Message(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val conversationId: Long, // Foreign key to Conversation
    val role: String,
    val textContent: String,
    val displayContent: String? = null, // if available, show instead of text content
    val images: List<String>,
    val toolCallId: String? = null,
    val toolCalls: List<ToolCall> = listOf(),
    val timestamp: Long = System.currentTimeMillis()
)

fun Message.toGrokMessage(): GrokRequest.Message {
    return GrokRequest.Message(
        role = role,
        content = listOf(TextContent(textContent), *images.map { ImageUrlContent(ImageUrl("data:image/jpeg;base64,$it")) }.toTypedArray()),
        toolCalls = toolCalls,
        toolCallId = toolCallId
    )
}