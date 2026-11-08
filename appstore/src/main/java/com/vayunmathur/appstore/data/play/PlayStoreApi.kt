package com.vayunmathur.appstore.data.play

import com.aurora.gplayapi.data.models.App
import com.aurora.gplayapi.data.models.AuthData
import com.aurora.gplayapi.data.models.PlayFile
import com.aurora.gplayapi.data.models.StreamCluster
import com.aurora.gplayapi.exceptions.GooglePlayException
import com.aurora.gplayapi.helpers.AppDetailsHelper
import com.aurora.gplayapi.helpers.PurchaseHelper
import com.aurora.gplayapi.helpers.SearchHelper
import com.aurora.gplayapi.helpers.StreamHelper
import com.aurora.gplayapi.helpers.TopChartsHelper
import com.aurora.gplayapi.helpers.contracts.StreamContract
import com.aurora.gplayapi.helpers.contracts.TopChartsContract
import com.vayunmathur.appstore.data.AppProvider
import com.vayunmathur.appstore.data.AppSource
import com.vayunmathur.appstore.data.UnifiedApp
import com.vayunmathur.appstore.data.security.ApkCertificates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A titled row of apps, as Play's own home screen is built from. */
data class PlayCluster(val title: String, val apps: List<UnifiedApp>)

/**
 * Wrapper around gplayapi helpers for Play Store operations.
 * Uses AuthData + IHttpClient (PlayHttpClient).
 */
class PlayStoreApi(
    private val authData: AuthData,
    private val httpClient: com.aurora.gplayapi.network.IHttpClient
) {

    suspend fun getDetails(packageName: String): UnifiedApp? = withContext(Dispatchers.IO) {
        try {
            AppDetailsHelper(authData).using(httpClient)
                .getAppByPackageName(packageName)
                .toUnifiedApp()
        } catch (_: java.io.IOException) {
            null
        } catch (_: IllegalStateException) {
            null
        } catch (_: GooglePlayException) {
            // gplayapi reports HTTP failures (e.g. 429 Too Many Requests, issue #797)
            // as GooglePlayException, not IOException.
            null
        }
    }

    /**
     * Details for many packages in one request.
     *
     * Play accepts a batch here, and the update check is the reason it matters: asking
     * per-package meant one round trip plus a throttling delay for every installed app,
     * so a phone with 60 Play apps spent minutes on a check that this does in a handful
     * of requests. Chunked because the endpoint rejects unbounded lists.
     */
    suspend fun getDetails(packageNames: List<String>): List<UnifiedApp> = withContext(Dispatchers.IO) {
        if (packageNames.isEmpty()) return@withContext emptyList()
        val helper = AppDetailsHelper(authData).using(httpClient)
        packageNames.chunked(DETAILS_BATCH).flatMap { chunk ->
            try {
                helper.getAppByPackageName(chunk).map { it.toUnifiedApp() }
            } catch (_: java.io.IOException) {
                emptyList()
            } catch (_: IllegalStateException) {
                emptyList()
            } catch (_: GooglePlayException) {
                // gplayapi reports HTTP failures (e.g. 429 Too Many Requests, issue #797)
                // as GooglePlayException, not IOException.
                emptyList()
            }
        }
    }

    /**
     * Play's search, following pages only while the answer is unsatisfying.
     *
     * The first page is often editorial filler — searching "Google Maps" and getting
     * Facebook, Messenger, Netflix and Snapchat is issue #595 — with the genuine hits on
     * the page behind it. [answered] decides when to stop, so a query that is answered
     * straight away still costs exactly one request and the extra round trips are spent
     * only where the alternative was showing the user nothing they asked for.
     *
     * @throws GooglePlayException e.g. Unknown(429) when Play rate-limits (bytesOrThrow,
     * issue #797). Deliberately not caught here: PlayRepository.withApi cycles the shared
     * anonymous account and retries once, then records it so the search screen can explain
     * the missing Play half instead of crashing.
     */
    suspend fun search(
        query: String,
        maxPages: Int,
        answered: (List<UnifiedApp>) -> Boolean,
    ): List<UnifiedApp> = withContext(Dispatchers.IO) {
        try {
            val helper = SearchHelper(authData).using(httpClient)
            val found = LinkedHashMap<String, UnifiedApp>()
            var bundle = helper.searchResults(query)
            // Collect first, then decide whether another page is worth
            // fetching. Checking hasNext() before collecting dropped a
            // single-page result set entirely, and the page counter
            // stopped one page short of maxPages while still fetching
            // (and discarding) that last page.
            var pagesRead = 0
            while (true) {
                collectPage(bundle, found)
                pagesRead++
                if (answered(found.values.toList()) || pagesRead >= maxPages || !bundle.hasNext()) break
                bundle = helper.nextStreamBundle(query, bundle.streamNextPageUrl)
            }
            found.values.toList()
        } catch (_: java.io.IOException) {
            emptyList()
        } catch (_: IllegalStateException) {
            emptyList()
        }
    }

    private fun collectPage(
        bundle: com.aurora.gplayapi.data.models.StreamBundle,
        found: LinkedHashMap<String, UnifiedApp>,
    ) {
        bundle.streamClusters.values
            .flatMap { it.clusterAppList }
            .forEach { app ->
                val unified = app.toUnifiedApp()
                found.putIfAbsent(unified.packageName, unified)
            }
    }

    /** A real top chart, rather than whatever the store's HTML happened to render. */
    suspend fun topChart(
        type: TopChartsContract.Type = TopChartsContract.Type.APPLICATION,
        chart: TopChartsContract.Chart = TopChartsContract.Chart.TOP_SELLING_FREE,
    ): List<UnifiedApp> = withContext(Dispatchers.IO) {
        try {
            TopChartsHelper(authData).using(httpClient)
                .getCluster(type.value, chart.value)
                .toApps()
        } catch (_: java.io.IOException) {
            emptyList()
        } catch (_: IllegalStateException) {
            emptyList()
        } catch (_: GooglePlayException) {
            // gplayapi reports HTTP failures (e.g. 429 Too Many Requests, issue #797)
            // as GooglePlayException, not IOException.
            emptyList()
        }
    }

    /**
     * Play's own home stream: several titled clusters ("Recommended for you", "New
     * releases", …). Used to give the browse screen real editorial rows instead of one
     * undifferentiated list.
     */
    suspend fun homeClusters(
        category: StreamContract.Category = StreamContract.Category.APPLICATION,
    ): List<PlayCluster> = withContext(Dispatchers.IO) {
        try {
            StreamHelper(authData).using(httpClient)
                .fetch(StreamContract.Type.HOME, category)
                .streamClusters.values
                .mapNotNull { cluster ->
                    val apps = cluster.toApps()
                    if (apps.isEmpty()) null
                    else PlayCluster(cluster.clusterTitle.ifBlank { "" }, apps)
                }
                .filter { it.title.isNotBlank() }
        } catch (_: java.io.IOException) {
            emptyList()
        } catch (_: IllegalStateException) {
            emptyList()
        } catch (_: GooglePlayException) {
            // gplayapi reports HTTP failures (e.g. 429 Too Many Requests, issue #797)
            // as GooglePlayException, not IOException.
            emptyList()
        }
    }

    /**
     * Purchase flow - returns list of PlayFile.
     * Throws on failure.
     */
    suspend fun purchase(
        packageName: String,
        versionCode: Long,
        offerType: Int = 0,
        certHash: String? = null
    ): List<PlayFile> = withContext(Dispatchers.IO) {
        val purchaseHelper = PurchaseHelper(authData).using(httpClient)
        purchaseHelper.purchase(packageName, versionCode, offerType, certHash)
    }

    private fun StreamCluster.toApps(): List<UnifiedApp> =
        AppProvider.filterTargetSdk(clusterAppList.map { it.toUnifiedApp() })
            .distinctBy { it.packageName }

    companion object {
        /**
         * Play rejects very long batches, and a rejected chunk loses every package in it,
         * so this stays well under whatever the real ceiling is.
         */
        private const val DETAILS_BATCH = 40
    }
}

/**
 * Everything Play publishes that the store shows, in one place.
 *
 * Cluster listings fill in far less than a details call does — most fields come back
 * empty — which is fine: the detail screen refetches by package name, and the empty
 * defaults on [UnifiedApp] are what a list row falls back to.
 */
internal fun App.toUnifiedApp(): UnifiedApp {
    val ratingCount = rating.let {
        it.oneStar + it.twoStar + it.threeStar + it.fourStar + it.fiveStar
    }
    return UnifiedApp(
        packageName = packageName,
        source = AppSource.PLAYSTORE,
        name = displayName.takeIf { it.isNotBlank() } ?: packageName.substringAfterLast('.'),
        summary = shortDescription,
        description = description.takeIf { it.isNotBlank() } ?: shortDescription,
        iconUrl = iconArtwork.url.takeIf { it.isNotBlank() },
        featureGraphic = coverArtwork.url.takeIf { it.isNotBlank() },
        screenshots = screenshots.mapNotNull { it.url.takeIf { u -> u.isNotBlank() } },
        author = developerName.takeIf { it.isNotBlank() },
        categories = listOfNotNull(categoryName.takeIf { it.isNotBlank() }),
        versionName = versionName.takeIf { it.isNotBlank() },
        versionCode = versionCode,
        sizeBytes = size,
        website = "https://play.google.com/store/apps/details?id=$packageName",
        offerType = offerType,
        isFree = isFree,
        whatsNew = changes.takeIf { it.isNotBlank() },
        targetSdk = targetSdk.takeIf { it > 0 },
        rating = rating.average.takeIf { it > 0f },
        ratingCount = ratingCount,
        installs = installs,
        updatedOn = updatedOn.takeIf { it.isNotBlank() },
        contentRating = contentRating.title.takeIf { it.isNotBlank() },
        privacyPolicyUrl = privacyPolicyUrl.takeIf { it.isNotBlank() },
        containsAds = containsAds,
        permissions = permissions,
        // AppDetails.certificateSet[].sha256 is the signing certificate Google
        // says this package should carry — the same expectation the Play client
        // stores on its library entry and compares against PackageManager. It
        // pins the bytes against a swapped CDN response, but note that Google
        // supplies both this value and the APK, so it is not a publisher key.
        expectedSigners = certificateSetList
            .mapNotNull { ApkCertificates.normalizeFingerprint(it.sha256) }
            .distinct(),
    )
}
