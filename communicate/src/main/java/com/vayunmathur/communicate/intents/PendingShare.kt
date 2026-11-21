package com.vayunmathur.communicate.intents

/**
 * Content shared to Communicate as the regular share target (no conversation
 * chosen in the share sheet yet).
 *
 * Held process-locally, not in a route: the message list is already on screen
 * when this arrives, and the next thread the user opens consumes it once to
 * seed that conversation's composer. A Direct Share tap never lands here —
 * it names its conversation, so [DeepLinks] returns it directly.
 */
object PendingShare {
    private var pending: DeepLink.SharePicker? = null

    /** Stash [share], replacing any unconsumed earlier share. */
    fun store(share: DeepLink.SharePicker) {
        pending = share
    }

    /** The stashed share, clearing it so a later plain thread open is unseeded. */
    fun consume(): DeepLink.SharePicker? = pending.also { pending = null }
}
