package com.vayunmathur.library.ml

/**
 * Gemma's chat template and tool protocol, as pure functions over
 * [Gemma4Handle.Turn] and [Gemma4Handle.ToolDeclaration].
 *
 * Split out of [Gemma4Handle]'s companion so the handle file stays under the
 * FileLength limit. The companion keeps thin delegating wrappers, so every
 * existing `Gemma4Handle.render(...)` / `declareTools(...)` /
 * `parseToolCall(...)` / `renderToolResponse(...)` call site keeps working.
 */

/**
 * A conversation as the prompt string Gemma was trained on.
 *
 * Ported from `chat_template.jinja` by rendering it and matching the output exactly,
 * rather than by reading the Jinja - the macros are dense and the quoting is unusual, and
 * a template that is nearly right degrades quality without failing. The shape is:
 *
 * ```text
 * <bos><|turn>system\n{system}{tools}<turn|>\n<|turn>user\n{text}<turn|>\n<|turn>model\n
 * ```
 *
 * The system turn is emitted only when there is a system prompt or a tool to declare,
 * matching the template's own conditional.
 */
fun renderGemma4Prompt(
    conversation: List<Gemma4Handle.Turn>,
    system: String?,
    tools: List<Gemma4Handle.ToolDeclaration> = emptyList(),
    continuation: String = "",
): String = buildString {
    append("<bos>")
    val declared = declareGemma4Tools(tools)
    if (!system.isNullOrBlank() || declared.isNotEmpty()) {
        append("<|turn>system\n")
        if (!system.isNullOrBlank()) append(system)
        append(declared)
        append("<turn|>\n")
    }
    for (turn in conversation) {
        append("<|turn>").append(turn.role.marker).append('\n')
        append(turn.text)
        append("<turn|>\n")
    }
    // The generation prompt: an open model turn for the model to complete.
    append("<|turn>model\n")
    // A tool call and its result belong **inside** that turn, not before it.
    //
    // Appending them as a finished model turn instead - `<|turn>model ... <turn|>` and
    // then a fresh `<|turn>model` - was a real bug and a subtle one: the model saw its
    // own turn ended and a new one beginning, which from its point of view is the start
    // of a conversation, so it opened with a greeting instead of answering. It only
    // showed on the first message, because that is the one the system prompt forces a
    // tool call on.
    append(continuation)
}

/**
 * Tool declarations in the template's own syntax.
 *
 * Not JSON. Gemma's template writes `<|tool>declaration:name{...}<tool|>` with values
 * wrapped in the `<|"|>` marker rather than in quotation marks, so that a description
 * containing a quote cannot break the parse. Reproduced exactly, including the
 * alphabetical ordering of parameters that `dictsort` imposes - the model saw them in
 * that order during training.
 */
fun declareGemma4Tools(tools: List<Gemma4Handle.ToolDeclaration>): String = buildString {
    for (tool in tools) {
        append("<|tool>declaration:").append(tool.name).append('{')
        append("description:").append(quotedGemma4(tool.description))
        append(",parameters:{properties:{")
        val sorted = tool.parameters.sortedBy { it.name }
        for ((index, parameter) in sorted.withIndex()) {
            if (index > 0) append(',')
            append(parameter.name).append(":{description:")
            append(quotedGemma4(parameter.description))
            append(",type:").append(quotedGemma4(parameter.type.uppercase()))
            append('}')
        }
        append("}")
        val required = sorted.filter { it.required }
        if (required.isNotEmpty()) {
            append(",required:[")
            append(required.joinToString(",") { quotedGemma4(it.name) })
            append(']')
        }
        append(",type:").append(quotedGemma4("OBJECT"))
        append("}}<tool|>")
    }
}

/**
 * The tool call in [reply], or null if there is not a complete one.
 *
 * The model emits `<|tool_call>call:name{arg:<|"|>value<|"|>}<tool_call|>`. Parsed rather
 * than pattern-matched loosely because a half-written call arrives during streaming and
 * must not be acted on: this returns null until the closing marker is present.
 */
fun parseGemma4ToolCall(reply: String): Gemma4Handle.ToolCall? {
    val body = toolCallBody(reply) ?: return null
    val brace = body.indexOf('{')
    if (brace < 0) return null
    val name = body.substring(0, brace).trim()
    if (name.isEmpty()) return null
    return Gemma4Handle.ToolCall(name, parseToolArguments(body, brace + 1))
}

/** The raw call body between the open/close markers, or null when incomplete. */
private fun toolCallBody(reply: String): String? {
    val open = reply.indexOf(TOOL_CALL_OPEN)
    if (open < 0) return null
    val close = reply.indexOf(TOOL_CALL_CLOSE, open)
    if (close < 0) return null
    return reply.substring(open + TOOL_CALL_OPEN.length, close)
}

/**
 * Parses `key:value,...` pairs from [body] starting at [at].
 *
 * Strings arrive as `<|"|>value<|"|>`, but the template writes numbers and booleans bare
 * (`seconds:120`), so a value without the quote marker runs to the next `,` or `}`.
 */
@Suppress("LoopWithTooManyJumpStatements")
private fun parseToolArguments(body: String, at: Int): Map<String, String> {
    val arguments = LinkedHashMap<String, String>()
    var cursor = at
    while (cursor < body.length) {
        while (cursor < body.length && (body[cursor] == ',' || body[cursor].isWhitespace())) cursor++
        val colon = body.indexOf(':', cursor)
        if (colon < 0) break
        val key = body.substring(cursor, colon).trim().trim(',', '{', '}')
        var valueStart = colon + 1
        while (valueStart < body.length && body[valueStart].isWhitespace()) valueStart++
        val value: String
        if (body.startsWith(GEMMA4_QUOTE, valueStart)) {
            val valueEnd = body.indexOf(GEMMA4_QUOTE, valueStart + GEMMA4_QUOTE.length)
            if (valueEnd < 0) break
            value = body.substring(valueStart + GEMMA4_QUOTE.length, valueEnd)
            cursor = valueEnd + GEMMA4_QUOTE.length
        } else {
            var valueEnd = valueStart
            while (valueEnd < body.length && body[valueEnd] != ',' && body[valueEnd] != '}') valueEnd++
            value = body.substring(valueStart, valueEnd).trim()
            cursor = valueEnd
        }
        if (key.isNotEmpty()) arguments[key] = value
        while (cursor < body.length && body[cursor] == '}') cursor++
    }
    return arguments
}

private const val TOOL_CALL_OPEN = "<|tool_call>call:"
private const val TOOL_CALL_CLOSE = "<tool_call|>"

/** A tool's result, in the shape the model expects to read back. */
fun renderGemma4ToolResponse(name: String, value: String): String =
    "<|tool_response>response:$name{value:${quotedGemma4(value)}}<tool_response|>"

/** Gemma's value delimiter, which is a token rather than a quotation mark. */
internal const val GEMMA4_QUOTE = "<|\"|>"

internal fun quotedGemma4(value: String): String = GEMMA4_QUOTE + value + GEMMA4_QUOTE
