package com.vayunmathur.openassistant.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// For streaming
@Serializable
data class GrokChunk(
    val id: String,
    val `object`: String,
    val created: Long,
    val model: String,
    val choices: List<ChunkChoice>
)

@Serializable
data class ChunkChoice(
    val index: Int,
    val delta: Delta
)

@Serializable
data class Delta(
    val role: String? = null,
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<ToolCall>? = null,
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String,
    val function: Function
) {
    @Serializable
    data class Function(
        val name: String,
        val arguments: String
    )
}
