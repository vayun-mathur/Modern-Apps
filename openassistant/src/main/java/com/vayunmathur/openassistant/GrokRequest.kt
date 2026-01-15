package com.vayunmathur.openassistant

import com.vayunmathur.openassistant.data.Tool
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface ContentPart

@Serializable
@SerialName("text")
data class TextContent(val text: String) : ContentPart

@Serializable
@SerialName("image_url")
data class ImageUrlContent(
    @SerialName("image_url")
    val imageUrl: ImageUrl,
) : ContentPart

@Serializable
data class ImageUrl(
    val url: String,
    val detail: String = "high"
)

@Serializable
data class GrokRequest(
    val messages: List<Message>,
    val model: String,
    val stream: Boolean,
    val temperature: Double,
    val tools: List<Tool>? = null
) {
    @Serializable
    data class Message(
        val role: String,
        val content: List<ContentPart>,
        @SerialName("tool_calls")
        val toolCalls: List<ToolCall>? = null,
        @SerialName("tool_call_id")
        val toolCallId: String? = null
    )
}