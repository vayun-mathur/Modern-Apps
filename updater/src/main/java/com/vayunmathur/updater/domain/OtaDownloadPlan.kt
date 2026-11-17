package com.vayunmathur.updater.domain

/**
 * The two artifact names for a given target build.
 *
 * The incremental is preferred because it is tens of megabytes against the full package's one to
 * two gigabytes. It is also frequently absent: an incremental is published against one exact
 * source build, so a device that skipped a release has none. That is the normal case, not an
 * error — a 404 means fall back to [full], and only a 404 on both is a failure.
 */
object OtaDownloadPlan {

    /** OTA artifact filenames: optional incremental plus the always-available full package. */
    data class Artifacts(
        /** Null when no incremental can be named, i.e. the running build is unknown. */
        val incremental: String?,
        val full: String,
    )

    /**
     * [currentBuild] is what is running. When it is blank there is no incremental to name — the
     * filename embeds the source build — so only [Artifacts.full] is available.
     */
    fun artifacts(device: String, currentBuild: String, targetBuild: String): Artifacts =
        Artifacts(
            incremental = if (currentBuild.isBlank() || currentBuild == targetBuild) {
                null
            } else {
                // streaming = false throughout: applyPayload is only ever given a file:// path,
                // because RecoverySystem.verifyPackage takes a File and an https:// URL handed
                // to update_engine would never be signature-checked at all.
                OtaArtifacts.incremental(device, currentBuild, targetBuild, streaming = false)
            },
            full = OtaArtifacts.full(device, targetBuild, streaming = false),
        )
}
