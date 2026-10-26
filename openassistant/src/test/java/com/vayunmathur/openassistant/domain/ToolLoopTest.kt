package com.vayunmathur.openassistant.domain

import com.vayunmathur.library.ml.parseGemma4ToolCall
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ToolLoopTest {

    private class FakeGenerator(private val replies: List<String>) : ChatGenerator {
        val continuations = mutableListOf<String>()
        override suspend fun generate(continuation: String, onText: (String) -> Boolean): String? {
            continuations += continuation
            val reply = replies.getOrNull(continuations.size - 1) ?: return null
            var out = ""
            for (ch in reply) {
                out += ch
                if (!onText(out)) return out
            }
            return out
        }
    }

    private val parse = { reply: String -> parseGemma4ToolCall(reply)?.let { ParsedToolCall(it.name, it.arguments) } }

    private val render = { name: String, value: String -> "<R:$name=$value>" }

    @Test
    fun callThenTextRunsTheToolAndContinuesInsideTheTurn() = runBlocking<Unit> {
        val generator = FakeGenerator(
            listOf("<|tool_call>call:set_timer{seconds:60}<tool_call|><|tool_response>", "Timer set."),
        )
        val calls = mutableListOf<ParsedToolCall>()
        val shown = mutableListOf<String>()
        val outcome = runToolLoop(generator, parse, { calls += it; "ok" }, render) { shown += it; true }
        assertEquals(ToolLoopOutcome.Finished("Timer set."), outcome)
        assertEquals(listOf(ParsedToolCall("set_timer", mapOf("seconds" to "60"))), calls)
        assertEquals("<|tool_call>call:set_timer{seconds:60}<tool_call|><R:set_timer=ok>", generator.continuations[1])
        assertTrue(shown.none { "<" in it })
    }

    @Test
    fun stopsGeneratingAsSoonAsACallCloses() = runBlocking<Unit> {
        val generator = FakeGenerator(listOf("<|tool_call>call:get_notes{}<tool_call|> trailing junk", "done"))
        runToolLoop(generator, parse, { "[]" }, render) { true }
        assertEquals("<|tool_call>call:get_notes{}<tool_call|><R:get_notes=[]>", generator.continuations[1])
    }

    @Test
    fun roundCapEndsTheTurn() = runBlocking<Unit> {
        val call = "<|tool_call>call:get_notes{}<tool_call|>"
        val generator = FakeGenerator(List(10) { call })
        var executed = 0
        val outcome = runToolLoop(generator, parse, { executed++; "x" }, render, maxRounds = 6) { true }
        assertIs<ToolLoopOutcome.Finished>(outcome)
        assertEquals(6, generator.continuations.size)
        assertEquals(5, executed)
    }

    @Test
    fun malformedCallGetsAnErrorResponseNotACrash() = runBlocking<Unit> {
        val generator = FakeGenerator(listOf("<|tool_call>call:oops<tool_call|>", "Sorry."))
        var executed = 0
        val outcome = runToolLoop(generator, parse, { executed++; "x" }, render) { true }
        assertEquals(ToolLoopOutcome.Finished("Sorry."), outcome)
        assertEquals(0, executed)
        assertTrue(generator.continuations[1].contains("<R:oops=Error: malformed tool call"))
    }

    @Test
    fun haltStopsTheLoop() = runBlocking<Unit> {
        val generator = FakeGenerator(listOf("Hello there", "unused"))
        val outcome = runToolLoop(generator, parse, { "x" }, render) { it.length < 5 }
        assertEquals(ToolLoopOutcome.Halted("Hello"), outcome)
        assertEquals(1, generator.continuations.size)
    }

    @Test
    fun proseFromEveryRoundIsKept() = runBlocking<Unit> {
        val generator = FakeGenerator(listOf("Checking.<|tool_call>call:get_notes{}<tool_call|>", "You have none."))
        val outcome = runToolLoop(generator, parse, { "[]" }, render) { true }
        assertEquals(ToolLoopOutcome.Finished("Checking.\n\nYou have none."), outcome)
    }

    @Test
    fun generatorFailureIsReported() = runBlocking<Unit> {
        val outcome = runToolLoop(FakeGenerator(emptyList()), parse, { "x" }, render) { true }
        assertIs<ToolLoopOutcome.Failed>(outcome)
    }
}
