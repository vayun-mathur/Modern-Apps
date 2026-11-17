package com.vayunmathur.updater.domain

/**
 * Checks a *signature-verified* OTA package is the one we asked for, for this device, from this
 * starting point.
 *
 * Ordering matters and is not negotiable: `RecoverySystem.verifyPackage` runs FIRST. Everything
 * here reads fields out of the package itself, so on an unsigned package it is worth nothing.
 * What it adds on top of the signature is the checks a signature cannot make — a package we
 * signed is still the wrong package if it targets another device, or patches from a build we
 * are not running.
 *
 * Every failure is fatal and the package is deleted. There is no "warn and continue" here: the
 * next step writes to a system partition.
 */
object OtaPackageValidation {

    /** What the client already knows, independently of the package. */
    data class Expected(
        /** From the server metadata line. */
        val buildDateUtcSeconds: Long,
        /** From the server metadata line. */
        val targetBuild: String,
        /** `ro.product.device`. */
        val device: String,
        /** `ro.build.version.incremental` — what we are running NOW. */
        val currentBuild: String,
        /** `ro.build.fingerprint` — what we are running NOW. */
        val currentFingerprint: String,
    )

    /** Validation outcome for a signature-verified OTA package. */
    sealed interface Result {
        /** Safe to apply. [payloadOffset] and [incremental] are needed to do it. */
        data class Valid(val payloadOffset: Long, val incremental: Boolean) : Result

        /** Package must not be applied; [reason] explains which check failed. */
        data class Rejected(val reason: String) : Result
    }

    fun validate(metadata: OtaPackageMetadata, expected: Expected): Result {
        checkServerAgreement(metadata, expected)?.let { return it }
        checkDevice(metadata, expected)?.let { return it }
        checkIncrementalSource(metadata, expected)?.let { return it }
        val offset = metadata.payloadOffset()
            ?: return Result.Rejected("payload offset missing")
        val incremental = metadata.preBuildIncremental != null
        return Result.Valid(payloadOffset = offset, incremental = incremental)
    }

    private fun checkServerAgreement(
        metadata: OtaPackageMetadata,
        expected: Expected,
    ): Result.Rejected? {
        // The server said this build; the package must agree. A mismatch means the server and
        // the artifact disagree, which is either a broken publish or a swapped file.
        if (metadata.postTimestamp != expected.buildDateUtcSeconds) {
            return Result.Rejected(
                "timestamp does not match server metadata " +
                    "(package=${metadata.postTimestamp} server=${expected.buildDateUtcSeconds})",
            )
        }
        if (metadata.postBuildIncremental != expected.targetBuild) {
            return Result.Rejected(
                "build does not match server metadata " +
                    "(package=${metadata.postBuildIncremental} server=${expected.targetBuild})",
            )
        }
        return null
    }

    private fun checkDevice(
        metadata: OtaPackageMetadata,
        expected: Expected,
    ): Result.Rejected? {
        if (metadata.preDevice != expected.device) {
            return Result.Rejected(
                "package is for ${metadata.preDevice}, this is ${expected.device}",
            )
        }
        // A serialno constraint pins a package to specific hardware. We never publish one, so
        // its presence means this package was built for someone else's purposes.
        if (metadata.serialNo != null) {
            return Result.Rejected("serialno constraint not permitted")
        }
        // update_engine writes to the inactive slot. A non-A/B package would target a
        // recovery-based flow that does not exist here.
        if (metadata.otaType != "AB") {
            return Result.Rejected("package is not an A/B update (ota-type=${metadata.otaType})")
        }
        return null
    }

    private fun checkIncrementalSource(
        metadata: OtaPackageMetadata,
        expected: Expected,
    ): Result.Rejected? {
        // An incremental is a patch against one exact source build. Applying one to a different
        // starting point produces a corrupt system that may still boot far enough to matter.
        // Both fields are optional — a full package declares neither — but if either is present
        // it must match.
        if (metadata.preBuildIncremental != null &&
            metadata.preBuildIncremental != expected.currentBuild
        ) {
            return Result.Rejected(
                "incremental patches from ${metadata.preBuildIncremental}, " +
                    "this is ${expected.currentBuild}",
            )
        }
        if (metadata.preBuildFingerprint != null &&
            metadata.preBuildFingerprint != expected.currentFingerprint
        ) {
            return Result.Rejected("incremental source fingerprint mismatch")
        }
        return null
    }
}
