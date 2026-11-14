package com.vayunmathur.openassistant.domain

/**
 * Why the on-device model did not come up, told apart for the error the user sees.
 *
 * The service used to say "Model missing or failed to load at <dir>" for every cause, so a
 * user whose files were all there - downloaded and SHA-verified - read it as "missing" and
 * reported a false model-missing bug (#917), when the runtime had refused the files on their
 * GPU. Each variant here is one thing the user (or a bug report) can act on.
 */
sealed interface ModelLoadFailure {
    /** Required files absent from the model directory: a download that never finished. */
    data class Missing(val files: List<String>) : ModelLoadFailure

    /** Files present but short of their pinned size, as `name actual/expected` bytes. */
    data class Incomplete(val files: List<String>) : ModelLoadFailure

    /**
     * Every required file present and full size, yet the runtime refused them: a device or
     * GPU limit, a corrupt file, or a library that did not load. [reason] is the runtime's
     * own words, null when it gave none.
     */
    data class FailedToLoad(val reason: String?) : ModelLoadFailure
}

/**
 * Classify a failed load from what is on disk and what the engine reported.
 *
 * [sizes] maps each file name to its on-disk length, null when it is absent. [required] are
 * the files the text model cannot start without; [expectedBytes] the pinned size of every
 * model file, which the loader refuses to open short of (optional towers included).
 *
 * Disk state wins over [engineError]: a missing or short file is the cause whatever the
 * runtime said about it, and only when the files are all there is the failure the runtime's.
 */
fun classifyModelLoadFailure(
    sizes: Map<String, Long?>,
    required: List<String>,
    expectedBytes: Map<String, Long>,
    engineError: String?,
): ModelLoadFailure {
    val missing = required.filter { sizes[it] == null }
    if (missing.isNotEmpty()) return ModelLoadFailure.Missing(missing)
    val short = expectedBytes.mapNotNull { (name, expected) ->
        val actual = sizes[name] ?: return@mapNotNull null
        if (actual < expected) "$name $actual/$expected" else null
    }
    if (short.isNotEmpty()) return ModelLoadFailure.Incomplete(short)
    return ModelLoadFailure.FailedToLoad(engineError?.takeIf { it.isNotBlank() })
}

/** The message for a failed load from [directory], naming the cause rather than guessing. */
fun ModelLoadFailure.describe(directory: String): String = when (this) {
    is ModelLoadFailure.Missing ->
        "Model files missing from $directory: ${files.joinToString()}"
    is ModelLoadFailure.Incomplete ->
        "Model files incomplete in $directory (bytes on disk/expected): ${files.joinToString()}"
    is ModelLoadFailure.FailedToLoad ->
        "Model files are all present in $directory but failed to load: " +
            (reason ?: "the runtime gave no reason (see the log)")
}
