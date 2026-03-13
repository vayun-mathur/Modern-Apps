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

    // Llama 3.1 start token
    builder.append("<|begin_of_text|>")

    // 1. Updated System Prompt to match your specific instructions
    val toolSystemPrompt = """
        You are an expert in composing functions. You are given a question and a set of possible functions. 
        Based on the question, you will need to make one or more function/tool calls to achieve the purpose. 
        If none of the functions can be used, point it out. If the given question lacks the parameters required by the function,also point it out. You should only return the function call in tools call sections.
        If you decide to invoke any of the function(s), you MUST put it in the format of [{name: "get_weather", parameters: {latitude: 40.7128, longitude: -74.0060}}]
        You SHOULD NOT include any other text in the response.
        Here is a description of the functions in JSON format that you can invoke.
        [
            ${Tools.ALL_TOOLS.joinToString(",\n") { it.systemDescription() }}
        ]
        DO NOT call functions unless ABSOLUTELY NECESSARY. You should usually just respond in natural language to the user.
    """.trimIndent()

    builder.append("<|start_header_id|>system<|end_header_id|>\n\n")
    builder.append(toolSystemPrompt)
    builder.append("<|eot_id|>")

    this.forEach { message ->
        val role = message.role
        builder.append("<|start_header_id|>$role<|end_header_id|>\n\n")

        // Handle Tool Calls (Formatting as [name(args)])
        if (message.toolCalls.isNotEmpty()) {
            val calls = message.toolCalls.joinToString(", ") { call ->
                val params = call.parameters.entries.joinToString(", ") { "${it.key}=${it.value}" }
                "${call.name}($params)"
            }
            builder.append("[$calls]")
        }
        // Handle Tool Outputs or Text Content
        else {
            builder.append(message.textContent.trim())
        }

        builder.append("<|eot_id|>")
    }

    // Preparation for the model's next response
    if (this.lastOrNull()?.role != "assistant") {
        builder.append("<|start_header_id|>assistant<|end_header_id|>\n\n")
    }

    return builder.toString()
}