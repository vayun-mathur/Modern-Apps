package com.vayunmathur.openassistant.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PendingToolCallsTest {

    @Test fun expiryAndConsumeOnce() {
        val store = InMemoryPendingToolCallStore()
        val pending = PendingToolCall("p1", "create_note", mapOf("content" to "x"), "s", 1_000L)
        assertEquals(1_000L + PendingToolCall.TTL_MILLIS, pending.expiresAtMillis)
        assertFalse(pending.isExpired(1_000L))
        assertTrue(pending.isExpired(pending.expiresAtMillis))
        store.put(pending)
        assertEquals(pending, store.get("p1"))
        assertTrue(store.markConsumed("p1"))
        assertFalse(store.markConsumed("p1"))
        assertTrue(store.get("p1")!!.consumed)
        store.remove("p1")
        assertEquals(null, store.get("p1"))
    }

    @Test fun summaryHelpers() {
        assertEquals("create_note", pendingToolSummary("create_note", emptyMap()))
        assertTrue(pendingToolSummary("x", mapOf("a" to "b")).contains("a=b"))
    }
}
