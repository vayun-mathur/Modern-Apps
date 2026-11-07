package com.vayunmathur.library.ml

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Pure companion/template logic of Gemma 4: no handle construction, no native calls. */
class Gemma4PureTest {

    private fun turn(role: Gemma4Handle.Role, text: String) = Gemma4Handle.Turn(role, text)

    // --- render ----------------------------------------------------------------

    @Test
    fun `render without system or tools is bos turns and model open`() {
        val out = Gemma4Handle.render(
            listOf(turn(Gemma4Handle.Role.USER, "hello"), turn(Gemma4Handle.Role.MODEL, "hi there")),
            system = null,
        )
        assertEquals(
            "<bos><|turn>user\nhello<turn|>\n<|turn>model\nhi there<turn|>\n<|turn>model\n",
            out,
        )
    }

    @Test
    fun `render with system includes system turn and blank system behaves as none`() {
        val withSystem = Gemma4Handle.render(emptyList(), system = "Be brief.")
        assertTrue(withSystem.startsWith("<bos><|turn>system\nBe brief.<turn|>\n<|turn>model\n"), withSystem)
        val blankSystem = Gemma4Handle.render(emptyList(), system = "   ")
        assertFalse(blankSystem.contains("system"))
    }

    @Test
    fun `render continuation stays inside the open model turn`() {
        val out = Gemma4Handle.render(
            listOf(turn(Gemma4Handle.Role.USER, "q")),
            system = null,
            continuation = "<|tool_response>response:x{value:<|\"|>1<|\"|>}<tool_response|>",
        )
        assertTrue(out.endsWith("<|turn>model\n<|tool_response>response:x{value:<|\"|>1<|\"|>}<tool_response|>"), out)
    }

    @Test
    fun `render with tools emits system turn even without system text`() {
        val tool = Gemma4Handle.ToolDeclaration(
            "get_weather", "Weather lookup",
            listOf(Gemma4Handle.ToolDeclaration.Parameter("city", "City name", "string")),
        )
        val out = Gemma4Handle.render(emptyList(), system = null, tools = listOf(tool))
        assertTrue(out.contains("<|turn>system\n<|tool>declaration:get_weather"), out)
    }

    // --- declareTools -------------------------------------------------------------

    @Test
    fun `declare tools sorts parameters alphabetically and lists required`() {
        val tool = Gemma4Handle.ToolDeclaration(
            "search", "Search the web",
            listOf(
                Gemma4Handle.ToolDeclaration.Parameter("zeta", "last", "number", required = false),
                Gemma4Handle.ToolDeclaration.Parameter("alpha", "first", "string"),
                Gemma4Handle.ToolDeclaration.Parameter("mid", "middle", "boolean"),
            ),
        )
        val declared = Gemma4Handle.declareTools(listOf(tool))
        assertTrue(declared.indexOf("alpha:") < declared.indexOf("mid:"))
        assertTrue(declared.indexOf("mid:") < declared.indexOf("zeta:"))
        assertTrue(declared.contains("required:[<|\"|>alpha<|\"|>,<|\"|>mid<|\"|>]"), declared)
        assertTrue(declared.contains("type:<|\"|>OBJECT<|\"|>"), declared)
        assertTrue(declared.contains("type:<|\"|>STRING<|\"|>"), declared)
        assertTrue(declared.endsWith("}<tool|>"), declared)
    }

    @Test
    fun `declare tools with no parameters and no required omits required list`() {
        val tool = Gemma4Handle.ToolDeclaration("ping", "Ping it", emptyList())
        val declared = Gemma4Handle.declareTools(listOf(tool))
        assertTrue(declared.contains("parameters:{properties:{}"), declared)
        assertFalse(declared.contains("required"))
        assertEquals("", Gemma4Handle.declareTools(emptyList()))
    }

    @Test
    fun `top level declare and render wrappers match companion`() {
        val tools = listOf(Gemma4Handle.ToolDeclaration("t", "d", emptyList()))
        assertEquals(Gemma4Handle.declareTools(tools), declareGemma4Tools(tools))
        assertEquals(
            Gemma4Handle.render(listOf(turn(Gemma4Handle.Role.USER, "x")), "sys"),
            renderGemma4Prompt(listOf(turn(Gemma4Handle.Role.USER, "x")), "sys"),
        )
    }

    // --- parseToolCall ---------------------------------------------------------------

    @Test
    fun `parse tool call extracts quoted and bare arguments`() {
        val call = Gemma4Handle.parseToolCall(
            "noise <|tool_call>call:set_timer{seconds:120,label:<|\"|>tea pot<|\"|>,loud:true}<tool_call|> tail",
        )
        assertNotNull(call)
        assertEquals("set_timer", call.name)
        assertEquals(mapOf("seconds" to "120", "label" to "tea pot", "loud" to "true"), call.arguments)
    }

    @Test
    fun `parse tool call returns null when incomplete or empty`() {
        assertNull(Gemma4Handle.parseToolCall("no call here"))
        assertNull(Gemma4Handle.parseToolCall("<|tool_call>call:half{arg:1"))
        assertNull(Gemma4Handle.parseToolCall("<|tool_call>call:{arg:1}<tool_call|>"))
        assertNull(Gemma4Handle.parseToolCall(""))
        val emptyArgs = Gemma4Handle.parseToolCall("<|tool_call>call:noop{}<tool_call|>")
        assertNotNull(emptyArgs)
        assertEquals(emptyMap(), emptyArgs.arguments)
    }

    @Test
    fun `render tool response wraps value in gemma quotes`() {
        val out = Gemma4Handle.renderToolResponse("set_timer", "done")
        assertEquals("<|tool_response>response:set_timer{value:<|\"|>done<|\"|>}<tool_response|>", out)
        assertEquals(out, renderGemma4ToolResponse("set_timer", "done"))
    }

    // --- promptCeiling / fitAudio --------------------------------------------------------

    @Test
    fun `prompt ceiling reserves the larger of limit and minimum`() {
        assertEquals(Gemma4Handle.MAX_CONTEXT - Gemma4Handle.DEFAULT_REPLY, Gemma4Handle.promptCeiling())
        assertEquals(Gemma4Handle.MAX_CONTEXT - 3, Gemma4Handle.promptCeiling(0))
        assertEquals(Gemma4Handle.MAX_CONTEXT - 1000, Gemma4Handle.promptCeiling(1000))
    }

    private fun audio(tokens: Int) = Gemma4Handle.Part.Audio(FloatArray(tokens * Gemma4Handle.SOFT_TOKEN_WIDTH))
    private fun tokens(count: Int) = Gemma4Handle.Part.Tokens(IntArray(count) { it })

    @Test
    fun `fit audio returns same list when there is no audio`() {
        val parts = listOf<Gemma4Handle.Part>(tokens(3), Gemma4Handle.Part.Text("x"))
        assertSame(parts, Gemma4Handle.fitAudio(parts, budget = 0))
    }

    @Test
    fun `fit audio keeps early clips and trims or drops later ones`() {
        val first = audio(4)
        val second = audio(4)
        val parts = listOf<Gemma4Handle.Part>(tokens(2), first, second)
        val fitted = Gemma4Handle.fitAudio(parts, budget = 6)
        assertEquals(3, fitted.size)
        assertEquals(4, (fitted[1] as Gemma4Handle.Part.Audio).positions)
        assertEquals(2, (fitted[2] as Gemma4Handle.Part.Audio).positions)
        // The trimmed clip keeps the start of the second recording (its first floats).
        assertEquals(
            second.soft.copyOf(2 * Gemma4Handle.SOFT_TOKEN_WIDTH).toList(),
            (fitted[2] as Gemma4Handle.Part.Audio).soft.toList(),
        )
        assertEquals(first, fitted[1])
        val dropped = Gemma4Handle.fitAudio(parts, budget = 0)
        assertEquals(listOf<Gemma4Handle.Part>(parts[0]), dropped)
        val negative = Gemma4Handle.fitAudio(parts, budget = -5)
        assertEquals(1, negative.size)
        val abundant = Gemma4Handle.fitAudio(parts, budget = 100)
        assertEquals(parts, abundant)
    }

    // --- Part data classes --------------------------------------------------------------------

    @Test
    fun `part positions and equality follow their arrays`() {
        assertEquals(0, Gemma4Handle.Part.Text("anything").positions)
        assertEquals(3, tokens(3).positions)
        assertEquals(2, Gemma4Handle.Part.Image(FloatArray(2 * Gemma4Handle.SOFT_TOKEN_WIDTH)).positions)
        assertEquals(1, audio(1).positions)
        assertEquals(tokens(3), Gemma4Handle.Part.Tokens(intArrayOf(0, 1, 2)))
        assertEquals(tokens(3).hashCode(), Gemma4Handle.Part.Tokens(intArrayOf(0, 1, 2)).hashCode())
        assertNotEquals(tokens(3), Gemma4Handle.Part.Tokens(intArrayOf(0, 1, 9)))
        assertEquals(audio(1), Gemma4Handle.Part.Audio(FloatArray(Gemma4Handle.SOFT_TOKEN_WIDTH)))
        assertFalse(Gemma4Handle.Part.Audio(FloatArray(Gemma4Handle.SOFT_TOKEN_WIDTH)) == Gemma4Handle.Part.Image(FloatArray(Gemma4Handle.SOFT_TOKEN_WIDTH)))
    }

    @Test
    fun `turn tool declaration and role defaults`() {
        val t = Gemma4Handle.Turn(Gemma4Handle.Role.USER, "hi")
        assertEquals(emptyList(), t.images)
        assertEquals(emptyList(), t.audio)
        assertEquals("user", Gemma4Handle.Role.USER.marker)
        assertEquals("model", Gemma4Handle.Role.MODEL.marker)
        val param = Gemma4Handle.ToolDeclaration.Parameter("x", "y", "STRING")
        assertTrue(param.required)
        val call = Gemma4Handle.ToolCall("fn", mapOf("a" to "b"))
        assertEquals(call, call.copy())
    }

    // --- digest / readInt / files ------------------------------------------------------------

    @Test
    fun `digest is deterministic 32 bytes and input sensitive`() {
        val a = Gemma4Handle.digest(intArrayOf(1, 2, 3, 1000))
        assertEquals(32, a.size)
        assertEquals(a.toList(), Gemma4Handle.digest(intArrayOf(1, 2, 3, 1000)).toList())
        assertNotEquals(a.toList(), Gemma4Handle.digest(intArrayOf(1, 2, 3, 1001)).toList())
        assertNotEquals(a.toList(), Gemma4Handle.digest(intArrayOf()).toList())
    }

    @Test
    fun `readInt is little endian`() {
        assertEquals(0x04030201, Gemma4Handle.readInt(byteArrayOf(1, 2, 3, 4), 0))
        assertEquals(0x04030201, Gemma4Handle.readInt(byteArrayOf(9, 1, 2, 3, 4), 1))
        assertEquals(255, Gemma4Handle.readInt(byteArrayOf(-1, 0, 0, 0), 0))
    }

    @Test
    fun `file lists and markers are consistent`() {
        assertEquals(
            listOf(Gemma4Handle.TEXT, Gemma4Handle.EMBED, Gemma4Handle.TOKENIZER),
            Gemma4Handle.FILES,
        )
        assertEquals(
            listOf(Gemma4Handle.TEXT, Gemma4Handle.EMBED, Gemma4Handle.HEAD, Gemma4Handle.TOKENIZER),
            Gemma4Handle.FILES_WITH_HEAD,
        )
        assertEquals(intArrayOf(1, 106, 50).toList(), Gemma4Handle.STOP.toList())
        for (marker in listOf(
            Gemma4Handle.BOI_MARKER, Gemma4Handle.EOI_MARKER,
            Gemma4Handle.BOA_MARKER, Gemma4Handle.EOA_MARKER,
        )) {
            assertTrue(marker in Gemma4Handle.MARKERS, marker)
        }
        assertEquals(16384, Gemma4Handle.MAX_CONTEXT)
    }

    @Test
    fun `shortFiles reports only present files below expected size`() {
        val dir = java.nio.file.Files.createTempDirectory("gemma-shortfiles").toFile()
        assertEquals(emptyList(), shortFiles(dir))
        File(dir, Gemma4Handle.TEXT).writeBytes(byteArrayOf(1, 2))
        File(dir, Gemma4Handle.TOKENIZER).writeBytes(byteArrayOf(1))
        val short = shortFiles(dir)
        assertEquals(2, short.size)
        assertTrue(short.any { it.startsWith(Gemma4Handle.TEXT) && it.contains("2/") }, short.toString())
        assertTrue(short.any { it.startsWith(Gemma4Handle.TOKENIZER) }, short.toString())
        // A directory entry (not a file) is not reported.
        File(dir, Gemma4Handle.HEAD).mkdir()
        assertEquals(2, shortFiles(dir).size)
        assertEquals(
            setOf(
                Gemma4Handle.TEXT, Gemma4Handle.EMBED, Gemma4Handle.HEAD,
                Gemma4VisionHandle.VISION, Gemma4AudioHandle.AUDIO, Gemma4Handle.TOKENIZER,
            ),
            GEMMA4_EXPECTED_BYTES.keys,
        )
    }
}
