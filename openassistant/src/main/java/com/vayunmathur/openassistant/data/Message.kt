package com.vayunmathur.openassistant.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.vayunmathur.library.util.DatabaseItem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

@Serializable
data class ToolCall(
    val name: String,
    val parameters: Map<String, JsonElement>
)


@Entity(
    foreignKeys = [ForeignKey(
        entity = Conversation::class,
        parentColumns = ["id"],
        childColumns = ["conversationId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index(value = ["conversationId"])]
)
data class Message(
    @PrimaryKey(autoGenerate = true)
    override val id: Long = 0,
    val conversationId: Long, // Foreign key to Conversation
    val role: String,
    val textContent: String,
    val displayContent: String? = null, // if available, show instead of text content
    val images: List<String>,
    val toolCallId: String? = null,
    val toolCalls: List<ToolCall> = listOf(),
    val timestamp: Long = System.currentTimeMillis()
): DatabaseItem

fun List<Message>.toStreamedText(): String {
    val builder = StringBuilder()

    // Qwen uses ChatML format
    // Note: Qwen typically doesn't use a "begin_of_text" string like Llama's 128000 token

    // 1. System Prompt
    val toolSystemPrompt = """
        You are an expert in composing functions. You are given a question and a set of possible functions. 
        Based on the question, you will need to make one or more function/tool calls to achieve the purpose. 
        If none of the function can be used, point it out. If the given question lacks the parameters required by the function,
        also point it out. You should only return the function call in tools call sections.

        If you decide to invoke any of the function(s), you MUST put it in the format of [{"name": "function_name", "parameters": {"key": "value"}}] an array of json objects, each representing one function call\n
        You SHOULD NOT include any other text in the response.

        Here is a list of functions in JSON format that you can invoke.
        ${Tools.ALL_TOOLS.joinToString("\n") { it.systemDescription() }}
    """.trimIndent()

    builder.append("<|im_start|>system\n")
    builder.append(toolSystemPrompt)
    builder.append("<|im_end|>\n")

    this.forEach { message ->
        val role = message.role // e.g., "user" or "assistant"
        builder.append("<|im_start|>$role\n")

        if (message.toolCalls.isNotEmpty()) {
            // Qwen 2.5/3 performs better with pure JSON objects for tool calls
            val calls = message.toolCalls.joinToString("\n") { call ->
                // Constructing a simple JSON string manually or via a serializer
                """{"name": "${call.name}", "parameters": ${call.parameters}}"""
            }
            builder.append(calls)
        } else {
            builder.append(message.textContent.trim())
        }

        builder.append("<|im_end|>\n")
    }

    // Trigger the assistant's turn
    if (this.lastOrNull()?.role != "assistant") {
        builder.append("<|im_start|>assistant\n")
    }

    return builder.toString()
}