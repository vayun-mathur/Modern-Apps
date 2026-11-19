package com.vayunmathur.games.wordmaker.domain
import android.content.Context
import java.nio.ByteBuffer
import org.brotli.dec.BrotliInputStream

/**
 * Word data backed by two compact assets generated offline by
 * scripts/wordmaker/generate_dictionary_assets.py:
 *
 *   words.dawg      minimized DAWG over the UTF-8 bytes of every valid word,
 *                   loaded eagerly for fast membership tests.
 *   definitions.br  brotli-compressed word -> definitions store, decoded lazily
 *                   the first time a definition is requested.
 */
class Dictionary {
    @Volatile private var edges: IntArray = IntArray(0)
    @Volatile private var rootOffset: Int = 0
    @Volatile private var loaded: Boolean = false
    private lateinit var appContext: Context

    @Volatile private var definitions: Map<String, List<String>>? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        val raw = context.assets.open("words.dawg").use { it.readBytes() }
        val bb = ByteBuffer.wrap(raw) // big-endian, matches the generator
        require(bb.int == MAGIC) { "bad words.dawg magic" }
        val edgeCount = bb.int
        rootOffset = bb.int
        val arr = IntArray(edgeCount)
        bb.asIntBuffer().get(arr)
        edges = arr
        loaded = true
    }

    operator fun contains(word: String): Boolean {
        if (!loaded) return false
        val bytes = word.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty()) return false
        val edges = this.edges
        var node = rootOffset
        for (j in bytes.indices) {
            val code = bytes[j].toInt() and 0xFF
            var i = node
            var found = -1
            while (true) {
                val e = edges[i]
                if ((e ushr 24) and 0xFF == code) { found = i; break }
                if (e and LAST_BIT != 0) break
                i++
            }
            if (found < 0) return false
            val e = edges[found]
            if (j == bytes.size - 1) return e and FINAL_BIT != 0
            node = e and OFFSET_MASK
        }
        return false
    }

    fun getDefinition(word: String): List<String> {
        // Tapping a word before the fire-and-forget init() finished (or
        // after it failed) must show no definition, not crash on the
        // lateinit context or a failed asset read.
        if (!::appContext.isInitialized) return emptyList()
        return runCatching {
            (definitions ?: loadDefinitions())[word.lowercase()] ?: emptyList()
        }.getOrDefault(emptyList())
    }

    @Synchronized
    private fun loadDefinitions(): Map<String, List<String>> {
        definitions?.let { return it }
        val map = HashMap<String, List<String>>()
        BrotliInputStream(appContext.assets.open("definitions.br")).bufferedReader().use { reader ->
            reader.forEachLine { line ->
                val parts = line.split('\t')
                val w = parts[0]
                map[w] = if (parts.size > 1) parts.subList(1, parts.size).toList() else emptyList()
            }
        }
        definitions = map
        return map
    }

    private companion object {
        const val MAGIC = 0x57444731 // "WDG1"
        const val OFFSET_MASK = (1 shl 22) - 1
        const val FINAL_BIT = 1 shl 22
        const val LAST_BIT = 1 shl 23
    }
}
