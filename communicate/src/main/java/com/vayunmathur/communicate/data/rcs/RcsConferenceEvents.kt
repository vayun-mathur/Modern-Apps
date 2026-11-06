package com.vayunmathur.communicate.data.rcs

/**
 * Conference event package (RFC 4575 `conference-info`, RFC 4353 SUBSCRIBE/
 * NOTIFY for conferences).
 *
 * When we join a carrier-hosted focus, SUBSCRIBEing to `Event: conference`
 * at the focus URI yields the real participant list + join/part transitions
 * — without it we only know who we invited and who INVITEd us. When we host
 * (§3.2), we publish minimal NOTIFYs to our subscribers.
 *
 * All parsing/building here is pure string work (no transport, no session
 * map); the dialog flows live in `RcsSessionManager` + `RcsInboundDialog`.
 */
object RcsConferenceEvents {
    /** One participant from a `conference-info` document. */
    data class Participant(
        /** SIP URI of the endpoint. */
        val uri: String,
        /** `full` / `partial` state wrapper is handled by the caller. */
        val state: String,
        /** Display name when present. */
        val displayName: String?,
    )

    /** Parsed `conference-info`: entity (focus URI) + current users. */
    data class ConferenceInfo(
        val entity: String,
        val version: Int,
        val users: List<Participant>,
    )

    /**
     * Parse an `application/conference-info+xml` body. Returns null when the
     * body isn't conference-info. `state="deleted"` users are excluded (the
     * caller reconciles them out of the member set separately via
     * [deletedUris]).
     */
    fun parseConferenceInfo(body: String): ConferenceInfo? {
        if (!body.contains("conference-info", ignoreCase = true)) return null
        val entity = Regex("<conference-info[^>]*\\bentity=\"([^\"]+)\"", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val version = Regex("<conference-info[^>]*\\bversion=\"(\\d+)\"", RegexOption.IGNORE_CASE)
            .find(body)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val users = mutableListOf<Participant>()
        // Group 1 = the full <user ...> opening tag (may carry state="deleted"
        // per RFC 4575 §5.4.1), group 2 = entity, group 3 = element body.
        val userRe = Regex(
            "(<user[^>]*\\bentity=\"([^\"]+)\"[^>]*>)(.*?)</user>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        for (m in userRe.findAll(body)) {
            val tag = m.groupValues.getOrNull(1).orEmpty()
            val uri = m.groupValues.getOrNull(2)?.trim()
            val inner = m.groupValues.getOrNull(3).orEmpty()
            val deleted = tag.contains("state=\"deleted\"", ignoreCase = true) ||
                inner.contains("state=\"deleted\"", ignoreCase = true) ||
                inner.contains("<state>deleted</state>", ignoreCase = true)
            if (uri != null && !deleted) {
                val state = Regex("<state>([^<]+)</state>", RegexOption.IGNORE_CASE)
                    .find(inner)?.groupValues?.getOrNull(1)?.trim() ?: "full"
                val display = Regex("<display-text>([^<]*)</display-text>", RegexOption.IGNORE_CASE)
                    .find(inner)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
                users += Participant(uri, state, display)
            }
        }
        return ConferenceInfo(entity, version, users)
    }

    /** URIs in `state="deleted"` (parted members to reconcile out). */
    fun deletedUris(body: String): List<String> {
        if (!body.contains("conference-info", ignoreCase = true)) return emptyList()
        val out = mutableListOf<String>()
        val userRe = Regex(
            "(<user[^>]*\\bentity=\"([^\"]+)\"[^>]*>)(.*?)</user>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        for (m in userRe.findAll(body)) {
            val tag = m.groupValues.getOrNull(1).orEmpty()
            val inner = m.groupValues.getOrNull(3).orEmpty()
            if (tag.contains("state=\"deleted\"", ignoreCase = true) ||
                inner.contains("state=\"deleted\"", ignoreCase = true) ||
                inner.contains("<state>deleted</state>", ignoreCase = true)
            ) {
                m.groupValues.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
            }
        }
        return out
    }

    /**
     * Build a minimal `conference-info` NOTIFY body for a hosted focus:
     * [focusUri] entity, [version] counter, [members] as full users.
     */
    fun buildConferenceInfo(focusUri: String, version: Int, members: Collection<String>): String =
        buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<conference-info xmlns=\"urn:ietf:params:xml:ns:conference-info\"\n")
            append("    entity=\"$focusUri\" version=\"$version\" state=\"full\">\n")
            append("  <users>\n")
            for (member in members) {
                append("    <user entity=\"$member\" state=\"full\">\n")
                append("      <endpoint entity=\"$member\"><state>connected</state></endpoint>\n")
                append("    </user>\n")
            }
            append("  </users>\n")
            append("</conference-info>")
        }

    /**
     * Build a `message/sipfrag` body (RFC 3420) reporting a referral outcome,
     * e.g. `SIP/2.0 200 OK`. Sent inside NOTIFYs to a REFER referrer (§3.3).
     */
    fun buildSipfrag(statusCode: Int, reason: String): String = "SIP/2.0 $statusCode $reason"

    /** Parse a `message/sipfrag` body into (code, reason), or null. */
    fun parseSipfrag(body: String): Pair<Int, String>? {
        val trimmed = body.trim()
        if (!trimmed.startsWith("SIP/2.0")) return null
        val code = trimmed.substringAfter("SIP/2.0").trim().substringBefore(" ")
            .trim().toIntOrNull() ?: return null
        val reason = trimmed.substringAfter("SIP/2.0").trim().substringAfter(" ").trim()
        return code to reason
    }
}
