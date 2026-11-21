package com.vayunmathur.madeveloper.data

import java.io.File

/** One persisted last-good payload: raw JSON plus when it was fetched. */
data class LastGoodEntry(val text: String, val fetchedAtMillis: Long)

/**
 * Disk persistence for last-good host JSON, so a backend outage renders the
 * previous data (marked stale) instead of blanking the app.
 *
 * Keys are short stable names (`repos`, `tests`, …), never URLs, so a host
 * rename does not orphan the cache. Implementations must never throw into
 * the caller: a broken cache is just a cache miss.
 */
interface LastGoodStore {
    fun read(key: String): LastGoodEntry?
    fun write(key: String, text: String, fetchedAtMillis: Long)
}

/**
 * [LastGoodStore] over plain files in [dir]: `<key>.json` for the payload and
 * `<key>.meta` for the fetch time. Writing via a temp file + rename keeps a
 * crash mid-write from leaving a truncated payload behind.
 */
class FileLastGoodStore(private val dir: File) : LastGoodStore {
    override fun read(key: String): LastGoodEntry? = runCatching {
        val json = File(dir, "$key.json")
        if (!json.isFile) return null
        val at = File(dir, "$key.meta").readText().trim().toLongOrNull() ?: return null
        LastGoodEntry(json.readText(), at)
    }.getOrNull()

    override fun write(key: String, text: String, fetchedAtMillis: Long) {
        runCatching {
            dir.mkdirs()
            val tmp = File(dir, "$key.json.tmp")
            tmp.writeText(text)
            tmp.renameTo(File(dir, "$key.json"))
            File(dir, "$key.meta").writeText(fetchedAtMillis.toString())
        }
    }
}
