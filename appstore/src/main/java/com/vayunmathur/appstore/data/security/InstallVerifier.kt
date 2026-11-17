package com.vayunmathur.appstore.data.security
import com.vayunmathur.appstore.platform.reason

import android.content.Context
import com.vayunmathur.appstore.data.AppProvider
import java.io.File

/**
 * What must hold for a downloaded set of APK files before they may be handed to
 * PackageInstaller. Built per install by the caller from whatever the source could
 * authenticate; see [InstallVerifier.verify] for how each field is enforced.
 */
data class InstallRequirement(
    val expectedPackage: String,
    /**
     * Certificate fingerprints the APK must be signed by, or prove rotation lineage to.
     * Non-empty means authoritative: a mismatch is a hard failure. Empty means the source
     * could not tell us.
     */
    val requiredSigners: Set<String> = emptySet(),
    /**
     * Expected SHA-256 per downloaded file, keyed by file name. A file present here must
     * match; files absent from the map are not hash-checked. Play supplies one entry per
     * split via `PlayFile.sha256`; F-Droid supplies one for the single APK.
     */
    val expectedSha256: Map<String, String> = emptyMap(),
    /** Human-readable origin of [requiredSigners], for the failure message. */
    val signerOrigin: String = "",
    /**
     * Previously pinned source-stamp fingerprint for this package, if any. The APK's
     * stamp (or its rotation lineage) must include it. Only used for Play, where the
     * APK signing key belongs to Google and cannot identify the publisher.
     */
    val pinnedStamp: String? = null,
    /** Refuse to install if the APK carries no verifiable source stamp at all. */
    val requireStamp: Boolean = false,
    /**
     * Minimum version code the base APK may declare, below which the install is rejected.
     * Used by Accrescent, whose ed25519-signed repodata carries a per-app minimum version
     * for rollback protection. Null (the default) means no floor, so every other source is
     * unaffected — the gate is additive and defaults to a no-op.
     */
    val minVersionCode: Long? = null,
    /**
     * True when the install is happening on a TV. Refuses APKs that cannot run there:
     * ones declaring phone-only hardware features, and ones with no leanback entry
     * (no `android.software.leanback` feature and no leanback-launched activity).
     * False (the default) skips the check entirely, so every phone install is
     * unaffected — the gate is additive and defaults to a no-op.
     *
     * This is a second opinion on the downloaded bytes, not a replacement for the
     * catalogue filter: a listing that lies about compatibility is still refused here.
     */
    val requireTvCompatible: Boolean = false,
)

/** Outcome of verifying a downloaded package before installation. */
sealed class VerificationResult {
    /** Every check that could be run, passed. */
    data class Verified(
        val detail: String,
        /** Stamp fingerprint to persist as this package's pin, if newly observed. */
        val stampToPin: String? = null,
    ) : VerificationResult()

    /** Nothing contradicted, but no publisher key or hash was available to check against. */
    data class Unverified(val reason: String, val stampToPin: String? = null) : VerificationResult()

    /** A check actively failed. The install must not proceed. */
    data class Rejected(val reason: String) : VerificationResult()

    val allowsInstall: Boolean get() = this !is Rejected

    val stamp: String?
        get() = when (this) {
            is Verified -> stampToPin
            is Unverified -> stampToPin
            is Rejected -> null
        }
}

/**
 * Runs the certificate and hash checks on downloaded files before install.
 *
 * Policy (chosen deliberately: block on contradiction, warn on absence):
 * - the archive must parse and declare [InstallRequirement.expectedPackage] — blocks a
 *   swapped APK being installed under a name the user thought they were getting;
 * - if the package is already installed, the new APK's signers must intersect the
 *   installed app's signer history. Android would refuse this anyway, but failing here
 *   turns a cryptic `INSTALL_FAILED_UPDATE_INCOMPATIBLE` into a stated key mismatch, and
 *   catches it before the bytes reach the system installer;
 * - a published SHA-256 must match;
 * - a published signer set must intersect the APK's signing lineage;
 * - with none of the above available, the result is [VerificationResult.Unverified] and
 *   the caller may proceed with a visible warning.
 */
object InstallVerifier {

    private class CheckContext(
        val context: Context,
        val files: List<File>,
        val requirement: InstallRequirement,
        val parsed: List<Pair<File, android.content.pm.PackageInfo?>>,
        val checks: MutableList<String> = mutableListOf(),
        var stampCheck: StampCheck? = null,
    )

    fun verify(
        context: Context,
        files: List<File>,
        requirement: InstallRequirement,
    ): VerificationResult {
        if (files.isEmpty()) return VerificationResult.Rejected("nothing was downloaded")

        // Parse every file. Splits legitimately parse to the same package as the base.
        val parsed = files.map { file -> file to ApkCertificates.archiveInfo(context, file) }
        val unreadable = parsed.filter { (_, info) -> info == null }
        if (unreadable.size == files.size) {
            return VerificationResult.Rejected("the download is not a valid signed app file")
        }

        val ctx = CheckContext(context, files, requirement, parsed)
        firstRejection(ctx)?.let { return it }
        val stampToPin = stampCheck(ctx).stampToPin

        if (ctx.checks.isEmpty()) {
            return VerificationResult.Unverified(
                "the source published no key or hash to check this download against",
                stampToPin,
            )
        }
        return VerificationResult.Verified(ctx.checks.joinToString("; "), stampToPin)
    }

    private fun firstRejection(ctx: CheckContext): VerificationResult.Rejected? {
        val signerCheck = checkSignerAgreement(ctx)
        val checks: List<() -> VerificationResult.Rejected?> = listOf(
            { checkPackageName(ctx) },
            { checkTargetSdk(ctx) },
            { checkLeanback(ctx) },
            { signerCheck.rejection },
            { checkRollbackFloor(ctx) },
            { checkInstalledContinuity(ctx, signerCheck.signers) },
            { checkHashes(ctx) },
            { checkRequiredSigners(ctx, signerCheck.signers) },
            { stampCheck(ctx).rejection },
        )
        return checks.firstNotNullOfOrNull { it() }
    }

    private fun stampCheck(ctx: CheckContext): StampCheck {
        // checkStamp both records check-notes and computes the pin; run once and share.
        // firstRejection only needs the rejection half, verify only the pin half — but
        // running it twice would double-append notes. Cache per verify() call instead.
        return ctx.stampCheck ?: checkStamp(ctx).also { ctx.stampCheck = it }
    }

    private data class SignerCheck(
        val signers: Set<String> = emptySet(),
        val rejection: VerificationResult.Rejected? = null,
    )

    private data class StampCheck(
        val stampToPin: String? = null,
        val rejection: VerificationResult.Rejected? = null,
    )

    private fun checkPackageName(ctx: CheckContext): VerificationResult.Rejected? {
        val declared = ctx.parsed.mapNotNull { (_, info) -> info?.packageName }.toSet()
        if (declared.isNotEmpty() && ctx.requirement.expectedPackage !in declared) {
            return VerificationResult.Rejected(
                "the download is ${declared.joinToString()}, not ${ctx.requirement.expectedPackage}"
            )
        }
        return null
    }

    private fun checkTargetSdk(ctx: CheckContext): VerificationResult.Rejected? {
        // The catalogue filters on the target SDK each source *claims*, which a source
        // may not state at all. Re-check it against the manifest we now hold, so the floor
        // holds for every install regardless of what the listing said.
        //
        // Read it from the *base* APK specifically: a config split carries no <uses-sdk>,
        // so an arbitrary file's value (0, or absent) is meaningless and reading it first
        // by chance would either miss a low-target base or wrongly reject a valid one. Block
        // only when the base APK's target is actually readable and below the floor —
        // targetSdkVersion == 0 means "couldn't determine", which stays allowed by policy.
        val baseTargetSdk = ctx.parsed.firstOrNull { (_, info) ->
            info?.packageName == ctx.requirement.expectedPackage && info.splitNames.isNullOrEmpty()
        }?.second?.applicationInfo?.targetSdkVersion ?: 0
        if (baseTargetSdk in 1 until AppProvider.MIN_TARGET_SDK) {
            return VerificationResult.Rejected(
                "it is built for Android API $baseTargetSdk, and this store needs " +
                    "${AppProvider.MIN_TARGET_SDK} or newer"
            )
        }
        return null
    }

    /**
     * TV-compat gate: refuse APKs that cannot run on a TV, from the manifest we hold.
     *
     * Mirrors [checkTargetSdk]: the catalogue filters on what each source *claims*,
     * which a source may not state at all, so re-check the downloaded bytes. A lying
     * listing is refused here rather than installed.
     *
     * Two refusals, in order:
     * - phone-only hardware: a `<uses-feature>` naming telephony or other hardware a
     *   TV has not got means the OS would refuse the install anyway — fail here with
     *   a stated reason instead of a cryptic `INSTALL_FAILED_MISSING_FEATURE`;
     * - no leanback entry: without the leanback feature *or* a leanback-launched
     *   activity, the app has no TV entry point and would install invisibly — never
     *   listed in the TV launcher, never launchable by the user.
     *
     * Skipped entirely unless [InstallRequirement.requireTvCompatible]: every phone
     * install passes untouched. An APK whose manifest cannot be read for these fields
     * stays allowed — absence of evidence is not evidence of incompatibility, and the
     * OS remains the final arbiter at commit time.
     */
    private fun checkLeanback(ctx: CheckContext): VerificationResult.Rejected? {
        if (!ctx.requirement.requireTvCompatible) return null
        val baseInfo = ctx.parsed.firstOrNull { (_, info) ->
            info?.packageName == ctx.requirement.expectedPackage && info.splitNames.isNullOrEmpty()
        }?.second ?: return null

        val declaredFeatures = baseInfo.reqFeatures?.mapNotNull { it.name }.orEmpty()
        if (AppProvider.requiresPhoneHardware(declaredFeatures)) {
            return VerificationResult.Rejected(
                "it needs phone hardware (${declaredFeatures.firstOrNull().orEmpty()}) " +
                    "this TV does not have"
            )
        }

        val declaresLeanback = declaredFeatures.any {
            it.equals(AppProvider.FEATURE_LEANBACK, ignoreCase = true)
        }
        if (!declaresLeanback && !hasLeanbackActivity(ctx, declaredFeatures)) {
            return VerificationResult.Rejected(
                "it has no TV entry point, so it would install with no way to open it"
            )
        }
        ctx.checks += "TV-compatible"
        return null
    }

    /**
     * Whether the downloaded base APK names a leanback-launched activity.
     *
     * Heuristic limits — read before tightening this:
     * - `GET_ACTIVITIES` does not return intent-filters, so the true
     *   `LEANBACK_LAUNCHER` category cannot be observed here; an activity *name*
     *   containing "leanback" or ".tv." is only a naming convention, not proof of
     *   a TV entry point. A correctly-declared TV activity with an unrelated name
     *   is missed (false negative), and a phone activity that merely mentions "tv"
     *   passes (false positive).
     * - To narrow the false negatives without breaking the fail-open contract, an
     *   APK that declares ANY `<uses-feature>` this TV also reports is treated as
     *   TV-targeted: a phone-only manifest names hardware the TV has not got
     *   (already refused above), so a surviving feature intersect means the author
     *   aimed at hardware present here.
     * - Fail-open throughout: null base file, unreadable archive, or an unreadable
     *   device-feature list stays allowed — absence of evidence is not evidence of
     *   incompatibility, and the OS remains the final arbiter at commit time.
     */
    private fun hasLeanbackActivity(
        ctx: CheckContext,
        declaredFeatures: List<String>,
    ): Boolean {
        if (declaredFeatures.isNotEmpty() && declaresDeviceFeature(ctx, declaredFeatures)) {
            return true
        }
        val baseFile = ctx.parsed.firstOrNull { (_, info) ->
            info?.packageName == ctx.requirement.expectedPackage && info.splitNames.isNullOrEmpty()
        }?.first ?: return true
        val withActivities = try {
            @Suppress("DEPRECATION")
            ctx.context.packageManager.getPackageArchiveInfo(
                baseFile.absolutePath,
                android.content.pm.PackageManager.GET_ACTIVITIES,
            )
        } catch (_: Exception) {
            null
        } ?: return true
        return withActivities.activities.orEmpty().any { activity ->
            val name = activity.name.orEmpty()
            name.contains("leanback", ignoreCase = true) || name.contains(".tv.", ignoreCase = true)
        }
    }

    /**
     * True when any of the APK's declared `<uses-feature>` names is also reported
     * by this device — a TV-targeted signal that complements the activity-name
     * heuristic above. Fail-open: an unreadable device-feature list returns false
     * rather than refusing, leaving the name heuristic (and the OS) to decide.
     */
    private fun declaresDeviceFeature(ctx: CheckContext, declaredFeatures: List<String>): Boolean {
        val deviceFeatures = try {
            ctx.context.packageManager.systemAvailableFeatures.mapNotNullTo(mutableSetOf()) {
                it.name?.lowercase()
            }
        } catch (_: Exception) {
            return false
        }
        if (deviceFeatures.isEmpty()) return false
        return declaredFeatures.any { it.lowercase() in deviceFeatures }
    }

    // Signers of the bytes we actually hold. Every readable file must agree, or the
    // set is mixed and something has been substituted.
    private fun checkSignerAgreement(ctx: CheckContext): SignerCheck {
        val perFileSigners = ctx.parsed
            .filter { (_, info) -> info != null }
            .map { (file, _) -> file to ApkCertificates.apkSigners(ctx.context, file) }
        val emptySigner = perFileSigners.firstOrNull { (_, signers) -> signers.isEmpty() }
        if (emptySigner != null) {
            val rejection = VerificationResult.Rejected("${emptySigner.first.name} is not signed")
            return SignerCheck(rejection = rejection)
        }
        val signerSets = perFileSigners.map { (_, signers) -> signers }.toSet()
        if (signerSets.size > 1) {
            val rejection = VerificationResult.Rejected(
                "the app's files are not all signed by the same key"
            )
            return SignerCheck(rejection = rejection)
        }
        return SignerCheck(signers = signerSets.first())
    }

    // Rollback guard (Accrescent): the base APK's version code must be at least the
    // minimum the source's signed allowlist records. Read from the base APK's manifest,
    // like the target-SDK check above. Fail closed — a version we cannot read (0) is below
    // any positive floor and is rejected, since the whole point is to refuse an old build.
    private fun checkRollbackFloor(ctx: CheckContext): VerificationResult.Rejected? {
        val minVc = ctx.requirement.minVersionCode ?: return null
        val baseInfo = ctx.parsed.firstOrNull { (_, info) ->
            info?.packageName == ctx.requirement.expectedPackage && info.splitNames.isNullOrEmpty()
        }?.second
        val versionCode = baseInfo?.longVersionCode ?: 0L
        if (versionCode < minVc) {
            return VerificationResult.Rejected(
                "it is version $versionCode, older than the minimum $minVc the source allows"
            )
        }
        ctx.checks += "version $versionCode is at or above the minimum $minVc"
        return null
    }

    private fun checkInstalledContinuity(
        ctx: CheckContext,
        actualSigners: Set<String>,
    ): VerificationResult.Rejected? {
        val installed = ApkCertificates.installedSigners(ctx.context, ctx.requirement.expectedPackage)
        if (installed.isEmpty()) return null
        if (actualSigners.none { it in installed }) {
            return VerificationResult.Rejected(
                "it is signed with a different key than the copy you have installed"
            )
        }
        ctx.checks += "same key as the installed copy"
        return null
    }

    private fun checkHashes(ctx: CheckContext): VerificationResult.Rejected? {
        if (ctx.requirement.expectedSha256.isEmpty()) return null
        var hashed = 0
        for (file in ctx.files) {
            val expected = ctx.requirement.expectedSha256[file.name] ?: continue
            val actual = ApkCertificates.sha256(file)
            if (!actual.equals(expected, ignoreCase = true)) {
                return VerificationResult.Rejected(
                    "${file.name} does not match the hash the source published"
                )
            }
            hashed++
        }
        // A published hash set that matches nothing we downloaded means the file
        // names moved under us; treat that as a failure rather than a silent skip.
        if (hashed == 0) {
            return VerificationResult.Rejected(
                "the published hashes do not cover any of the files that downloaded"
            )
        }
        ctx.checks += "hash matches for $hashed of ${ctx.files.size} file(s)"
        return null
    }

    private fun checkRequiredSigners(
        ctx: CheckContext,
        actualSigners: Set<String>,
    ): VerificationResult.Rejected? {
        if (ctx.requirement.requiredSigners.isEmpty()) return null
        val required = ctx.requirement.requiredSigners.map { it.lowercase() }.toSet()
        // Match the app's identity, not just the bytes: a source publishes the original
        // certificate, so an app that has rotated its signing key presents a current
        // certificate the source never listed. Accepting the proven rotation lineage keeps
        // a substituted APK out while letting a rotated one through, which is the same
        // call Android makes when it applies the update.
        val base = baseApk(ctx.parsed, ctx.requirement.expectedPackage) ?: ctx.parsed.first()
        val lineage = actualSigners + ApkCertificates.signerLineage(base.second)
        if (lineage.none { it in required }) {
            val origin = ctx.requirement.signerOrigin.ifBlank { "the source" }
            return VerificationResult.Rejected("it is not signed by the key $origin expects")
        }
        ctx.checks += "signed by the key ${ctx.requirement.signerOrigin.ifBlank { "the source" }} expects"
        return null
    }

    // Source stamp: a second identity that survives Play App Signing re-signing.
    // Checked on the base APK — splits do not carry their own stamp.
    private fun checkStamp(ctx: CheckContext): StampCheck {
        if (!ctx.requirement.requireStamp && ctx.requirement.pinnedStamp == null) {
            return StampCheck()
        }
        // The stamp check and the required-signer check below both read the base APK, since a
        // config split carries neither a stamp nor a rotation lineage of its own.
        val base = baseApk(ctx.parsed, ctx.requirement.expectedPackage) ?: ctx.parsed.first()
        val stamp = SourceStamp.of(base.first)
        if (stamp == null) {
            if (ctx.requirement.requireStamp) {
                val rejection = VerificationResult.Rejected("it carries no publisher stamp to check")
                return StampCheck(rejection = rejection)
            }
            if (ctx.requirement.pinnedStamp != null) {
                val rejection = VerificationResult.Rejected(
                    "the publisher stamp is gone, and this app had one last time"
                )
                return StampCheck(rejection = rejection)
            }
            return StampCheck()
        }
        val pinned = ctx.requirement.pinnedStamp
        if (pinned != null && stamp.lineage.none { it.equals(pinned, true) }) {
            val rejection = VerificationResult.Rejected(
                "the publisher stamp changed since you installed this app"
            )
            return StampCheck(rejection = rejection)
        }
        ctx.checks += if (pinned != null) "same publisher stamp as before" else "publisher stamp saved"
        return StampCheck(stampToPin = stamp.current)
    }

    /** The file, and its parsed manifest, declaring [packageName] without a split name. */
    private fun baseApk(
        parsed: List<Pair<File, android.content.pm.PackageInfo?>>,
        packageName: String,
    ): Pair<File, android.content.pm.PackageInfo?>? = parsed.firstOrNull { (_, info) ->
        info?.packageName == packageName && info.splitNames.isNullOrEmpty()
    }
}
