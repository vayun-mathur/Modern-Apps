package com.vayunmathur.openassistant.domain

/** One model round: generate after [continuation] inside the open model turn. */
fun interface ChatGenerator {
    /** [onText] gets the cumulative raw reply; returning false stops. Null means the model failed. */
    suspend fun generate(continuation: String, onText: (String) -> Boolean): String?
}

/** Contract for tool loop outcome. */
sealed interface ToolLoopOutcome {
    val text: String

    /** Immutable value describing finished; carries text. */
    data class Finished(override val text: String) : ToolLoopOutcome
    /** Immutable value describing halted; carries text. */
    data class Halted(override val text: String) : ToolLoopOutcome
    /** Immutable value describing failed; carries text. */
    data class Failed(override val text: String) : ToolLoopOutcome
    /** A WRITE_CONFIRM call is pending host confirmation; it was not executed. */
    data class PendingConfirmation(
        override val text: String,
        val callName: String,
        val pendingId: String,
    ) : ToolLoopOutcome
}

const val MAX_TOOL_ROUNDS = 6

/**
 * Runs generate -> tool call -> tool response rounds until the model answers in prose.
 *
 * Each call and its response are appended to the continuation so they stay inside the open
 * model turn. Malformed calls get an error response rather than ending the turn. [onVisible]
 * receives the user-visible text so far (markup stripped); returning false halts.
 */
suspend fun runToolLoop(
    generator: ChatGenerator,
    parseCall: (String) -> ParsedToolCall?,
    executeTool: suspend (ParsedToolCall) -> String,
    renderToolResponse: (name: String, value: String) -> String,
    maxRounds: Int = MAX_TOOL_ROUNDS,
    pendingFor: (ParsedToolCall) -> PendingToolCall? = { null },
    onVisible: (String) -> Boolean,
): ToolLoopOutcome {
    var continuation = ""
    var visible = ""
    for (round in 0 until maxRounds) {
        var halted = false
        val reply = generator.generate(continuation) { partial ->
            if (!onVisible(joinVisible(visible, stripToolMarkup(partial)))) {
                halted = true
                false
            } else {
                !hasCompleteToolCall(partial)
            }
        } ?: return ToolLoopOutcome.Failed(visible)
        visible = joinVisible(visible, stripToolMarkup(reply, streaming = false))
        if (halted) return ToolLoopOutcome.Halted(visible)
        if (!hasCompleteToolCall(reply) || round == maxRounds - 1) break
        val call = parseCall(reply)
        if (call != null) {
            pendingFor(call)?.let { pending ->
                return ToolLoopOutcome.PendingConfirmation(visible, call.name, pending.id)
            }
        }
        val (name, result) = if (call == null) {
            toolCallNameGuess(reply) to "Error: malformed tool call; use name{arg:value}"
        } else {
            call.name to executeTool(call)
        }
        continuation += reply.substringBefore(TOOL_CALL_CLOSE) + TOOL_CALL_CLOSE +
            renderToolResponse(name, result)
    }
    return ToolLoopOutcome.Finished(visible)
}

/** Prose from successive rounds, separated by a blank line. */
fun joinVisible(before: String, after: String): String = when {
    before.isBlank() -> after.trimStart()
    after.isBlank() -> before
    else -> before.trimEnd() + "\n\n" + after.trimStart()
}
