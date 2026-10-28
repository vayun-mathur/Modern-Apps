package com.vayunmathur.appstore.data

import com.vayunmathur.appstore.domain.SearchRanking

enum class AppSource {
    /** This monorepo's own repository, signed with the same key as this app. */
    MODERN_APPS,
    FDROID,
    PLAYSTORE,

    /**
     * IzzyOnDroid (apt.izzysoft.de/fdroid/repo). Ships the developers' own signed
     * binaries (not source-built), so only apps whose advertised version an
     * independent verification builder rebuilt bit-for-bit are listed — see
     * [com.vayunmathur.appstore.data.IzzyReproducibleBuilds]. Off by default.
     */
    IZZY,

    /**
     * Accrescent (accrescent.app). Listings and per-device split download URLs come from
     * Accrescent's gRPC API (appstore-api.accrescent.app); the trust anchor is a separate
     * ed25519-signed allowlist (repo.accrescent.app/repodata) that records each app's expected
     * signing certificate and minimum version code. Installs go through the store's ordinary
     * verify-then-commit path with those values, plus the new min-version gate. See
     * [com.vayunmathur.appstore.data.accrescent.AccrescentRepository].
     */
    ACCRESCENT,

    /**
     * This store's own Play mirror: a curated allowlist of free-but-proprietary apps
     * (e.g. WhatsApp) fetched from Google Play by the server, stored on R2, and published
     * as a signed F-Droid-format repo. Consumed exactly like the other offline repos —
     * no Google contact at runtime. Per-version signer keys + APK hashes come from the
     * signed index, so installs verify end to end.
     */
    PROPRIETARY;

    companion object {
        /**
         * Sources switched on for a fresh install. Only the two first-party
         * repos — everything third-party is opt-in via Sources settings.
         */
        val DEFAULT_ENABLED: Set<AppSource> = setOf(MODERN_APPS, PROPRIETARY)

        /**
         * Precedence when more than one source offers the same package, most preferred first.
         *
         * Below is a provenance preference rather than a security ranking (see
         * [com.vayunmathur.appstore.data.security.TrustProfile]): catalogue rows carry a
         * publisher key and a hash a download can be checked against, and Modern Apps rows are
         * signed with this store's own key.
         */
        val PRIORITY: List<AppSource> =
            listOf(MODERN_APPS, PROPRIETARY, FDROID, IZZY, ACCRESCENT, PLAYSTORE)

        /**
         * Sources the user may switch off, in the order the sources screen lists them.
         */
        val TOGGLEABLE: List<AppSource> = listOf(MODERN_APPS, PROPRIETARY, FDROID, IZZY, PLAYSTORE, ACCRESCENT)
    }
}

/**
 * Rank of this source in [AppSource.PRIORITY]; lower wins. A source missing from the list
 * sorts last rather than first, so forgetting to place a new one cannot silently promote it
 * above every other source.
 */
val AppSource.priority: Int
    get() = AppSource.PRIORITY.indexOf(this).takeIf { it >= 0 } ?: AppSource.PRIORITY.size

data class UnifiedApp(
    val packageName: String,
    val source: AppSource,
    val name: String,
    val summary: String = "",
    val description: String = "",
    val iconUrl: String? = null,
    val author: String? = null,
    val categories: List<String> = emptyList(),
    val versionName: String? = null,
    val versionCode: Long = 0L,
    val sizeBytes: Long = 0L,
    val apkUrl: String? = null,
    val targetSdk: Int? = null,
    /**
     * True when this exact version (packageName + versionCode) was independently
     * reproduced bit-for-bit: by F-Droid's verification server for F-Droid rows, or
     * by an rbtlog verification builder for IzzyOnDroid rows (where it is also the
     * admission gate — see [com.vayunmathur.appstore.data.IzzyReproducibleBuilds]).
     * A badge, not a gate, for F-Droid — apps are listed regardless. See
     * [com.vayunmathur.appstore.data.ReproducibleBuilds].
     */
    val reproducible: Boolean = false,
    val license: String? = null,
    val website: String? = null,
    val sourceCode: String? = null,
    val whatsNew: String? = null,
    val addedTimestamp: Long = 0L,
    val lastUpdated: Long = 0L,
    val antiFeatures: List<String> = emptyList(),
    val repoUrl: String? = null,
    val offerType: Int = 0,
    val containsSplit: Boolean = false,
    /**
     * Sibling APKs that must install in the same session as [apkUrl] (split-set
     * versions: base + config splits sharing one versionCode). Empty for
     * monolithic APKs. URLs are absolute; each carries its own hash/size for
     * per-file verification — see `InstallCoordinator.installFromUrl`.
     */
    val splitFiles: List<SplitFile> = emptyList(),
    val isFree: Boolean = true,
    /** Listing screenshots, in the order the source published them. */
    val screenshots: List<String> = emptyList(),
    /**
     * TV screenshots, where the source publishes a separate set (F-Droid's `tv` set).
     * Shown on TV in place of [screenshots], which are phone-aspect and jump between
     * ratios when mixed. Empty when the source publishes no TV set.
     */
    val tvScreenshots: List<String> = emptyList(),
    /** Wide header image, where the source publishes one. */
    val featureGraphic: String? = null,
    /** Average star rating, 0..5. Null when the source doesn't publish one. */
    val rating: Float? = null,
    /** How many ratings [rating] is an average of, 0 when unknown. */
    val ratingCount: Long = 0L,
    /** Install count as the source reports it, 0 when unknown. */
    val installs: Long = 0L,
    /** Human-readable release date from the source, e.g. "Jul 28, 2026". */
    val updatedOn: String? = null,
    /** Age rating label, e.g. "Everyone" / "PEGI 3". */
    val contentRating: String? = null,
    val privacyPolicyUrl: String? = null,
    val containsAds: Boolean = false,
    /** Permission names the listing declares, before install. */
    val permissions: List<String> = emptyList(),
    /**
     * Lowercase-hex SHA-256 fingerprints of the certificates the publisher says this
     * APK is signed with, from a source we authenticated (a JAR-signed F-Droid index).
     * Empty when the source can't tell us — notably Play, where Google holds the key.
     */
    val expectedSigners: List<String> = emptyList(),
    /** Lowercase-hex SHA-256 of the APK itself, when the source publishes one. */
    val apkSha256: String? = null,
    /**
     * Hardware/software features the APK declares via `<uses-feature>` (F-Droid's
     * per-version `features` list; Play's `requiresFeature` where the proto exposes it).
     * Empty when the source publishes none. Used for TV-compat filtering — see
     * [com.vayunmathur.appstore.data.AppProvider.filterTvCompatible] — never as a
     * trust signal.
     */
    val requiredFeatures: List<String> = emptyList(),
    /**
     * Whether this listing is usable on Android TV, as far as the catalogue can tell.
     *
     * True when a TV signal is present (leanback `<uses-feature>`, a TV category, a
     * TV screenshot set); false when the listing positively declares itself
     * phone-only; null when the source says nothing either way (notably Accrescent,
     * whose server already decides per-device, and Play, whose phone-profile spoof
     * means the server never saw a TV). A null is *not* a verdict — it means unknown,
     * and the filter treats it as compatible rather than hiding an app for lack of
     * metadata. The install gate ([com.vayunmathur.appstore.data.security.InstallVerifier])
     * re-checks the actual APK regardless.
     */
    val tvCompatible: Boolean? = null,
) {
    /** The single best image to head the detail page with. */
    val heroImage: String? get() = featureGraphic ?: screenshots.firstOrNull()
}

/**
 * One sibling APK of a split-set version ([UnifiedApp.splitFiles]): anything
 * sharing the chosen entry's versionCode. Downloaded and installed in the same
 * [PackageInstaller] session as [UnifiedApp.apkUrl].
 */
data class SplitFile(
    val fileName: String,
    val url: String,
    val sha256: String? = null,
    val size: Long = 0L,
)

/** The fields [SearchRanking] looks at, for a listing from any source. */
fun UnifiedApp.searchCandidate(): SearchRanking.Candidate = SearchRanking.Candidate(
    name = name,
    packageName = packageName,
    author = author.orEmpty(),
    summary = summary,
    popularity = installs,
)

data class InstalledInfo(
    val packageName: String,
    val name: String,
    val versionName: String?,
    val versionCode: Long,
    val enabled: Boolean = true,
    /** When the package was last updated on this device, ms since epoch. */
    val lastUpdateTime: Long = 0L,
)

/** A fixed, signed F-Droid-format repository consumed by this store. */
data class RepoDescriptor(
    val url: String,
    val displayName: String,
    val pinnedFingerprint: String,
    val source: AppSource,
    val supportsReproducibilityFeed: Boolean,
)

/**
 * The only repositories this store will talk to.
 *
 * Third-party F-Droid-format repos beyond what's listed here are deliberately not
 * supported and cannot be added: they republish binaries built by the upstream developer
 * rather than building from source, so an app from one carries the developer's release
 * pipeline *and* the mirror operator in its trusted set, with no published source to
 * check the binary against. IzzyOnDroid is the controlled exception: it is only listed
 * with its reproducible-builds gate (see [IZZY]), so every app offered from it has had
 * its binary independently rebuilt from published source. The f-droid.org archive is
 * excluded for a different reason: it exists to serve superseded versions, which is
 * the opposite of what we want.
 *
 * [PROPRIETARY] is the deliberate exception to the no-mirror rule: it is operated by
 * the same party that ships this store (not a third party), serves only an explicit
 * allowlist of free-but-proprietary apps that cannot come from source-built repos,
 * and pins each version's developer signing key + APK hash in the signed index, so
 * the install still verifies end to end.
 *
 * Either of these can be switched off (see [AppSource.TOGGLEABLE]); neither can be replaced.
 */
object DefaultRepos {
    val FDROID = RepoDescriptor(
        url = "https://f-droid.org/repo",
        displayName = "F-Droid",
        pinnedFingerprint =
            "43238d512c1e5eb2d6569f4a3afbf5523418b82e0a3ed1552770abb9a9c9ccab",
        source = AppSource.FDROID,
        supportsReproducibilityFeed = true,
    )

    val MODERN_APPS = RepoDescriptor(
        url = "https://maproj.org/fdroid/repo",
        displayName = "Modern Apps",
        pinnedFingerprint =
            "176fcb2525573e5be8e1cb3a496dd97b137e81ca5b887a1d32cb894b4e5717b4",
        source = AppSource.MODERN_APPS,
        supportsReproducibilityFeed = false,
    )

    val PROPRIETARY = RepoDescriptor(
        url = "https://maproj.org/proprietary/repo",
        displayName = "Proprietary",
        pinnedFingerprint =
            "176fcb2525573e5be8e1cb3a496dd97b137e81ca5b887a1d32cb894b4e5717b4",
        source = AppSource.PROPRIETARY,
        supportsReproducibilityFeed = false,
    )

    val IZZY = RepoDescriptor(
        url = "https://apt.izzysoft.de/fdroid/repo",
        displayName = "IzzyOnDroid",
        pinnedFingerprint =
            "3bf0d6abfeae2f401707b6d966be743bf0eee49c2561b9ba39073711f628937a",
        source = AppSource.IZZY,
        supportsReproducibilityFeed = false,
    )

    val ALL: List<RepoDescriptor> = listOf(FDROID, MODERN_APPS, PROPRIETARY, IZZY)

    /** Cache key written by the retired GitHub releases provider. */
    const val LEGACY_MODERN_APPS_REPO_KEY = "https://github.com/vayun-mathur/Modern-Apps"
}

/** The Modern-Apps monorepo's project page. */
object ModernAppsRepo {
    const val OWNER_REPO = "vayun-mathur/Modern-Apps"
    const val PROJECT_URL = "https://github.com/$OWNER_REPO"
}
