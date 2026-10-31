package com.vayunmathur.todo.domain

/**
 * Local tombstones that never reached a sync baseline are only kept long enough for a first
 * sync to pick them up. Synced tombstones are collected by the relay merge itself once both
 * sides acknowledge the delete.
 */
object TombstonePolicy {
    const val RETENTION_MS: Long = 30L * 24 * 60 * 60 * 1000

    fun cutoff(now: Long): Long = now - RETENTION_MS
}
