package com.vayunmathur.openassistant.domain

/** A write tool call awaiting host confirmation. Args stay raw, as parsed. */
data class PendingToolCall(
    val id: String,
    val name: String,
    val args: Map<String, String>,
    val summary: String,
    val createdAtMillis: Long,
    val expiresAtMillis: Long = createdAtMillis + TTL_MILLIS,
    val consumed: Boolean = false,
) {
    fun isExpired(nowMillis: Long): Boolean = nowMillis >= expiresAtMillis

    /** Timing constants for [PendingToolCall] expiry. */
    companion object {
        const val TTL_MILLIS: Long = 10 * 60_000L
    }
}

/** Human summary shown on the host confirmation card. */
fun pendingToolSummary(name: String, args: Map<String, String>): String =
    if (args.isEmpty()) {
        name
    } else {
        name + ": " + args.entries.joinToString(", ") { (k, v) -> "$k=${v.take(120)}" }
    }

/** PII-redacted args summary for audit records: lengths only, never raw bodies. */
fun redactedArgsSummary(args: Map<String, String>): String =
    if (args.isEmpty()) {
        "(no args)"
    } else {
        args.entries.joinToString(", ") { (k, v) -> "$k(len=${v.length})" }
    }

/** Storage for pending calls; pure logic so the gate is unit-testable. */
interface PendingToolCallStore {
    fun put(pending: PendingToolCall)
    fun get(id: String): PendingToolCall?
    fun remove(id: String)
    /** Marks consumed exactly once; false when unknown or already consumed. */
    fun markConsumed(id: String): Boolean
}

/** In-memory [PendingToolCallStore]. */
class InMemoryPendingToolCallStore : PendingToolCallStore {
    private val map = HashMap<String, PendingToolCall>()

    @Synchronized override fun put(pending: PendingToolCall) {
        map[pending.id] = pending
    }

    @Synchronized override fun get(id: String): PendingToolCall? = map[id]

    @Synchronized override fun remove(id: String) {
        map.remove(id)
    }

    @Synchronized override fun markConsumed(id: String): Boolean {
        val pending = map[id] ?: return false
        if (pending.consumed) return false
        map[id] = pending.copy(consumed = true)
        return true
    }
}
