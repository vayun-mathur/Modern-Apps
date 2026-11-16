package com.vayunmathur.communicate.data.rcs.e2e

/**
 * Group-query helpers for [RcsE2E] (split for file length).
 * Extension functions on [RcsE2E]; behavior identical, call sites unchanged.
 */

internal fun RcsE2E.parseMembers(csv: String): Map<String, Int> {
    if (csv.isBlank()) return emptyMap()
    return csv.split(",").mapNotNull { entry ->
        val (member, idx) = entry.split("=", limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
        val index = idx.trim().toIntOrNull() ?: return@mapNotNull null
        member.trim().takeIf { it.isNotEmpty() }?.to(index)
    }.toMap()
}

/** Drop [droppedIdx] leaves from a member map, re-encoding the CSV. */
internal fun RcsE2E.pruneMembers(csv: String, droppedIdx: Set<Int>): String =
    parseMembers(csv).filterValues { it !in droppedIdx }
        .entries.joinToString(",") { "${it.key}=${it.value}" }

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

internal fun RcsE2E.commitLockFor(conversationId: String): kotlinx.coroutines.sync.Mutex =
    commitLocks.getOrPut(conversationId) { kotlinx.coroutines.sync.Mutex() }

internal fun e2eThreadFor(e164: String): String = "e2e:$e164"
