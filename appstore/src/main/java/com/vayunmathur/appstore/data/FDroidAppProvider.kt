package com.vayunmathur.appstore.data

import android.content.Context
import com.vayunmathur.library.log.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Imports one hard-pinned F-Droid-format repository into the shared offline catalogue.
 *
 * The whole catalogue is imported (newest version of every app). For repositories that
 * support it, F-Droid's reproducibility feed ([ReproducibleBuilds]) is consulted only to
 * badge versions independently reproduced bit-for-bit. IzzyOnDroid is the exception:
 * it ships developers' own binaries, so its rbtlog feed ([IzzyReproducibleBuilds]) is
 * an admission gate — versions no independent builder reproduced are dropped, and a
 * failed feed fetch imports nothing. Either feed is best-effort; signed-index
 * verification always fails closed, so unauthenticated hashes and signer keys never replace
 * cached rows.
 */
class FDroidAppProvider(
    private val db: AppDatabase,
    private val appContext: Context,
    private val descriptor: RepoDescriptor,
) {

    /** Number of packages tagged reproducible on the last sync. */
    @Volatile
    var lastReproducibleCount: Int = 0
        private set

    /** Refresh [descriptor] and replace only that repository's cached rows. */
    suspend fun syncIntoDb(): Int = withContext(Dispatchers.IO) {
        var result = try {
            fetchVerifiedIndex()
        } catch (expected: Exception) {
            Log.status(TAG, "sync of ${descriptor.displayName} failed", expected)
            throw expected
        }
        // Izzy ships developers' own binaries rather than source builds, so only
        // versions an independent builder reproduced bit-for-bit are listed at
        // all (issue #492). The feed callback above only badges; the gate is here.
        // A failed feed fetch imports nothing rather than everything unfiltered.
        if (descriptor.source == AppSource.IZZY) {
            val kept = result.apps.filter { it.reproducible }
            Log.status(
                TAG,
                "sync of ${descriptor.displayName}: kept ${kept.size} of " +
                    "${result.apps.size} apps with reproduced builds"
            )
            result = result.copy(apps = kept)
        }
        Log.status(
            TAG,
            "sync of ${descriptor.displayName}: fetched ${result.apps.size} " +
                "apps (signer ${result.signerSha256.take(16)}…)"
        )
        // A verified-but-empty index is a bad publish window, not an empty store: keep the
        // previous rows so one bad sync cannot wipe the catalogue (issue: "catalogue
        // updated — 0 apps" left the Modern Apps section empty until the next good sync).
        if (result.apps.isEmpty()) {
            Log.status(TAG, "sync of ${descriptor.displayName} parsed 0 apps; keeping cached rows")
            return@withContext 0
        }
        val repo = db.repoDao().all().find { it.url == descriptor.url }
        db.cachedAppDao().replaceByRepo(descriptor.url, result.apps.map { it.toEntity() })
        db.repoDao().upsert(
            (repo ?: descriptor.toEntity()).copy(
                fingerprint = result.signerSha256,
                lastSync = System.currentTimeMillis(),
            )
        )
        result.apps.size
    }

    private suspend fun fetchVerifiedIndex(): FDroidRepository.IndexResult {
        val verified = if (descriptor.supportsReproducibilityFeed) {
            runCatching { ReproducibleBuilds.fetch(appContext) }.getOrNull()
        } else {
            null
        }
        // Izzy ships developers' own binaries, so unverified versions are dropped outright
        // rather than badged: a failed feed fetch then imports nothing, never everything.
        val izzyVerified = if (descriptor.source == AppSource.IZZY) {
            runCatching { IzzyReproducibleBuilds.fetch(appContext) }.getOrNull()
        } else {
            null
        }
        var reproduced = 0
        val result = FDroidRepository.fetchRepoIndex(
            context = appContext,
            repoUrl = descriptor.url,
            pinnedFingerprint = descriptor.pinnedFingerprint,
            source = descriptor.source,
        ) { pkg, versionCode ->
            if (descriptor.source == AppSource.IZZY) {
                val ok = IzzyReproducibleBuilds.admits(izzyVerified, pkg, versionCode)
                if (ok) reproduced++
                ok
            } else {
                val ok = descriptor.supportsReproducibilityFeed &&
                    verified?.contains(pkg, versionCode) == true
                if (ok) reproduced++
                ok
            }
        }
        lastReproducibleCount = reproduced
        return result
    }

    private companion object {
        const val TAG = "FDroidAppProvider"
    }
}

fun RepoDescriptor.toEntity(): RepoEntity = RepoEntity(
    url = url,
    name = displayName,
    enabled = true,
    fingerprint = pinnedFingerprint,
)
