package com.vayunmathur.appstore.data.security

import androidx.annotation.StringRes
import com.vayunmathur.appstore.R
import com.vayunmathur.appstore.data.AppProvider
import com.vayunmathur.appstore.data.AppSource

/**
 * A rule the store applies to every app, whatever its source.
 *
 * Everything user-visible here is a string resource rather than a literal, because this is
 * the text that explains the store's security model and it has to be readable in the user's
 * own language to be worth anything.
 */
data class StoreRule(
    @param:StringRes val title: Int,
    @param:StringRes val detail: Int,
    /** Format arguments for [detail], when it takes any. */
    val detailArgs: List<Any> = emptyList(),
)

/**
 * Guarantees that hold for **every** install, from every source.
 *
 * These live here rather than being repeated on each profile so that a profile card only
 * ever describes what is *distinctive* about that source. If something is true of all
 * of them, it belongs in this list.
 */
object StoreGuarantees {

    val rules: List<StoreRule> = listOf(
        StoreRule(
            R.string.store_rule_modern_android_title,
            R.string.store_rule_modern_android_detail,
            listOf(AppProvider.MIN_TARGET_SDK),
        ),
        StoreRule(R.string.store_rule_signed_title, R.string.store_rule_signed_detail),
        StoreRule(R.string.store_rule_identity_title, R.string.store_rule_identity_detail),
        StoreRule(R.string.store_rule_same_key_title, R.string.store_rule_same_key_detail),
        StoreRule(R.string.store_rule_split_key_title, R.string.store_rule_split_key_detail),
        StoreRule(R.string.store_rule_precheck_title, R.string.store_rule_precheck_detail),
        StoreRule(R.string.store_rule_fail_blocks_title, R.string.store_rule_fail_blocks_detail),
        StoreRule(R.string.store_rule_updates_stay_title, R.string.store_rule_updates_stay_detail),
        StoreRule(R.string.store_rule_fixed_sources_title, R.string.store_rule_fixed_sources_detail),
    )
}

/**
 * How an app's provenance can be checked, described **per source and deliberately not
 * ranked**.
 *
 * This used to be a 1-2-3 "security tier" with Play at the bottom, which was wrong in a
 * way worth spelling out: the ranking scored only "can this phone verify the bytes
 * against a publisher key", and then presented that single axis as overall security.
 * Google Play holds its signing keys in hardware security modules, requires verified
 * developer identity, scans every upload and every device, and can pull a malicious app
 * from the whole fleet within hours — a set of practices no F-Droid build server comes
 * close to. F-Droid's reproducible builds prove a *different* thing (that the binary
 * matches published source), and prove it well, but F-Droid also holds the signing key
 * itself and can take weeks to publish a security fix.
 *
 * Neither of those is strictly better than the other, so the store no longer pretends
 * one is. Each profile below states what its source actually does, what this app checks
 * on top, and where it falls short — and the UI presents the three side by side in source
 * order rather than best-to-worst.
 */
enum class TrustProfile {

    /**
     * Signed with the same key as this store app, checked against our own signing
     * certificate as read back from PackageManager at install time.
     */
    MODERN_APPS,

    /**
     * F-Droid, from the one hard-pinned repository. Versions independently reproduced
     * bit-for-bit by F-Droid's verification server are badged; reproducibility is a badge,
     * not an admission gate.
     */
    FDROID,

    /**
     * IzzyOnDroid, from the one hard-pinned repository. Only versions an independent
     * verification builder rebuilt bit-for-bit from published source are listed at
     * all — reproducibility is the admission gate, since Izzy ships the developers'
     * own binaries rather than source builds.
     */
    IZZY,

    /** Google Play, via an anonymous account. */
    PLAY,

    /**
     * Accrescent, via its ed25519-signed allowlist plus its gRPC listing/download API. Trust
     * is the pinned repo signing key and, per app, the signing certificate and minimum version
     * that key vouches for.
     */
    ACCRESCENT,

    /**
     * This store's own Play mirror: curated free-but-proprietary APKs fetched from Google
     * Play by the server and published in a signed F-Droid-format index. Trust is the
     * pinned repo signing key and, per version, the developer signing certificate and
     * APK hash that index vouches for.
     */
    PROPRIETARY;

    @get:StringRes
    val title: Int
        get() = when (this) {
            MODERN_APPS -> R.string.trust_modern_apps_title
            FDROID -> R.string.source_chip_fdroid
            IZZY -> R.string.source_chip_izzy
            PLAY -> R.string.trust_play_title
            ACCRESCENT -> R.string.source_chip_accrescent
            PROPRIETARY -> R.string.source_chip_proprietary
        }

    /** One line, shown under the heading on the app's page. */
    @get:StringRes
    val summary: Int
        get() = when (this) {
            MODERN_APPS -> R.string.trust_modern_apps_summary
            FDROID -> R.string.trust_fdroid_summary
            IZZY -> R.string.trust_izzy_summary
            PLAY -> R.string.trust_play_summary
            ACCRESCENT -> R.string.trust_accrescent_summary
            PROPRIETARY -> R.string.trust_proprietary_summary
        }

    /** What the source itself does, beyond anything this app can check. */
    val sourcePractices: List<Int>
        get() = when (this) {
            MODERN_APPS -> listOf(
                R.string.trust_modern_apps_practice_open,
                R.string.trust_modern_apps_practice_one_key,
            )
            FDROID -> listOf(
                R.string.trust_fdroid_practice_source,
                R.string.trust_fdroid_practice_rebuild,
                R.string.trust_fdroid_practice_antifeatures,
            )
            IZZY -> listOf(
                R.string.trust_izzy_practice_binaries,
                R.string.trust_izzy_practice_reproduced,
                R.string.trust_izzy_practice_screened,
            )
            PLAY -> listOf(
                R.string.trust_play_practice_identity,
                R.string.trust_play_practice_scanning,
                R.string.trust_play_practice_hsm,
                R.string.trust_play_practice_takedown,
            )
            ACCRESCENT -> listOf(
                R.string.trust_accrescent_practice_developer_key,
                R.string.trust_accrescent_practice_signed_list,
                R.string.trust_accrescent_practice_min_version,
            )
            PROPRIETARY -> listOf(
                R.string.trust_proprietary_practice_curated,
                R.string.trust_proprietary_practice_signed_index,
                R.string.trust_proprietary_practice_play_origin,
            )
        }

    /** Checks this app performs on the download, on top of [StoreGuarantees.rules]. */
    val ourChecks: List<Int>
        get() = when (this) {
            MODERN_APPS -> listOf(
                R.string.trust_modern_apps_check_index_signed,
                R.string.trust_modern_apps_check_key,
                R.string.trust_modern_apps_check_hash,
            )
            FDROID -> listOf(
                R.string.trust_fdroid_check_index_signed,
                R.string.trust_fdroid_check_chained,
                R.string.trust_fdroid_check_rebuilt,
                R.string.trust_fdroid_check_hash_and_key,
            )
            IZZY -> listOf(
                R.string.trust_izzy_check_index_signed,
                R.string.trust_izzy_check_chained,
                R.string.trust_izzy_check_reproduced,
                R.string.trust_izzy_check_hash_and_key,
            )
            PLAY -> listOf(
                R.string.trust_play_check_hash,
                R.string.trust_play_check_key,
                R.string.trust_play_check_stamp,
            )
            ACCRESCENT -> listOf(
                R.string.trust_accrescent_check_list_signed,
                R.string.trust_accrescent_check_key,
                R.string.trust_accrescent_check_min_version,
            )
            PROPRIETARY -> listOf(
                R.string.trust_proprietary_check_index_signed,
                R.string.trust_proprietary_check_hash_and_key,
            )
        }

    /** Where this source is weaker than the others. Every source has one. */
    @get:StringRes
    val limits: Int
        get() = when (this) {
            MODERN_APPS -> R.string.trust_modern_apps_limits
            FDROID -> R.string.trust_fdroid_limits
            IZZY -> R.string.trust_izzy_limits
            PLAY -> R.string.trust_play_limits
            ACCRESCENT -> R.string.trust_accrescent_limits
            PROPRIETARY -> R.string.trust_proprietary_limits
        }

    companion object {
        fun of(source: AppSource): TrustProfile = when (source) {
            AppSource.MODERN_APPS -> MODERN_APPS
            // The whole F-Droid catalogue is listed; whether a given version was reproduced
            // is shown per-app as a badge (see UnifiedApp.reproducible).
            AppSource.FDROID -> FDROID
            // Only reproduced Izzy versions are ever listed, so every Izzy row carries
            // the badge — the gate is the profile's distinctive fact, not the badge.
            AppSource.IZZY -> IZZY
            AppSource.PLAYSTORE -> PLAY
            // Accrescent apps are signed by their developers, not by Accrescent; trust is the
            // ed25519-signed allowlist that pins each app's signing certificate + min version.
            AppSource.ACCRESCENT -> ACCRESCENT
            // Mirror apps are signed by their developers too; trust is this store's signed
            // index pinning each version's signing certificate + APK hash.
            AppSource.PROPRIETARY -> PROPRIETARY
        }
    }
}
