package com.vayunmathur.updater.domain

/**
 * `payload_properties.txt` from inside the OTA zip, as the header array `applyPayload` wants.
 *
 * Each line is already in the `KEY=VALUE` shape update_engine expects, so there is nothing to
 * transform — the work here is rejecting a file that is missing something. update_engine uses
 * these hashes and sizes to check the payload it is about to write, so a package that does not
 * carry all four is a package whose payload cannot be checked, and this runs immediately before
 * something is written to a system partition. Fail closed.
 */
object PayloadProperties {

    /**
     * What update_engine needs to validate the payload. `METADATA_*` covers the payload manifest
     * it reads first; `FILE_*` covers the payload as a whole.
     */
    private val REQUIRED = setOf("FILE_HASH", "FILE_SIZE", "METADATA_HASH", "METADATA_SIZE")

    /** Parse outcome for `payload_properties.txt` headers. */
    sealed interface Result {
        /** [headers] goes straight to `UpdateEngine.applyPayload`. */
        data class Parsed(val headers: List<String>) : Result

        /** Properties file is unusable; [reason] explains the rejection. */
        data class Rejected(val reason: String) : Result
    }

    fun parse(lines: Sequence<String>): Result {
        val headers = mutableListOf<String>()
        val keys = mutableSetOf<String>()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            // <= 0 also rejects a leading '=', which would be an empty key.
            val separator = line.indexOf('=')
            if (separator <= 0) return Result.Rejected("malformed line '$line'")
            keys += line.substring(0, separator)
            headers += line
        }
        val missing = REQUIRED - keys
        if (missing.isNotEmpty()) {
            return Result.Rejected("missing ${missing.sorted().joinToString(", ")}")
        }
        return Result.Parsed(headers)
    }
}
