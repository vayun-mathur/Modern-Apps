package com.vayunmathur.openassistant.domain

/** A tool call the model emitted, with every argument still as its raw text. */
data class ParsedToolCall(val name: String, val arguments: Map<String, String>)

enum class ToolParamType(val gemmaName: String) {
    STRING("STRING"),
    NUMBER("NUMBER"),
}

data class ToolParamSpec(
    val name: String,
    val description: String,
    val type: ToolParamType,
    val required: Boolean = true,
)

data class ToolSpec(
    val name: String,
    val description: String,
    val params: List<ToolParamSpec> = emptyList(),
)

/** Coerced arguments: strings stay strings, numbers are Doubles as LiteRT passed them. */
class ToolArgs(private val values: Map<String, Any>) {
    fun string(name: String, default: String = ""): String = values[name] as? String ?: default
    fun number(name: String): Double = values[name] as? Double ?: 0.0
}

sealed interface ToolArgsResult {
    data class Ok(val args: ToolArgs) : ToolArgsResult
    data class Invalid(val message: String) : ToolArgsResult
}

fun coerceToolArgs(spec: ToolSpec, raw: Map<String, String>): ToolArgsResult {
    val values = HashMap<String, Any>()
    for (param in spec.params) {
        val value = raw[param.name]?.takeUnless { param.type == ToolParamType.NUMBER && it.isBlank() }
        if (value == null) {
            if (param.required) return ToolArgsResult.Invalid("Error: ${spec.name} needs ${param.name}")
            continue
        }
        values[param.name] = when (param.type) {
            ToolParamType.STRING -> value
            ToolParamType.NUMBER -> value.trim().toDoubleOrNull()
                ?: return ToolArgsResult.Invalid("Error: ${param.name} must be a number, got '$value'")
        }
    }
    return ToolArgsResult.Ok(ToolArgs(values))
}

const val TOOL_CALL_OPEN = "<|tool_call>"
const val TOOL_CALL_CLOSE = "<tool_call|>"
private const val CALL_PREFIX = "call:"
private const val QUOTE = "<|\"|>"

private val MARKUP_BLOCKS = listOf(
    TOOL_CALL_OPEN to TOOL_CALL_CLOSE,
    "<|tool_response>" to "<tool_response|>",
    "<|tool>" to "<tool|>",
)
private val PARTIAL_CANDIDATES = MARKUP_BLOCKS.flatMap { listOf(it.first, it.second) } +
    listOf("<|turn>", "<turn|>", QUOTE)
private val LONGEST_MARKER = PARTIAL_CANDIDATES.maxOf { it.length }

/** Whether [reply] holds a tool call whose closing marker has arrived. */
fun hasCompleteToolCall(reply: String): Boolean {
    val open = reply.indexOf(TOOL_CALL_OPEN)
    return open >= 0 && reply.indexOf(TOOL_CALL_CLOSE, open) >= 0
}

/** The raw text between the first complete pair of tool-call markers, or null. */
fun toolCallBody(reply: String): String? {
    val open = reply.indexOf(TOOL_CALL_OPEN)
    if (open < 0) return null
    val close = reply.indexOf(TOOL_CALL_CLOSE, open)
    if (close < 0) return null
    return reply.substring(open + TOOL_CALL_OPEN.length, close)
}

/**
 * The first complete call in [reply], or null when there is none or it is malformed.
 *
 * Accepts both `<|"|>`-quoted values and bare ones: Gemma writes numbers unquoted
 * (`seconds:120`), which a quoted-only parser silently drops.
 */
fun parseToolCall(reply: String): ParsedToolCall? {
    val body = toolCallBody(reply)?.trim()?.removePrefix(CALL_PREFIX) ?: return null
    val brace = body.indexOf('{')
    val end = body.lastIndexOf('}')
    if (brace <= 0 || end < brace) return null
    val name = body.substring(0, brace).trim()
    if (name.isEmpty()) return null
    val arguments = parseArguments(body.substring(brace + 1, end)) ?: return null
    return ParsedToolCall(name, arguments)
}

/** Best-effort tool name of a malformed call, for addressing the error response. */
fun toolCallNameGuess(reply: String): String =
    toolCallBody(reply)?.trim()?.removePrefix(CALL_PREFIX)?.substringBefore('{')?.trim()
        ?.takeIf { it.isNotEmpty() } ?: "unknown"

private fun parseArguments(body: String): Map<String, String>? {
    val out = LinkedHashMap<String, String>()
    var at = 0
    while (at < body.length) {
        while (at < body.length && (body[at] == ',' || body[at].isWhitespace())) at++
        if (at >= body.length) break
        val colon = body.indexOf(':', at)
        if (colon < 0) return null
        val key = body.substring(at, colon).trim().trim('"')
        if (key.isEmpty()) return null
        var value = colon + 1
        while (value < body.length && body[value].isWhitespace()) value++
        if (body.startsWith(QUOTE, value)) {
            val close = body.indexOf(QUOTE, value + QUOTE.length)
            if (close < 0) return null
            out[key] = body.substring(value + QUOTE.length, close)
            at = close + QUOTE.length
        } else {
            var close = value
            while (close < body.length && body[close] != ',') close++
            out[key] = body.substring(value, close).trim().trim('"')
            at = close
        }
    }
    return out
}

/**
 * [text] with tool markup removed, for display.
 *
 * Complete blocks are cut out; an unclosed block drops everything after its opener. With
 * [streaming], a trailing fragment that could still grow into a marker (`<|too`) is hidden too.
 */
fun stripToolMarkup(text: String, streaming: Boolean = true): String {
    val out = StringBuilder()
    var at = 0
    while (at < text.length) {
        val next = MARKUP_BLOCKS
            .map { (open, close) -> Triple(text.indexOf(open, at), open, close) }
            .filter { it.first >= 0 }
            .minByOrNull { it.first }
        if (next == null) {
            out.append(text, at, text.length)
            break
        }
        val (open, opener, closer) = next
        out.append(text, at, open)
        val close = text.indexOf(closer, open + opener.length)
        if (close < 0) {
            at = text.length
            break
        }
        at = close + closer.length
    }
    if (streaming) trimPartialMarker(out)
    return out.toString()
}

private fun trimPartialMarker(out: StringBuilder) {
    val lt = out.lastIndexOf("<")
    if (lt < 0 || out.length - lt >= LONGEST_MARKER) return
    val tail = out.substring(lt)
    if (PARTIAL_CANDIDATES.any { it.startsWith(tail) }) out.setLength(lt)
}
