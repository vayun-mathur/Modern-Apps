package com.vayunmathur.updater.domain

/**
 * The one line served at `<ota-server>/<device>-stable`, e.g.
 *
 * ```
 * 2026090700 1788644317 shiba stable
 * ```
 *
 * which is `<build> <buildDateUtcSeconds> <device> <channel>`.
 *
 * Only the first two fields are read. The trailing ones are the publisher's own bookkeeping:
 * the device is already implied by the URL we fetched, and MAOS has exactly one channel, so
 * neither tells us anything we did not already know. Ignoring them rather than validating them
 * means a publisher that adds a fifth field later does not brick every client's update check.
 */
data class UpdateMetadata(
    /** The target build, matching `ro.build.version.incremental` on a device already on it. */
    val build: String,
    /** Target build date, in seconds since the epoch, matching `ro.build.date.utc`. */
    val buildDateUtcSeconds: Long,
) {
    /** Parser for the single-line server update metadata format. */
    companion object {

        /**
         * Parse one metadata line, or null if it is not one.
         *
         * Null rather than an exception because the realistic failure here is not a malformed
         * release — it is a captive portal or an error page served with a 200, which arrives as
         * HTML. That is an ordinary network condition, not a bug, and it must not crash a
         * background check.
         */
        fun parse(line: String): UpdateMetadata? {
            val fields = line.trim().split(Regex("\\s+"))
            if (fields.size < 2) return null
            val build = fields[0]
            // A build is a date-derived number. Requiring digits keeps an HTML error page from
            // parsing as a plausible-looking build id.
            if (build.isEmpty() || !build.all { it.isDigit() }) return null
            val date = fields[1].toLongOrNull() ?: return null
            if (date <= 0) return null
            return UpdateMetadata(build, date)
        }
    }
}
