package com.vayunmathur.appstore.data

import java.io.File

/**
 * Where downloaded APKs live, and how they die (#871).
 *
 * Every install path stages its bytes in [cacheDir] before committing them to
 * a PackageInstaller session: direct downloads as `<package>.apk` (+
 * `<package>.splitN.apk` siblings) in the root, Play splits under
 * `PlayDownloads/<package>/<versionCode>/` with `.tmp` resume files beside
 * each finished split. The session owns its own copy of the bytes once
 * `writeFiles` returns, so nothing here is needed after the install attempt
 * ends — success, failure, user-cancel or crash alike.
 *
 * What actually accumulated gigabytes: `SessionInstaller` only deleted files
 * on verification *rejection*. A committed install left its APKs behind, a
 * failed/cancelled one left them behind, and every Play update left a whole
 * version dir behind (the resume cache keys per version, so it never reused
 * them). Hence three cleanups, all funnelled through here so the naming and
 * the sweep stay in one place:
 * - [deleteStagedApks]: after every direct/APK install attempt, in a
 *   `finally` — unneeded whatever the outcome.
 * - [deletePlayDownloads]: after every Play install attempt, same deal.
 * - [sweepStale]: on startup (both entries + the update worker, which can
 *   cold-start the process alone), for whatever a crash orphaned. Age-gated
 *   so it can never race an install this process just started.
 *
 * Everything here takes the directories as arguments and touches nothing
 * else, so it unit-tests on the JVM with temp dirs.
 */
object DownloadCache {

    /**
     * Stale-file age for [sweepStale]. An install takes minutes; anything this
     * old is debris from a dead process, not an in-flight download.
     */
    const val STALE_AFTER_MS = 24 * 60 * 60 * 1000L

    /** Direct-download staging name for [packageName]'s base APK. */
    fun apkName(packageName: String): String = "$packageName.apk"

    /** Direct-download staging name for [packageName]'s Nth split sibling. */
    fun splitApkName(packageName: String, index: Int): String = "$packageName.split$index.apk"

    /** The Play resume-cache tree: `PlayDownloads/<package>/<versionCode>/`. */
    fun playDir(cacheDir: File, packageName: String, versionCode: Long): File =
        File(cacheDir, "PlayDownloads/$packageName/$versionCode")

    /**
     * Delete [packageName]'s staged root APKs (base + split siblings). Safe to
     * call when none exist, or twice: verification rejection already deletes.
     */
    fun deleteStagedApks(cacheDir: File, packageName: String) {
        cacheDir.listFiles { file ->
            file.isFile && (file.name == apkName(packageName) ||
                file.name.startsWith("$packageName.split"))
        }?.forEach { runCatching { it.delete() } }
    }

    /**
     * Delete [packageName]'s whole Play download tree (all versions, finished
     * splits and `.tmp` resume files). Called after the install attempt ends;
     * the resume cache only ever resumes within one attempt, so keeping old
     * version dirs can never pay off.
     */
    fun deletePlayDownloads(cacheDir: File, packageName: String) {
        val dir = File(cacheDir, "PlayDownloads/$packageName")
        if (dir.exists()) runCatching { dir.deleteRecursively() }
    }

    /**
     * Delete crash leftovers older than [staleAfterMs] (default [STALE_AFTER_MS]):
     * root `*.apk` files, `*.tmp` resume files under `PlayDownloads/`, version
     * dirs under `PlayDownloads/<pkg>/` with nothing fresh in them, and
     * `fdroid-index/` work dirs (normally deleted in a `finally`, orphaned by
     * a crash mid-sync). Returns the number of files/dirs removed.
     *
     * Small verification downloads (`fdroid-verified.json`,
     * `izzy-verified-*.json`) are deliberately left alone: they are kilobytes,
     * overwritten in place each run, and bounded by construction.
     */
    fun sweepStale(
        cacheDir: File,
        nowMs: Long = System.currentTimeMillis(),
        staleAfterMs: Long = STALE_AFTER_MS,
    ): Int {
        var removed = 0
        val cutoff = nowMs - staleAfterMs
        // Root staging APKs from a dead install attempt.
        cacheDir.listFiles { file ->
            file.isFile && file.name.endsWith(".apk") && file.lastModified() < cutoff
        }?.forEach { if (runCatching { it.delete() }.getOrDefault(false)) removed++ }
        val playRoot = File(cacheDir, "PlayDownloads")
        playRoot.listFiles { file -> file.isDirectory }?.forEach { pkgDir ->
            pkgDir.listFiles { file -> file.isDirectory }?.forEach { versionDir ->
                // A version dir is stale when nothing in it was touched recently:
                // neither a finished split nor a .tmp still being resumed.
                val fresh = versionDir.walkTopDown().any { it.lastModified() >= cutoff }
                if (!fresh && runCatching { versionDir.deleteRecursively() }.getOrDefault(false)) {
                    removed++
                }
            }
            // A package dir left with no versions in it is just an empty shell.
            if (pkgDir.listFiles()?.isEmpty() == true &&
                runCatching { pkgDir.delete() }.getOrDefault(false)
            ) {
                removed++
            }
        }
        // Index-sync work dirs are deleted in a finally on the normal path; a
        // crash between mkdirs and the finally leaves them behind (tens of MB).
        val indexRoot = File(cacheDir, "fdroid-index")
        if (indexRoot.isDirectory && indexRoot.lastModified() < cutoff &&
            runCatching { indexRoot.deleteRecursively() }.getOrDefault(false)
        ) {
            removed++
        }
        return removed
    }
}
