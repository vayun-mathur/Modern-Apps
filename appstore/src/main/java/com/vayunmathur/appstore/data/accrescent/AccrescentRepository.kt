package com.vayunmathur.appstore.data.accrescent

import android.content.Context
import com.vayunmathur.library.log.Log
import app.accrescent.appstore.v1.AppListing
import com.vayunmathur.appstore.data.AppDatabase
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale

/** One split (base or config) APK to download for an Accrescent install. */
data class AccrescentSplit(val url: String, val size: Long)

/** An available Accrescent update: the new version and the split APKs that make it up. */
data class AccrescentUpdate(
    val versionCode: Long,
    val versionName: String,
    val splits: List<AccrescentSplit>,
)

/** A page of Accrescent listings plus the token to fetch the next one (blank when done). */
data class AccrescentPage(val apps: List<UnifiedApp>, val nextPageToken: String)

/**
 * Everything the Accrescent source needs, behind one object — the analogue of
 * [com.vayunmathur.appstore.data.play.PlayRepository].
 *
 * Two halves that stay firmly separated:
 * - the **gRPC API** ([AccrescentApi]) supplies browsable listings and per-device split
 *   download URLs. This is untrusted metadata; a bad response can at worst show a wrong name
 *   or a URL that fails verification.
 * - the **signed allowlist** ([AccrescentTrustStore], populated by [AccrescentRepoDataFetcher])
 *   is the trust anchor: the expected signing certificate and minimum version per app id.
 *
 * Reads (list/search/details) fail soft and return empty/null like the Play repository, so a
 * network blip just shows fewer apps rather than an error. The install-critical calls
 * ([refreshRepoData], the trust lookups) surface failure so the installer can fail closed.
 */
class AccrescentRepository(
    context: Context,
    db: AppDatabase,
    private val api: AccrescentApi = AccrescentApi(),
) {
    private val repoDataFetcher = AccrescentRepoDataFetcher(context)
    private val trustStore = AccrescentTrustStore(db.accrescentTrustDao())
    private val deviceAttributes = DeviceAttributesProvider(context)
    private val appContext = context.applicationContext

    /** Listings seen so far, for the client-side [search] (the API has no search RPC). */
    private val listingsCache = LinkedHashMap<String, UnifiedApp>()

    /** Guards [ensureAllListingsLoaded] so concurrent searches paginate the catalogue once. */
    private val listingsMutex = Mutex()

    /** True once the whole catalogue has been paged into [listingsCache] at least once. */
    @Volatile
    private var allListingsLoaded = false

    // --- Trust anchor ---------------------------------------------------------------

    /**
     * Fetch, ed25519-verify and cache the signed allowlist. Fail closed: on any failure the
     * previously cached allowlist is left untouched and the error is returned, so an install
     * never proceeds against unverified trust data.
     */
    suspend fun refreshRepoData(): Result<Unit> =
        repoDataFetcher.fetch().mapCatching { repoData ->
            trustStore.replaceFrom(repoData)
        }

    suspend fun signerFor(appId: String): String? = trustStore.signerFor(appId)
    suspend fun minVersionFor(appId: String): Long? = trustStore.minVersionFor(appId)
    suspend fun entryFor(appId: String) = trustStore.entryFor(appId)
    suspend fun appIds(): Set<String> =
        runCatching { trustStore.appIds() }.getOrDefault(emptySet())

    // --- Browse / search ------------------------------------------------------------

    /** One page of listings. Blank [pageToken] starts from the beginning. */
    suspend fun listApps(pageToken: String = ""): AccrescentPage {
        return try {
            val response = api.listAppListings(PAGE_SIZE, pageToken, preferredLanguages())
            val apps = response.listingsList.map { it.toUnifiedApp() }
            apps.forEach { listingsCache[it.packageName] = it }
            AccrescentPage(apps, response.nextPageToken)
        } catch (e: CancellationException) {
            throw e
        } catch (expected: java.io.IOException) {
            Log.status(TAG, "listApps failed", expected)
            AccrescentPage(emptyList(), "")
        } catch (expected: IllegalStateException) {
            Log.status(TAG, "listApps failed", expected)
            AccrescentPage(emptyList(), "")
        } catch (expected: Exception) {
            // gRPC failures (StatusRuntimeException) — fail soft like a network blip (issue #812).
            Log.status(TAG, "listApps failed", expected)
            AccrescentPage(emptyList(), "")
        }
    }

    /**
     * The whole listing catalogue, A–Z. Backs the Browse page, which lists every
     * Accrescent app rather than the home carousel's first page.
     *
     * Fail-soft like everything else here: an interrupted pagination returns whatever
     * listings are cached rather than an error.
     */
    suspend fun allListings(): List<UnifiedApp> {
        runCatching { ensureAllListingsLoaded() }
        return listingsCache.values.sortedBy { it.name.lowercase() }
    }

    /**
     * Client-side search over the full catalogue. Accrescent's API has no search RPC, so this
     * pages every listing into memory once (mirroring Accrescent's own client) and then matches
     * app id, name and summary. The full load is cached for the session.
     */
    suspend fun search(query: String): List<UnifiedApp> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        runCatching { ensureAllListingsLoaded() }.getOrNull() ?: return emptyList()
        return listingsCache.values.filter {
            it.packageName.lowercase().contains(q) ||
                it.name.lowercase().contains(q) ||
                it.summary.lowercase().contains(q)
        }
    }

    /** Page the entire listing catalogue into [listingsCache], once per session. */
    private suspend fun ensureAllListingsLoaded() {
        if (allListingsLoaded) return
        listingsMutex.withLock {
            if (allListingsLoaded) return
            val loaded = loadAllPages()
            // Mark loaded when we reached the end (or the page cap); leave it false on a network
            // failure so the next search retries the full load.
            if (loaded) allListingsLoaded = true
        }
    }

    private suspend fun loadAllPages(): Boolean {
        var token = ""
        var pages = 0
        while (pages < MAX_LISTING_PAGES) {
            val response = loadOnePage(token, pages) ?: return false
            response.listingsList.forEach { listingsCache[it.appId] = it.toUnifiedApp() }
            token = response.nextPageToken
            pages++
            if (token.isBlank()) return true
        }
        return pages >= MAX_LISTING_PAGES
    }

    private suspend fun loadOnePage(
        token: String,
        pages: Int,
    ): app.accrescent.appstore.v1.ListAppListingsResponse? {
        return try {
            api.listAppListings(PAGE_SIZE, token, preferredLanguages())
        } catch (e: CancellationException) {
            throw e
        } catch (expected: java.io.IOException) {
            Log.status(TAG, "listAppListings page $pages failed", expected)
            null
        } catch (expected: IllegalStateException) {
            Log.status(TAG, "listAppListings page $pages failed", expected)
            null
        } catch (expected: Exception) {
            // gRPC failures (StatusRuntimeException) — fail soft like a network blip (issue #812).
            Log.status(TAG, "listAppListings page $pages failed", expected)
            null
        }
    }

    /** Full details for one app: listing + package info, enriched with signer/min-version. */
    suspend fun details(appId: String): UnifiedApp? {
        return try {
            val listing = api.getAppListing(appId, preferredLanguages())
            val packageInfo = runCatching { api.getAppPackageInfo(appId) }.getOrNull()
            val trust = trustStore.entryFor(appId)
            listing.toUnifiedApp().copy(
                versionName = packageInfo?.versionName?.ifBlank { null },
                versionCode = packageInfo?.versionCode ?: 0L,
                expectedSigners = trust?.signingCertHash?.let { listOf(it) } ?: emptyList(),
            ).also { listingsCache[appId] = it }
        } catch (e: CancellationException) {
            throw e
        } catch (expected: java.io.IOException) {
            Log.status(TAG, "details failed for $appId", expected)
            null
        } catch (expected: IllegalStateException) {
            Log.status(TAG, "details failed for $appId", expected)
            null
        } catch (expected: Exception) {
            // gRPC failures (StatusRuntimeException) — fail soft like a network blip (issue #812).
            Log.status(TAG, "details failed for $appId", expected)
            null
        }
    }

    // --- Install / update -----------------------------------------------------------

    /**
     * Per-device split download URLs for a fresh install. Throws
     * [IncompatibleDeviceException] when Accrescent has no build for this device.
     */
    suspend fun downloadInfo(appId: String): List<AccrescentSplit> {
        val info = api.getAppDownloadInfo(appId, deviceAttributes.deviceAttributes())
        return info.splitDownloadInfoList.map { AccrescentSplit(it.url, it.downloadSize.toLong()) }
    }

    /**
     * Split URLs for updating from [currentVersionCode], or null when no newer version is
     * available (the API omits update info when up to date). Throws
     * [IncompatibleDeviceException] when the device is unsupported. The new version code/name
     * come from a follow-up package-info lookup, since the update message carries only splits.
     */
    suspend fun updateInfo(appId: String, currentVersionCode: Long): AccrescentUpdate? {
        val update = api.getAppUpdateInfo(
            appId, deviceAttributes.deviceAttributes(), currentVersionCode
        ) ?: return null
        val packageInfo = runCatching { api.getAppPackageInfo(appId) }.getOrNull()
        return AccrescentUpdate(
            // The presence of update info already means "newer than installed"; fall back to
            // currentVersionCode + 1 so it still surfaces if the package-info lookup fails.
            versionCode = packageInfo?.versionCode?.takeIf { it > currentVersionCode }
                ?: (currentVersionCode + 1),
            versionName = packageInfo?.versionName.orEmpty(),
            splits = update.splitUpdateInfoList.map { AccrescentSplit(it.apkUrl, it.apkDownloadSize.toLong()) },
        )
    }

    fun shutdown() = api.shutdown()

    // --- Internals ------------------------------------------------------------------

    private fun AppListing.toUnifiedApp(): UnifiedApp = UnifiedApp(
        packageName = appId,
        source = AppSource.ACCRESCENT,
        name = name.ifBlank { appId },
        summary = shortDescription,
        iconUrl = if (hasIcon()) icon.url.ifBlank { null } else null,
        repoUrl = AccrescentRepo.REPOSITORY_URL,
        apkSha256 = null,
    )

    private fun preferredLanguages(): List<String> {
        val locales = appContext.resources.configuration.locales
        val tags = (0 until locales.size()).map { locales[it].toLanguageTag() }
        return tags.ifEmpty { listOf(Locale.getDefault().toLanguageTag()) }
    }

    private companion object {
        const val TAG = "AccrescentRepository"
        const val PAGE_SIZE = 50
        /** Safety cap on full-catalogue pagination (50 * 40 = 2000 apps, well above the catalogue). */
        const val MAX_LISTING_PAGES = 40
    }
}
