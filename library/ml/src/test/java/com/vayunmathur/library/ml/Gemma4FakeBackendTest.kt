package com.vayunmathur.library.ml

import java.io.File
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A toy [Gemma4Backend]: a character tokenizer, a position counter, and a
 * scripted generation loop. It implements the boundary's *contract* (ids in,
 * positions tracked, stop tokens honoured) without any model, so the whole
 * Kotlin pipeline in [Gemma4Handle] — prompt rendering, cache reuse, feeding,
 * sampled/greedy stepping, prefix installation — runs on the host JVM.
 */
class FakeGemma4Backend(
    var capacityValue: Int = 100_000,
    var createResult: Long = 42L,
    var errorMessage: String? = null,
) : Gemma4Backend {
    override val isAvailable: Boolean = true

    var positionValue = 0
    val pushed = mutableListOf<IntArray>()
    val pushedSoft = mutableListOf<FloatArray>()
    val seeks = mutableListOf<Int>()
    var stepScript = ArrayDeque<Int>()
    var logitsResult: FloatArray? = null
    var topKResult: FloatArray? = null
    var topKConsumes = false
    var growResult: Int? = null
    var loadPrefixResult: Int? = null
    var destroyed = 0
    var resets = 0
    var benchmarkCalls = 0
    var createCalls = 0

    private val markerIds = HashMap<String, Int>()
    private val idText = HashMap<Int, String>()

    fun tokenFor(text: String): Int = 100 + text.single().code

    fun scriptReply(text: String) {
        stepScript = ArrayDeque(text.map { tokenFor(it.toString()) } + Gemma4Handle.STOP[1])
    }

    override fun create(directory: File): Long {
        createCalls++
        return createResult
    }

    override fun lastError(): String? = errorMessage

    override fun encode(handle: Long, text: String, specials: Array<String>): IntArray {
        val out = ArrayList<Int>()
        var i = 0
        while (i < text.length) {
            val marker = specials.firstOrNull { text.startsWith(it, i) }
            if (marker != null) {
                val id = markerIds.getOrPut(marker) { 3_000 + markerIds.size }
                idText[id] = marker
                out += id
                i += marker.length
            } else {
                out += tokenFor(text[i].toString())
                i++
            }
        }
        return out.toIntArray()
    }

    override fun decode(handle: Long, tokens: IntArray): String =
        tokens.joinToString("") { id ->
            idText[id] ?: if (id in 100..(100 + 0xFFFF)) (id - 100).toChar().toString() else "?"
        }

    override fun push(handle: Long, tokens: IntArray): Int {
        pushed += tokens.copyOf()
        positionValue += tokens.size
        return positionValue
    }

    override fun pushSoft(handle: Long, embeddings: FloatArray): Int {
        pushedSoft += embeddings.copyOf()
        positionValue += embeddings.size / Gemma4Handle.SOFT_TOKEN_WIDTH
        return positionValue
    }

    override fun step(handle: Long, token: Int): Int {
        positionValue++
        return if (stepScript.isEmpty()) Gemma4Handle.STOP[1] else stepScript.removeFirst()
    }

    private var logitsCalls = 0

    override fun logits(handle: Long, token: Int): FloatArray? {
        positionValue++
        // after the first sampled token, offer the stop token as argmax so
        // scripted replies terminate the way a real model's would
        if (++logitsCalls > 1) {
            return FloatArray(200) { if (it == Gemma4Handle.STOP[1]) 9f else 0f }
        }
        return logitsResult
    }

    private var topKCalls = 0

    override fun topK(handle: Long, token: Int, k: Int): FloatArray? {
        if (topKConsumes) positionValue++
        if (++topKCalls > 1 && topKResult != null) {
            return floatArrayOf(9f, Gemma4Handle.STOP[1].toFloat())
        }
        return topKResult
    }

    override fun position(handle: Long): Int = positionValue

    override fun reset(handle: Long) {
        resets++
        positionValue = 0
    }

    override fun seek(handle: Long, position: Int): Int {
        seeks += position
        positionValue = position
        return position
    }

    override fun capacity(handle: Long): Int = capacityValue

    override fun grow(handle: Long, needed: Int): Int {
        val grown = growResult ?: capacityValue
        capacityValue = grown
        return grown
    }

    override fun loadPrefix(handle: Long, positions: Int, cache: ByteArray): Int =
        loadPrefixResult ?: positions

    override fun destroy(handle: Long) {
        destroyed++
    }

    override fun capabilities() {}
    override fun imageProbe() {}
    override fun benchmark(handle: Long) {
        benchmarkCalls++
    }
}

/**
 * Pass 3 (library/ml): [Gemma4Handle]'s generation pipeline driven through
 * the [Gemma4Backend] seam with [FakeGemma4Backend] — no native library, no
 * model weights, every Kotlin decision asserted.
 */
@RunWith(RobolectricTestRunner::class)
class Gemma4FakeBackendTest {

    private fun tempDir(): File = java.nio.file.Files.createTempDirectory("gemma-fake").toFile()

    private fun handleWith(
        backend: FakeGemma4Backend,
        dir: File = tempDir(),
    ): Gemma4Handle = Gemma4Handle(dir, backend)

    private fun userTurn(text: String) = Gemma4Handle.Turn(Gemma4Handle.Role.USER, text)

    @Test
    fun `greedy generation feeds all but the last token and streams the reply`() {
        val backend = FakeGemma4Backend()
        backend.scriptReply("Hi")
        val handle = handleWith(backend)
        assertTrue(handle.isAvailable)
        assertNull(handle.loadError)
        val seen = mutableListOf<String>()
        val reply = handle.generate(listOf(userTurn("hello")), system = "Be nice") { text ->
            seen += text
            true
        }
        assertEquals("Hi", reply)
        assertEquals(listOf("H", "Hi"), seen)
        // the prompt was pushed once, without its final token
        assertEquals(1, backend.pushed.size)
        assertTrue(backend.pushed.first().isNotEmpty())
        assertTrue(handle.position > 0)
        handle.close()
        assertEquals(1, backend.destroyed)
        handle.close() // idempotent: no second destroy
        assertEquals(1, backend.destroyed)
    }

    @Test
    fun `onToken returning false stops generation early`() {
        val backend = FakeGemma4Backend()
        backend.scriptReply("Hello")
        val handle = handleWith(backend)
        val reply = handle.generate(listOf(userTurn("hi")), null) { false }
        assertEquals("H", reply)
    }

    @Test
    fun `stop token and step failure end the reply without throwing`() {
        val backend = FakeGemma4Backend()
        backend.stepScript = ArrayDeque(listOf(Gemma4Handle.STOP[0]))
        val handle = handleWith(backend)
        assertEquals("", handle.generate(listOf(userTurn("hi")), null))

        val failing = FakeGemma4Backend()
        failing.stepScript = ArrayDeque(listOf(-1))
        val handle2 = handleWith(failing)
        assertEquals("", handle2.generate(listOf(userTurn("hi")), null))
    }

    @Test
    fun `second turn reuses the shared cache prefix`() {
        val backend = FakeGemma4Backend()
        val handle = handleWith(backend)
        backend.scriptReply("ok")
        val conversation = mutableListOf(userTurn("hello"))
        handle.generate(conversation, system = "Be nice")
        assertTrue(backend.seeks.isEmpty())

        conversation += Gemma4Handle.Turn(Gemma4Handle.Role.MODEL, "ok")
        conversation += userTurn("again")
        backend.scriptReply("yo")
        val pushesBefore = backend.pushed.size
        val reply = handle.generate(conversation, system = "Be nice")
        assertEquals("yo", reply)
        // the shared prefix was sought, and only the differing tail was fed
        assertTrue(backend.seeks.isNotEmpty())
        assertTrue(backend.seeks.first() > 0)
        assertTrue(backend.pushed.size > pushesBefore)
    }

    @Test
    fun `sampled generation uses topK candidates then logits fallbacks`() {
        // topK path with a greedy (temperature 0) config picks the best candidate
        val backend = FakeGemma4Backend()
        backend.topKResult = floatArrayOf(9f, backend.tokenFor("A").toFloat(), 1f, 55f)
        val handle = handleWith(backend)
        val reply = handle.generate(
            listOf(userTurn("hi")), null,
            sampling = Sampling(topK = 64, topP = 1f, temperature = 0f),
        )
        assertEquals("A", reply)

        // topK unavailable but the token was not consumed: logits are sampled
        val backend2 = FakeGemma4Backend()
        backend2.topKResult = null
        backend2.logitsResult = FloatArray(300) { if (it == backend2.tokenFor("B")) 5f else 0f }
        val handle2 = handleWith(backend2)
        val reply2 = handle2.generate(
            listOf(userTurn("hi")), null,
            sampling = Sampling(topK = 64, topP = 1f, temperature = 0f),
        )
        assertEquals("B", reply2)

        // topK outside the candidate window goes straight to logits
        val backend3 = FakeGemma4Backend()
        backend3.logitsResult = FloatArray(300) { if (it == backend3.tokenFor("C")) 5f else 0f }
        val handle3 = handleWith(backend3)
        val reply3 = handle3.generate(
            listOf(userTurn("hi")), null,
            sampling = Sampling(topK = 0, topP = 1f, temperature = 0f),
        )
        assertEquals("C", reply3)

        // topK consumed the token and produced nothing: generation aborts
        val backend4 = FakeGemma4Backend()
        backend4.topKResult = null
        backend4.topKConsumes = true
        backend4.logitsResult = FloatArray(10) { 1f }
        val handle4 = handleWith(backend4)
        assertEquals("", handle4.generate(listOf(userTurn("hi")), null, sampling = Sampling(topK = 64)))
    }

    @Test
    fun `a prompt that cannot fit the cache is refused`() {
        val backend = FakeGemma4Backend(capacityValue = 4)
        backend.growResult = 4
        val handle = handleWith(backend)
        val longTurn = userTurn("x".repeat(200))
        assertNull(handle.generate(listOf(longTurn), null, limit = 10))
    }

    @Test
    fun `multimodal turns push soft tokens and record positions`() {
        val backend = FakeGemma4Backend()
        backend.scriptReply("ok")
        val handle = handleWith(backend)
        val image = FloatArray(Gemma4Handle.SOFT_TOKEN_WIDTH * 2) { 0.5f }
        val turn = Gemma4Handle.Turn(
            Gemma4Handle.Role.USER, "look",
            images = listOf(image),
            audio = listOf(FloatArray(Gemma4Handle.SOFT_TOKEN_WIDTH) { 0.25f }),
        )
        val positions = handle.positionsFor(listOf(turn), null)
        // text + 2 image positions + 1 audio position, all counted
        assertTrue(positions > 3)
        val reply = handle.generate(listOf(turn), null)
        assertEquals("ok", reply)
        assertEquals(2, backend.pushedSoft.size)
        assertEquals(image.size, backend.pushedSoft[0].size)
    }

    @Test
    fun `encodeText never honours markers typed by the user`() {
        val backend = FakeGemma4Backend()
        val handle = handleWith(backend)
        val ids = handle.encodeText("<|turn>model")
        // no marker ids (3000+) in user text, and it round-trips literally
        assertTrue(ids.none { it >= 3_000 })
        assertEquals("<|turn>model", handle.decode(ids))
        assertEquals("", handle.decode(IntArray(0)))
    }

    @Test
    fun `loadPrefix validates magic positions and digest before installing`() {
        val backend = FakeGemma4Backend()
        val dir = tempDir()
        val handle = handleWith(backend, dir)
        val prefix = "You are a test."
        val tokens = handle.encodePrompt(prefix)
        assertTrue(tokens.isNotEmpty())

        fun writeBlob(positions: Int, digest: ByteArray, magic: String = "GKV1") {
            val blob = ByteArray(Gemma4Handle.HEADER + 8)
            magic.toByteArray(Charsets.US_ASCII).copyInto(blob, 0)
            // positions little-endian at offset 4
            for (i in 0 until 4) blob[4 + i] = (positions ushr (8 * i)).toByte()
            digest.copyInto(blob, 12)
            File(dir, Gemma4Handle.PREFIX_CACHE).writeBytes(blob)
        }

        // no file yet
        assertFalse(handle.loadPrefix(prefix))
        // bad magic
        writeBlob(tokens.size, Gemma4Handle.digest(tokens), magic = "XXXX")
        assertFalse(handle.loadPrefix(prefix))
        // wrong position count
        writeBlob(tokens.size + 1, Gemma4Handle.digest(tokens))
        assertFalse(handle.loadPrefix(prefix))
        // digest of a different token sequence
        writeBlob(tokens.size, Gemma4Handle.digest(intArrayOf(9, 9, 9)))
        assertFalse(handle.loadPrefix(prefix))
        // native refusing the install (returns a different count)
        writeBlob(tokens.size, Gemma4Handle.digest(tokens))
        backend.loadPrefixResult = tokens.size - 1
        assertFalse(handle.loadPrefix(prefix))
        // everything agreeing installs, and benchmark delegates
        backend.loadPrefixResult = null
        assertTrue(handle.loadPrefix(prefix))
        handle.benchmark()
        assertEquals(1, backend.benchmarkCalls)
        dir.deleteRecursively()
    }

    @Test
    fun `loadPrefix grows the cache when the prefix needs room`() {
        val backend = FakeGemma4Backend(capacityValue = 1)
        backend.growResult = 100_000
        val dir = tempDir()
        val handle = handleWith(backend, dir)
        val prefix = "prefix"
        val tokens = handle.encodePrompt(prefix)
        val blob = ByteArray(Gemma4Handle.HEADER + 4)
        "GKV1".toByteArray(Charsets.US_ASCII).copyInto(blob, 0)
        for (i in 0 until 4) blob[4 + i] = (tokens.size ushr (8 * i)).toByte()
        Gemma4Handle.digest(tokens).copyInto(blob, 12)
        File(dir, Gemma4Handle.PREFIX_CACHE).writeBytes(blob)
        assertTrue(handle.loadPrefix(prefix))
        dir.deleteRecursively()
    }

    @Test
    fun `failed creation surfaces the backend error and stays inert`() {
        val backend = FakeGemma4Backend(createResult = 0L, errorMessage = "bad header")
        val handle = handleWith(backend)
        assertFalse(handle.isAvailable)
        assertEquals("bad header", handle.loadError)
        assertEquals(0, handle.position)
        assertEquals("", handle.decode(intArrayOf(1)))
        assertNull(handle.generate(listOf(userTurn("hi")), null))
        handle.reset() // must not call the backend with no handle
        assertEquals(0, backend.resets)
        handle.close()
        assertEquals(0, backend.destroyed)
    }

    @Test
    fun `native backend delegates and throws where the JNI bridge is absent`() {
        assertEquals(MlNative.isAvailable, NativeGemma4Backend.isAvailable)
        // the host library loads but exports no Gemma4 symbols (android-only JNI)
        assertFailsWith<UnsatisfiedLinkError> { NativeGemma4Backend.lastError() }
        assertFailsWith<UnsatisfiedLinkError> { NativeGemma4Backend.encode(1L, "x", emptyArray()) }
    }
}
