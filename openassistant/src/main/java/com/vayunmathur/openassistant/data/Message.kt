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
    val arguments: Map<String, JsonElement>
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

fun List<Message>.toStreamedText(tools: List<ToolSimple> = Tools.ALL_TOOLS): String {
    val builder = StringBuilder()

    // 1. System Prompt & Tool Header
    builder.append("<|im_start|>system\n")

    val toolSystemPrompt = """
        You are an expert in composing functions. You are given a question and a set of possible functions. 
        Based on the question, you will need to make one or more function/tool calls to achieve the purpose. 
        If none of the function can be used, point it out. If the given question lacks the parameters required by the function,
        also point it out. You should only return the function call in tools call sections.

        If you decide to invoke any of the function(s), you MUST put it in the format:
        <tool_call>
        {"name": "function_name", "arguments": {"key": "value"}}
        </tool_call>

        Here is a list of functions in JSON format that you can invoke.
        ${tools.joinToString("\n") { it.systemDescription() }}
    """.trimIndent()

    builder.append(toolSystemPrompt)

    // Qwen 3 XML Tool Schema Section
    if (tools.isNotEmpty()) {
        builder.append("\n\n# Tools\n\nYou may call one or more functions to assist with the user query.\n\n")
        builder.append("You are provided with function signatures within <tools></tools> XML tags:\n<tools>")
        tools.forEach { tool ->
            builder.append("\n").append(tool.systemDescription())
        }
        builder.append("\n</tools>\n\n")
        builder.append("For each function call, return a json object with function name and arguments within <tool_call></tool_call> XML tags:\n")
        builder.append("<tool_call>\n{\"name\": <function-name>, \"arguments\": <args-json-object>}\n</tool_call>")
    }
    builder.append("<|im_end|>\n")

    // 2. Message History
    this.forEachIndexed { index, message ->
        if (index == 0 && message.role == "system") return@forEachIndexed

        when (message.role) {
            "tool" -> {
                val prevRole = this.getOrNull(index - 1)?.role
                if (index == 0 || prevRole != "tool") {
                    builder.append("<|im_start|>user")
                }
                builder.append("\n<tool_response>\n").append(message.textContent.trim()).append("\n</tool_response>")

                val nextRole = this.getOrNull(index + 1)?.role
                if (nextRole != "tool") {
                    builder.append("<|im_end|>\n")
                }
            }
            "assistant" -> {
                builder.append("<|im_start|>assistant\n")

//                if (message.reasoningContent != null) {
//                    builder.append("<think>\n").append(message.reasoningContent.trim()).append("\n</think>\n\n")
//                }

                if (message.textContent.isNotEmpty()) {
                    builder.append(message.textContent.trim())
                }

                // Strictly following the <tool_call> format
                if (message.toolCalls.isNotEmpty()) {
                    message.toolCalls.forEach { call ->
                        builder.append("\n<tool_call>\n")
                        builder.append("""{"name": "${call.name}", "arguments": ${call.arguments}}""")
                        builder.append("\n</tool_call>")
                    }
                }
                builder.append("<|im_end|>\n")
            }
            else -> {
                builder.append("<|im_start|>${message.role}\n")
                builder.append(message.textContent.trim())
                builder.append("<|im_end|>\n")
            }
        }
    }

    // 3. Trigger Assistant Turn
    if (this.lastOrNull()?.role != "assistant") {
        builder.append("<|im_start|>assistant\n")
        builder.append("<think>\n\n</think>\n\n")
    }

    return builder.toString()
}