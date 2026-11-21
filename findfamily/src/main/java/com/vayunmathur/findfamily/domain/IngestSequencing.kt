package com.vayunmathur.findfamily.domain

import kotlin.time.Instant

/**
 * Per-sender monotonic acceptance for inbound location fixes (sync ingest).
 *
 * Pure policy by construction (the [NoShowPolicy] pattern): every input is a
 * primitive, an [Instant], or a [String]. The ingest boundary in
 * `platform/LocationTrackingInbound.kt` maps stored rows and wire fixes to
 * [FixStamp]s and applies [accepts].
 *
 * ## The hole this closes
 *
 * Storage was append-only with no ordering at all: `upsertLocations` inserted
 * every authenticated fix, and ordering existed only at read time
 * (`LocationValueDao.getLatest()` ranks on MAX(reportedAt)). That read-side
 * ranking kept the *map pin* mostly right, but the stale row was still
 * persisted, and the ingest batch itself was enriched as if it were the
 * present — waypoint/battery recomputation and `updateLocationMeta` ran on
 * the batch's contents, so a delayed or replayed older fix could move a
 * person's displayed place, battery state, and last-change time backwards
 * until the next genuine fix arrived. Nothing rejected the stale arrival.
 *
 * ## Rule
 *
 * A fix is stored only if its [FixStamp] is strictly newer than the stored
 * latest stamp *for that sender* (the per-sender high-water mark). Senders
 * are independent: one sender's high-water never gates another's, and a
 * sender with no stored fix is always accepted. Ordering is:
 *
 *  1. `reportedAt` — when the report was sent, device-stamped by the sender.
 *     This (not measurement time) decides "newest", for the same reason
 *     `getLatest()` ranks on it: shutdown/low-battery parting reports
 *     deliberately carry a stale measured fix under a fresh send stamp.
 *  2. `measuredAt` — when the position was measured.
 *  3. `payloadHash` — a deterministic hash of the fix payload (see
 *     [payloadHash]).
 *
 * ## Equal stamps and duplicates
 *
 * Two distinct fixes can share a millisecond (a pre-hybrid sender mints plain
 * wall stamps). The tie is broken by [payloadHash], never by arrival order,
 * so the accept/drop decision is a pure function of (candidate, stored) and
 * replays decide identically every time: the higher-hash payload is accepted,
 * the lower-hash one is dropped whenever it arrives. A true duplicate —
 * every field equal, so the whole stamp is equal — is not strictly newer and
 * is dropped, which makes redelivery idempotent: no second history row, no
 * re-fired enrichment. Within one batch the same fold applies in arrival
 * order against a running high-water, so a batch containing [new, old]
 * stores only the new fix; a fix that beats the batch's running high-water
 * only on this hash tiebreak *replaces* that batch entry rather than
 * appending a second same-second row. A win on time appends normally —
 * a batch's genuine progression stays in history.
 *
 * One residue is deliberately left alone: if a higher-hash fix arrives in a
 * *later batch* than an equal-stamp lower-hash fix, both rows exist in
 * history (history is append-only by design — the scrubber needs it), and
 * `getLatest()`'s MAX(reportedAt) tie between them is the pre-existing
 * reader behaviour. It self-resolves on the sender's next fix and is bounded
 * to same-second collisions.
 *
 * ## Granularity: one second, because that is what storage keeps
 *
 * Room persists `Instant` columns as epoch *seconds*
 * (`DefaultConverters.fromInstant`), so a stored high-water read back from
 * the database has one-second precision no matter what the sender stamped.
 * Stamps are therefore compared in epoch seconds on both sides: comparing a
 * full-precision candidate against a second-truncated stored stamp would
 * re-accept every duplicate whose sub-second part survived (the candidate
 * always looks newer). The cost is that two genuinely distinct fixes from
 * one sender inside the same second collapse onto the hash tiebreak — one
 * is stored, the other dropped. Fixes from a single sender are seconds to
 * minutes apart in every real flow (30 s heartbeat, keep-alive, parting
 * reports), so nothing observable is lost.
 *
 * ## Clock-corrected senders — no regression window (decided)
 *
 * A sender whose clock is corrected backwards will stamp below its stored
 * high-water and be dropped until its stamps pass the high-water again. We
 * deliberately do NOT accept a one-time regression to rescue that case:
 *
 *  - At the receiver, a legitimate correction is indistinguishable from a
 *    delayed duplicate or a replay — all three are "older than high-water".
 *    A window any of them can consume reopens the hole this policy closes,
 *    and the stale fix it admits becomes the person's displayed position
 *    until the next genuine fix.
 *  - The lockout is bounded. Anything this build (or any clamping build)
 *    ingested was future-clamped to receiver-now + 5 min before storage, so
 *    the stored high-water can sit at most ~5 min past the receiver's wall
 *    clock: a corrected sender is accepted again within minutes, not days.
 *  - The real fix is at the source: a sender minting stamps with the
 *    library's hybrid rule ([com.vayunmathur.library.util.SyncStamp.next] —
 *    `max(wallNow, last + 1)`) never stamps backwards at all, so the
 *    situation cannot arise. This receiver policy is the backstop for
 *    senders that predate hybrid stamps, and it favours never resurrecting
 *    a stale position over briefly delaying a corrected one — in a
 *    family-location app, showing where someone *was* as if it were current
 *    is the worse failure.
 */
object IngestSequencing {

    /**
     * The ordering stamp of one fix: epoch *seconds* (the precision storage
     * keeps — see the granularity note above), compared lexicographically:
     * [reportedAtSec], then [measuredAtSec], then [payloadHash]. The ingest
     * boundary truncates sender stamps with `epochSeconds` when mapping.
     */
    data class FixStamp(
        val reportedAtSec: Long,
        val measuredAtSec: Long,
        val payloadHash: Int,
    ) : Comparable<FixStamp> {
        override fun compareTo(other: FixStamp): Int =
            compareValuesBy(this, other, { it.reportedAtSec }, { it.measuredAtSec }, { it.payloadHash })
    }

    /**
     * True when [candidate] must be stored over the sender's stored latest
     * stamp ([stored] null = first fix from this sender, always accepted).
     * Strictly-greater only: an equal stamp is a duplicate, not a newer fix.
     */
    fun accepts(candidate: FixStamp, stored: FixStamp?): Boolean =
        stored == null || candidate > stored

    /**
     * Deterministic payload hash for the equal-stamp tiebreak. Built only
     * from value bits (never identity or arrival state), so the same payload
     * hashes identically on every device and every replay. Collisions between
     * different payloads are acceptable: they degrade the tiebreak to
     * "duplicate", dropping one of two same-millisecond fixes, never to
     * accepting a stale one.
     */
    fun payloadHash(
        lat: Double,
        lon: Double,
        speed: Float,
        acc: Float,
        battery: Float,
        sourceName: String,
    ): Int {
        var hash = foldBits(lat.toBits())
        hash = 31 * hash + foldBits(lon.toBits())
        hash = 31 * hash + speed.toBits()
        hash = 31 * hash + acc.toBits()
        hash = 31 * hash + battery.toBits()
        hash = 31 * hash + sourceName.hashCode()
        return hash
    }

    /** Folds a 64-bit IEEE pattern to 32 bits (the `Double.hashCode` recipe). */
    private fun foldBits(bits: Long): Int = (bits xor (bits ushr 32)).toInt()
}
