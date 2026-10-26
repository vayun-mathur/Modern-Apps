package com.vayunmathur.library.image

import android.graphics.Bitmap
import android.util.LruCache

/**
 * Simple LRU memory cache counting [Bitmap.byteCount].
 * Fixed at 10 MB — deliberately not sized off the device heap.
 */
class MemoryCache internal constructor(
    private val lru: LruCache<String, Bitmap>,
) {
    fun get(key: String): Bitmap? = lru.get(key)

    fun put(key: String, bitmap: Bitmap) {
        lru.put(key, bitmap)
    }

    fun remove(key: String) = lru.remove(key)

    fun clear() = lru.evictAll()

    companion object {
        const val MAX_BYTES = 10 * 1024 * 1024
    }

    class Builder {
        fun build(): MemoryCache {
            val cache = object : LruCache<String, Bitmap>(MAX_BYTES) {
                override fun sizeOf(key: String, value: Bitmap): Int {
                    return try {
                        value.byteCount
                    } catch (_: Exception) {
                        value.allocationByteCount
                    }
                }
            }
            return MemoryCache(cache)
        }
    }
}
