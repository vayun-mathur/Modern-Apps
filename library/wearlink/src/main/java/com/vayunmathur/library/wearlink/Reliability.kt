package com.vayunmathur.library.wearlink

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Exponential reconnect backoff: 1s doubling to a 30s ceiling, no jitter deps. */
class WearLinkBackoff(
    private val baseMillis: Long = 1_000L,
    private val maxMillis: Long = 30_000L,
) {
    private var failures = 0

    fun nextDelayMillis(): Long {
        val delay = minOf(maxMillis, baseMillis shl minOf(failures, 15))
        failures++
        return delay
    }

    fun reset() {
        failures = 0
    }
}

/** Monotonic frame/message ids for one node. */
class IdGenerator(start: Long = System.currentTimeMillis()) {
    private val counter = AtomicLong(start)
    fun next(): Long = counter.incrementAndGet()
}

/**
 * Correlates requests with responses and MESSAGE_ACKs. Registration is
 * bounded: a caller that never gets an answer removes its entry on timeout
 * (the Android node does), so a dead peer cannot grow this map.
 */
class RequestTracker<T> {
    private val pending = ConcurrentHashMap<Long, T>()

    fun register(id: Long, value: T) {
        pending[id] = value
    }

    fun complete(id: Long): T? = pending.remove(id)

    fun remove(id: Long): T? = pending.remove(id)

    fun failAll(): List<T> {
        val values = pending.values.toList()
        pending.clear()
        return values
    }

    val size: Int get() = pending.size
}

/**
 * Duplicate suppression for request messages: a retried request (sender
 * reconnected mid-flight) must not execute twice. Bounded LRU of seen ids.
 */
class SeenMessageIds(private val capacity: Int = 256) {
    private val seen = object : LinkedHashMap<Long, Boolean>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Boolean>?) =
            size > capacity
    }

    /** True when [id] is new (and now recorded); false when already seen. */
    @Synchronized
    fun markIfNew(id: Long): Boolean {
        if (seen.containsKey(id)) return false
        seen[id] = true
        return true
    }
}
